package com.github.ephemient.truth.junit5

import com.google.common.truth.FailureStrategy
import com.google.common.truth.StandardSubjectBuilder
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.Extension
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import org.junit.runners.model.MultipleFailureException

/**
 * [Expect][com.google.common.truth.Expect] for JUnit 5.
 *
 * Annotate your test with
* <code>@[ExtendWith][org.junit.jupiter.api.extension.ExtendWith]‌([ExpectExtension].class)</code>
 * to receive a [StandardSubjectBuilder] whose assertion failures, if any, will be reported at the end of the test.
 */
class ExpectExtension : Extension, ParameterResolver, AfterEachCallback {
    override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Boolean =
        parameterContext.parameter.type.isAssignableFrom(StandardSubjectBuilder::class.java)

    override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Any {
        val store = extensionContext.getStore(ExtensionContext.Namespace.create(ExpectExtension::class))
        return store.getOrComputeIfAbsent(extensionContext.uniqueId, { ExpectHolder() }, ExpectHolder::class.java).get()
    }

    override fun afterEach(context: ExtensionContext) {
        val store = context.getStore(ExtensionContext.Namespace.create(ExpectExtension::class))
        val holder = store.get(context.uniqueId, ExpectHolder::class.java)
        val failures = holder?.run {
            synchronized(this) {
                checkNotNull(failures).also { failures = null }
            }
        }
        MultipleFailureException.assertEmpty(failures.orEmpty())
    }

    private class ExpectHolder : FailureStrategy, ExtensionContext.Store.CloseableResource {
        var failures: MutableList<AssertionError>? = mutableListOf()

        fun get(): StandardSubjectBuilder = StandardSubjectBuilder.forCustomFailureStrategy(this)

        override fun fail(failure: AssertionError) {
            synchronized(this) { checkNotNull(failures).add(failure) }
        }

        override fun close() {
            check(synchronized(this) { failures } == null)
        }
    }
}
