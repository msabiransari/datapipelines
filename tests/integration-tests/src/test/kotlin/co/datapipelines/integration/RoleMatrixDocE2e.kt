package co.datapipelines.integration

import java.io.File

/**
 * The auth.md §7.6 permission catalog, parsed for the role walk (#215) — the sibling of auth's
 * test-side `RoleMatrixDoc` (test fixtures do not cross module boundaries, module-structure §4.2,
 * and this module compiles against `:modules:app` alone, so the roles here are STRINGS and the
 * permissions their `<functionality>.<permission>` WIRE names).
 *
 * ONE walker, keyed by the bold marker that opens the table. A table ends at the first non-blank
 * line after its rows that is not a table row — never at a prose sentence about the content (the
 * 068 lesson).
 *
 * The cell alphabet is §7.6's: ✗ and `fenced` refuse; ✓, `own`, `all` and `lens` admit. Anything
 * else throws, naming the cell — a cell that parsed as "refused" by accident would be a row
 * silently refusing a role the record admits, and the walk would then demand a refusal the
 * server rightly does not give.
 */
object RoleMatrixDocE2e {
    const val AUTH_SPEC_PATH = "docs/auth.md"
    const val CATALOG_MARKER = "**The catalog — permissions and roles:**"

    /** The four workspace roles in the doc's column order, then super admin. */
    val ROLE_COLUMNS = listOf("viewer", "author", "promoter", "workspace_admin", "super_admin")

    /** One row's verdict per role: `role -> admitted`, and the routes and tools the row places. */
    data class Cells(
        val label: String,
        val byRole: Map<String, Boolean>,
        val routes: List<String> = emptyList(),
        val tools: List<String> = emptyList(),
    ) {
        fun allows(role: String): Boolean = byRole.getValue(role)
    }

    /** `permission wire -> cells`, from the catalog. Reserved rows (no permission) are left out. */
    fun permissionRows(doc: String): Map<String, Cells> {
        val result = linkedMapOf<String, Cells>()
        tableRows(doc, CATALOG_MARKER)
            .filter { it.size == 2 + ROLE_COLUMNS.size }
            .filterNot { it[0] == "Permission" || it[0].startsWith("---") }
            .forEach { cells ->
                val permission = PERMISSION.find(cells[0])?.groupValues?.get(1) ?: return@forEach
                val routes =
                    CODE
                        .findAll(cells[1])
                        .map { it.groupValues[1] }
                        .filter(ROUTE::matches)
                        .toList()
                val tools =
                    CODE
                        .findAll(cells[1].substringAfter(MCP_LABEL, ""))
                        .map { it.groupValues[1] }
                        .filter(TOOL::matches)
                        .toList()
                result[permission] = Cells(cells[0], decode(cells.drop(2)), routes, tools)
            }
        return result
    }

    /** `tool -> the cells of the catalog row that places it` (after the Surfaces cell's `MCP:` label). */
    fun mcpRows(doc: String): Map<String, Cells> {
        val result = linkedMapOf<String, Cells>()
        permissionRows(doc).values.forEach { row -> row.tools.forEach { tool -> result[tool] = row } }
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
            "✗", "fenced" -> false
            "✓", "own", "all", "lens" -> true
            else -> throw IllegalArgumentException("Unknown §7.6 role cell '$token' — the alphabet is ✓ ✗ own all lens fenced")
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

    private const val MCP_LABEL = "MCP:"

    /** A dotted catalog name in code font: `pipeline.read`, `workspace.members.manage`. */
    private val PERMISSION = Regex("`([a-z_]+\\.[a-z_.]+)`")
    private val CODE = Regex("`([^`]+)`")
    private val ROUTE = Regex("(GET|POST|PUT|PATCH|DELETE) /\\S*")
    private val TOOL = Regex("[a-z][a-z_]+")
}
