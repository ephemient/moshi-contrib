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
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.WildcardTypeName.Companion.producerOf
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.buildCodeBlock
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.jvm.throws
import com.squareup.kotlinpoet.metadata.KotlinPoetMetadataPreview
import com.squareup.kotlinpoet.metadata.toImmutableKmClass
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import java.io.IOException
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
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.NestingKind
import javax.lang.model.element.TypeElement
import javax.lang.model.type.TypeMirror
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic
import kotlinx.metadata.Flag
import kotlinx.metadata.flagsOf
import kotlinx.metadata.isLocal
import net.ltgt.gradle.incap.IncrementalAnnotationProcessor
import net.ltgt.gradle.incap.IncrementalAnnotationProcessorType

@AutoService(Processor::class)
@IncrementalAnnotationProcessor(IncrementalAnnotationProcessorType.AGGREGATING)
@OptIn(ExperimentalStdlibApi::class, KotlinPoetMetadataPreview::class)
internal class JsonSubtypesCodeGenerator : AbstractProcessor() {
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

    override fun getSupportedAnnotationTypes(): Set<String> =
        listOfNotNull(JsonClass::class.qualifiedName, JsonSubTypes::class.qualifiedName).toSet()

    override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.latestSupported()

    @Suppress("ComplexMethod", "LoopWithTooManyJumpStatements")
    override fun process(annotations: Set<TypeElement>, env: RoundEnvironment): Boolean {
        val annotatedTypes = env.getElementsAnnotatedWith(JsonClass::class.java).filterIsInstance<TypeElement>()
        for (type in annotatedTypes) {
            if (type.getAnnotationsByType(JsonClass::class.java).none {
                it.generateAdapter && it.generator ==
                    JsonSubtypesCodeGenerator::class.qualifiedName
            }
            ) continue
            val declaredSubtypes = run {
                type.annotationMirrors.flatMap { expandSubtypes(type, it) ?: return@run null }
            } ?: continue
            val subtypes = declaredSubtypes.ifEmpty {
                annotatedTypes.mapNotNull { subtype ->
                    if (typeUtils.isStrictSubtype(subtype.asType(), type.asType())) subtype to "" else null
                }
            }
            if (subtypes.isEmpty()) {
                messager.printMessage(Diagnostic.Kind.ERROR, "$type has no subtypes")
                continue
            }
            val kmClass = type.getAnnotation(Metadata::class.java)?.toImmutableKmClass()
            if (declaredSubtypes.isEmpty() && (
                kmClass == null ||
                    flagsOf(Flag.IS_PUBLIC, Flag.IS_PROTECTED, Flag.IS_SEALED) and kmClass.flags !=
                    flagsOf(Flag.IS_SEALED)
                )
            ) {
                messager.printMessage(Diagnostic.Kind.WARNING, "$type may be open to extension externally")
            } else if (kmClass == null || kmClass.name.isLocal ||
                flagsOf(Flag.Class.IS_CLASS, Flag.Class.IS_INTERFACE) and kmClass.flags == 0
            ) {
                messager.printMessage(
                    Diagnostic.Kind.WARNING,
                    "$type is not a regular Kotlin class, the generated code may not work as expected"
                )
            }
            generateAdapter(type, checkNames(subtypes) ?: continue).writeTo(filer)
        }
        return false
    }

    @Suppress("ReturnCount", "UNCHECKED_CAST")
    private fun expandSubtypes(parent: TypeElement, annotation: AnnotationMirror): List<Pair<TypeElement, String>>? {
        val annotationType = (typeUtils.asElement(annotation.annotationType) as? TypeElement)?.takeIf {
            elementUtils.getPackageOf(it).qualifiedName.contentEquals(JsonSubTypes::class.java.`package`.name) &&
                it.simpleName.contentEquals(JsonSubTypes::class.java.simpleName) &&
                it.nestingKind == NestingKind.TOP_LEVEL
        } ?: return emptyList()
        val valueMember = elementUtils.getAllMembers(annotationType)
            .filterIsInstance<ExecutableElement>()
            .firstOrNull { it.simpleName.contentEquals(JsonSubTypes::value.name) } ?: return emptyList()
        val subtypes = elementUtils.getElementValuesWithDefaults(annotation)[valueMember]?.value as? Collection<*>
            ?: return emptyList()
        return subtypes.mapNotNull { annotationValue ->
            val value = (annotationValue as? AnnotationValue)?.value as? AnnotationMirror ?: return@mapNotNull null
            val subAnnotationType = typeUtils.asElement(value.annotationType) as? TypeElement ?: return@mapNotNull null
            val members = elementUtils.getAllMembers(subAnnotationType).filterIsInstance<ExecutableElement>()
            val subvalues = elementUtils.getElementValuesWithDefaults(value)
            val subtype = subvalues[members.firstOrNull { it.simpleName.contentEquals(JsonSubTypes.Type::value.name) }]
                ?.value as? TypeMirror ?: return@mapNotNull null
            val name = subvalues[members.firstOrNull { it.simpleName.contentEquals(JsonSubTypes.Type::name.name) }]
                ?.value as? String ?: return@mapNotNull null
            if (!typeUtils.isStrictSubtype(subtype, parent.asType())) {
                messager.printMessage(Diagnostic.Kind.ERROR, "$subtype is not subtype of $parent")
                return null
            }
            (typeUtils.asElement(subtype) as? TypeElement ?: return@mapNotNull null) to name
        }
    }

