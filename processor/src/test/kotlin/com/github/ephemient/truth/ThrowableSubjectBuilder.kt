package com.github.ephemient.truth

import com.google.common.truth.CustomSubjectBuilder
import com.google.common.truth.FailureMetadata
import com.google.common.truth.ThrowableSubject

class ThrowableSubjectBuilder private constructor(metadata: FailureMetadata) : CustomSubjectBuilder(metadata) {
    fun that(assertionCallback: () -> Any?): ThrowableSubject =
        object : ThrowableSubject(metadata(), runCatching(assertionCallback).exceptionOrNull() ?: UnexpectedSuccess) {
            init {
                isNotInstanceOf(UnexpectedSuccess::class.java)
            }
        }

    companion object {
        @JvmStatic
        fun throws() = Factory { ThrowableSubjectBuilder(it) }
    }
}

private object UnexpectedSuccess : AssertionError("Expected failure did not occur") {
    override fun fillInStackTrace(): Throwable = apply { stackTrace = emptyArray() }
}
