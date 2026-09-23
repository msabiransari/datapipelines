package co.datapipelines.scripting

/**
 * The body does not parse (transform-nodes design §4.1: `compile` parses only; a syntax
 * error surfaces at compile, never mid-evaluation).
 *
 * [line] and [column] are 1-based, derived from the library's character offset into the
 * body. Carries the `template.validation.syntax_error` code the record §2.1 maps save-time
 * parsing refusals to.
 */
class ScriptSyntaxException(
    val line: Int,
    val column: Int,
    message: String,
) : ScriptingException(
        CODE,
        "syntax error at line $line, column $column: $message",
        mapOf("line" to line, "column" to column),
    ) {
    companion object {
        /** pipeline-contract §13.18 — the save-time parse refusal (record §2.1). */
        const val CODE = "template.validation.syntax_error"
    }
}