    @Suppress("ReturnCount")
    private fun checkNames(subtypes: List<Pair<TypeElement, String>>): Map<TypeElement, String>? {
        val uniqueTypes = mutableMapOf<TypeElement, String>()
        for ((type, name) in subtypes) {
            val other = uniqueTypes[type]
            if (!other.isNullOrEmpty() && !name.isEmpty() && other != name) {
                messager.printMessage(Diagnostic.Kind.ERROR, "$type has conflicting names $other and $name")
                return null
            }
            if (other.isNullOrEmpty()) uniqueTypes[type] = name
        }
        val uniqueNames = mutableMapOf<String, ClassName>()
        return uniqueTypes.mapValues { (type, name) ->
            val updatedName = name.ifEmpty { type.toString() }
            if (updatedName in uniqueNames) {
                messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "${uniqueNames[updatedName]} and $type have conflicting name $updatedName"
                )
                return null
            }
            updatedName
        }
    }

    private fun generateAdapter(type: TypeElement, subtypes: Map<TypeElement, String>): FileSpec {
        val rawTypeName = type.asClassName()
        val typeName = rawTypeName.applyIf(type.typeParameters.isNotEmpty()) {
            parameterizedBy(List(type.typeParameters.size) { STAR })
        }
        val adapterName = rawTypeName.simpleNames.joinToString(separator = "_", prefix = "", postfix = "JsonAdapter")
        val adapterClass = JsonAdapter::class.asClassName()
        val lambdaType = LambdaTypeName.get(
            null,
            listOf(ParameterSpec("key", STRING.copy(nullable = true))),
            typeName.copy(nullable = true)
        )
        return FileSpec.builder(rawTypeName.packageName, adapterName)
            .addAnnotation(
                AnnotationSpec.builder(Suppress::class)
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .addMember("%S", "ALL")
                    .build()
            )
            .addType(
                TypeSpec.classBuilder(adapterName)
                    .superclass(adapterClass.parameterizedBy(typeName))
                    .primaryConstructor(
                        FunSpec.constructorBuilder()
                            .addAnnotation(JvmOverloads::class)
                            .addParameter("moshi", Moshi::class)
                            .addParameter(ParameterSpec.builder("labelKey", STRING).defaultValue("%S", "type").build())
                            .addParameter(
                                ParameterSpec.builder("defaultValue", lambdaType)
                                    .defaultValue(
                                        "{ %2N -> " +
                                            "throw %1T(if (%2N == null) %4S + %3N else %5S + %3N + %6S + %2N + %7S) }",
                                        JsonDataException::class,
                                        "key",
                                        "labelKey",
                                        "Missing label for ",
                                        "Expected one of ${subtypes.keys} for key '",
                                        "' but found '",
                                        "'. Register a subtype for this label."
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .addProperty(
                        PropertySpec.builder("labelKey", STRING, KModifier.PRIVATE)
                            .initializer("%N", "labelKey")
                            .build()
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
                            ARRAY.parameterizedBy(adapterClass.parameterizedBy(producerOf(typeName)))
                        )
                            .initializer(
                                "%M(%L)",
                                MemberName("kotlin", "arrayOf"),
                                subtypes.keys.map {
                                    CodeBlock.of(
                                        "%N.%N(%T::class.%M)",
                                        "moshi",
                                        "adapter",
                                        it.asClassName(),
                                        MemberName("kotlin.jvm", "java")
                                    )
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
                                    beginControlFlow(
                                        "if (%N.%N() == %T.%N)",
                                        "reader",
                                        "peek",
                                        JsonReader.Token::class,
                                        JsonReader.Token.NULL.name
                                    )
                                    addStatement("return %N.%N()", "reader", "nextNull")
                                    endControlFlow()
                                    addStatement("val %N = %N.%N()", "peeked", "reader", "peekJson")
                                    addStatement("%N.%N(%L)", "peeked", "setFailOnUnknown", false)
                                    addStatement("var %N: %T = %L", "labelIndex", INT, -1)
                                    addStatement("var %N: %T = %L", "labelString", STRING.copy(nullable = true), null)
                                    beginControlFlow("try")
                                    addStatement("%N.%N()", "peeked", "beginObject")
                                    beginControlFlow("while (%N.%N())", "peeked", "hasNext")
                                    beginControlFlow(
                                        "if (%N.%N(%N) == %L)",
                                        "peeked",
                                        "selectName",
                                        "labelKeyOptions",
                                        -1
                                    )
                                    addStatement("%N.%N()", "peeked", "skipName")
                                    addStatement("%N.%N()", "peeked", "skipValue")
                                    addStatement("continue")
                                    endControlFlow()
                                    addStatement(
                                        "%N = %N.%N(%N)",
                                        "labelIndex",
                                        "peeked",
                                        "selectString",
                                        "labelOptions"
                                    )
                                    beginControlFlow("if (%N == %L)", "labelIndex", -1)
                                    addStatement("%N = %N.%N()", "labelString", "peeked", "nextString")
                                    endControlFlow()
                                    endControlFlow()
                                    nextControlFlow("finally")
                                    addStatement("%N.%N()", "peeked", "close")
                                    endControlFlow()
                                    beginControlFlow("return if (%N == %L)", "labelIndex", -1)
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
                                    addStatement("%N.%N()", "writer", "nullValue")
                                    addStatement("return")
                                    endControlFlow()
                                    beginControlFlow(
                                        "val %N = when (%N::class)",
                                        "labelIndex",
                                        "value"
                                    )
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
            )
            .build()
    }

    private fun Types.isStrictSubtype(t1: TypeMirror, t2: TypeMirror) = isSubtype(t1, t2) && !isSameType(t1, t2)

    private inline fun <T, R> T.applyIf(condition: Boolean, block: T.() -> R): R where T : R =
        if (condition) block() else this
}
