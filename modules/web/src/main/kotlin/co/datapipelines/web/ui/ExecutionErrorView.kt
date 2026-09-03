package co.datapipelines.web.ui

import com.fasterxml.jackson.databind.JsonNode

/**
 * The 057 execution-error fragment's model (ui-screens §4.9): `error_json` parsed once into
 * the plain values `partials/execution-error.html` renders, shared by the execution detail
 * page and the result partial so both show the SAME structured failure — code, message,
 * correlation id, node context, rendered SQL, and the exception chain ROOT-CAUSE-FIRST
 * (the wire's `caused_by` is outermost-first; humans read the root cause first, the same
 * reversal the editor's `PEErrorDetails` performs client-side).
 *
 * `errorChain` is pre-joined (`framesText`) and pre-reversed — the fragment stays markup,
 * not string surgery, and the two surfaces cannot disagree about orientation.
 */
object ExecutionErrorView {
    fun attributes(errorJson: JsonNode?): Map<String, Any?> =
        buildMap {
            put("errorCode", errorJson.text("code"))
            put("errorMessage", errorJson.text("message"))
            put("errorUserMessage", errorJson.text("user_message"))
            put("errorCorrelationId", errorJson.text("correlation_id"))
            put("errorDocUrl", errorJson.text("doc_url"))
            put("errorSql", errorJson.text("sql"))
            put("errorNodeLine", errorJson.nodeLine("node"))
            val exception = errorJson?.path("exception")?.takeIf { it.isObject }
            put("errorDetailsJson", errorJson?.path("details")?.takeIf { it.isObject }?.toPrettyString())
            put(
                "errorChain",
                buildList {
                    // Root-cause FIRST: the wire's caused_by is outermost-first, so reverse.
                    exception
                        ?.path("caused_by")
                        ?.filterIsInstance<JsonNode>()
                        ?.reversed()
                        ?.forEach { add(level(it, "Caused by: ")) }
                    exception?.let { add(level(it, "Raised at: ")) }
                },
            )
        }

    private fun level(
        node: JsonNode,
        label: String,
    ): Map<String, String?> =
        mapOf(
            "label" to label,
            "cls" to classText(node, "class"),
            "message" to classText(node, "message"),
            "framesText" to framesText(node),
        )

    private fun classText(
        node: JsonNode,
        field: String,
    ): String? = node.path(field).takeIf { it.isTextual }?.asText()

    private fun framesText(node: JsonNode): String? =
        node
            .path("frames")
            .filterIsInstance<JsonNode>()
            .joinToString("\n") { it.asText() }
            .ifEmpty { null }

    private fun JsonNode?.text(field: String): String? =
        this
            ?.path(field)
            ?.takeIf { it.isTextual && !it.isNull }
            ?.asText()

    private fun JsonNode?.nodeLine(field: String): String? {
        val node =
            this
                ?.path(field)
                ?.takeIf { it.isObject }
                ?: return null
        val parts =
            buildList {
                node.text("id")?.let { add(it) }
                node.text("type")?.let { add(it) }
                node.text("datasource")?.let { ds ->
                    add(node.text("dialect")?.let { "$ds ($it)" } ?: ds)
                }
                node.text("template")?.let { t ->
                    node.path("template_version").takeIf { it.isInt }?.let { add("$t @ v${it.asInt()}") } ?: add(t)
                }
            }
        return parts
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" · ")
    }
}
