package co.datapipelines.auth

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The §7.6 matrix as an enforceable structure, asserted against the doc so a change to
 * auth.md's tables without a code change fails the build (and vice versa). Four tables are
 * parsed — REST roles, REST key scopes, MCP roles, MCP key scopes — each with a row-count
 * guard, so a row added to the doc cannot slip past an over-permissive parser.
 *
 * ## Role-first (2026-09-20)
 * The doc's role tables carry FIVE role columns per row, so the expectation for a row is the
 * SET of roles whose cell is not ✗ — compared with [Permission.roles] of the operation's
 * permission, and with the rule that the super-admin column is ✓ everywhere (D7). No token
 * for the permission appears in the doc: the five cells ARE the predicate, which is the whole
 * point of writing the table role-first. Each REST row names its [ScopeMatrix.RestOperation]
 * constant in code font, so no hand-written label map is needed and a renamed label cannot
 * detach a row from its code. Reserved rows (a documented row with NO constant — today the
 * audit log, D12) are listed by name here so a real row cannot hide as one.
 *
 * Also exercises the key-scope-subset privilege-escalation guard (§7.4).
 */
class ScopeMatrixSpecDriftTest {
    private val doc = RepoFiles.read(RepoFiles.AUTH_SPEC_PATH)

    @Test
    fun `the REST role table has exactly one row per RestOperation, plus the reserved rows`() {
        val rows = RoleMatrixDoc.restRoleRows(doc)

        rows.mapNotNull { it.operation } shouldContainExactly ScopeMatrix.RestOperation.entries.toList()
        rows.filter { it.operation == null }.map { it.label } shouldContainExactly RESERVED_ROWS
    }

    @Test
    fun `every REST operation's roles match its documented row`() {
        RoleMatrixDoc.restRoleRows(doc).filter { it.operation != null }.forEach { row ->
            val operation = row.operation ?: return@forEach
            withClue("§7.6 row '${row.label}' (${operation.name})") {
                row.cells.allowedRoles shouldContainExactlyInAnyOrder operation.permission.roles
                row.cells.superAdminAllowed.shouldBeTrue()
            }
        }
    }

    @Test
    fun `every REST operation's key scope matches the key-scope table`() {
        val fromDoc = RoleMatrixDoc.restKeyScopes(doc)

        fromDoc.keys shouldContainExactlyInAnyOrder ScopeMatrix.RestOperation.entries.map { it.name }
        ScopeMatrix.RestOperation.entries.forEach { operation ->
            withClue("key scope of ${operation.name}") { fromDoc[operation.name] shouldBe operation.minScope }
        }
    }

    @Test
    fun `every MCP tool's roles match auth-md §7-6`() {
        val fromDoc = RoleMatrixDoc.mcpRoleRows(doc)

        // All 41 tools present (auth.md §7.6 / mcp-server §6.2) — 18 → 20 with 037's
        // data-visibility pair, 20 → 21 with 040's `templates_used_by`, 21 → 22 with 068's
        // `datasources_create`, 22 → 24 with 072's `calculators_list` / `calculators_get`,
        // 24 → 28 with 074's four `endpoints_*` tools, 28 → 31 with 089's three
        // `lake_tables_*` tools, 31 → 30 with 094 REMOVING `datasources_create`
        // (no credential travels through an agent), 30 → 34 with 107's
        // `datasources_get_table_stats`, `sql_probe`, `executions_cancel` and
        // `templates_purge_draft`, 34 → 35 with 117's `templates_update`; 35 → 38 with 118's
        // `semantics_record` / `semantics_list` / `semantics_retire`; 38 → 40 with 120's
        // `docs_list` / `docs_get`; 40 → 41 with 140's `pipelines_run_checks`.
        fromDoc.size shouldBe TOOL_COUNT
        fromDoc.keys shouldContainExactlyInAnyOrder ScopeMatrix.MCP_TOOL_MIN_PERMISSION.keys
        fromDoc.forEach { (tool, cells) ->
            val permission = ScopeMatrix.MCP_TOOL_MIN_PERMISSION.getValue(tool)
            withClue("§7.6 MCP row for `$tool`") {
                cells.allowedRoles shouldContainExactlyInAnyOrder permission.roles
                cells.superAdminAllowed.shouldBeTrue()
            }
        }
    }

    @Test
    fun `every MCP tool minimum scope matches auth-md §7-6`() {
        val fromDoc = RoleMatrixDoc.mcpKeyScopes(doc)

        fromDoc.size shouldBe TOOL_COUNT
        ScopeMatrix.MCP_TOOL_MIN_SCOPE shouldContainExactly fromDoc
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

    private companion object {
        const val TOOL_COUNT = 42

        /** Documented rows with no code behind them yet — each one a decision the record made ahead of a surface. */
        val RESERVED_ROWS = listOf("Read the audit log — **reserved** (D12)")
    }
}
