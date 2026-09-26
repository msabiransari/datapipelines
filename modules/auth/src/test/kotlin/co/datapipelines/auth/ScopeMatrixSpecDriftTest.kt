package co.datapipelines.auth

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * **The catalog drift gate** (#215, record §7 gate 1): auth.md §7.6's permission catalog, the
 * [Permission] enum and the one role table [RolePermissions] agree in BOTH directions, with
 * counts — so a row added to the doc without code fails the build, and a permission added to
 * the code (or a role cell flipped in it) without the doc does too.
 *
 * The comparisons, each naming what disagrees:
 * - the catalog's rows ARE `Permission.entries`, in declaration order, plus the listed reserved
 *   rows (a real row cannot hide as a reserved one);
 * - every MEMBER-role CELL — role × permission — reads the same in the doc and in
 *   [RolePermissions], and a `fenced` row is exactly a [RolePermissions.FENCED] permission;
 * - every KEY-role cell (slice (b): `api_caller`, `promotion_receiver`) reads the same in the doc
 *   and in [RolePermissions.of] — the key roles hold exactly what the record's §3.2 lists.
 *
 * The MCP tool placement moved with the tool permission onto `McpToolCatalog` (a module this one
 * cannot see); web's `MatrixRowReachabilityTest` holds it to the doc.
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
    fun `every key-role cell of the catalog matches the key-role table - both directions`() {
        val mismatches = mutableListOf<String>()
        rows.forEach { row ->
            val permission = row.permission ?: return@forEach
            KeyRole.entries.forEach { role ->
                val inDoc = role in row.keyRoles
                val inCode = permission in RolePermissions.of(role)
                if (inDoc != inCode) mismatches += cellMismatch(permission, role.wire, row.keyRoleRaw[role], inCode)
            }
        }
        withClue(mismatches.joinToString("\n")) { mismatches.shouldBeEmpty() }
    }

    /** Non-vacuity for the key-role columns: a parse that found no `✓` would pass the test above for a table of ✗. */
    @Test
    fun `the key-role columns name exactly the record's section 3-2 permissions`() {
        rows.filter { KeyRole.API_CALLER in it.keyRoles }.mapNotNull { it.permission }.toSet() shouldBe
            setOf(Permission.ENDPOINT_SERVE, Permission.EXECUTION_READ, Permission.EXECUTION_RESULT_READ)
        rows.filter { KeyRole.PROMOTION_RECEIVER in it.keyRoles }.mapNotNull { it.permission }.toSet() shouldBe
            setOf(Permission.PROMOTION_INVENTORY_READ, Permission.PROMOTION_PUSH)
    }

    private fun cellMismatch(
        permission: Permission,
        column: String,
        docCell: String?,
        codeHolds: Boolean,
    ): String = "`${permission.wire}` × $column: doc '$docCell', code ${if (codeHolds) "holds" else "lacks"}"

    private companion object {
        /**
         * The record's 64 + `template.evaluate` (7b) + keys v2's two (#233 A14) + the scheduler's six
         * (#9: `schedule.read`, `.create`, `.update`, `.pause`, `.delete`, `.run`), re-derived.
         */
        const val PERMISSION_COUNT = 73

        /** Documented rows with no code behind them yet — each one a decision the record made ahead of a surface. */
        val RESERVED_ROWS = listOf("Read the audit log — **reserved** (D12)")
    }
}
