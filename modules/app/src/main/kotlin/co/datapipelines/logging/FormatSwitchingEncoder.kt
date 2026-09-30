package co.datapipelines.logging

import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
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
 * programmatically set pattern bypasses variable substitution entirely, so the redaction regexes
 * live here, next to the [LogRedactor] they must agree with.
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

    private fun patternEncoder(): PatternLayoutEncoder =
        PatternLayoutEncoder().apply {
            pattern = REDACTING_CONSOLE_PATTERN
            charset = StandardCharsets.UTF_8
        }

    private companion object {
        /**
         * Boot's development shape with `%m` and the exception word wrapped in logback's built-in
         * `%replace` carrying BOTH redaction patterns — §9.2's layer 2 under the `console` format,
         * the same two shapes [LogRedactor.scrubText] rewrites on the structured side. Flattened
         * on purpose: no `${}` placeholder survives into a pattern value (Boot's own nested
         * defaults mangle under double substitution — the same trap that forced this encoder to
         * exist).
         */
        private fun scrubbed(word: String): String =
            "%replace(%replace($word){'${LogRedactor.messageReplacePattern()}','${LogRedactor.messageReplaceReplacement()}'})" +
                "{'${LogRedactor.exceptionReplacePattern()}','${LogRedactor.exceptionReplaceReplacement()}'}"

        /**
         * The two correlation slots, by NAME (#337-b F4, observability.md §3.3): `%X{key:--}` reads
         * exactly one MDC entry and prints `-` when it is absent, so an event with no context shows
         * no id and never a stale one. Deliberately not a bare `%X`, which dumps every MDC entry
         * — a member the redaction key list would then have to catch. Wrapped in the same
         * `%replace` as the message so no rendered word bypasses the scrub.
         */
        private const val CORRELATION_FIELDS = "correlation_id=%X{correlation_id:--} execution_id=%X{execution_id:--} "

        private val REDACTING_CONSOLE_PATTERN: String =
            "%clr(%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX}){faint} %clr(%5p){} " +
                "%clr(--- %esb(){APPLICATION_NAME}%esb{APPLICATION_GROUP}[%15.15t]){faint} " +
                "%clr(%-40.40logger{39}){cyan} %clr(:){faint} " +
                scrubbed(CORRELATION_FIELDS) + scrubbed("%m") + "%n" + scrubbed("%wEx")
    }
}
