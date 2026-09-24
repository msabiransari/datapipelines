package co.datapipelines.web.api

import co.datapipelines.auth.Permission
import co.datapipelines.auth.PublicPaths
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.mcp.McpToolCatalog
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import org.junit.jupiter.api.Test
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPatternParser
import java.io.File

/**
 * Gate 3 of the roles design, re-keyed to the permission catalog (#215, record §7 gate 2):
 * **every catalog permission is claimed, and every route and tool sits on the §7.6 row of the
 * permission it declares** — in both directions.
 *
 * - A permission is CLAIMED when a handler declares it, a tool declares it, or a main-source
 *   service CHECK asks for it (`holds(Permission.X)`, `permits(Permission.X)`,
 *   `Permission.X.satisfiedBy(`, `requirePermission(…, Permission.X)`,
 *   `requireInstancePermission(…, Permission.X)`) — the check is the claim, which is how the
 *   four rows with no surface of their own (`execution.read_all`, `execution.cancel_all`,
 *   `server_key.create`, `server_key.revoke`) are reachable. A row nothing claims is a lie in the
 *   doc: it reads as an enforced rule and enforces nothing.
 * - Every governed route is named in auth.md §7.6's Surfaces cell of EXACTLY the row whose
 *   permission it declares, and every route the table names is a governed handler — so the
 *   placement the doc shows the owner is the placement the code enforces.
 * - Every catalogued MCP tool declares a permission, and every declared tool is catalogued.
 *
 * `ScopeMatrixSpecDriftTest` proves the catalog equals the doc's cells; this proves the doc's
 * placement equals the CODE that declares it. Falsified at birth: drop a route from the table
 * and the second test names it; add a permission nothing declares and the first does.
 */
class MatrixRowReachabilityTest {
    private val parser = PathPatternParser()
    private val publicPatterns = PublicPaths.PATTERNS.map { parser.parse(it) }
    private val governed = HandlerInventory.handlers().filterNot { isPublic(it.path) }
    private val doc = repoFile("docs/auth.md").readText()

    @Test
    fun `every catalog permission is claimed by a handler, a tool or a service check`() {
        val byHandler = governed.mapNotNull { it.permission }.toSet()
        val byTool = ScopeMatrix.MCP_TOOL_PERMISSION.values.toSet()
        val byCheck = serviceChecks().keys

        val unclaimed = Permission.entries.filter { it !in byHandler && it !in byTool && it !in byCheck }
        withClue("catalog permissions nothing declares or checks — a §7.6 row nothing enforces: ${unclaimed.map { it.wire }}") {
            unclaimed.shouldBeEmpty()
        }
    }

    @Test
    fun `every governed route sits on the catalog row of the permission it declares - and every route the table names exists`() {
        val placed = placedRoutes()
        val problems = mutableListOf<String>()
        governed.groupBy { it.route }.forEach { (route, handlers) ->
            val declared = handlers.mapNotNull { it.permission }.toSet()
            val names = handlers.joinToString { it.name }
            val rows = placed[route].orEmpty()
            when {
                declared.size != 1 -> {
                    problems += "$route ($names) declares ${declared.map { it.wire }} — one route, one permission"
                }

                rows.isEmpty() -> {
                    problems += "$route ($names, ${declared.single().wire}) is on no §7.6 catalog row"
                }

                rows != setOf(declared.single()) -> {
                    problems += "$route ($names) declares ${declared.single().wire}, §7.6 places it on ${rows.map { it.wire }}"
                }
            }
        }
        (placed.keys - governed.map { it.route }.toSet()).forEach { problems += "§7.6 names $it, which no governed handler maps" }
        withClue(problems.joinToString("\n")) { problems.shouldBeEmpty() }
    }

    @Test
    fun `every MCP tool in the matrix is a catalogued tool, and every catalogued tool is in the matrix`() {
        ScopeMatrix.MCP_TOOL_PERMISSION.keys shouldContainExactlyInAnyOrder McpToolCatalog.NAMES
    }

