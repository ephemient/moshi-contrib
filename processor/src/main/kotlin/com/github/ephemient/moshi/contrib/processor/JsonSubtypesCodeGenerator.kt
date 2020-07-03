package com.github.ephemient.moshi.contrib.processor

import com.github.ephemient.moshi.contrib.annotations.JsonSubTypes
import com.google.auto.service.AutoService
import com.squareup.kotlinpoet.ARRAY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.SET
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.WildcardTypeName.Companion.producerOf
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.buildCodeBlock
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.jvm.throws
import com.squareup.kotlinpoet.metadata.ImmutableKmClass
import com.squareup.kotlinpoet.metadata.KotlinPoetMetadataPreview
import com.squareup.kotlinpoet.metadata.toImmutableKmClass
import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types as MoshiTypes
import com.squareup.moshi.Types.generatedJsonAdapterName
import java.io.IOException
import java.lang.reflect.Type
import java.util.Locale
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Filer
import javax.annotation.processing.Messager
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.AnnotationValue
import javax.lang.model.element.Element
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.PackageElement
import javax.lang.model.element.TypeElement
import javax.lang.model.type.TypeMirror
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic
import kotlin.reflect.KCallable
import kotlinx.metadata.Flag
import kotlinx.metadata.flagsOf
import kotlinx.metadata.isLocal
import net.ltgt.gradle.incap.IncrementalAnnotationProcessor
import net.ltgt.gradle.incap.IncrementalAnnotationProcessorType

@AutoService(Processor::class)
@IncrementalAnnotationProcessor(IncrementalAnnotationProcessorType.AGGREGATING)
@Suppress("TooManyFunctions")
class JsonSubtypesCodeGenerator : AbstractProcessor() {
    private lateinit var locale: Locale
    private lateinit var messager: Messager
    private lateinit var filer: Filer
    private lateinit var elementUtils: Elements
    private lateinit var typeUtils: Types

    override fun init(env: ProcessingEnvironment) {
        super.init(env)
        locale = env.locale
        messager = env.messager
        filer = env.filer
        elementUtils = env.elementUtils
        typeUtils = env.typeUtils
    }

    override fun getSupportedAnnotationTypes(): Set<String> = setOf(
        JsonClass::class.java.canonicalName,
        JsonSubTypes::class.java.canonicalName,
        JsonSubTypes.LabelKey::class.java.canonicalName
    )

    override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.latestSupported()

    override fun process(annotations: Set<TypeElement>, env: RoundEnvironment): Boolean {
        val annotatedTypes = env.getElementsAnnotatedWith(JsonClass::class.java).filterIsInstance<TypeElement>()
        for (type in annotatedTypes) {
            val isPrimaryGenerator = type.getAnnotationsByType(JsonClass::class.java).any {
                it.generateAdapter && it.generator == JsonSubtypesCodeGenerator::class.qualifiedName
            }
            val configurations = type.annotationMirrors
                .filter { typeUtils.asElement(it.annotationType).isNamed(JsonSubTypes::class.java.names()) }
                .groupBy { (it[JsonSubTypes::factoryName]?.value as String?).orEmpty() }
            if (isPrimaryGenerator) {
                processOne("", type, annotatedTypes, configurations[""].orEmpty())
            } else if ("" in configurations) {
                messager.printMessage(Diagnostic.Kind.ERROR, "Not declared as adapter generator for $type")
            }
            for ((factoryName, list) in configurations) {
                if (factoryName.isNotEmpty()) processOne(factoryName, type, annotatedTypes, list)
            }
        }
        return false
    }

