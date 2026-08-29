package co.datapipelines.auth

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The §7.6 scope matrix as an enforceable structure, asserted against the doc so a
 * change to auth.md's tables without a code change fails the build (and vice versa).
 * Both halves are parsed — MCP tools **and** REST operations (AU-API-2) — each with a
 * row-count guard, so a row added to the doc cannot slip past an over-permissive
 * parser. Also exercises the key-scope-subset privilege-escalation guard (§7.4).
 */
class ScopeMatrixSpecDriftTest {
    /**
     * The doc's REST rows, in document order, mapped to the enum constant that
     * implements each. The mapping is explicit rather than inferred: an operation
     * label is prose, and guessing at it is how a renamed row silently stops being
     * checked. A row added to §7.6 fails [`the REST table has exactly the documented
     * rows`] until it is listed here and given an enum constant.
     */
    private val docRowToOperation: List<Pair<String, ScopeMatrix.RestOperation>> =
        listOf(
            "Read pipelines / templates / datasources (metadata) / executions" to ScopeMatrix.RestOperation.READ_RESOURCES,
            "Retrieve execution results (cursor)" to ScopeMatrix.RestOperation.RETRIEVE_RESULT,
            "Execute a pipeline" to ScopeMatrix.RestOperation.EXECUTE_PIPELINE,
            "Cancel an execution" to ScopeMatrix.RestOperation.CANCEL_EXECUTION,
            "Create / update / delete pipelines & templates, import" to ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES,
            "Test a datasource connection" to ScopeMatrix.RestOperation.TEST_DATASOURCE,
            "Introspect a datasource schema" to ScopeMatrix.RestOperation.INTROSPECT_DATASOURCE,
            "Create / update / delete workspace-bound datasources" to ScopeMatrix.RestOperation.MUTATE_WORKSPACE_DATASOURCES,
            "Create / update / delete global datasources" to ScopeMatrix.RestOperation.MUTATE_DATASOURCES,
            "Manage own API keys" to ScopeMatrix.RestOperation.MANAGE_OWN_API_KEYS,
            "Get current principal" to ScopeMatrix.RestOperation.CURRENT_PRINCIPAL,
            "User administration" to ScopeMatrix.RestOperation.USER_ADMINISTRATION,
            "List / read own workspaces & members" to ScopeMatrix.RestOperation.WORKSPACES_READ,
            "Create a workspace (per provisioning mode)" to ScopeMatrix.RestOperation.WORKSPACE_CREATE,
            "Update a workspace / manage its members" to ScopeMatrix.RestOperation.MANAGE_WORKSPACE,
        )

    @Test
    fun `every MCP tool minimum scope matches auth-md §7-6`() {
        val fromDoc = parseMcpTable(RepoFiles.read(RepoFiles.AUTH_SPEC_PATH))

        // All 18 tools present (auth.md §7.6 / mcp-server §6.2).
        fromDoc.size shouldBe 18
        ScopeMatrix.MCP_TOOL_MIN_SCOPE shouldContainExactly fromDoc
    }

    @Test
    fun `the REST table has exactly the documented rows, in order`() {
        val rows = parseRestTable(RepoFiles.read(RepoFiles.AUTH_SPEC_PATH))

        // Row-count guard: a new §7.6 REST row must be wired, not silently ignored.
        rows.size shouldBe docRowToOperation.size
        rows.map { it.first } shouldContainExactly docRowToOperation.map { it.first }
    }

    @Test
    fun `every REST operation minimum scope matches its documented row`() {
        val rows = parseRestTable(RepoFiles.read(RepoFiles.AUTH_SPEC_PATH))

        rows.forEachIndexed { index, (label, documentedScope) ->
            val operation = docRowToOperation[index].second
            // "any authenticated" (Manage own API keys) resolves to `read`: it is the
            // floor of the §7.5 hierarchy — every scope implies it — so requiring read
            // IS "any authenticated principal". §7.4's subset guard is the real control.
            val expected = documentedScope ?: Scope.READ
            (label to operation.minScope) shouldBe (label to expected)
        }
    }

    @Test
    fun `every RestOperation constant is claimed by a documented row`() {
        docRowToOperation.map { it.second } shouldContainExactly ScopeMatrix.RestOperation.entries.toList()
    }

    @Test
    fun `key-scope-subset guard - requested must be within creator effective scopes`() {
        // A read-scoped creator cannot mint an author key (§7.4).
        ScopeMatrix.keyScopesWithinCreator(setOf(Scope.AUTHOR), setOf(Scope.READ)).shouldBeFalse()
        // An author creator can mint read/execute/author (hierarchy expansion), but not admin.
        ScopeMatrix.keyScopesWithinCreator(setOf(Scope.READ, Scope.EXECUTE, Scope.AUTHOR), setOf(Scope.AUTHOR)).shouldBeTrue()
        ScopeMatrix.keyScopesWithinCreator(setOf(Scope.ADMIN), setOf(Scope.AUTHOR)).shouldBeFalse()
        // Admin creator can mint anything.
        ScopeMatrix.keyScopesWithinCreator(setOf(Scope.ADMIN), setOf(Scope.ADMIN)).shouldBeTrue()
    }

    /** `operation label -> documented minimum` (null = the doc's "any authenticated"). */
    private fun parseRestTable(doc: String): List<Pair<String, Scope?>> {
        val start = doc.indexOf("**REST endpoints:**")
        require(start >= 0) { "Could not find the REST endpoints table in ${RepoFiles.AUTH_SPEC_PATH}" }
        val end = doc.indexOf("**MCP tools**", start)
        require(end > start) { "Could not find the end of the REST endpoints table" }

        return doc
            .substring(start, end)
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("|") }
            .map { it.trim('|').split("|").map(String::trim) }
            .filter { it.size == REST_COLUMNS }
            .filterNot { it[0] == "Operation" || it[0].startsWith("---") }
            .map { cells -> cells[0] to scopeCell(cells[2]) }
            .toList()
    }

    private fun scopeCell(cell: String): Scope? =
        Regex("`([a-z]+)`")
            .find(cell)
            ?.groupValues
            ?.get(1)
            ?.let { Scope.fromWire(it) }

    private fun parseMcpTable(doc: String): Map<String, Scope> {
        val start = doc.indexOf("**MCP tools**")
        require(start >= 0) { "Could not find the MCP tools table in ${RepoFiles.AUTH_SPEC_PATH}" }
        // The table ends at the parenthetical note that follows it in §7.6.
        val end = doc.indexOf("(MCP has no datasource-management tools", start)
        val section = doc.substring(start, if (end >= 0) end else doc.length)

        val tokenRegex = Regex("`([a-z_]+)`")
        val result = linkedMapOf<String, Scope>()
        section
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("|") }
            .map { it.trim('|').split("|").map(String::trim) }
            .filter { it.size >= 2 }
            .forEach { cells ->
                // The scope cell (last) carries a single backtick token; the header /
                // separator rows have none, so they resolve to null and are skipped.
                val scope =
                    tokenRegex
                        .find(cells.last())
                        ?.groupValues
                        ?.get(1)
                        ?.let { Scope.fromWire(it) }
                if (scope != null) {
                    val toolCell = cells.dropLast(1).joinToString(" ")
                    tokenRegex.findAll(toolCell).forEach { m -> result[m.groupValues[1]] = scope }
                }
            }
        return result
    }

    private companion object {
        const val REST_COLUMNS = 3
    }
}
