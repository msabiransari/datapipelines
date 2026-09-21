package co.datapipelines.auth

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The role × operation matrix as BEHAVIOUR (roles design 2026-09-20 §2, ratified): one
 * section per role, each asserting the verbs it holds AND the verbs it is refused, through
 * the real [ScopeMatrix.allowed] — the same function `ScopeInterceptor` and
 * `McpToolDispatcher` call.
 *
 * ## Why the allowed set is spelled out per role rather than derived
 * A test that computed the expected set from `Permission.satisfiedBy` would pass for any
 * matrix, including a wrong one: it would be asserting that the code agrees with itself. So
 * each role's allowed operations are written down as the record's §2 table states them, and
 * the exhaustiveness test below proves no operation escaped classification — a new
 * `RestOperation` fails this suite until somebody decides which roles hold it.
 *
 * `ScopeMatrixSpecDriftTest` covers the other direction (the matrix versus the DOC); this
 * covers the decision function over the matrix.
 */
class RoleMatrixTest {
    private val workspaceId = UUID.randomUUID()

    // ------------------------------------------------------------------ the five roles

    /** D3: reads everything, runs everything, reads its own executions, changes nothing. */
    @Test
    fun `viewer`() {
        assertRole(role = WorkspaceRole.VIEWER, allowed = EVERY_ROLE + EXECUTE_ROLES)
    }

    /** D4/D8: the viewer's verbs + authoring, release, switch, endpoints, lake tables, the promotion page. */
    @Test
    fun `author`() {
        assertRole(role = WorkspaceRole.AUTHOR, allowed = EVERY_ROLE + EXECUTE_ROLES + AUTHOR_ONLY + Op.PROMOTION_READ)
    }

    /**
     * D5 (ratified 2026-09-20): the ops role. Reads and introspects (§2 rows 1 and 8), reads
     * the promotion page and promotes — and NOTHING that executes, reads executions, tests a
     * connection, authors or releases. The lens over what it READS is R2's (#178).
     */
    @Test
    fun `promoter`() {
        assertRole(role = WorkspaceRole.PROMOTER, allowed = EVERY_ROLE + Op.PROMOTION_READ + Op.PROMOTE_VERSION)
    }

    /** D6: everything in the workspace — authoring, promotion, members, workspace datasources, the page. */
    @Test
    fun `workspace admin`() {
        assertRole(
            role = WorkspaceRole.WORKSPACE_ADMIN,
            allowed = EVERY_ROLE + EXECUTE_ROLES + AUTHOR_ONLY + Op.PROMOTION_READ + Op.PROMOTE_VERSION + WS_ADMIN_ONLY,
        )
    }

    /** D7: implicitly a member of every workspace, with every permission. */
    @Test
    fun `super admin`() {
        val principal = superAdminSession(explicitRole = null)
        val context = WorkspaceContext.superAdminOver(workspaceId, "acme", explicitRole = null)
        Op.entries.filter { ScopeMatrix.allowed(principal, it, context) is ScopeMatrix.Decision.Allowed }.toSet() shouldContainExactly
            Op.entries.toSet()
    }

    // ------------------------------------------------------------------ the 2026-09-20 changes, named

    @Test
    fun `release moved from the promoter to the author (D8) and switch followed it (O-1 collapsed)`() {
        ScopeMatrix.allowed(session(WorkspaceRole.AUTHOR), Op.RELEASE_VERSION, context(WorkspaceRole.AUTHOR)) shouldBe
            ScopeMatrix.Decision.Allowed
        ScopeMatrix.allowed(session(WorkspaceRole.AUTHOR), Op.SWITCH_SERVED_VERSION, context(WorkspaceRole.AUTHOR)) shouldBe
            ScopeMatrix.Decision.Allowed
        refusalFor(session(WorkspaceRole.PROMOTER), Op.RELEASE_VERSION).code shouldBe AuthErrorCodes.ROLE_REQUIRED
        refusalFor(session(WorkspaceRole.PROMOTER), Op.SWITCH_SERVED_VERSION).code shouldBe AuthErrorCodes.ROLE_REQUIRED
    }

