package com.github.ephemient.moshi.contrib.annotations

import com.squareup.moshi.JsonQualifier
import kotlin.reflect.KClass

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
@Repeatable
annotation class JsonSubTypes(vararg val value: Type, val factoryName: String = "") {
    @Target()
    annotation class Type(val value: KClass<*>, val name: String = "")

    @JsonQualifier
    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD)
    annotation class LabelKey(val value: String)
}
