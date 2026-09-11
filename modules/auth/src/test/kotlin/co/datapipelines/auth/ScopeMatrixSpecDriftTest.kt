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
            "Register / update / delete a datasource bound to THIS workspace" to
                ScopeMatrix.RestOperation.MUTATE_WORKSPACE_DATASOURCES,
            "Register / update / delete an INSTANCE datasource" to ScopeMatrix.RestOperation.MUTATE_DATASOURCES,
            "Manage own API keys" to ScopeMatrix.RestOperation.MANAGE_OWN_API_KEYS,
            "Get current principal" to ScopeMatrix.RestOperation.CURRENT_PRINCIPAL,
            "Set own theme preference" to ScopeMatrix.RestOperation.PROFILE_PREFERENCE,
            "User administration" to ScopeMatrix.RestOperation.USER_ADMINISTRATION,
            "List / read own workspaces & members" to ScopeMatrix.RestOperation.WORKSPACES_READ,
            "Create a workspace" to ScopeMatrix.RestOperation.WORKSPACE_CREATE,
            "Update a workspace / manage its members" to ScopeMatrix.RestOperation.MANAGE_WORKSPACE,
            "Change own password" to ScopeMatrix.RestOperation.CHANGE_OWN_PASSWORD,
            "Serve a published endpoint" to ScopeMatrix.RestOperation.SERVE_PUBLISHED_ENDPOINT,
            "Manage published endpoints" to ScopeMatrix.RestOperation.MANAGE_ENDPOINTS,
            // 089 §A — the dp-lake catalog writes sit on the datasource-mutation floor. Listed
            // LAST, matching the row's position at the end of the §7.6 REST table.
            "Register / import / unregister lake tables of a datasource (the dp-lake catalog)" to
                ScopeMatrix.RestOperation.MUTATE_LAKE_TABLES,
            // RBAC round 1 — the verbs that got their own operation class when capability
            // stopped being global. Listed LAST, matching their position at the end of the
            // §7.6 REST table.
            "Release a version" to ScopeMatrix.RestOperation.RELEASE_VERSION,
            "Switch the served version" to ScopeMatrix.RestOperation.SWITCH_SERVED_VERSION,
            "Promote to the higher environment" to ScopeMatrix.RestOperation.PROMOTE_VERSION,
            "Create / deactivate / reactivate a workspace" to ScopeMatrix.RestOperation.MANAGE_INSTANCE_WORKSPACES,
            "Add / remove members, set their flags" to ScopeMatrix.RestOperation.MANAGE_WORKSPACE_MEMBERS,
            "Grant / revoke a datasource to a workspace" to ScopeMatrix.RestOperation.MANAGE_DATASOURCE_GRANTS,
        )

    @Test
    fun `every MCP tool minimum scope matches auth-md §7-6`() {
        val fromDoc = parseMcpTable(RepoFiles.read(RepoFiles.AUTH_SPEC_PATH), column = SCOPE_COLUMN).mapValues { Scope.fromWire(it.value) }

        // All 35 tools present (auth.md §7.6 / mcp-server §6.2) — 18 → 20 with 037's
        // data-visibility pair, 20 → 21 with 040's `templates_used_by`, 21 → 22 with 068's
        // `datasources_create`, 22 → 24 with 072's `calculators_list` / `calculators_get`,
        // 24 → 28 with 074's four `endpoints_*` tools, 28 → 31 with 089's three
        // `lake_tables_*` tools, 31 → 30 with 094 REMOVING `datasources_create`
        // (no credential travels through an agent), 30 → 34 with 107's
        // `datasources_get_table_stats`, `sql_probe`, `executions_cancel` and
        // `templates_purge_draft`, and 34 → 35 with 117's `templates_update`.
        fromDoc.size shouldBe 35
        ScopeMatrix.MCP_TOOL_MIN_SCOPE shouldContainExactly fromDoc
    }

    @Test
    fun `the REST table has exactly the documented rows, in order`() {
        val rows = parseRestTable(RepoFiles.read(RepoFiles.AUTH_SPEC_PATH))

        // Row-count guard: a new §7.6 REST row must be wired, not silently ignored.
        rows.size shouldBe docRowToOperation.size
        rows.map { it.label } shouldContainExactly docRowToOperation.map { it.first }
    }

    @Test
    fun `every REST operation minimum scope matches its documented row`() {
        val rows = parseRestTable(RepoFiles.read(RepoFiles.AUTH_SPEC_PATH))

        rows.forEachIndexed { index, row ->
            val operation = docRowToOperation[index].second
            (row.label to operation.minScope) shouldBe (row.label to (row.scope ?: Scope.READ))
        }
    }

    @Test
    fun `every REST operation minimum capability matches its documented row`() {
        val rows = parseRestTable(RepoFiles.read(RepoFiles.AUTH_SPEC_PATH))

        rows.forEachIndexed { index, row ->
            val operation = docRowToOperation[index].second
            // No default: a row whose capability cell does not parse must fail loudly, not
            // resolve to the weakest capability. The whole point of the second axis is that a
            // missing answer is a build failure rather than an open door.
            (row.label to operation.capability) shouldBe (row.label to row.capability)
        }
    }

    @Test
    fun `every MCP tool minimum capability matches auth-md §7-6`() {
        val fromDoc =
            parseMcpTable(RepoFiles.read(RepoFiles.AUTH_SPEC_PATH), column = CAPABILITY_COLUMN)
                .mapValues { Capability.fromWire(it.value) }
        fromDoc.size shouldBe 35
        ScopeMatrix.MCP_TOOL_MIN_CAPABILITY shouldContainExactly fromDoc
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

    /** One §7.6 REST row as the doc states it — both axes, so neither can drift unnoticed. */
    private data class RestRow(
        val label: String,
        val scope: Scope?,
        val capability: Capability?,
    )

    /** The documented rows of the §7.6 REST table, in document order. */
    private fun parseRestTable(doc: String): List<RestRow> {
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
            .map { cells -> RestRow(cells[0], scopeCell(cells[2]), capabilityCell(cells[3])) }
            .toList()
    }

    private fun capabilityCell(cell: String): Capability? =
        Regex("`([a-z_]+)`")
            .find(cell)
            ?.groupValues
            ?.get(1)
            ?.let { Capability.fromWire(it) }

    private fun scopeCell(cell: String): Scope? =
        Regex("`([a-z]+)`")
            .find(cell)
            ?.groupValues
            ?.get(1)
            ?.let { Scope.fromWire(it) }

    /**
     * The §7.6 MCP table as `tool -> the token in column [column]`. Both axes are parsed by
     * the same walker, keyed by column index, because a second hand-written parser is a second
     * place for the two tables to disagree about where a table ends.
     *
     * [column] is counted from the RIGHT (0 = the last cell), so the tool cell — which is the
     * one that varies in width, listing many tools per row — stays "everything before the
     * axes" without the parser having to know how many tools a row holds.
     */
    private fun parseMcpTable(
        doc: String,
        column: Int,
    ): Map<String, String> {
        val start = doc.indexOf("**MCP tools**")
        require(start >= 0) { "Could not find the MCP tools table in ${RepoFiles.AUTH_SPEC_PATH}" }
        // The table ends at the first non-blank line after it that is not a table row.
        //
        // It used to end at a literal prose sentence ("(MCP has no datasource-management
        // tools…"), which 068 made false by shipping `datasources_create` — and the parser then
        // ran on past §7.6 and died on `dpk_` in the key-anatomy prose. An end marker that is a
        // sentence about the CONTENT is a guard that breaks whenever the content is right.
        val section =
            buildString {
                var seenRow = false
                for (line in doc.substring(start).lineSequence()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("|")) {
                        seenRow = true
                    } else if (seenRow && trimmed.isNotEmpty()) {
                        break
                    }
                    appendLine(line)
                }
            }

        val tokenRegex = Regex("`([a-z_]+)`")
        val result = linkedMapOf<String, String>()
        section
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("|") }
            .map { it.trim('|').split("|").map(String::trim) }
            .filter { it.size >= AXIS_COLUMNS + 1 }
            .forEach { cells ->
                // The axis cells carry a single backtick token each; the header / separator
                // rows have none, so they resolve to null and are skipped.
                val value = tokenRegex.find(cells[cells.size - 1 - column])?.groupValues?.get(1)
                if (value != null) {
                    val toolCell = cells.dropLast(AXIS_COLUMNS).joinToString(" ")
                    tokenRegex.findAll(toolCell).forEach { m -> result[m.groupValues[1]] = value }
                }
            }
        return result
    }

    private companion object {
        // Four since RBAC round 1: Operation | Endpoints | Min scope | Min role. The count is
        // asserted structurally rather than assumed — a row that lost a cell is dropped by the
        // filter, and the row-count guard above then names it.
        const val REST_COLUMNS = 4

        /** The two axis cells at the right of every MCP row: `| tools | scope | role |`. */
        const val AXIS_COLUMNS = 2

        /** Column indices counted from the right — 0 is the last cell. */
        const val CAPABILITY_COLUMN = 0
        const val SCOPE_COLUMN = 1
    }
}