    @Test
    fun `the promoter executes nothing and reads no executions (D5, D11) - the viewer does both`() {
        listOf(Op.EXECUTE_PIPELINE, Op.CANCEL_EXECUTION, Op.READ_EXECUTIONS, Op.RETRIEVE_RESULT, Op.TEST_DATASOURCE).forEach { op ->
            refusalFor(session(WorkspaceRole.PROMOTER), op).code shouldBe AuthErrorCodes.ROLE_REQUIRED
            ScopeMatrix.allowed(session(WorkspaceRole.VIEWER), op, context(WorkspaceRole.VIEWER)) shouldBe ScopeMatrix.Decision.Allowed
        }
    }

    @Test
    fun `the workspaces page is the admin's (D13) - the switcher is every member's`() {
        refusalFor(session(WorkspaceRole.VIEWER), Op.WORKSPACES_READ).code shouldBe AuthErrorCodes.ROLE_REQUIRED
        refusalFor(session(WorkspaceRole.PROMOTER), Op.WORKSPACES_READ).code shouldBe AuthErrorCodes.ROLE_REQUIRED
        WorkspaceRole.entries.forEach { role ->
            ScopeMatrix.allowed(session(role), Op.WORKSPACE_SWITCH, context(role)) shouldBe ScopeMatrix.Decision.Allowed
        }
    }

    @Test
    fun `the promotion PAGE is wider than the promote VERB (owner rule 13)`() {
        ScopeMatrix.allowed(session(WorkspaceRole.AUTHOR), Op.PROMOTION_READ, context(WorkspaceRole.AUTHOR)) shouldBe
            ScopeMatrix.Decision.Allowed
        refusalFor(session(WorkspaceRole.AUTHOR), Op.PROMOTE_VERSION).code shouldBe AuthErrorCodes.ROLE_REQUIRED
        refusalFor(session(WorkspaceRole.VIEWER), Op.PROMOTION_READ).code shouldBe AuthErrorCodes.ROLE_REQUIRED
    }

    // ------------------------------------------------------------------ the two axes

    @Test
    fun `a key's SCOPE refuses what its issuer's role would allow - the credential axis`() {
        // An author's `read`-scoped key: the role says yes to authoring, the scope says no.
        // Both axes must pass, which is the whole reason there are two.
        val key = key(scopes = setOf(Scope.READ), role = WorkspaceRole.AUTHOR)

        refusalFor(key, Op.MUTATE_PIPELINES_TEMPLATES).code shouldBe AuthErrorCodes.SCOPE_INSUFFICIENT
    }

    @Test
    fun `a key's ISSUER's role refuses what its scope would allow - the role axis`() {
        // The mirror image: an `author`-scoped key whose issuer has been demoted to viewer.
        // The code is the DEMOTION one, because retrying with this key can never work.
        val key = key(scopes = setOf(Scope.AUTHOR), role = WorkspaceRole.VIEWER)

        refusalFor(key, Op.MUTATE_PIPELINES_TEMPLATES).code shouldBe AuthErrorCodes.KEY_ISSUER_ROLE_LOST
    }

    @Test
    fun `the two axes disagree about EXECUTE, and both answers are right`() {
        // `execute` is the second SCOPE but the viewer-level PERMISSION (D3). So:
        val readKeyOfAViewer = key(scopes = setOf(Scope.READ), role = WorkspaceRole.VIEWER)
        val viewerSession = session(WorkspaceRole.VIEWER)

        // …a read-scoped key may NOT execute…
        refusalFor(readKeyOfAViewer, Op.EXECUTE_PIPELINE).code shouldBe AuthErrorCodes.SCOPE_INSUFFICIENT
        // …while a viewer's SESSION may. Same person, same workspace, different credential.
        ScopeMatrix.allowed(viewerSession, Op.EXECUTE_PIPELINE, context(WorkspaceRole.VIEWER)) shouldBe
            ScopeMatrix.Decision.Allowed
    }

    @Test
    fun `a session carries no scopes, and is judged on the role axis alone (D-R1)`() {
        // If the scope axis were applied to sessions, an empty scope set would refuse an
        // author's own session on its own content — the regression this asserts against.
        val authorSession = session(WorkspaceRole.AUTHOR)
        authorSession.scopes shouldBe emptySet()

        ScopeMatrix.allowed(authorSession, Op.MUTATE_PIPELINES_TEMPLATES, context(WorkspaceRole.AUTHOR)) shouldBe
            ScopeMatrix.Decision.Allowed
    }

