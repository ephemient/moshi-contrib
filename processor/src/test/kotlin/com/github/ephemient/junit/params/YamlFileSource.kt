package com.github.ephemient.junit.params

import org.junit.jupiter.params.provider.ArgumentsSource

@Target(
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER
)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@ArgumentsSource(YamlFileArgumentsProvider::class)
annotation class YamlFileSource(
    val resources: Array<String>,
    val encoding: String = "UTF-8",
    val numDocumentsToSkip: Int = 0,
    val composeAnchors: Boolean = false
)
