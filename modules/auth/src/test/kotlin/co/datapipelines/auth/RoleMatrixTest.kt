package co.datapipelines.auth

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The role × operation matrix as BEHAVIOUR (RBAC design §8.3): one section per role, each
 * asserting the verbs it holds AND the verbs it is refused, through the real
 * [ScopeMatrix.allowed] — the same function `ScopeInterceptor` and `McpToolDispatcher` call.
 *
 * ## Why the allowed set is spelled out per role rather than derived
 * A test that computed the expected set from `Capability.satisfiedBy` would pass for any
 * matrix, including a wrong one: it would be asserting that the code agrees with itself. So
 * each role's allowed operations are written down as the design's §1 table states them, and
 * the exhaustiveness test below proves no operation escaped classification — a new
 * `RestOperation` fails this suite until somebody decides which roles hold it.
 *
 * `ScopeMatrixSpecDriftTest` covers the other direction (the matrix versus the DOC); this
 * covers the decision function over the matrix.
 */
class RoleMatrixTest {
    private val workspaceId = UUID.randomUUID()

    // ------------------------------------------------------------------ the five roles

    /** A member with no flags. D-R3: reads everything, runs everything, changes nothing. */
    @Test
    fun `viewer`() {
        assertRole(
            flags = MembershipFlags.VIEWER,
            allowed =
                setOf(
                    Op.READ_RESOURCES,
                    Op.RETRIEVE_RESULT,
                    Op.EXECUTE_PIPELINE,
                    Op.CANCEL_EXECUTION,
                    Op.MANAGE_OWN_API_KEYS,
                    Op.CURRENT_PRINCIPAL,
                    Op.PROFILE_PREFERENCE,
                    Op.WORKSPACES_READ,
                    Op.CHANGE_OWN_PASSWORD,
                    Op.SERVE_PUBLISHED_ENDPOINT,
                ),
        )
    }

    /** D-R4: the authoring verbs, including discard/restore/purge and switch — but not release. */
    @Test
    fun `author`() {
        assertRole(
            flags = MembershipFlags(author = true),
            allowed =
                viewerVerbs() +
                    setOf(
                        Op.MUTATE_PIPELINES_TEMPLATES,
                        Op.INTROSPECT_DATASOURCE,
                        Op.MANAGE_ENDPOINTS,
                        Op.MUTATE_LAKE_TABLES,
                        Op.SWITCH_SERVED_VERSION,
                    ),
        )
    }

    /** D-R2, the owner's "DevOps guys": release and promote, and the switch lever — no authoring. */
    @Test
    fun `promoter`() {
        assertRole(
            flags = MembershipFlags(promoter = true),
            allowed =
                viewerVerbs() +
                    setOf(
                        Op.RELEASE_VERSION,
                        Op.PROMOTE_VERSION,
                        Op.SWITCH_SERVED_VERSION,
                    ),
        )
    }

    /** The old `owner`. Everything inside the workspace, nothing about the instance. */
    @Test
    fun `workspace admin`() {
        assertRole(
            flags = MembershipFlags(author = true, admin = true),
            allowed =
                viewerVerbs() +
                    setOf(
                        Op.MUTATE_PIPELINES_TEMPLATES,
                        Op.INTROSPECT_DATASOURCE,
                        Op.MANAGE_ENDPOINTS,
                        Op.MUTATE_LAKE_TABLES,
                        Op.SWITCH_SERVED_VERSION,
                        Op.RELEASE_VERSION,
                        Op.PROMOTE_VERSION,
                        Op.TEST_DATASOURCE,
                        Op.MUTATE_WORKSPACE_DATASOURCES,
                        Op.MANAGE_WORKSPACE,
                        Op.MANAGE_WORKSPACE_MEMBERS,
                    ),
        )
    }

    /** D-R8: implicitly a member of every workspace, with every capability. */
    @Test
    fun `super admin`() {
        assertRole(flags = MembershipFlags.IMPLICIT_SUPER_ADMIN, allowed = Op.entries.toSet())
    }

    // ------------------------------------------------------------------ the two axes

    @Test
    fun `a key's SCOPE refuses what its issuer's role would allow - the credential axis`() {
        // An author's `read`-scoped key: the role says yes to authoring, the scope says no.
        // Both axes must pass, which is the whole reason there are two.
        val key = key(scopes = setOf(Scope.READ), flags = MembershipFlags(author = true))

        refusalFor(key, Op.MUTATE_PIPELINES_TEMPLATES).code shouldBe AuthErrorCodes.SCOPE_INSUFFICIENT
    }

    @Test
    fun `a key's ISSUER's role refuses what its scope would allow - the role axis`() {
        // The mirror image: an `author`-scoped key whose issuer has been demoted to viewer.
        // The code is the DEMOTION one, because retrying with this key can never work.
        val key = key(scopes = setOf(Scope.AUTHOR), flags = MembershipFlags.VIEWER)

        refusalFor(key, Op.MUTATE_PIPELINES_TEMPLATES).code shouldBe AuthErrorCodes.KEY_ISSUER_ROLE_LOST
    }

