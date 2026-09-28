package co.datapipelines.pipeline

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * The platform-wide request limits (#279, pipeline-contract §13.21): the byte cap every JSON
 * request body is refused past on BOTH surfaces (REST `/api/v1` and MCP `/mcp`), and the
 * Jackson [com.fasterxml.jackson.core.StreamReadConstraints] every request-body mapper is
 * built with.
 *
 * The constants live here — the module every surface already reads for the error catalog —
 * so `web` (the filter, Spring's REST mapper and every route that parses its own `String`
 * body), `mcp-server` (the transport's mapper) and `app` (the §7 bounds) share one home and
 * cannot drift. Since #291 the Jackson objects are built here too ([jsonFactory],
 * [requestMapper]): a number stated in one place and assembled in three was two drifts away
 * from a surface that parsed under the library's defaults. configuration.md is the authority
 * for the key and its default; this object is where the code keeps the one spelling.
 *
 * ## The numbers, and why these
 *
 *  - **Body cap, 2 MiB** (`DEFAULT_MAX_REQUEST_BYTES`): Tomcat's own default
 *    `max-http-form-post-size` (the bound this cap extends from form posts to JSON bodies),
 *    comfortably above every declared product cap (the largest legitimate body — a pipeline
 *    with a 256K-char template reference, a 4 MiB evaluate RESPONSE being an output, not an
 *    input), and 2× the avatar proxy's own 1 MiB, which therefore stays reachable.
 *  - **Jackson nesting depth, 100**: the transform engine's own depth bound; a request-body
 *    document deeper than that is adversarial — the pipeline/template/parameter schemas are
 *    flat by construction.
 *  - **Jackson string length, 4M chars**: unreachable under the default byte cap (a capped
 *    body cannot carry a longer string), generous if an operator raises the cap. Guards a
 *    parser allocating for a string that no product surface could have produced.
 *  - **Jackson number length, 1000 digits**: the pinned Jackson 2.21.5 default, stated
 *    rather than inherited; the textual BIG-number paths carry the tighter §6.3 digit cap.
 */
object RequestLimits {
    /** §13.21 — the default body cap: 2 MiB, 2_097_152 bytes. */
    const val DEFAULT_MAX_REQUEST_BYTES: Long = 2_097_152L

    /** configuration.md §3.31 — the lowest body cap a deployment may configure. */
    const val MIN_REQUEST_BYTES: Long = 65_536L

    /** configuration.md §3.31 — the highest body cap a deployment may configure (64 MiB). */
    const val MAX_REQUEST_BYTES: Long = 67_108_864L

    /** The deepest JSON nesting a request body may carry. */
    const val MAX_NESTING_DEPTH: Int = 100

    /** The longest JSON string (in characters) a request body may carry. */
    const val MAX_STRING_LENGTH: Int = 4_194_304

    /** The longest JSON number (in digits) a request body may carry. */
    const val MAX_NUMBER_LENGTH: Int = 1000

    /** The stated constraints, as Jackson reads them (#291: the ONE place they are built). */
    fun streamReadConstraints(): StreamReadConstraints =
        StreamReadConstraints
            .builder()
            .maxNestingDepth(MAX_NESTING_DEPTH)
            .maxStringLength(MAX_STRING_LENGTH)
            .maxNumberLength(MAX_NUMBER_LENGTH)
            .build()

    /**
     * A `JsonFactory` carrying [streamReadConstraints] — what Spring's REST mapper and the MCP
     * transport's mapper are built over.
     */
    fun jsonFactory(): JsonFactory =
        JsonFactory
            .builder()
            .streamReadConstraints(streamReadConstraints())
            .build()

    /**
     * The REQUEST copy of a route's domain [mapper] (#291): the same modules and features — a
     * route's numbers and bindings must not change under the caller — over [jsonFactory].
     *
     * A route that takes its body as a `String` and parses it itself reads the body through
     * this copy, never through the domain mapper: the domain mappers (`PipelineJson`,
     * `TemplateJson`, `ExecutorJson`, …) also read stored rows, transform values and JSON
     * columns, where a document deeper than a request may be is legitimate data. A request
     * limit is a statement about request bytes, so it lives on the request's copy alone.
     *
     * `copy()` then the copy's OWN factory: `copyWith(JsonFactory)` is refused at run time for a
     * `JsonMapper` on the pinned Jackson ("does not override copy()/copyWith()"), and `copy()`
     * duplicates the factory, so constraining the copy's leaves the domain mapper's untouched —
     * `RequestLimitsTest` pins both halves.
     */
    fun requestMapper(mapper: ObjectMapper): ObjectMapper =
        mapper.copy().also { copy -> copy.factory.setStreamReadConstraints(streamReadConstraints()) }
}
