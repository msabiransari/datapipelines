package co.datapipelines.auth

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The permission-resolution seam (security-assurance record §7.1, ratified B4) as a unit: WHERE
 * the three decision points ask, and that the production resolver answers what the doc says.
 *
 * The seam moves where a decision is computed, never what it decides; the role walk, the drift
 * tests and [RoleMatrixTest] prove the WHAT unchanged. What only this class can see is the wiring:
 * that [WorkspaceContext.permits], [RolePermissions.holds] and both member branches of
 * [AuthenticatedPrincipal.holds] consult the INSTALLED resolver with the right workspace — so a
 * synthetic grant installed by a test context reaches every one of them — that a key role does not
 * (its column is the matrix's, read at admission), and that closing the installation puts the
 * production resolver back.
 *
 * The expectation for the production resolver is the doc's columns ([RoleMatrixDoc]), never
 * [RolePermissions]: a resolver checked against the table it reads would agree with itself.
 */
class PermissionResolutionTest {
    private val workspaceId = UUID.randomUUID()
    private val catalog = RoleMatrixDoc.catalogRows(RepoFiles.read(RepoFiles.AUTH_SPEC_PATH)).filter { it.permission != null }

    @AfterEach
    fun restore() {
        PermissionResolution.install(RolePermissionsResolver)
    }

    @Test
    fun `with no context installed, every decision asks the production resolver`() {
        PermissionResolution.resolver shouldBeSameInstanceAs RolePermissionsResolver
    }

    @Test
    fun `the production resolver answers each member column and the super admin column exactly as auth-md §7-6 says`() {
        WorkspaceRole.entries.forEach { role ->
            val column = catalog.filter { role in it.cells.allowedRoles }.mapNotNull { it.permission }.toSet()
            withClue("role ${role.wire}") {
                Permission.entries.filter { RolePermissionsResolver.holds(workspaceId, role, false, it) } shouldContainExactly column
            }
        }
        val superAdminColumn = catalog.filter { it.cells.superAdminAllowed }.mapNotNull { it.permission }.toSet()
        Permission.entries.filter { RolePermissionsResolver.holds(null, null, true, it) } shouldContainExactly superAdminColumn
        Permission.entries.filter { RolePermissionsResolver.holds(null, null, false, it) }.shouldBeEmpty()
    }

    @Test
    fun `the context, the table and both member branches of a principal ask the installed resolver - with the workspace named`() {
        val asked = mutableListOf<Asked>()
        PermissionResolution.install(Grant(Permission.PIPELINE_DELETE, asked))
        val context = WorkspaceContext(workspaceId, "acme", WorkspaceRole.WORKSPACE_ADMIN)
        val principal = session(context)

        context.permits(Permission.PIPELINE_DELETE) shouldBe true
        context.permits(Permission.PIPELINE_CREATE) shouldBe false
        RolePermissions.holds(WorkspaceRole.WORKSPACE_ADMIN, false, Permission.PIPELINE_READ) shouldBe false
        Permission.PIPELINE_DELETE.satisfiedBy(WorkspaceRole.VIEWER, superAdmin = false) shouldBe true
        principal.holds(Permission.PIPELINE_DELETE) shouldBe true
        principal.holds(Permission.WORKSPACE_CREATE) shouldBe false

        asked.map { it.workspaceId to it.permission } shouldContainExactly
            listOf(
                workspaceId to Permission.PIPELINE_DELETE,
                workspaceId to Permission.PIPELINE_CREATE,
                null to Permission.PIPELINE_READ,
                null to Permission.PIPELINE_DELETE,
                workspaceId to Permission.PIPELINE_DELETE,
                workspaceId to Permission.WORKSPACE_CREATE,
            )
    }

    @Test
    fun `a key role is read from its column - the installed resolver is never asked`() {
        val asked = mutableListOf<Asked>()
        PermissionResolution.install(Grant(Permission.PIPELINE_DELETE, asked))
        val caller =
            session(WorkspaceContext(workspaceId, "acme", WorkspaceRole.VIEWER))
                .copy(authMethod = AuthMethod.API_KEY, keyId = "dpk_TEST", keyKind = ApiKeyKind.ENDPOINT, keyRole = KeyRole.API_CALLER)

        caller.holds(Permission.ENDPOINT_SERVE) shouldBe true
        caller.holds(Permission.PIPELINE_DELETE) shouldBe false
        asked.shouldBeEmpty()
    }

    @Test
    fun `an installation installs its resolver and closing it restores the production one`() {
        val grant = Grant(Permission.TEMPLATE_RELEASE, mutableListOf())

        val installation = PermissionResolverInstallation(grant)
        PermissionResolution.resolver shouldBeSameInstanceAs grant
        installation.close()

        PermissionResolution.resolver shouldBeSameInstanceAs RolePermissionsResolver
    }

    private data class Asked(
        val workspaceId: UUID?,
        val permission: Permission,
    )

    /** A synthetic grant: exactly [granted], to anyone, and a record of every question. */
    private class Grant(
        private val granted: Permission,
        private val asked: MutableList<Asked>,
    ) : PermissionResolver {
        override fun holds(
            workspaceId: UUID?,
            role: WorkspaceRole?,
            superAdmin: Boolean,
            permission: Permission,
        ): Boolean {
            asked += Asked(workspaceId, permission)
            return permission == granted
        }
    }

    private fun session(context: WorkspaceContext) =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "member@company.com",
            displayName = "Member",
            authMethod = AuthMethod.OIDC,
            workspace = context,
        )
}
