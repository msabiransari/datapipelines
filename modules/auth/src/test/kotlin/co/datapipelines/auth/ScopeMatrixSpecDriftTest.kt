package co.datapipelines.auth

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * **The catalog drift gate** (#215, record §7 gate 1): auth.md §7.6's permission catalog, the
 * [Permission] enum and the one role table [RolePermissions] agree in BOTH directions, with
 * counts — so a row added to the doc without code fails the build, and a permission added to
 * the code (or a role cell flipped in it) without the doc does too.
 *
 * Four comparisons, each naming what disagrees:
 * - the catalog's rows ARE `Permission.entries`, in declaration order, plus the listed reserved
 *   rows (a real row cannot hide as a reserved one);
 * - every CELL — role × permission — reads the same in the doc and in [RolePermissions], and a
 *   `fenced` row is exactly a [RolePermissions.FENCED] permission;
 * - the A4 shim: the **Permissions — key scopes** table equals [ScopeMatrix.PERMISSION_MIN_SCOPE],
 *   which must be total — a permission with no scope floor is a build failure;
 * - the MCP surface: every tool sits on the catalog row of the permission it declares in
 *   [ScopeMatrix.MCP_TOOL_PERMISSION], and the MCP key-scope table equals the scope the shim
 *   gives it.
 *
 * Also exercises the key-scope-subset privilege-escalation guard (§7.4).
 */
class ScopeMatrixSpecDriftTest {
    private val doc = RepoFiles.read(RepoFiles.AUTH_SPEC_PATH)
    private val rows = RoleMatrixDoc.catalogRows(doc)

    @Test
    fun `the catalog has exactly one row per Permission, in declaration order, plus the reserved rows`() {
        rows.mapNotNull { it.permission } shouldContainExactly Permission.entries.toList()
        rows.filter { it.permission == null }.map { it.label } shouldContainExactly RESERVED_ROWS
        Permission.entries.size shouldBe PERMISSION_COUNT
    }

    @Test
    fun `every cell of the catalog matches the role table - role by role, both directions`() {
        val mismatches = mutableListOf<String>()
        rows.forEach { row ->
            val permission = row.permission ?: return@forEach
            WorkspaceRole.entries.forEach { role ->
                val inDoc = role in row.cells.allowedRoles
                val inCode = permission in RolePermissions.of(role)
                if (inDoc != inCode) mismatches += cellMismatch(permission, role.wire, row.cells.raw[role.wire], inCode)
            }
            val superInCode = permission in RolePermissions.SUPER_ADMIN
            if (row.cells.superAdminAllowed != superInCode) {
                mismatches += cellMismatch(permission, "super_admin", row.cells.raw["super_admin"], superInCode)
            }
            if (row.cells.fenced != (permission in RolePermissions.FENCED)) {
                mismatches += "`${permission.wire}`: doc fenced=${row.cells.fenced}, code fenced=${permission in RolePermissions.FENCED}"
            }
        }
        withClue(mismatches.joinToString("\n")) { mismatches.shouldBeEmpty() }
    }

    @Test
    fun `the reserved row names no permission and admits no surface`() {
        rows.filter { it.permission == null }.forEach { row ->
            withClue(row.label) {
                row.routes.shouldBeEmpty()
                row.tools.shouldBeEmpty()
            }
        }
    }

    @Test
    fun `the A4 shim is total and every permission's key scope matches the doc`() {
        ScopeMatrix.PERMISSION_MIN_SCOPE.keys shouldBe Permission.entries.toSet()
        RoleMatrixDoc.permissionKeyScopes(doc) shouldContainExactly ScopeMatrix.PERMISSION_MIN_SCOPE
    }

    @Test
    fun `every MCP tool sits on the catalog row of the permission it declares`() {
        val fromDoc = RoleMatrixDoc.toolPermissions(doc)

        fromDoc.size shouldBe TOOL_COUNT
        fromDoc shouldContainExactly ScopeMatrix.MCP_TOOL_PERMISSION
    }

    @Test
    fun `every MCP tool's minimum key scope matches the doc - through the shim`() {
        val fromDoc = RoleMatrixDoc.mcpKeyScopes(doc)

        fromDoc.size shouldBe TOOL_COUNT
        fromDoc shouldContainExactly ScopeMatrix.MCP_TOOL_PERMISSION.keys.associateWith { ScopeMatrix.requiredScopeForTool(it) }
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

    private fun cellMismatch(
        permission: Permission,
        column: String,
        docCell: String?,
        codeHolds: Boolean,
    ): String = "`${permission.wire}` × $column: doc '$docCell', code ${if (codeHolds) "holds" else "lacks"}"

    private companion object {
        /** The record's 64 + `template.evaluate` (7b), re-derived on the lane's base (A7). */
        const val PERMISSION_COUNT = 65

        /**
         * 42 since 7b's `templates_evaluate` — the full history is in auth.md's change log
         * (18 → … → 41 with 140's `pipelines_run_checks`, 42 with 7b).
         */
        const val TOOL_COUNT = 42

        /** Documented rows with no code behind them yet — each one a decision the record made ahead of a surface. */
        val RESERVED_ROWS = listOf("Read the audit log — **reserved** (D12)")
    }
}
