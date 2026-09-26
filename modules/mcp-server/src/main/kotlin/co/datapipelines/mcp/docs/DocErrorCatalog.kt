package co.datapipelines.mcp.docs

/**
 * One row of the generated error-code reference: the code's HTTP status, its catalog family's
 * anchor in pipeline-contract §13, and the server's own non-technical message (rest-api §4.2).
 *
 * The data lives with the REST surface's error rendering — `ApiErrorCatalog` in `web`, the one
 * projection of §13's status and `user_message` columns — and `mcp-server` must not depend on
 * `web` (module-structure §5.8). So the renderer codes against this port and the assembled
 * application supplies the implementation from `web` (the 068/074 pattern: wherever the MCP
 * surface is wired, the collaborator's bean exists).
 */
interface DocErrorCatalog {
    /**
     * The row for [code], which is a constant of `PipelineErrorCodes` — the implementation must
     * answer every code the enumeration walks, including the family-default ones, so a missing
     * row is a defect of the implementation, not a gap to skip over: implementations throw.
     */
    fun describe(code: String): ErrorDocRow

    /** The status, family anchor and user message of one catalogued code. */
    data class ErrorDocRow(
        /** The HTTP status the REST envelope answers with (§13's HTTP column). */
        val status: Int,
        /** The §13 family anchor the code is catalogued under, e.g. `1310-result-retrieval`. */
        val familyAnchor: String,
        /** The non-technical `user_message` the REST envelope carries for [status]. */
        val userMessage: String,
    )
}