    @OptIn(KotlinPoetMetadataPreview::class)
    private fun processOne(
        factoryName: String,
        type: TypeElement,
        annotatedTypes: List<TypeElement>,
        annotations: List<AnnotationMirror>
    ) {
        val explicitSubtypes = annotations.flatMap { expandSubtypes(type, it) }
        if (factoryName.isEmpty() && explicitSubtypes.any { typeUtils.isSameType(type.asType(), it.first.asType()) }) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Self-referencing default adapter for $type wil lead to doom")
        }
        val subtypes = explicitSubtypes.ifEmpty {
            annotatedTypes.mapNotNull { subtype ->
                if (typeUtils.isSubtype(subtype.asType(), type.asType()) &&
                    !(factoryName.isEmpty() && typeUtils.isSameType(type.asType(), subtype.asType()))
                ) subtype to "" else null
            }
        }
        if (subtypes.isEmpty()) messager.printMessage(Diagnostic.Kind.ERROR, "$type has no subtypes")
        val kmClass = type.getAnnotation(Metadata::class.java)?.toImmutableKmClass()
        if (explicitSubtypes.isEmpty() && kmClass?.isEffectivelySealed() != true) {
            messager.printMessage(Diagnostic.Kind.WARNING, "$type may be open to extension externally")
        } else if (kmClass == null || kmClass.name.isLocal ||
            flagsOf(Flag.Class.IS_CLASS, Flag.Class.IS_INTERFACE) and kmClass.flags != 0
        ) {
            messager.printMessage(
                Diagnostic.Kind.WARNING,
                "$type is not a regular Kotlin class, the generated code may not work as expected"
            )
        }
        generateFileSpec(factoryName, type, checkNames(subtypes) ?: return).writeTo(filer)
    }

    @Suppress("UNCHECKED_CAST")
    private fun expandSubtypes(parent: TypeElement, annotation: AnnotationMirror): List<Pair<TypeElement, String>> =
        (annotation[JsonSubTypes::value]?.value as Collection<AnnotationValue>?)?.mapNotNull { inner ->
            val value = (inner.value as? AnnotationMirror)?.takeIf {
                typeUtils.asElement(it.annotationType).isNamed(JsonSubTypes.Type::class.java.names())
            } ?: return@mapNotNull null
            val subtype = value[JsonSubTypes.Type::value]?.value as TypeMirror? ?: return@mapNotNull null
            if (!typeUtils.isSubtype(subtype, parent.asType())) {
                messager.printMessage(Diagnostic.Kind.ERROR, "$subtype is not subtype of $parent")
            }
            val name = value[JsonSubTypes.Type::name]?.value as String?
            typeUtils.asElement(subtype) as TypeElement to name.orEmpty()
        }.orEmpty()

    @Suppress("ReturnCount")
    private fun checkNames(subtypes: List<Pair<TypeElement, String>>): Map<TypeElement, String>? {
        val uniqueTypes = mutableMapOf<TypeElement, String>()
        for ((type, name) in subtypes) {
            val other = uniqueTypes[type]
            if (!(other.isNullOrBlank() || name.isEmpty() || other == name)) {
                messager.printMessage(Diagnostic.Kind.ERROR, "$type has conflicting names $other and $name")
                return null
            }
            if (other.isNullOrEmpty()) uniqueTypes[type] = name
        }
        val uniqueNames = mutableMapOf<String, TypeElement>()
        return uniqueTypes.mapValues { (type, name) ->
            val updatedName = name.ifEmpty { null }
                ?: type.getAnnotation(Json::class.java)?.name
                ?: type.toString()
            if (updatedName in uniqueNames) {
                messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "${uniqueNames[updatedName]} and $type have conflicting name $updatedName"
                )
                return null
            }
            uniqueNames[updatedName] = type
            updatedName
        }
    }

    private fun generateFileSpec(
        factoryName: String,
        type: TypeElement,
        subtypes: Map<TypeElement, String>
    ): FileSpec {
        val rawTypeName = type.asClassName()
        val name = factoryName.ifEmpty { generatedJsonAdapterName(rawTypeName.simpleNames.joinToString("$")) }
        val typeSpec = if (factoryName.isEmpty()) {
            val adapter = generateAdapterTypeSpec(name, type, subtypes, true)
            adapter.toBuilder().addType(generateFactoryTypeSpec("Factory", name, type, subtypes)).build()
        } else {
            val factory = generateFactoryTypeSpec(name, "Adapter", type, subtypes)
            factory.toBuilder().addType(generateAdapterTypeSpec("Adapter", type, subtypes, false)).build()
        }
        return FileSpec.builder(rawTypeName.packageName, name)
            .addComment("Code generated by moshi-contrib-processor. Do not edit.\nktlint-disable")
            .addAnnotation(
                AnnotationSpec.builder(Suppress::class)
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .addMember("%S", "ALL")
                    .build()
            )
            .addType(
                typeSpec.toBuilder()
                    .addOriginatingElement(type)
                    .apply { originatingElements.addAll(subtypes.keys) }
                    .build()
            )
            .build()
    }

    private fun generateDefaultValue(labelKey: CodeBlock, labels: Collection<String>): CodeBlock = CodeBlock.of(
        "{ %2N -> throw %1T(if (%2N == null) %4S + %3L else %5S + %3L + %6S + %2N + %7S) }",
        JsonDataException::class,
        "label",
        labelKey,
        "Missing label for ",
        "Expected one of $labels for key '",
        "' but found '",
        "'. Register a subtype for this label."
    )

    @Suppress("LongMethod")
    private fun generateAdapterTypeSpec(
        name: String,
        type: TypeElement,
        subtypes: Map<TypeElement, String>,
        isPrimaryAdapter: Boolean
    ): TypeSpec {
        val labelKey = type.getAnnotation(JsonSubTypes.LabelKey::class.java)?.value ?: "type"
        val rawTypeName = type.asClassName()
        val typeName = when (val size = type.typeParameters.size) {
            0 -> rawTypeName
            else -> rawTypeName.parameterizedBy(List(size) { STAR })
        }
        val adapterClass = JsonAdapter::class.asClassName()
        val lambdaType = LambdaTypeName.get(
            parameters = listOf(ParameterSpec("label", STRING.copy(nullable = true))),
            returnType = typeName.copy(nullable = true)
        )
        return TypeSpec.classBuilder(name)
            .applyIf(!isPrimaryAdapter) { addModifiers(KModifier.PRIVATE) }
            .superclass(adapterClass.parameterizedBy(typeName))
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .applyIf(isPrimaryAdapter) { addModifiers(KModifier.PRIVATE) }
                    .addParameter("moshi", Moshi::class)
                    .addParameter(
                        ParameterSpec.builder(
                            "skipPast",
                            JsonAdapter.Factory::class.asClassName().copy(nullable = true)
                        )
                            .addAnnotation(
                                AnnotationSpec.builder(Suppress::class)
                                    .addMember("%S", "UNUSED_PARAMETER")
                                    .build()
                            )
                            .build()
                    )
                    .addParameter("labelKey", STRING)
                    .addParameter("defaultValue", lambdaType)
                    .build()
            )
            .applyIf(isPrimaryAdapter) {
                addFunction(
                    FunSpec.constructorBuilder()
                        .addParameter("moshi", Moshi::class)
                        .callThisConstructor(
                            CodeBlock.of("%N", "moshi"),
                            CodeBlock.of("%L", null),
                            CodeBlock.of("%S", labelKey),
                            CodeBlock.of("%L", generateDefaultValue(CodeBlock.of("%S", labelKey), subtypes.values))
                        )
                        .build()
                )
            }
            .addProperty(
                PropertySpec.builder("labelKey", STRING, KModifier.PRIVATE).initializer("%N", "labelKey").build()
            )
            .addProperty(
                PropertySpec.builder("defaultValue", lambdaType, KModifier.PRIVATE)
                    .initializer("%N", "defaultValue")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("labels", ARRAY.parameterizedBy(STRING), KModifier.PRIVATE)
                    .initializer(
                        "%M(%L)",
                        MemberName("kotlin", "arrayOf"),
                        subtypes.values.map { CodeBlock.of("%S", it) }.joinToCode()
                    )
                    .build()
            )
            .addProperty(
                PropertySpec.builder("labelKeyOptions", JsonReader.Options::class, KModifier.PRIVATE)
                    .initializer("%T.%N(%N)", JsonReader.Options::class, "of", "labelKey")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("labelOptions", JsonReader.Options::class, KModifier.PRIVATE)
                    .initializer("%T.%N(*%N)", JsonReader.Options::class, "of", "labels")
                    .build()
            )
            .addProperty(
                PropertySpec.builder(
                    "jsonAdapters",
                    ARRAY.parameterizedBy(adapterClass.parameterizedBy(producerOf(typeName))),
                    KModifier.PRIVATE
                )
                    .initializer(
                        "%M(%L)",
                        MemberName("kotlin", "arrayOf"),
                        subtypes.keys.map { subtype ->
                            val subclass = CodeBlock.of(
                                "%T::class.%M",
                                subtype.asClassName(),
                                MemberName("kotlin.jvm", "java")
                            )
                            val emptySet = MemberName("kotlin.collections", "emptySet")
                            val adapter = CodeBlock.of("%N.%N(%L, %M())", "moshi", "adapter", subclass, emptySet)
                            if (typeUtils.isSameType(type.asType(), subtype.asType())) {
                                CodeBlock.of(
                                    "if (%1N != null) %2N.%3N(%1N, %4L, %5M()) else %6L",
                                    "skipPast",
                                    "moshi",
                                    "nextAdapter",
                                    subclass,
                                    emptySet,
                                    adapter
                                )
                            } else {
                                adapter
                            }
                        }.joinToCode()
                    )
                    .build()
            )
            .addFunction(
                FunSpec.builder("toString")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(STRING)
                    .addStatement("return %S", "GeneratedJsonAdapter(${rawTypeName.simpleName})")
                    .build()
            )
            .addFunction(
                FunSpec.builder("fromJson")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("reader", JsonReader::class)
                    .returns(typeName.copy(nullable = true))
                    .throws(IOException::class)
                    .addCode(
                        buildCodeBlock {
                            addStatement("val %N = %N.%N()", "peeked", "reader", "peekJson")
                            addStatement("%N.%N(%L)", "peeked", "setFailOnUnknown", false)
                            addStatement("var %N: %T = %L", "labelIndex", INT, -1)
                            addStatement("var %N: %T = %L", "labelString", STRING.copy(nullable = true), null)
                            beginControlFlow("try")
                            addStatement("%N.%N()", "peeked", "beginObject")
                            beginControlFlow("while (%N.%N())", "peeked", "hasNext")
                            beginControlFlow("if (%N.%N(%N) == %L)", "peeked", "selectName", "labelKeyOptions", -1)
                            addStatement("%N.%N()", "peeked", "skipName")
                            addStatement("%N.%N()", "peeked", "skipValue")
                            addStatement("continue")
                            endControlFlow()
                            addStatement("%N = %N.%N(%N)", "labelIndex", "peeked", "selectString", "labelOptions")
                            beginControlFlow("if (%N == %L)", "labelIndex", -1)
                            addStatement("%N = %N.%N()", "labelString", "peeked", "nextString")
                            endControlFlow()
                            addStatement("break")
                            endControlFlow()
                            nextControlFlow("finally")
                            addStatement("%N.%N()", "peeked", "close")
                            endControlFlow()
                            beginControlFlow("return·if (%N == %L)", "labelIndex", -1)
                            addStatement("%N.%N()", "reader", "skipValue")
                            addStatement("%N(%N)", "defaultValue", "labelString")
                            nextControlFlow("else")
                            addStatement("%N[%N].%N(%N)", "jsonAdapters", "labelIndex", "fromJson", "reader")
                            endControlFlow()
                        }
                    )
                    .build()
            )
            .addFunction(
                FunSpec.builder("toJson")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("writer", JsonWriter::class)
                    .addParameter("value", typeName.copy(nullable = true))
                    .throws(IOException::class)
                    .addCode(
                        buildCodeBlock {
                            beginControlFlow("if (%N == %L)", "value", null)
                            addStatement(
                                "throw %T(%S)",
                                NullPointerException::class,
                                "value was null! Wrap in .nullSafe() to write nullable values."
                            )
                            endControlFlow()
                            beginControlFlow("val %N = when (%N::class)", "labelIndex", "value")
                            subtypes.keys.forEachIndexed { index, subtype ->
                                addStatement("%T::class -> %L", subtype.asClassName(), index)
                            }
                            addStatement(
                                "else -> throw %1T(%4S + %2N + %5S + %2N::class.%3M + %6S)",
                                IllegalArgumentException::class,
                                "value",
                                MemberName("kotlin.jvm", "java"),
                                "Expected one of ${subtypes.keys} but found ",
                                ", a ",
                                ". Register this subtype."
                            )
                            endControlFlow()
                            addStatement("@%T(%S)", Suppress::class, "UNCHECKED_CAST")
                            addStatement(
                                "val %N = %N[%N] as %T",
                                "adapter",
                                "jsonAdapters",
                                "labelIndex",
                                adapterClass.parameterizedBy(typeName)
                            )
                            addStatement("%N.%N()", "writer", "beginObject")
                            addStatement("%N.%N(%N)", "writer", "name", "labelKey")
                            addStatement("%N.%N(%N[%N])", "writer", "value", "labels", "labelIndex")
                            addStatement("val %N = %N.%N()", "flattenToken", "writer", "beginFlatten")
                            addStatement("%N.%N(%N, %N)", "adapter", "toJson", "writer", "value")
                            addStatement("%N.%N(%N)", "writer", "endFlatten", "flattenToken")
                            addStatement("%N.%N()", "writer", "endObject")
                        }
                    )
                    .build()
            )
            .build()
    }

    @Suppress("LongMethod")
    private fun generateFactoryTypeSpec(
        factoryName: String,
        adapterName: String,
        type: TypeElement,
        subtypes: Map<TypeElement, String>
    ): TypeSpec {
        val rawTypeName = type.asClassName()
        val typeName = when (val size = type.typeParameters.size) {
            0 -> rawTypeName
            else -> rawTypeName.parameterizedBy(List(size) { STAR })
        }
        val lambdaType = LambdaTypeName.get(
            parameters = listOf(ParameterSpec("label", STRING.copy(nullable = true))),
            returnType = typeName.copy(nullable = true)
        )
        return TypeSpec.classBuilder(factoryName)
            .addSuperinterface(JsonAdapter.Factory::class)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addAnnotation(JvmOverloads::class)
                    .addParameter(
                        ParameterSpec.builder("labelKey", STRING.copy(nullable = true))
                            .defaultValue("%L", null)
                            .build()
                    )
                    .addParameter(
                        ParameterSpec.builder("defaultValue", lambdaType.copy(nullable = true))
                            .defaultValue(CodeBlock.of("%L", null))
                            .build()
                    )
                    .build()
            )
            .addProperty(
                PropertySpec.builder("labelKey", STRING.copy(nullable = true), KModifier.PRIVATE)
                    .initializer("%N", "labelKey")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("defaultValue", lambdaType.copy(nullable = true), KModifier.PRIVATE)
                    .initializer("%N", "defaultValue")
                    .build()
            )
            .addFunction(
                FunSpec.builder("create")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("type", Type::class)
                    .addParameter("annotations", SET.parameterizedBy(Annotation::class.asClassName()))
                    .addParameter("moshi", Moshi::class)
                    .returns(JsonAdapter::class.asClassName().parameterizedBy(STAR).copy(nullable = true))
                    .addCode(
                        buildCodeBlock {
                            beginControlFlow(
                                "if (%M(%N) != %T::class.%M)",
                                MemberName(MoshiTypes::class.asClassName(), "getRawType"),
                                "type",
                                rawTypeName,
                                MemberName("kotlin.jvm", "java")
                            )
                            addStatement("return·%L", null)
                            endControlFlow()
                            addStatement(
                                "val %N = (%N.%M() as? %T)?.%N ?: %N?.%M·{ %N.%N() } ?: return·%L",
                                "labelKey",
                                "annotations",
                                MemberName("kotlin.collections", "singleOrNull"),
                                JsonSubTypes.LabelKey::class,
                                JsonSubTypes.LabelKey::value.name,
                                "labelKey",
                                MemberName("kotlin", "takeIf"),
                                "annotations",
                                "isEmpty",
                                null
                            )
                            addStatement(
                                "return·%N(%N, this, %N, %N ?: %L)",
                                adapterName,
                                "moshi",
                                "labelKey",
                                "defaultValue",
                                generateDefaultValue(CodeBlock.of("%N", "labelKey"), subtypes.values)
                            )
                        }
                    )
                    .build()
            )
            .build()
    }

    @OptIn(ExperimentalStdlibApi::class, KotlinPoetMetadataPreview::class)
    private fun TypeElement.asClassName(): ClassName =
        getAnnotation(Metadata::class.java)?.toImmutableKmClass()?.name?.takeIf { !it.isLocal }?.let { ktName ->
            ClassName(
                packageName = ktName.substringBeforeLast('/', "").replace('/', '.'),
                simpleNames = ktName.substringAfterLast('/').split('.')
            )
        } ?: ClassName(
            packageName = elementUtils.getPackageOf(this).qualifiedName.toString(),
            simpleNames = buildList {
                var element = this@asClassName
                while (true) {
                    add(element.simpleName.toString())
                    element = element.enclosingElement as? TypeElement ?: break
                }
            }.asReversed()
        )

    private inline operator fun <reified R> AnnotationMirror.get(noinline key: R.() -> Any?): AnnotationValue? =
        get(R::class.java.names() + (key as KCallable<*>).name)

    @Suppress("ReturnCount", "UNCHECKED_CAST")
    private operator fun AnnotationMirror.get(names: List<String>): AnnotationValue? {
        for ((key, value) in elementValues) {
            if (key.isNamed(names)) return value
        }
        for (member in elementUtils.getAllMembers(typeUtils.asElement(annotationType) as TypeElement)) {
            if (member.isNamed(names)) return (member as ExecutableElement).defaultValue
        }
        return null
    }
}

