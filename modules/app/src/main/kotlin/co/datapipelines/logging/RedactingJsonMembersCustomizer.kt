package co.datapipelines.logging

import org.springframework.boot.json.JsonWriter
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer

/**
 * Applies [LogRedactor] to every member of the JSON log line (#337, observability.md §9.2 layer 1 —
 * the record's field filter, built on Boot 3.5's structured-logging customizer point instead of
 * `logstash-logback-encoder`).
 *
 * The processor sees every value the formatter writes, by member path: an MDC entry or a key-value
 * pair whose key is on the sensitive list has its VALUE replaced by `***` (the key stays — its
 * presence is itself diagnostic); the `message` member is passed through the text scrub, which is
 * layer 2 for the rendered message. The `stack_trace` member is scrubbed by the printer itself
 * ([RedactingStackTracePrinter] — one owner per channel, so each layer is falsifiable on its own).
 * There is no configuration that reaches this class other than the encoder property naming it, and
 * no call site that can choose a path around it: every member goes through the same
 * `JsonWriter.Members`.
 */
class RedactingJsonMembersCustomizer : StructuredLoggingJsonMembersCustomizer<Any> {
    override fun customize(members: JsonWriter.Members<Any>) {
        members.applyingValueProcessor(
            JsonWriter.ValueProcessor<Any?> { path, value ->
                val memberPath = path.toUnescapedString()
                val name = memberPath.substringAfterLast('.')
                when {
                    name == MESSAGE_MEMBER -> {
                        if (value is String) LogRedactor.scrubText(value) else value
                    }

                    LogRedactor.isSensitiveKey(memberPath) -> {
                        LogRedactor.MASK
                    }

                    else -> {
                        value
                    }
                }
            },
        )
    }

    private companion object {
        const val MESSAGE_MEMBER = "message"
    }
}
