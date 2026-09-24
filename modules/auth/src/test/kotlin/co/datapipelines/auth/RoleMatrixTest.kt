package co.datapipelines.auth

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The role × permission matrix as BEHAVIOUR (#215; record §2): for every role, the set of
 * catalog permissions [ScopeMatrix.allowed] admits — the function `ScopeInterceptor` and
 * `McpToolDispatcher` call — equals that role's COLUMN in auth.md §7.6, and every refusal is on
 * the role axis.
 *
 * ## Why the expectation is the doc's column
 * A test that computed the expected set from [RolePermissions] would pass for any table,
 * including a wrong one: it would be asserting that the code agrees with itself. The doc is
 * the independent source the owner ratifies, so each role's column is READ from it; the
 * decision function is then judged against it end to end. (`ScopeMatrixSpecDriftTest` compares
 * the table itself with the doc, cell by cell; this compares the DECISIONS.)
 *
 * The named rules below restate, as behaviour, the rulings that shaped the columns — so a
 * change that keeps the doc and the code in step but breaks a ruling still has to fail
 * somewhere a reviewer reads it.
 */
class RoleMatrixTest {
    private val workspaceId = UUID.randomUUID()
    private val catalog = RoleMatrixDoc.catalogRows(RepoFiles.read(RepoFiles.AUTH_SPEC_PATH)).filter { it.permission != null }

    // ------------------------------------------------------------------ the five roles, from the doc's columns

    @Test
    fun `each workspace role is admitted exactly its column of the catalog - every refusal on the role axis`() {
        WorkspaceRole.entries.forEach { role ->
            val column = catalog.filter { role in it.cells.allowedRoles }.mapNotNull { it.permission }.toSet()
            val principal = session(role)
            withClue("role ${role.wire}") {
                admitted(principal, context(role)) shouldContainExactly column
                (Permission.entries.toSet() - column).forEach { permission ->
                    refusalFor(principal, permission).code shouldBe AuthErrorCodes.ROLE_REQUIRED
                }
            }
        }
    }

    /** D7: implicitly a member of every workspace, with every permission — except the fenced two, which are nobody's. */
    @Test
    fun `a super admin is admitted the super_admin column - everything but the fenced rows`() {
        val column = catalog.filter { it.cells.superAdminAllowed }.mapNotNull { it.permission }.toSet()
        val principal = superAdminSession()
        val context = WorkspaceContext.superAdminOver(workspaceId, "acme", explicitRole = null)

        admitted(principal, context) shouldContainExactly column
        column shouldBe Permission.entries.toSet() - RolePermissions.FENCED
        RolePermissions.FENCED.forEach { permission ->
            (ScopeMatrix.allowed(principal, permission, context) as ScopeMatrix.Decision.Refused).code shouldBe
                AuthErrorCodes.ROLE_REQUIRED
        }
    }

    // ------------------------------------------------------------------ the rulings, named

    @Test
    fun `release and the served-version switch are the author's (D8, O-1) - never the promoter's`() {
        listOf(
            Permission.PIPELINE_RELEASE,
            Permission.PIPELINE_SWITCH_VERSION,
            Permission.TEMPLATE_RELEASE,
            Permission.TEMPLATE_SWITCH_VERSION,
        ).forEach { permission ->
            allowed(session(WorkspaceRole.AUTHOR), permission) shouldBe ScopeMatrix.Decision.Allowed
            refusalFor(session(WorkspaceRole.PROMOTER), permission).code shouldBe AuthErrorCodes.ROLE_REQUIRED
        }
    }

    @Test
    fun `the promoter executes nothing and reads no executions (D5, D11) - the viewer does both`() {
        listOf(
            Permission.PIPELINE_EXECUTE,
            Permission.PIPELINE_RUN_CHECKS,
            Permission.EXECUTION_CANCEL,
            Permission.EXECUTION_READ,
            Permission.EXECUTION_RESULT_READ,
            Permission.DATASOURCE_TEST,
        ).forEach { permission ->
            refusalFor(session(WorkspaceRole.PROMOTER), permission).code shouldBe AuthErrorCodes.ROLE_REQUIRED
            allowed(session(WorkspaceRole.VIEWER), permission) shouldBe ScopeMatrix.Decision.Allowed
        }
    }

    @Test
    fun `seeing and cancelling OTHER people's runs is the workspace admin's (D11) - the two _all permissions`() {
        listOf(Permission.EXECUTION_READ_ALL, Permission.EXECUTION_CANCEL_ALL).forEach { permission ->
            listOf(WorkspaceRole.VIEWER, WorkspaceRole.AUTHOR, WorkspaceRole.PROMOTER).forEach { role ->
                context(role).permits(permission) shouldBe false
            }
            context(WorkspaceRole.WORKSPACE_ADMIN).permits(permission) shouldBe true
        }
    }

    @Test
    fun `the workspaces page is the admin's (D13) - the switcher is every member's`() {
        refusalFor(session(WorkspaceRole.VIEWER), Permission.WORKSPACE_READ).code shouldBe AuthErrorCodes.ROLE_REQUIRED
        refusalFor(session(WorkspaceRole.PROMOTER), Permission.WORKSPACE_READ).code shouldBe AuthErrorCodes.ROLE_REQUIRED
        WorkspaceRole.entries.forEach { role ->
            allowed(session(role), Permission.WORKSPACE_SWITCH) shouldBe ScopeMatrix.Decision.Allowed
        }
    }

    @Test
    fun `the promotion PAGE is wider than the promote VERB (owner rule 13)`() {
        allowed(session(WorkspaceRole.AUTHOR), Permission.PROMOTION_READ) shouldBe ScopeMatrix.Decision.Allowed
        refusalFor(session(WorkspaceRole.AUTHOR), Permission.PROMOTION_PROMOTE).code shouldBe AuthErrorCodes.ROLE_REQUIRED
        refusalFor(session(WorkspaceRole.VIEWER), Permission.PROMOTION_READ).code shouldBe AuthErrorCodes.ROLE_REQUIRED
    }

    /**
     * #215 (record §2.3): the row-data tools are author-and-above. The five MCP cells the record
     * moved (viewer × three, promoter × two) — for the keys a login mints, the SCOPE already
     * refused them (a viewer's key carries `execute`, a promoter's `read`, and these tools need
     * `author`), and it still does, first, with the same code. What the role axis adds is a
     * refusal for an `author`-scoped key whose member has been demoted to viewer or promoter
     * (the scope is fixed at mint, C2): v2 admitted that key to these three tools; the catalog
     * refuses it, with the demoted-issuer code.
     */
    @Test
    fun `the row-data tools are author-and-above - a login key is refused on scope, a demoted member's author key on role`() {
        val rowData = mapOf("datasources_preview_rows" to 2, "sql_probe" to 2, "pipelines_execute_node" to 1)
        rowData.keys.forEach { tool ->
            ScopeMatrix.requiredScopeForTool(tool) shouldBe Scope.AUTHOR
            val loginViewer = key(Scope.EXECUTE.expand(), WorkspaceRole.VIEWER)
            val loginPromoter = key(setOf(Scope.READ), WorkspaceRole.PROMOTER)
            val demotedViewer = key(Scope.AUTHOR.expand(), WorkspaceRole.VIEWER)
            val demotedPromoter = key(Scope.AUTHOR.expand(), WorkspaceRole.PROMOTER)

            toolRefusal(loginViewer, tool).code shouldBe AuthErrorCodes.SCOPE_INSUFFICIENT
            toolRefusal(loginPromoter, tool).code shouldBe AuthErrorCodes.SCOPE_INSUFFICIENT
            toolRefusal(demotedViewer, tool).code shouldBe AuthErrorCodes.KEY_ISSUER_ROLE_LOST
            val promoterCell = rowData.getValue(tool) == 2
            if (promoterCell) toolRefusal(demotedPromoter, tool).code shouldBe AuthErrorCodes.KEY_ISSUER_ROLE_LOST
            ScopeMatrix.allowedTool(key(Scope.AUTHOR.expand(), WorkspaceRole.AUTHOR), tool, context(WorkspaceRole.AUTHOR)) shouldBe
                ScopeMatrix.Decision.Allowed
        }
    }

    // ------------------------------------------------------------------ the two axes

    @Test
    fun `a key's SCOPE refuses what its issuer's role would allow - the credential axis`() {
        // An author's `read`-scoped key: the role says yes to authoring, the scope says no.
        val key = key(scopes = setOf(Scope.READ), role = WorkspaceRole.AUTHOR)

        refusalFor(key, Permission.PIPELINE_UPDATE).code shouldBe AuthErrorCodes.SCOPE_INSUFFICIENT
    }

    @Test
    fun `a key's ISSUER's role refuses what its scope would allow - the role axis`() {
        // The mirror image: an `author`-scoped key whose issuer has been demoted to viewer. The
        // code is the DEMOTION one, because retrying with this key can never work.
        val key = key(scopes = setOf(Scope.AUTHOR), role = WorkspaceRole.VIEWER)

        refusalFor(key, Permission.PIPELINE_UPDATE).code shouldBe AuthErrorCodes.KEY_ISSUER_ROLE_LOST
    }

    @Test
    fun `the two axes disagree about execution, and both answers are right`() {
        // `pipeline.execute` floors at the `execute` SCOPE but is a viewer-level PERMISSION (D3).
        val readKeyOfAViewer = key(scopes = setOf(Scope.READ), role = WorkspaceRole.VIEWER)

        refusalFor(readKeyOfAViewer, Permission.PIPELINE_EXECUTE).code shouldBe AuthErrorCodes.SCOPE_INSUFFICIENT
        allowed(session(WorkspaceRole.VIEWER), Permission.PIPELINE_EXECUTE) shouldBe ScopeMatrix.Decision.Allowed
    }

    @Test
    fun `a session carries no scopes, and is judged on the role axis alone (D-R1)`() {
        val authorSession = session(WorkspaceRole.AUTHOR)
        authorSession.scopes shouldBe emptySet()

        allowed(authorSession, Permission.PIPELINE_UPDATE) shouldBe ScopeMatrix.Decision.Allowed
    }

    @Test
    fun `a role refusal names the catalog permission and the ROLE it was judged as (A-6)`() {
        val refusal = refusalFor(session(WorkspaceRole.VIEWER), Permission.PIPELINE_UPDATE)

        refusal.details["required"] shouldBe "pipeline.update"
        refusal.details["held"] shouldBe "viewer"
        refusal.details["workspace"] shouldBe "acme"
    }

    // ------------------------------------------------------------------ no reachable workspace

    @Test
    fun `no reachable workspace is the 404, whatever the permission (D-R5) - except listing your workspaces`() {
        val session = session(WorkspaceRole.AUTHOR)

        (Permission.entries - LIST_OWN_WORKSPACES).forEach { permission ->
            val decision = ScopeMatrix.allowed(session, permission, context = null)
            withClue(permission.wire) { (decision as ScopeMatrix.Decision.Refused).code shouldBe WorkspaceErrorCodes.NOT_FOUND }
        }
    }

    /**
     * 114 §C.3a — the no-workspace page. "Which workspaces do I belong to" is the one question
     * that makes sense with the answer "none", so a SESSION may ask it with no context. A key
     * cannot, because a key without a context is a key whose workspace is gone (D-R5).
     */
    @Test
    fun `a session with no workspace may still list its workspaces - a key may not`() {
        LIST_OWN_WORKSPACES.forEach { permission ->
            ScopeMatrix.allowed(session(WorkspaceRole.VIEWER), permission, context = null) shouldBe ScopeMatrix.Decision.Allowed

            val decision = ScopeMatrix.allowed(key(setOf(Scope.READ), WorkspaceRole.VIEWER), permission, context = null)
            (decision as ScopeMatrix.Decision.Refused).code shouldBe WorkspaceErrorCodes.NOT_FOUND
        }
    }

    /**
     * #113 — the empty-instance recovery carve-out, keyed on [RolePermissions.INSTANCE]: a super
     * admin SESSION with no reachable workspace keeps exactly the instance permissions; every
     * workspace permission stays the D-R5 404; a key gets the exception NEVER.
     */
    @Test
    fun `a super admin with no reachable workspace keeps the instance permissions - and nothing else`() {
        val superAdmin = superAdminSession().copy(workspace = null)

        RolePermissions.INSTANCE.forEach { permission ->
            ScopeMatrix.allowed(superAdmin, permission, context = null) shouldBe ScopeMatrix.Decision.Allowed
            superAdmin.holds(permission) shouldBe true
        }
        (Permission.entries.toSet() - RolePermissions.INSTANCE - LIST_OWN_WORKSPACES).forEach { permission ->
            val decision = ScopeMatrix.allowed(superAdmin, permission, context = null)
            withClue(permission.wire) { (decision as ScopeMatrix.Decision.Refused).code shouldBe WorkspaceErrorCodes.NOT_FOUND }
            superAdmin.holds(permission) shouldBe false
        }

        val keyPrincipal = key(setOf(Scope.ADMIN), WorkspaceRole.VIEWER).copy(workspace = null, superAdmin = true)
        RolePermissions.INSTANCE.forEach { permission ->
            val decision = ScopeMatrix.allowed(keyPrincipal, permission, context = null)
            (decision as ScopeMatrix.Decision.Refused).code shouldBe WorkspaceErrorCodes.NOT_FOUND
        }
    }

    // ------------------------------------------------------------------ the table's own invariants

    @Test
    fun `the instance permissions are exactly the super admin's column minus every role's`() {
        val anyRole = WorkspaceRole.entries.flatMap { RolePermissions.of(it) }.toSet()

        RolePermissions.INSTANCE shouldBe RolePermissions.SUPER_ADMIN - anyRole
        RolePermissions.FENCED.filter { fenced -> WorkspaceRole.entries.any { fenced in RolePermissions.of(it) } }.shouldBeEmpty()
    }

    @Test
    fun `Permission's derived role rows are the table's columns read the other way`() {
        Permission.entries.forEach { permission ->
            permission.roles shouldBe WorkspaceRole.entries.filter { permission in RolePermissions.of(it) }.toSet()
            permission.wire shouldBe Permission.fromWire(permission.wire).wire
        }
    }

    @Test
    fun `a tool the matrix does not know is refused, never defaulted`() {
        val decision = ScopeMatrix.allowedTool(superAdminSession(), "pipelines_delete", context(WorkspaceRole.WORKSPACE_ADMIN))

        (decision as ScopeMatrix.Decision.Refused).code shouldBe AuthErrorCodes.SCOPE_INSUFFICIENT
    }

    // ------------------------------------------------------------------ helpers

    private fun admitted(
        principal: AuthenticatedPrincipal,
        context: WorkspaceContext,
    ): Set<Permission> = Permission.entries.filter { ScopeMatrix.allowed(principal, it, context) is ScopeMatrix.Decision.Allowed }.toSet()

    private fun allowed(
        principal: AuthenticatedPrincipal,
        permission: Permission,
    ): ScopeMatrix.Decision = ScopeMatrix.allowed(principal, permission, principal.workspace ?: context(WorkspaceRole.VIEWER))

    private fun refusalFor(
        principal: AuthenticatedPrincipal,
        permission: Permission,
    ): ScopeMatrix.Decision.Refused = allowed(principal, permission) as ScopeMatrix.Decision.Refused

    private fun toolRefusal(
        principal: AuthenticatedPrincipal,
        tool: String,
    ): ScopeMatrix.Decision.Refused = ScopeMatrix.allowedTool(principal, tool, principal.workspace) as ScopeMatrix.Decision.Refused

    private fun context(role: WorkspaceRole) = WorkspaceContext(workspaceId, "acme", role)

    private fun superAdminSession() =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "root@company.com",
            displayName = "Root",
            scopes = emptySet(),
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext.superAdminOver(workspaceId, "acme", explicitRole = null),
            superAdmin = true,
        )

    private fun session(role: WorkspaceRole) =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "member@company.com",
            displayName = "Member",
            scopes = emptySet(),
            authMethod = AuthMethod.OIDC,
            workspace = context(role),
        )

    private fun key(
        scopes: Set<Scope>,
        role: WorkspaceRole,
    ) = AuthenticatedPrincipal(
        userId = UUID.randomUUID(),
        email = "agent@company.com",
        displayName = "Agent",
        scopes = scopes,
        authMethod = AuthMethod.API_KEY,
        keyId = "dpk_TEST",
        workspaceName = "acme",
        workspace = context(role),
    )

    private companion object {
        /** The two permissions a session may hold with NO workspace context: the page and the REST list-own. */
        val LIST_OWN_WORKSPACES = setOf(Permission.WORKSPACE_READ, Permission.WORKSPACE_SWITCH)
    }
}