    @Test
    fun `no reachable workspace is the 404, whatever the operation (D-R5) - except listing your workspaces`() {
        val session = session(WorkspaceRole.AUTHOR)

        Op.entries.filter { it !in LIST_OWN_WORKSPACES }.forEach { op ->
            val decision = ScopeMatrix.allowed(session, op, context = null)
            (decision as ScopeMatrix.Decision.Refused).code shouldBe WorkspaceErrorCodes.NOT_FOUND
        }
    }

    /**
     * 114 §C.3a — the no-workspace page. "Which workspaces do I belong to" is the one question
     * that makes sense with the answer "none", so a SESSION may ask it with no context — even
     * though the page is a workspace admin's when there IS a workspace (D13): with no
     * membership there is no role to judge. A key cannot, because a key without a context is a
     * key whose workspace is gone (D-R5).
     */
    @Test
    fun `a session with no workspace may still list its workspaces - a key may not`() {
        val session = session(WorkspaceRole.VIEWER)
        LIST_OWN_WORKSPACES.forEach { op ->
            ScopeMatrix.allowed(session, op, context = null) shouldBe ScopeMatrix.Decision.Allowed

            val decision = ScopeMatrix.allowed(key(setOf(Scope.READ), WorkspaceRole.VIEWER), op, context = null)
            (decision as ScopeMatrix.Decision.Refused).code shouldBe WorkspaceErrorCodes.NOT_FOUND
        }
    }

    /**
     * #113 — the empty-instance recovery carve-out. A super admin SESSION with no reachable
     * workspace (every workspace deactivated) keeps exactly the instance verbs — the
     * `SUPER_ADMIN`-permission operations, instance-level by construction — so the deployment
     * can always be repaired: reactivate or create a workspace, administer users. Every
     * workspace-scoped operation stays the D-R5 404 even for that principal, and a key gets
     * the exception NEVER (its `admin`-scope floor is unobtainable since O-2, and the branch
     * is session-only besides).
     */
    @Test
    fun `a super admin with no reachable workspace keeps the instance verbs - and nothing else`() {
        val superAdminSession = superAdminSessionWithoutWorkspace()

        SUPER_ADMIN_ONLY.forEach { op ->
            ScopeMatrix.allowed(superAdminSession, op, context = null) shouldBe ScopeMatrix.Decision.Allowed
        }
        (Op.entries.toSet() - SUPER_ADMIN_ONLY - LIST_OWN_WORKSPACES).forEach { op ->
            val decision = ScopeMatrix.allowed(superAdminSession, op, context = null)
            (decision as ScopeMatrix.Decision.Refused).code shouldBe WorkspaceErrorCodes.NOT_FOUND
        }

        // The key twin: a super admin's key with no context is refused the same instance verb —
        // the exception is a property of the SESSION, never of the credential class.
        val keyPrincipal =
            AuthenticatedPrincipal(
                userId = UUID.randomUUID(),
                email = "agent@company.com",
                displayName = "Agent",
                scopes = setOf(Scope.ADMIN),
                authMethod = AuthMethod.API_KEY,
                keyId = "dpk_TEST",
                superAdmin = true,
            )
        SUPER_ADMIN_ONLY.forEach { op ->
            val decision = ScopeMatrix.allowed(keyPrincipal, op, context = null)
            (decision as ScopeMatrix.Decision.Refused).code shouldBe WorkspaceErrorCodes.NOT_FOUND
        }
    }

    // ------------------------------------------------------------------ exhaustiveness

    @Test
    fun `every operation is classified by this suite - a new one cannot slip in unjudged`() {
        // The non-vacuity floor. Without it, adding a RestOperation nobody listed above would
        // leave it untested here AND allowed by whichever role's permission it happened to
        // match — a guard that cannot go red for the case it exists to catch.
        val classified =
            EVERY_ROLE + EXECUTE_ROLES + AUTHOR_ONLY + Op.PROMOTION_READ + Op.PROMOTE_VERSION + WS_ADMIN_ONLY + SUPER_ADMIN_ONLY

        classified shouldContainExactly Op.entries.toSet()
    }

    @Test
    fun `every MCP tool carries BOTH axes - a tool on one axis only is a tool half-enforced`() {
        ScopeMatrix.MCP_TOOL_MIN_PERMISSION.keys shouldContainExactly ScopeMatrix.MCP_TOOL_MIN_SCOPE.keys
    }

