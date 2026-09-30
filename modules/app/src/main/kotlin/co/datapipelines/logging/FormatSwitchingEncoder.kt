package co.datapipelines.logging

import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Context
import ch.qos.logback.core.CoreConstants
import ch.qos.logback.core.encoder.Encoder
import ch.qos.logback.core.encoder.EncoderBase
import org.springframework.boot.logging.logback.StructuredLogEncoder
import java.nio.charset.StandardCharsets

/**
 * The console encoder that makes `datapipelines.observability.logging.format` real (#337, D2):
 * `json` emits the §3.1 structured line (Boot 3.5's `logstash` format, with the redacting
 * customizer and stack-trace printer supplied through the environment), `console` emits the
 * human-readable development pattern carrying the SAME redaction over the message and the
 * exception chain.
 *
 * Why an encoder and not `logging.pattern.console`: Boot wraps the console pattern into a
 * `${CONSOLE_LOG_PATTERN:-...}` fallback, and logback's variable parser consumes the whole value
 * as that default — a pattern carrying an unbalanced `}`, a bare `:` or a `$` (which a regex full
 * of character classes necessarily does) is truncated at the first brace the parser dislikes.
 * Found live: the pattern arrived at the layout cut mid-`%replace` and the line never parsed. A
 * programmatically set pattern bypasses variable substitution entirely, so the console pattern
 * lives here, next to the [LogRedactor] it must agree with — and since #337-c it carries no regex
 * at all: its redaction word is the house [RedactingConverter], which calls [LogRedactor].
 *
 * The structured half delegates to Boot's own [StructuredLogEncoder], which reads the Spring
 * Environment from the logger context for `logging.structured.json.*` — the properties the
 * [ObservabilityLoggingFormatPostProcessor] adds before logging initialises.
 */
class FormatSwitchingEncoder : EncoderBase<ILoggingEvent>() {
    /** The resolved value of the switch, wired from logback-spring.xml's `springProperty`. */
    var format: String? = null

    private var delegate: Encoder<ILoggingEvent>? = null

    override fun start() {
        val resolved = format?.trim()?.lowercase() ?: "console"
        delegate =
            when (resolved) {
                "json" -> {
                    structuredEncoder()
                }

                "console" -> {
                    patternEncoder()
                }

                else -> {
                    error(
                        "logging format: unknown value '$resolved' — expected 'json' or 'console'; refusing to start",
                    )
                }
            }
        delegate?.context = context
        delegate?.start()
        super.start()
    }

    override fun stop() {
        delegate?.stop()
        super.stop()
    }

    override fun encode(event: ILoggingEvent): ByteArray? = requireDelegate().encode(event)

    override fun headerBytes(): ByteArray? = requireDelegate().headerBytes()

    override fun footerBytes(): ByteArray? = requireDelegate().footerBytes()

    private fun requireDelegate(): Encoder<ILoggingEvent> = requireNotNull(delegate) { "encoder not started" }

    private fun structuredEncoder(): StructuredLogEncoder = StructuredLogEncoder().apply { setFormat("logstash") }

    private fun patternEncoder(): PatternLayoutEncoder {
        registerRedactionWord(context)
        return PatternLayoutEncoder().apply {
            pattern = REDACTING_CONSOLE_PATTERN
            charset = StandardCharsets.UTF_8
        }
    }

    private companion object {
        /**
         * The console pattern's redaction word, registered in the logger context's pattern-rule
         * registry — the same table a `<conversionRule>` element fills — so the encoder stays
         * self-contained: no logback-spring.xml entry can be forgotten by another configuration
         * that builds this encoder.
         */
        private const val REDACT_WORD = "dpRedact"

        /**
         * Wraps [word] in the house [RedactingConverter], which hands the RENDERED text to
         * [LogRedactor.scrubText] — §9.2's layer 2 under the `console` format. One recognizer
         * over the original text, not two nested `%replace` regexes: nested replacements rewrite
         * the output of the first before the second reads it, which is exactly how an inner
         * `secret=abc\"` could end an outer quoted value early (#337-c F5). Flattened on purpose:
         * no `${}` placeholder survives into a pattern value (Boot's own nested defaults mangle
         * under double substitution — the same trap that forced this encoder to exist).
         *
         * The empty `{}` after the closing parenthesis is load-bearing: logback's pattern parser
         * reads a `%` that directly follows a composite's `)` as LITERAL text (measured on the
         * locked 1.5.34: `%dpRedact(x)%dpRedact(y)` prints `x%dpRedact(y`), which would leave the
         * next word unparsed and unredacted with no error. After an option block the parser is back
         * in its ordinary state. [FormatSwitchingEncoderTest] pins the rendered line against any
         * stray pattern text.
         */
        private fun scrubbed(word: String): String = "%$REDACT_WORD($word){}"

        /**
         * The two correlation slots, by NAME (#337-b F4, observability.md §3.3): `%X{key:--}` reads
         * exactly one MDC entry and prints `-` when it is absent, so an event with no context shows
         * no id and never a stale one. Deliberately not a bare `%X`, which dumps every MDC entry
         * — a member the redaction key list would then have to catch. Wrapped in the same
         * redaction word as the message so no rendered word bypasses the scrub.
         */
        private const val CORRELATION_FIELDS = "correlation_id=%X{correlation_id:--} execution_id=%X{execution_id:--} "

        private val REDACTING_CONSOLE_PATTERN: String =
            "%clr(%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX}){faint} %clr(%5p){} " +
                "%clr(--- %esb(){APPLICATION_NAME}%esb{APPLICATION_GROUP}[%15.15t]){faint} " +
                "%clr(%-40.40logger{39}){cyan} %clr(:){faint} " +
                scrubbed(CORRELATION_FIELDS) + scrubbed("%m") + "%n" + scrubbed("%wEx")

        /** Adds [REDACT_WORD] to the context's pattern-rule registry, creating it when absent. */
        fun registerRedactionWord(context: Context) {
            val existing = context.getObject(CoreConstants.PATTERN_RULE_REGISTRY)
            val registry =
                if (existing is MutableMap<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    existing as MutableMap<String, String>
                } else {
                    HashMap<String, String>().also { context.putObject(CoreConstants.PATTERN_RULE_REGISTRY, it) }
                }
            registry[REDACT_WORD] = RedactingConverter::class.java.name
        }
    }
}
