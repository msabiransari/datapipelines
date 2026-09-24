package co.datapipelines.auth

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
     * #215 (record §2.3): the row-data tools are author-and-above. Since slice (b) the MCP key
     * carries no scope — it acts with its member's role, capped at author (PK4) — so the one
     * question is the role: a viewer's or promoter's key is refused with the member-role code, an
     * author's key is admitted. (Before slice (b) a login-minted viewer key was refused here on
     * its SCOPE first; the answer is the same, the axis is one.)
     */
    @Test
    fun `the row-data tools are author-and-above - an MCP key of a viewer or promoter is refused on its member's role`() {
        val rowData =
            mapOf(
                "datasources_preview_rows" to Permission.DATASOURCE_PREVIEW_ROWS,
                "sql_probe" to Permission.DATASOURCE_SQL_PROBE,
                "pipelines_execute_node" to Permission.PIPELINE_EXECUTE_NODE,
            )
        rowData.forEach { (tool, permission) ->
            withClue(tool) {
                toolRefusal(key(WorkspaceRole.VIEWER), tool, permission).code shouldBe AuthErrorCodes.KEY_ISSUER_ROLE_LOST
                toolRefusal(key(WorkspaceRole.PROMOTER), tool, permission).code shouldBe AuthErrorCodes.KEY_ISSUER_ROLE_LOST
                ScopeMatrix.allowedTool(key(WorkspaceRole.AUTHOR), tool, permission, context(WorkspaceRole.AUTHOR)) shouldBe
                    ScopeMatrix.Decision.Allowed
            }
        }
    }

    // ------------------------------------------------------------------ keys (#215 slice (b))

    /**
     * Record §3.2: a key that acts as its own identity is judged by its KEY ROLE's column and
     * nothing else — the same column-by-column walk the member roles get above, the expectation
     * READ from the doc's key-role column (not from `RolePermissions`, for the reason the class
     * KDoc gives).
     */
    @Test
    fun `each key role is admitted exactly its column of the catalog - and refused everything else with its role named`() {
        KeyRole.entries.forEach { role ->
            val principal = keyRole(role)
            val column = catalog.filter { role in it.keyRoles }.mapNotNull { it.permission }.toSet()
            withClue(role.wire) {
                column.isEmpty() shouldBe false
                admitted(principal, context(WorkspaceRole.VIEWER)) shouldBe column
                (Permission.entries - column).forEach { permission ->
                    val refusal = ScopeMatrix.allowed(principal, permission, context(WorkspaceRole.VIEWER)) as ScopeMatrix.Decision.Refused
                    refusal.code shouldBe AuthErrorCodes.ROLE_REQUIRED
                    refusal.details["held"] shouldBe role.wire
                }
            }
        }
    }

    /** B6: promotion intake is instance-wide — the receiver's role needs no workspace to be judged. */
    @Test
    fun `the promotion receiver is admitted with no workspace at all - intake is instance-wide (B6)`() {
        val receiver = keyRole(KeyRole.PROMOTION_RECEIVER).copy(authMethod = AuthMethod.PROMOTION, workspace = null)

        ScopeMatrix.allowed(receiver, Permission.PROMOTION_PUSH, context = null) shouldBe ScopeMatrix.Decision.Allowed
        ScopeMatrix.allowed(receiver, Permission.PROMOTION_INVENTORY_READ, context = null) shouldBe ScopeMatrix.Decision.Allowed
        (ScopeMatrix.allowed(receiver, Permission.PIPELINE_READ, context = null) as ScopeMatrix.Decision.Refused).code shouldBe
            AuthErrorCodes.ROLE_REQUIRED
    }

    /** A promotion principal that carries no key role is a wiring defect — refused, never admitted by default. */
    @Test
    fun `a promotion principal without the receiver role fails closed`() {
        val unwired = keyRole(KeyRole.PROMOTION_RECEIVER).copy(authMethod = AuthMethod.PROMOTION, keyRole = null, workspace = null)

        ScopeMatrix.allowed(unwired, Permission.PROMOTION_PUSH, context = null).shouldBeInstanceOf<ScopeMatrix.Decision.Refused>()
    }

    @Test
    fun `an MCP key's member role refuses what the member lacks - the demotion code`() {
        // Retrying with this key can never work: the fix is a role, so the code says so (D-R12).
        refusalFor(key(role = WorkspaceRole.VIEWER), Permission.PIPELINE_UPDATE).code shouldBe AuthErrorCodes.KEY_ISSUER_ROLE_LOST
    }

    /** C1 (record §5, ruled O1): with scopes gone the key follows the role — a viewer's key executes and introspects. */
    @Test
    fun `a viewer's MCP key executes and introspects, a promoter's introspects - the key follows the role (C1)`() {
        allowed(key(WorkspaceRole.VIEWER), Permission.PIPELINE_EXECUTE) shouldBe ScopeMatrix.Decision.Allowed
        allowed(key(WorkspaceRole.VIEWER), Permission.DATASOURCE_INTROSPECT) shouldBe ScopeMatrix.Decision.Allowed
        allowed(key(WorkspaceRole.VIEWER), Permission.DATASOURCE_TEST) shouldBe ScopeMatrix.Decision.Allowed
        allowed(key(WorkspaceRole.PROMOTER), Permission.DATASOURCE_INTROSPECT) shouldBe ScopeMatrix.Decision.Allowed
    }

    /**
     * B1: no key resolves an instance permission. `ApiKeyService.validate` never sets the flag on a
     * key and resolves a super admin's non-member MCP key as an implicit VIEWER — this pins that the
     * matrix refuses every instance permission to exactly that principal, in context and without.
     */
    @Test
    fun `a super admin's MCP key holds no instance permission (B1)`() {
        val superAdminsKey =
            key(WorkspaceRole.VIEWER).copy(workspace = WorkspaceContext(workspaceId, "acme", WorkspaceRole.VIEWER, implicit = true))

        RolePermissions.INSTANCE.forEach { permission ->
            withClue(permission.wire) {
                ScopeMatrix.allowed(superAdminsKey, permission, superAdminsKey.workspace).shouldBeInstanceOf<ScopeMatrix.Decision.Refused>()
                superAdminsKey.holds(permission) shouldBe false
            }
        }
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

            val decision = ScopeMatrix.allowed(key(WorkspaceRole.VIEWER), permission, context = null)
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

        val keyPrincipal = key(WorkspaceRole.VIEWER).copy(workspace = null, superAdmin = true)
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
    fun `a tool that declares no permission is refused, never defaulted`() {
        val decision = ScopeMatrix.allowedTool(superAdminSession(), "pipelines_delete", null, context(WorkspaceRole.WORKSPACE_ADMIN))

        (decision as ScopeMatrix.Decision.Refused).code shouldBe AuthErrorCodes.PERMISSION_UNDECLARED
        decision.details["tool"] shouldBe "pipelines_delete"
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
        permission: Permission,
    ): ScopeMatrix.Decision.Refused =
        ScopeMatrix.allowedTool(principal, tool, permission, principal.workspace) as ScopeMatrix.Decision.Refused

    /** A key acting as its own identity with [role] (record §3.2) — an `endpoint` or `server` key. */
    private fun keyRole(role: KeyRole) =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "dpk_key@keys.invalid",
            displayName = "key",
            authMethod = AuthMethod.API_KEY,
            keyId = "dpk_KEYROLE",
            workspaceName = "acme",
            workspace = context(WorkspaceRole.VIEWER),
            keyKind = if (role == KeyRole.API_CALLER) ApiKeyKind.ENDPOINT else ApiKeyKind.SERVER,
            keyRole = role,
        )

    private fun context(role: WorkspaceRole) = WorkspaceContext(workspaceId, "acme", role)

    private fun superAdminSession() =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "root@company.com",
            displayName = "Root",
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext.superAdminOver(workspaceId, "acme", explicitRole = null),
            superAdmin = true,
        )

    private fun session(role: WorkspaceRole) =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "member@company.com",
            displayName = "Member",
            authMethod = AuthMethod.OIDC,
            workspace = context(role),
        )

    private fun key(role: WorkspaceRole) =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "agent@company.com",
            displayName = "Agent",
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
