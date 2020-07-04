package com.github.ephemient.moshi.contrib.processor

import com.github.ephemient.junit.params.YamlFileSource
import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.kotlin.codegen.JsonClassCodegenProcessor
import com.squareup.moshi.kotlinpoet.metadata.KotlinPoetMetadataPreview
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.params.ParameterizedTest

class JsonSubtypesCodeGeneratorTest {
    @ParameterizedTest(name = "[{index}] {0}")
    @YamlFileSource(
        resources = ["JsonSubtypesCodeGeneratorTestData.yml"],
        numDocumentsToSkip = 1,
        composeAnchors = true
    )
    @OptIn(KotlinPoetMetadataPreview::class)
    fun test(
        name: String,
        sources: Map<String, String>,
        containsMessages: List<String>?,
        doesNotContainMessages: List<String>?
    ) {
        val result = KotlinCompilation().apply {
            this.sources = sources.map { SourceFile.new(it.key, it.value) }
            annotationProcessors = listOf(JsonSubtypesCodeGenerator(), JsonClassCodegenProcessor())
            inheritClassPath = true
        }.compile()
        assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
        assertThat(result.messages)
            .apply { containsMessages?.forEach { contains(it) } }
            .apply { doesNotContainMessages?.forEach { doesNotContain(it) } }
        result.classLoader
            .loadClass("${javaClass.canonicalName}_test")
            .getMethod("main")
            .invoke(null)
    }
}