@OptIn(ExperimentalStdlibApi::class)
private fun Class<*>.names(): List<String> = buildList {
    var clazz = this@names
    while (true) {
        add(clazz.simpleName)
        clazz = clazz.enclosingClass ?: break
    }
    add(`package`?.name.orEmpty())
}.asReversed()

@Suppress("ReturnCount")
private fun Element.isNamed(names: List<String>): Boolean {
    if (this is PackageElement) return names.singleOrNull()?.let { qualifiedName.contentEquals(it) } == true
    var element = this
    for (i in names.indices.reversed()) {
        if (!element.simpleName.contentEquals(names[i])) return false
        element = when (val enclosingElement = element.enclosingElement) {
            is TypeElement -> enclosingElement
            is PackageElement -> return i == 1 && enclosingElement.qualifiedName.contentEquals(names[0])
            else -> return false
        }
    }
    return false
}

@OptIn(KotlinPoetMetadataPreview::class)
private fun ImmutableKmClass.isEffectivelySealed(): Boolean =
    Flag.IS_SEALED(flags) || flagsOf(Flag.IS_PUBLIC, Flag.IS_PROTECTED) and flags == 0

private inline fun <T, R> T.applyIf(condition: Boolean, block: T.() -> R): R where T : R =
    if (condition) block() else this