    @Test
    fun `the two axes disagree about EXECUTE, and both answers are right`() {
        // `execute` is the second SCOPE but the viewer-level CAPABILITY (D-R3). So:
        val readKeyOfAViewer = key(scopes = setOf(Scope.READ), flags = MembershipFlags.VIEWER)
        val viewerSession = session(MembershipFlags.VIEWER)

        // …a read-scoped key may NOT execute…
        refusalFor(readKeyOfAViewer, Op.EXECUTE_PIPELINE).code shouldBe AuthErrorCodes.SCOPE_INSUFFICIENT
        // …while a viewer's SESSION may. Same person, same workspace, different credential.
        ScopeMatrix.allowed(viewerSession, Op.EXECUTE_PIPELINE, context(MembershipFlags.VIEWER)) shouldBe
            ScopeMatrix.Decision.Allowed
    }

    @Test
    fun `a session carries no scopes, and is judged on the role axis alone (D-R1)`() {
        // If the scope axis were applied to sessions, an empty scope set would refuse an
        // author's own session on its own content — the regression this asserts against.
        val authorSession = session(MembershipFlags(author = true))
        authorSession.scopes shouldBe emptySet()

        ScopeMatrix.allowed(authorSession, Op.MUTATE_PIPELINES_TEMPLATES, context(MembershipFlags(author = true))) shouldBe
            ScopeMatrix.Decision.Allowed
    }

    @Test
    fun `no reachable workspace is the 404, whatever the operation (D-R5)`() {
        val session = session(MembershipFlags(author = true))

        Op.entries.forEach { op ->
            val decision = ScopeMatrix.allowed(session, op, context = null)
            (decision as ScopeMatrix.Decision.Refused).code shouldBe WorkspaceErrorCodes.NOT_FOUND
        }
    }

    // ------------------------------------------------------------------ exhaustiveness

    @Test
    fun `every operation is classified by this suite - a new one cannot slip in unjudged`() {
        // The non-vacuity floor. Without it, adding a RestOperation nobody listed above would
        // leave it untested here AND allowed by whichever role's capability it happened to
        // match — a guard that cannot go red for the case it exists to catch.
        val classified =
            (viewerVerbs() + AUTHOR_ONLY + PROMOTER_ONLY + WS_ADMIN_ONLY + SUPER_ADMIN_ONLY).toSet()

        classified shouldContainExactly Op.entries.toSet()
    }

    @Test
    fun `every MCP tool carries BOTH axes - a tool on one axis only is a tool half-enforced`() {
        ScopeMatrix.MCP_TOOL_MIN_CAPABILITY.keys shouldContainExactly ScopeMatrix.MCP_TOOL_MIN_SCOPE.keys
    }

    // ------------------------------------------------------------------ helpers

    private fun assertRole(
        flags: MembershipFlags,
        allowed: Set<Op>,
    ) {
        val principal = session(flags)
        val context = context(flags)

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
        ScopeMatrix.allowed(principal, operation, principal.workspace ?: context(MembershipFlags.VIEWER))
            as ScopeMatrix.Decision.Refused

    private fun context(flags: MembershipFlags) = WorkspaceContext(workspaceId, "acme", flags)

    private fun session(flags: MembershipFlags) =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "member@company.com",
            displayName = "Member",
            scopes = emptySet(),
            authMethod = AuthMethod.OIDC,
            workspace = context(flags),
            superAdmin = flags.superAdmin,
        )

    private fun key(
        scopes: Set<Scope>,
        flags: MembershipFlags,
    ) = AuthenticatedPrincipal(
        userId = UUID.randomUUID(),
        email = "agent@company.com",
        displayName = "Agent",
        scopes = scopes,
        authMethod = AuthMethod.API_KEY,
        keyId = "dpk_TEST",
        workspaceName = "acme",
        workspace = context(flags),
    )

    private fun viewerVerbs(): Set<Op> = VIEWER_VERBS

    private companion object {
        val VIEWER_VERBS =
            setOf(
                Op.READ_RESOURCES,
                Op.RETRIEVE_RESULT,
                Op.EXECUTE_PIPELINE,
                Op.CANCEL_EXECUTION,
                Op.MANAGE_OWN_API_KEYS,
                Op.CURRENT_PRINCIPAL,
                Op.PROFILE_PREFERENCE,
                Op.WORKSPACES_READ,
                Op.CHANGE_OWN_PASSWORD,
                Op.SERVE_PUBLISHED_ENDPOINT,
            )
        val AUTHOR_ONLY =
            setOf(
                Op.MUTATE_PIPELINES_TEMPLATES,
                Op.INTROSPECT_DATASOURCE,
                Op.MANAGE_ENDPOINTS,
                Op.MUTATE_LAKE_TABLES,
                Op.SWITCH_SERVED_VERSION,
            )
        val PROMOTER_ONLY = setOf(Op.RELEASE_VERSION, Op.PROMOTE_VERSION)
        val WS_ADMIN_ONLY =
            setOf(
                Op.TEST_DATASOURCE,
                Op.MUTATE_WORKSPACE_DATASOURCES,
                Op.MANAGE_WORKSPACE,
                Op.MANAGE_WORKSPACE_MEMBERS,
            )
        val SUPER_ADMIN_ONLY =
            setOf(
                Op.MUTATE_DATASOURCES,
                Op.USER_ADMINISTRATION,
                Op.WORKSPACE_CREATE,
                Op.MANAGE_INSTANCE_WORKSPACES,
                Op.MANAGE_DATASOURCE_GRANTS,
            )
    }
}

/** Shorthand: this file names the operation enum on almost every line. */
private typealias Op = ScopeMatrix.RestOperation