    // ------------------------------------------------------------------ helpers

    private fun assertRole(
        role: WorkspaceRole,
        allowed: Set<Op>,
    ) {
        val principal = session(role)
        val context = context(role)

        val actual = Op.entries.filter { ScopeMatrix.allowed(principal, it, context) is ScopeMatrix.Decision.Allowed }.toSet()

        actual shouldContainExactly allowed

        // …and every refusal names the ROLE axis, not the credential one: a session has no
        // scopes to be short of, so an `auth.scope.insufficient` here would be a wrong answer
        // that still looked like a refusal.
        (Op.entries.toSet() - allowed).forEach { op ->
            refusalFor(principal, op).code shouldBe AuthErrorCodes.ROLE_REQUIRED
        }
    }

    private fun refusalFor(
        principal: AuthenticatedPrincipal,
        operation: Op,
    ): ScopeMatrix.Decision.Refused =
        ScopeMatrix.allowed(principal, operation, principal.workspace ?: context(WorkspaceRole.VIEWER))
            as ScopeMatrix.Decision.Refused

    private fun context(role: WorkspaceRole) = WorkspaceContext(workspaceId, "acme", role)

    /** A super admin session whose workspace resolution found NOTHING (the #113 empty instance). */
    private fun superAdminSessionWithoutWorkspace() =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "root@company.com",
            displayName = "Root",
            scopes = emptySet(),
            authMethod = AuthMethod.OIDC,
            workspace = null,
            superAdmin = true,
        )

    private fun superAdminSession(explicitRole: WorkspaceRole?) =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "root@company.com",
            displayName = "Root",
            scopes = emptySet(),
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext.superAdminOver(workspaceId, "acme", explicitRole),
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
        /** §2: the rows every workspace role holds — reads, introspection, the self verbs, the switcher. */
        val EVERY_ROLE =
            setOf(
                Op.READ_RESOURCES,
                Op.INTROSPECT_DATASOURCE,
                Op.VIEW_OWN_MCP_KEY,
                Op.CURRENT_PRINCIPAL,
                Op.PROFILE_PREFERENCE,
                Op.CHANGE_OWN_PASSWORD,
                Op.SERVE_PUBLISHED_ENDPOINT,
                Op.WORKSPACE_SWITCH,
            )

        /** §2 rows 2, 3 and 9: every role EXCEPT the promoter. */
        val EXECUTE_ROLES =
            setOf(
                Op.READ_EXECUTIONS,
                Op.RETRIEVE_RESULT,
                Op.EXECUTE_PIPELINE,
                Op.CANCEL_EXECUTION,
                Op.TEST_DATASOURCE,
            )

        /** D4/D8/D9: author and workspace admin. */
        val AUTHOR_ONLY =
            setOf(
                Op.MUTATE_PIPELINES_TEMPLATES,
                Op.MANAGE_ENDPOINTS,
                Op.MUTATE_LAKE_TABLES,
                Op.RELEASE_VERSION,
                Op.SWITCH_SERVED_VERSION,
            )

        /** D6/D13: workspace admin (and super admin). */
        val WS_ADMIN_ONLY =
            setOf(
                Op.MUTATE_WORKSPACE_DATASOURCES,
                Op.MANAGE_WORKSPACE,
                Op.MANAGE_WORKSPACE_MEMBERS,
                Op.WORKSPACES_READ,
                // 179 (D17): the workspace's API keys — create, delete, associate.
                Op.MANAGE_API_KEYS,
            )

        /** The two rows a session may hold with NO workspace context: the page and the REST list-own. */
        val LIST_OWN_WORKSPACES = setOf(Op.WORKSPACES_READ, Op.WORKSPACE_SWITCH)

        /** D7: the instance verbs. */
        val SUPER_ADMIN_ONLY =
            setOf(
                Op.USER_ADMINISTRATION,
                Op.WORKSPACE_CREATE,
                Op.MANAGE_INSTANCE_WORKSPACES,
                Op.MANAGE_DATASOURCE_GRANTS,
            )
    }
}

/** Shorthand: this file names the operation enum on almost every line. */
private typealias Op = ScopeMatrix.RestOperation
