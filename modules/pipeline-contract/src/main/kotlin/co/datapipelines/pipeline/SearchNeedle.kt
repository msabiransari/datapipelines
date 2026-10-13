package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException

/**
 * The one search-needle rule (#504b): every `q` on every surface — REST, MCP, the UI boxes —
 * is trimmed, a blank or absent term means "no search", and a trimmed term longer than
 * [MAX_LENGTH] is refused with [SearchQueryTooLongException]. Services that OWN a search call
 * [of] at the door; render models that only DISPLAY a search box truncate the term to
 * [MAX_LENGTH] instead of refusing it.
 */
object SearchNeedle {
    /** The bound every `q` shares — the constant the UI truncates to and the refusal names. */
    const val MAX_LENGTH = 200

    /**
     * The search needle for [raw]: trimmed, or `null` when the term is absent, empty or
     * whitespace only. A trimmed term longer than [MAX_LENGTH] raises [SearchQueryTooLongException]
     * with the shared `request.query_too_long` code — the surface's translator answers it (400 on
     * REST, `-32602 invalid params` on MCP); the needle itself never carries surface vocabulary.
     */
    fun of(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.length > MAX_LENGTH) throw SearchQueryTooLongException(limit = MAX_LENGTH, length = trimmed.length)
        return trimmed
    }
}

/**
 * A search needle longer than [limit] arrived at a surface that refuses over-long terms (#504b).
 * The message speaks about the term and its bound — never a single surface's vocabulary — so the
 * same exception can answer as HTTP 400 and as an MCP `invalid params` error.
 */
class SearchQueryTooLongException(
    val limit: Int,
    val length: Int,
) : DatapipelinesException(
        code = PipelineErrorCodes.Request.QUERY_TOO_LONG,
        message = "Search term is $length characters; at most $limit characters are allowed.",
        details = mapOf("limit" to limit, "length" to length),
    )
