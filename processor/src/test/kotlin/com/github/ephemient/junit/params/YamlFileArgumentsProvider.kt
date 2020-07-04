package com.github.ephemient.junit.params

import java.nio.charset.Charset
import java.util.Optional
import java.util.stream.Stream
import kotlin.streams.asStream
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.support.AnnotationConsumer
import org.snakeyaml.engine.v2.api.ConstructNode
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.common.FlowStyle
import org.snakeyaml.engine.v2.composer.Composer
import org.snakeyaml.engine.v2.constructor.StandardConstructor
import org.snakeyaml.engine.v2.env.EnvConfig
import org.snakeyaml.engine.v2.events.Event
import org.snakeyaml.engine.v2.events.SequenceEndEvent
import org.snakeyaml.engine.v2.events.SequenceStartEvent
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode
import org.snakeyaml.engine.v2.nodes.Tag
import org.snakeyaml.engine.v2.parser.Parser
import org.snakeyaml.engine.v2.parser.ParserImpl
import org.snakeyaml.engine.v2.scanner.StreamReader

class YamlFileArgumentsProvider : ArgumentsProvider, AnnotationConsumer<YamlFileSource> {
    private lateinit var resources: List<String>
    private lateinit var encoding: Charset
    private var numDocumentsToSkip = 0
    private var composeAnchors = false

    override fun accept(annotation: YamlFileSource) {
        this.resources = annotation.resources.toList()
        this.encoding = Charset.forName(annotation.encoding)
        this.numDocumentsToSkip = annotation.numDocumentsToSkip
        this.composeAnchors = annotation.composeAnchors
    }

    override fun provideArguments(context: ExtensionContext): Stream<out Arguments> {
        val settings = LoadSettings.builder()
            .setEnvConfig(Optional.of(CustomEnvConfig(context)))
            .build()
        return resources.asSequence()
            .flatMap { loadYaml(context, settings, it).drop(numDocumentsToSkip) }
            .map { document ->
                when (document) {
                    is Iterable<*> -> Arguments.of(*document.toList().toTypedArray())
                    is Map<*, *> -> Arguments.of(*document.values.toTypedArray())
                    else -> Arguments.of(document)
                }
            }
            .asStream()
    }

    private fun loadYaml(context: ExtensionContext, settings: LoadSettings, resource: String): Sequence<Any?> {
        val constructor = FormatConstructor(settings)
        val parser = ParserImpl(
            StreamReader(
                context.requiredTestClass.getResourceAsStream(resource).bufferedReader(charset = encoding),
                settings
            ),
            settings
        )
        return if (composeAnchors) {
            val doc = constructor.constructSingleDocument(Composer(ParserWrapper(parser), settings).singleNode)
            (doc as Iterable<*>).asSequence()
        } else {
            Composer(parser, settings).asSequence().map { constructor.constructSingleDocument(Optional.ofNullable(it)) }
        }
    }
}

private class CustomEnvConfig(private val context: ExtensionContext) : EnvConfig {
    override fun getValueFor(name: String, separator: String?, value: String?, environment: String?): Optional<String> =
        when (name) {
            "UNIQUE_ID" -> Optional.of(context.uniqueId)
            "DISPLAY_NAME" -> Optional.of(context.displayName)
            "PACKAGE" -> context.testClass.map { it.`package`?.name.orEmpty() }
            "TEST_CLASS" -> context.testClass.map { it.simpleName }
            "TEST_METHOD" -> context.testMethod.map { it.name }
            else -> context.getConfigurationParameter(name)
        }
}

private class FormatConstructor(settings: LoadSettings) : StandardConstructor(settings) {
    init {
        tagConstructors.getOrPut(Tag("!format")) { ConstructFormat() }
    }

    inner class ConstructFormat : ConstructNode {
        override fun construct(node: Node): String = when (node) {
            is ScalarNode -> node.value
            is SequenceNode -> {
                if (node.isRecursive) throw IllegalArgumentException("Cannot format recursive node: $node")
                val value = constructSequence(
                    SequenceNode(Tag.SEQ, false, node.value, node.flowStyle, node.startMark, node.endMark)
                )
                val format = (value.firstOrNull() ?: throw IllegalArgumentException("Missing format: $node"))
                try {
                    String.format(
                        format as? String ?: throw IllegalArgumentException("Non-scalar format: $format"),
                        *value.drop(1).toTypedArray()
                    )
                } catch (e: Exception) {
                    throw IllegalArgumentException(
                        value.joinToString(prefix = "String.format(", postfix = ")") {
                            if (it is String) """"$it"""" else it.toString()
                        },
                        e
                    )
                }
            }
            else -> throw IllegalArgumentException("Unexpected node type: $node")
        }
    }
}

private class ParserWrapper(private val delegate: Parser) : Parser {
    private var state: Event? = null

    @Suppress("LoopWithTooManyJumpStatements")
    private val events = iterator<Event> {
        head@ while (true) {
            if (!delegate.hasNext()) return@iterator
            val event = delegate.next()
            when (event.eventId) {
                Event.ID.StreamStart -> yield(event)
                Event.ID.DocumentStart -> {
                    yield(event)
                    yield(SequenceStartEvent(Optional.empty(), Optional.empty(), true, FlowStyle.AUTO))
                    break@head
                }
                else -> {
                    yield(SequenceStartEvent(Optional.empty(), Optional.empty(), true, FlowStyle.AUTO))
                    yield(event)
                    break@head
                }
            }
        }
        val closing = mutableListOf<Event>()
        tail@ while (delegate.hasNext()) {
            val event = delegate.next()
            when (event.eventId) {
                Event.ID.DocumentStart -> Unit
                Event.ID.DocumentEnd -> {
                    closing.clear()
                    closing.add(event)
                }
                Event.ID.StreamEnd -> {
                    closing.add(event)
                    break@tail
                }
                else -> {
                    closing.clear()
                    yield(event)
                }
            }
        }
        yield(SequenceEndEvent(Optional.empty(), Optional.empty()))
        yieldAll(closing)
        yieldAll(delegate)
    }

    override fun checkEvent(choice: Event.ID): Boolean = hasNext() && peekEvent().eventId == choice

    override fun peekEvent(): Event = state ?: events.next().also { state = it }

    override fun next(): Event = state?.also { state = null } ?: events.next()

    override fun hasNext(): Boolean = state != null || events.hasNext()

    override fun remove() {
        throw UnsupportedOperationException()
    }
}
