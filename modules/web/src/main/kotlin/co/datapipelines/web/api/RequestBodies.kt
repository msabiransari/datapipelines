package co.datapipelines.web.api

import co.datapipelines.pipeline.RequestLimits
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * How a route that takes its body as a `String` reads it (#291, pipeline-contract §13.21).
 *
 * Spring's message converters read every other body through the constrained REST mapper; a
 * `String` body reaches the handler as raw text and the route parses it itself. Before #291
 * those routes parsed with their DOMAIN mapper — Jackson's defaults (nesting 1000, strings
 * 20M) instead of the stated constraints — and a body that was not JSON at all raised a raw
 * Jackson exception no handler maps: measured on the wire, a malformed body on
 * `POST`/`PUT /api/v1/templates`, `POST`/`PUT /api/v1/pipelines` and both imports answered
 * **500** `pipeline.execution.aborted`, with an ERROR line and a stack trace in the operator's
 * log, for the caller's own mistake.
 *
 * [readTree] parses through the route's REQUEST mapper ([RequestLimits.requestMapper] over its
 * domain mapper, built once per route), and turns any failure to READ the caller's bytes —
 * malformed, or nested past the bound — into the route family's own catalogued 400 through
 * [malformed]. It only reads: a failure on the way OUT stays ours and keeps its 500.
 */
object RequestBodies {
    /** [body] parsed by [requestMapper], or [malformed]'s refusal when it cannot be read. */
    fun readTree(
        requestMapper: ObjectMapper,
        body: String,
        malformed: (JsonProcessingException) -> ApiException,
    ): JsonNode =
        try {
            requestMapper.readTree(body)
        } catch (e: JsonProcessingException) {
            throw malformed(e)
        }
}
