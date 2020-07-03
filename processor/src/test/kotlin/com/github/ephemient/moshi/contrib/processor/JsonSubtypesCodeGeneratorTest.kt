package com.github.ephemient.moshi.contrib.processor

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.kotlin.codegen.JsonClassCodegenProcessor
import com.squareup.moshi.kotlinpoet.metadata.KotlinPoetMetadataPreview
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvFileSource

@OptIn(KotlinPoetMetadataPreview::class)
class JsonSubtypesCodeGeneratorTest {
    @ParameterizedTest(name = "isSealed = {0}, isSubtypes = {1}")
    @CsvFileSource(resources = ["JsonSubtypesCodeGeneratorTestData.csv"], numLinesToSkip = 1)
    fun test(isSealed: Boolean, isSubtypes: Boolean, expectedJson: String) {
        val result = KotlinCompilation().apply {
            sources = listOf(mainKt(), testKt(isSealed, isSubtypes))
            annotationProcessors = listOf(JsonSubtypesCodeGenerator(), JsonClassCodegenProcessor())
            inheritClassPath = true
        }.compile()
        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
        if (isSealed || isSubtypes) {
            assertThat(result.messages).doesNotContain("Root may be open")
        } else {
            assertThat(result.messages).contains("Root may be open")
        }
        val main = result.classLoader.loadClass("com.github.ephemient.moshi.contrib.processor.test.MainKt")
        assertThat(main.getMethod("stringify").invoke(null)).isEqualTo(expectedJson)
        assertThat(main.getMethod("parse", String::class.java).invoke(null, expectedJson).toString())
            .isEqualTo("""Container(objects=[One(one=1), Two(two=2), Three(three=3)])""")
    }

    private fun mainKt(): SourceFile = SourceFile.kotlin(
        "main.kt",
        """
        package com.github.ephemient.moshi.contrib.processor.test

        import com.squareup.moshi.JsonClass
        import com.squareup.moshi.Moshi

        @JsonClass(generateAdapter = true)
        data class Container(val objects: List<Root>)

        val moshi = Moshi.Builder().build()

        fun stringify(): String = moshi.adapter(Container::class.java)
            .toJson(Container(listOf(Root.One(1), Root.Two(2), Root.Nested.Three(3))))

        fun parse(json: String): Container? = moshi.adapter(Container::class.java).fromJson(json)
        """.trimIndent()
    )

    private fun testKt(isSealed: Boolean, isSubtypes: Boolean): SourceFile {
        val annotation = if (isSubtypes) {
            """
            @JsonSubTypes(
                JsonSubTypes.Type(Root.One::class, "ONE"),
                JsonSubTypes.Type(Root.Two::class, "TWO"),
                JsonSubTypes.Type(Root.Nested.Three::class, "THREE")
            )
            """.trimIndent().trimEnd()
        } else ""
        return SourceFile.kotlin(
            "test.kt",
            """
            package com.github.ephemient.moshi.contrib.processor.test

            import com.squareup.moshi.JsonClass
            import com.github.ephemient.moshi.contrib.annotations.JsonSubTypes
            import com.github.ephemient.moshi.contrib.annotations.JsonSubTypes.Type

            @JsonClass(
                generateAdapter = true,
                generator = "com.github.ephemient.moshi.contrib.processor.JsonSubtypesCodeGenerator"
            )
            $annotation
            ${if (isSealed) "sealed" else "abstract"} class Root {
                @JsonClass(generateAdapter = true)
                data class One(val one: Int) : Root()
                @JsonClass(generateAdapter = true)
                data class Two(val two: Int) : Root()
                sealed class Nested : Root() {
                    @JsonClass(generateAdapter = true)
                    data class Three(val three: Int) : Nested()
                }
            }
            """
        )
    }
}
