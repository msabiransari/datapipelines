package co.datapipelines.typesystem.body

/**
 * The default diagnostics policy for anything that ran over a structured request body
 * (published-mutation requirement §3.4, §4 and §6; #1038): a failure discloses its catalogued code
 * and the detail keys named here, and nothing a caller or author typed. The message is the code.
 *
 * One list for every surface that accepts a body or a body fixture — the pipeline executor's
 * failure record and the authored test runner's case outcome (`templates_evaluate`, the REST
 * evaluate route, the save- and release-time suites and the editor's detail pass) — so the
 * disclosure cannot differ between a full run and an authored evaluation of the same fixture.
 *
 * Every key is an identifier the author chose for the artefact (a node, an input, a test case),
 * a count or position the platform computed, or a fixed vocabulary word (`reason` is the decoder's
 * wire word, `rule` a contract-rule word, `timeout` the suite-budget word). Deliberately absent:
 * `message`, `first_reasons`, `invariant`, `column`, `value`, `sql`, `assertion`, `console` and
 * anything not named here — a new detail key is hidden until it is added.
 */
object StructuredBodyDiagnostics {
    /** The detail keys a bounded failure keeps. */
    val SAFE_DETAILS: Set<String> =
        setOf(
            "node",
            "node_id",
            "phase",
            "rule",
            "reason",
            "rejected",
            "batch",
            "row",
            "rows",
            "max_input_rows",
            "sql_state",
            "vendor_code",
            "execution_id",
            "input",
            "case",
            "timeout",
            "template_id",
            "version",
        )

    /** [details] reduced to [SAFE_DETAILS]. */
    fun bounded(details: Map<String, Any?>): Map<String, Any?> = details.filterKeys { it in SAFE_DETAILS }
}
