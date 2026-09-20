package co.datapipelines.integration

import java.io.File

/**
 * The auth.md §7.6 role tables, parsed for the role walk — the sibling of auth's test-side
 * `RoleMatrixDoc` (test fixtures do not cross module boundaries, module-structure §4.2, and this
 * module compiles against `:modules:app` alone, so the roles here are STRINGS and the operations
 * are their constant NAMES).
 *
 * ONE walker for both role tables, keyed by the bold marker that opens each. A table ends at the
 * first non-blank line after its rows that is not a table row — never at a prose sentence about
 * the content (the 068 lesson).
 *
 * The cell alphabet is §7.6's: ✗ refuses; ✓, `own`, `all` and `lens` admit. Anything else throws,
 * naming the cell — a cell that parsed as "refused" by accident would be a row silently refusing
 * a role the record admits, and the walk would then demand a refusal the server rightly does not
 * give.
 */
object RoleMatrixDocE2e {
    const val AUTH_SPEC_PATH = "docs/auth.md"
    const val REST_ROLES_MARKER = "**REST endpoints — roles:**"
    const val MCP_ROLES_MARKER = "**MCP tools — roles**"

    /** The four workspace roles in the doc's column order, then super admin. */
    val ROLE_COLUMNS = listOf("viewer", "author", "promoter", "workspace_admin", "super_admin")

    /** One row's verdict per role: `role -> admitted`. */
    data class Cells(
        val label: String,
        val byRole: Map<String, Boolean>,
    ) {
        fun allows(role: String): Boolean = byRole.getValue(role)
    }

    /** `RestOperation name -> cells`, from the REST role table. Reserved rows (no constant) are left out. */
    fun restRows(doc: String): Map<String, Cells> {
        val result = linkedMapOf<String, Cells>()
        tableRows(doc, REST_ROLES_MARKER)
            .filter { it.size == 2 + ROLE_COLUMNS.size }
            .filterNot { it[0] == "Operation" || it[0].startsWith("---") }
            .forEach { cells ->
                val constant = CONSTANT.find(cells[0])?.groupValues?.get(1) ?: return@forEach
                result[constant] = Cells(cells[0], decode(cells.drop(2)))
            }
        return result
    }

    /** `tool -> cells`, from the MCP role table; rows group several tools, each gets the row's cells. */
    fun mcpRows(doc: String): Map<String, Cells> {
        val result = linkedMapOf<String, Cells>()
        tableRows(doc, MCP_ROLES_MARKER)
            .filter { it.size == 1 + ROLE_COLUMNS.size }
            .filterNot { it[0] == "Tools" || it[0].startsWith("---") }
            .forEach { cells ->
                val decoded = decode(cells.drop(1))
                TOOL.findAll(cells[0]).forEach { m -> result[m.groupValues[1]] = Cells(cells[0], decoded) }
            }
        return result
    }

    /** The doc, found by walking up from the working directory (a Gradle test task runs in its module). */
    fun read(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, AUTH_SPEC_PATH)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("$AUTH_SPEC_PATH not found walking up from ${File("").absolutePath}")
    }

    private fun decode(cells: List<String>): Map<String, Boolean> {
        require(cells.size == ROLE_COLUMNS.size) { "expected ${ROLE_COLUMNS.size} role cells, got ${cells.size}: $cells" }
        return ROLE_COLUMNS.zip(cells.map(::allowsCell)).toMap()
    }

    private fun allowsCell(cell: String): Boolean =
        when (val token = cell.trim().trim('*').trim()) {
            "✗" -> false
            "✓", "own", "all", "lens" -> true
            else -> throw IllegalArgumentException("Unknown §7.6 role cell '$token' — the alphabet is ✓ ✗ own all lens")
        }

    private fun tableRows(
        doc: String,
        marker: String,
    ): List<List<String>> {
        val start = doc.indexOf(marker)
        require(start >= 0) { "Could not find '$marker' in $AUTH_SPEC_PATH §7.6" }
        val section =
            buildString {
                var seenRow = false
                for (line in doc.substring(start + marker.length).lineSequence()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("|")) {
                        seenRow = true
                    } else if (seenRow && trimmed.isNotEmpty()) {
                        break
                    }
                    appendLine(line)
                }
            }
        return section
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("|") }
            .map { it.trim('|').split("|").map(String::trim) }
            .toList()
    }

    private val CONSTANT = Regex("`([A-Z][A-Z_]+)`")
    private val TOOL = Regex("`([a-z][a-z_]+)`")
}