    /** Non-vacuity: a scan that found nothing would claim nothing and the first test would name every row. */
    @Test
    fun `the inventory, the table and the check scan all see what they exist for`() {
        governed.size shouldBeGreaterThanOrEqual MINIMUM_HANDLERS
        governed.mapNotNull { it.permission }.toSet().size shouldBeGreaterThanOrEqual MINIMUM_PERMISSIONS
        placedRoutes().size shouldBeGreaterThanOrEqual MINIMUM_HANDLERS
        serviceChecks().keys.size shouldBeGreaterThanOrEqual MINIMUM_CHECKED
    }

    /** `VERB /path` → the permissions of every catalog row whose Surfaces cell names it. */
    private fun placedRoutes(): Map<String, Set<Permission>> {
        val result = mutableMapOf<String, MutableSet<Permission>>()
        catalogRows().forEach { (permission, surfaces) ->
            CODE.findAll(surfaces).map { it.groupValues[1] }.filter(ROUTE::matches).forEach { route ->
                result.getOrPut(route) { mutableSetOf() } += permission
            }
        }
        return result
    }

    /**
     * The catalog table's permission rows: the first cell's dotted name and the Surfaces cell. The
     * table ends at the first non-blank, non-table line after its rows (the 068 rule the auth
     * parsers share).
     */
    private fun catalogRows(): List<Pair<Permission, String>> {
        val start = doc.indexOf(CATALOG_MARKER)
        require(start >= 0) { "Could not find '$CATALOG_MARKER' in auth.md §7.6" }
        val lines =
            doc
                .substring(start + CATALOG_MARKER.length)
                .lineSequence()
                .map(String::trim)
                .toList()
        val firstRow = lines.indexOfFirst { it.startsWith("|") }
        return lines
            .drop(firstRow)
            .takeWhile { it.startsWith("|") || it.isEmpty() }
            .filter { it.startsWith("|") }
            .map { row -> row.trim('|').split("|").map(String::trim) }
            .mapNotNull { cells ->
                PERMISSION
                    .find(cells[0])
                    ?.groupValues
                    ?.get(1)
                    ?.let { Permission.fromWire(it) to cells[1] }
            }
    }

    /** Every permission a main-source service check asks for, with where — the static claim scan. */
    private fun serviceChecks(): Map<Permission, List<String>> {
        val result = mutableMapOf<Permission, MutableList<String>>()
        repoFile("modules")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/src/main/kotlin/" in it.path }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val code = line.trim()
                    if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) return@forEachIndexed
                    CHECKS.flatMap { it.findAll(line).map { m -> m.groupValues[1] }.toList() }.forEach { name ->
                        val permission = Permission.entries.first { it.name == name }
                        result.getOrPut(permission) { mutableListOf() } += "${file.name}:${index + 1}"
                    }
                }
            }
        return result
    }

    private fun isPublic(path: String): Boolean = publicPatterns.any { it.matches(PathContainer.parsePath(path)) }

    private companion object {
        const val CATALOG_MARKER = "**The catalog — permissions and roles:**"

        /** The 2026-09-24 inventory: 191 governed routes, 61 permissions declared by a handler or tool, 4 by a check alone. */
        const val MINIMUM_HANDLERS = 120
        const val MINIMUM_PERMISSIONS = 40
        const val MINIMUM_CHECKED = 8

        val PERMISSION = Regex("`([a-z_]+\\.[a-z_.]+)`")
        val CODE = Regex("`([^`]+)`")
        val ROUTE = Regex("(GET|POST|PUT|PATCH|DELETE) /\\S*")

        /** The check-shaped calls that claim a permission. A new spelling of a check fails the first test until it is listed here. */
        val CHECKS =
            listOf(
                Regex("""\b(?:holds|permits)\(\s*Permission\.([A-Z_]+)\s*\)"""),
                Regex("""\bPermission\.([A-Z_]+)\.satisfiedBy\("""),
                Regex("""\brequire(?:Instance)?Permission\([^)]*Permission\.([A-Z_]+)\s*\)"""),
            )

        fun repoFile(relativePath: String): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").isFile) return File(dir, relativePath)
                dir = dir.parentFile
            }
            error("repository root (settings.gradle.kts) not found walking up from ${File("").absolutePath}")
        }
    }
}
