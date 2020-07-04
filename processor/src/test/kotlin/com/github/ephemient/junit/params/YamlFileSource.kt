package com.github.ephemient.junit.params

import org.junit.jupiter.params.provider.ArgumentsSource

/**
 * Load arguments from [YAML](https://yaml.org/) files in classpath resources.
 * Each document (`---` ⋯ `...`) corresponds to one parameterized test.
 * The keys of a top-level mapping object are ignored when assigning arguments.
 * JSON schema types are supported, plus a custom `!format` tag which flattens sequence objects with [String.format],
 * as well as SnakeYAML's variable substitution.
 * https://bitbucket.org/asomov/snakeyaml-engine/wiki/Documentation#markdown-header-variable-substitution
 */
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
    /**
     * Skip documents at the start of each stream.  [numDocumentsToSkip] is useful in conjunction with
     * [composeAnchors] to set up anchors in a header document which can be used as references later.
     */
    val numDocumentsToSkip: Int = 0,
    /**
     * Anchors and references (`&` and `*`) are reset between documents in standard YAML.
     * When [composeAnchors] is `true`, anchors will not be reset between documents.
     * They will still be reset between streams.
     */
    val composeAnchors: Boolean = false
)
