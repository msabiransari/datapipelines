package co.datapipelines.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.pattern.CompositeConverter

/**
 * The console format's redaction word (`%dpRedact(...)`, #337-c): renders its child pattern and
 * returns the text through [LogRedactor.scrubText]. The message, the exception chain and the
 * correlation fields are each wrapped in it by [FormatSwitchingEncoder]'s pattern, so a rendered
 * word cannot bypass the scrub by being placed elsewhere.
 *
 * It replaces the nested `%replace(%replace(...){assignment}){json}` of #337-b. Two `%replace`
 * calls are two passes: the first rewrites text inside a later match's value (and can consume an
 * escape with it), so the second reads a boundary the original text never had and a secret tail
 * survives. One converter over the rendered text runs the ONE scanner that the JSON format also
 * uses — the formats agree by construction, not by two generated copies of a pattern.
 *
 * Registered under its pattern word in the logger context's rule registry by the encoder that
 * builds the pattern; there is no configuration key that removes it.
 */
class RedactingConverter : CompositeConverter<ILoggingEvent>() {
    override fun transform(
        event: ILoggingEvent,
        `in`: String,
    ): String = LogRedactor.scrubText(`in`)
}
