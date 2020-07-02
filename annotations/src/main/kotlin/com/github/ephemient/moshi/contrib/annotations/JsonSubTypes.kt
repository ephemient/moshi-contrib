package com.github.ephemient.moshi.contrib.annotations

import kotlin.reflect.KClass

@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class JsonSubTypes(vararg val value: Type) {
    @Target()
    annotation class Type(val value: KClass<*>, val name: String = "")
}
