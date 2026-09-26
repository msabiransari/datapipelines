package co.datapipelines.mcp.docs

import co.datapipelines.pipeline.PipelineErrorCodes
import java.lang.reflect.Modifier

/**
 * Renders `core-error-codes` — the whole §13 catalog as the agent reads it (record §3.3
 * generator (b)): every catalogued code once, with its HTTP status, its family anchor in
 * pipeline-contract §13 and the server's own non-technical message, through [DocErrorCatalog].
 *
 * The codes are enumerated the house way — plain Java reflection over `PipelineErrorCodes`'s
 * `const val`s, which `PipelineErrorCodesSpecDriftTest` already proved is the code-side catalog
 * (§13 ⊆ constants, in both directions). The reference therefore cannot describe a code the
 * server does not ship, and a code with no constant cannot exist for the drift tests to miss;
 * `DocSetStructureTest` holds the rendered table to §13's own parse, so the two authorities
 * cannot drift apart silently.
 */
object ErrorCodesReferenceGenerator {
    /** The generated `core-error-codes` document. */
    fun render(errorCatalog: DocErrorCatalog): Doc {
        val codes = declaredCodes()
        val byAnchor = codes.groupBy { errorCatalog.describe(it).familyAnchor }
        val out = StringBuilder()
        out.append("# Error codes\n\n")
        out.append(HEADER.trimIndent()).append("\n")
        for ((anchor, familyCodes) in byAnchor.entries.sortedBy { it.key }) {
            out.append("\n## ").append(familyTitle(anchor)).append("\n\n")
            out.append("Catalogued at pipeline-contract §13#$anchor.\n\n")
            out.append("| Code | HTTP | The server's message |\n|---|---|---|\n")
            for (code in familyCodes.sorted()) {
                val row = errorCatalog.describe(code)
                out
                    .append("| `")
                    .append(code)
                    .append("` | ")
                    .append(row.status)
                    .append(" | ")
                    .append(cell(row.userMessage))
                    .append(" |\n")
            }
        }
        val markdown = out.toString()
        return Doc(
            name = "core-error-codes",
            area = DocArea.CORE,
            layer = DocLayer.REFERENCE,
            title = "Error codes",
            purpose = "Every catalogued code with its status and the server's message — open it when a tool answered isError.",
            markdown = markdown,
            sections = MarkdownSections.cut(markdown),
        )
    }

    /**
     * Every `const val` of [PipelineErrorCodes] and its nested objects — the same walk
     * `PipelineErrorCodesSpecDriftTest` runs over the test classpath, here on the main one.
     * Sorted, duplicates impossible (a repeated constant spelling is a compile error upstream).
     */
    private fun declaredCodes(): List<String> =
        walk(PipelineErrorCodes)
            .flatMap { obj ->
                obj.fields
                    .filter { it.type == String::class.java && Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) }
                    .map { it.get(null) as String }
            }.distinct()
            .sorted()

    private fun walk(root: Any): List<Class<*>> {
        val visited = mutableSetOf<Class<*>>()
        val queue = ArrayDeque<Class<*>>(listOf(root::class.java))
        val result = mutableListOf<Class<*>>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            result.add(current)
            for (nested in current.declaredClasses) queue.addLast(nested)
        }
        return result
    }

    /** `1310-result-retrieval` → `Result retrieval`; `1313-versioning--draft-release-lifecycle--promotion` → its words. */
    private fun familyTitle(anchor: String): String =
        anchor
            .replace(Regex("^[0-9]+-"), "")
            .replace("--", " — ")
            .replace("-", " ")
            .replaceFirstChar { it.uppercaseChar() }

    /** A table cell: one line, and never a raw `|`. */
    private fun cell(text: String): String = text.replace(Regex("\\s+"), " ").trim().replace("|", "\\|")

    private const val HEADER =
        """
        Open when a tool answered `isError: true`: the code's HTTP status and the server's own
        non-technical message. The `Caused by` chain's LAST entry is the root cause, and
        `error.correlation_id` is what joins the failure to the server log — quote it when you
        escalate. `core` names the document to re-read for the whole rule set.
        """
}
