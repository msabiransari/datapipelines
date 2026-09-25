package co.datapipelines.auth

import java.util.UUID

/**
 * How a principal proved its identity (auth.md §3).
 *
 * [PROMOTION] is not a human and not a key a human issued: it is the pre-shared server key of
 * a peer DEPLOYMENT (versioning §10.6), resolved onto R7's system service account. It is
 * carried here rather than folded into [API_KEY] so that every `authMethod` check in the
 * system states its intent explicitly — a promotion peer must not be mistaken for an agent's
 * key at a rule written for one of them.
 */
enum class AuthMethod { OIDC, API_KEY, PROMOTION }

/**
 * How a SESSION was established (RFC 8176 `amr` values): [PWD] by a local password, [OIDC]
 * by an identity provider. Carried on the session JWT and on the principal so that policies
 * about the local credential — the forced password change (auth.md §5A.4) — apply only to
 * sessions that credential opened. Null on non-session principals (API key, promotion) and
 * on session tokens minted before the claim existed, which the gate treats as [PWD].
 */
enum class LoginMethod(
    val amr: String,
) {
    PWD("pwd"),
    OIDC("oidc"),
    ;

    companion object {
        fun fromAmr(value: String?): LoginMethod? = entries.firstOrNull { it.amr == value }
    }
}

/**
 * The internal principal both auth paths resolve to (auth.md §3).
 *
 * What it may do is a question of ROLE only since #215 slice (b) (scopes are gone, PK8): a
 * session's role is the membership's in [workspace], and every key carries a [keyRole] of its
 * own (keys v2 A13 — an `mcp` key's chosen member role, no derivation, no cap). [keyId] is
 * present only when [authMethod] is [AuthMethod.API_KEY] or a stored server key opened the
 * promotion route.
 *
 * ## Workspace (design §5)
 * [workspaceName] is the *unresolved* value the credential carries — the JWT's
 * `active_workspace` claim, or the key's pinned workspace name. [workspace] is the
 * resolved, membership-checked [WorkspaceContext] the `WorkspaceResolutionFilter`
 * stamps once per request; before that filter runs it is null, and after it, null
 * means "principal with zero memberships" (possible under `closed` provisioning —
 * every workspace-scoped operation must then 403, which is what [requireWorkspace]
 * raises).
 */
data class AuthenticatedPrincipal(
    val userId: UUID,
    val email: String,
    val displayName: String,
    val authMethod: AuthMethod,
    val keyId: String? = null,
    val workspaceName: String? = null,
    val workspace: WorkspaceContext? = null,
    /**
     * The presented key's [ApiKeyKind] (§7.7), or null when the credential is not an API key.
     *
     * Carried on the principal rather than re-read per check because the decision it drives —
     * "may this credential be here at all?" — is made in a filter and again in the scope
     * interceptor, and a second database read between the two could see a different answer.
     */
    val keyKind: ApiKeyKind? = null,
    /**
     * See [LoginMethod]; null for API-key and promotion principals and for session tokens
     * minted before the `amr` claim existed. LAST on purpose: positional constructor calls
     * across the test suites must keep compiling.
     */
    val loginMethod: LoginMethod? = null,
    /**
     * `users.is_admin` — the INSTANCE super admin (D-R1). Read from the live user snapshot on
     * every request, never from a JWT claim, so a revoked super admin loses it within one
     * `AuthCache` TTL instead of at token expiry.
     *
     * It replaced the derived `isAdmin` (which read `Scope.ADMIN` out of the principal's
     * scopes) when round 1 emptied session scope sets: capability is the membership now, and
     * the ONE thing that is still global is this flag. Named `superAdmin` because that is what
     * it means — the old name read as "workspace owner" at half its call sites.
     *
     * LAST and defaulting FALSE, for the same two reasons `loginMethod` is: positional
     * constructions across the suites keep compiling, and a caller that forgets it gets the
     * unprivileged answer.
     */
    val superAdmin: Boolean = false,
    /**
     * The KEY's role (#215 record §3.2; keys v2 A13 — every kind carries one): the whole answer
     * to [holds] when set — an `mcp` key's chosen member role (`author`, `promoter`,
     * `workspace_admin`), an `endpoint` key's [KeyRole.API_CALLER], a server key's
     * [KeyRole.PROMOTION_RECEIVER]. Null for a session. A key role reaches no member
     * permission beyond its column and no instance permission.
     */
    val keyRole: KeyRole? = null,
) {
    /**
     * True when this principal is an `endpoint` key (§7.7): its authority is [KeyRole.API_CALLER]
     * on the paths bound to it. Refused everywhere the published-endpoint surface does not reach,
     * and it holds NO authority on an endpoint with no binding on any ancestor.
     */
    val isEndpointKey: Boolean get() = keyKind == ApiKeyKind.ENDPOINT

    /**
     * True when this principal's authority is the promotion route family (§7.7).
     *
     * A server key opens the `/api/v1/promotion/` subtree and nothing else. It reaches that route only
     * through `DP-Promotion-Key` — presented as an ordinary `DP-API-Key` it authenticates a
     * principal that [ScopeInterceptor] refuses on every route, `/mcp` included (there by
     * `McpAuthFilter`, because a servlet never reaches the interceptor).
     */
    val isServerKey: Boolean get() = keyKind == ApiKeyKind.SERVER

    /**
     * Instance super admin (D-R1/D-R8): an implicit member of every workspace, with every
     * capability, and every action taken outside an explicit membership is audited with
     * `acting_via=super_admin`.
     *
     * A SESSION's property only: every key principal carries `superAdmin = false` (#215 B1 —
     * keys v2 keeps it), so no key resolves an instance permission, ever, whoever minted
     * it, and `super_admin` is never a value `api_keys.role` accepts.
     */
    val isSuperAdmin: Boolean get() = superAdmin

    /**
     * True when this principal administers the ACTIVE workspace's members — the workspace
     * admin's representative permission, `workspace.members.manage` — or is super admin (D-R8,
     * who administers every workspace).
     *
     * Kept for the screens' "administers this workspace" question (the role model, the shell).
     * A CHECK asks for the permission it is about instead (#215 A.3): the own-only execution
     * filters ask `execution.read_all`, the cross-workspace fact retire `datasource.manage` —
     * so a later ruling that splits one of those from membership administration moves only
     * that check. The successor, before that, to the `admin`-scope question, which round 1 made
     * permanently FALSE (a session carried no scopes, D-R1; no key held `admin`, O-2).
     */
    val isWorkspaceAdmin: Boolean
        get() = superAdmin || holds(Permission.WORKSPACE_MEMBERS_MANAGE)

    /**
     * "May this principal AUTHOR here" — asked through the author row's representative
     * permission, `template.update` (#215: every authoring permission is held by exactly the
     * author and workspace-admin columns). The same defect [isWorkspaceAdmin] had, for the same
     * reason: the UI once asked it as a scope question, and a SESSION carried no scopes at all
     * since D-R1, so two screens' action columns were invisible to every signed-in human. Found
     * by the browser suite. Since #215 slice (b) there is no credential axis left: the role is
     * the whole answer, for a session and for the MCP key alike (its role capped at author, PK4).
     */
    val isAuthor: Boolean
        get() = superAdmin || holds(Permission.TEMPLATE_UPDATE)

    /**
     * "May this principal PROMOTE here" — [Permission.PROMOTION_PROMOTE], the third rung of the same
     * question [isWorkspaceAdmin] and [isAuthor] answer, spelled here for the same reason: the
     * screens ask it, and a screen that re-derives a permission predicate is one more place the
     * matrix can drift away from (114 — the round that made the UI stop offering verbs the
     * server refuses).
     *
     * Deliberately NOT implied by [isAuthor] and not implying it: since the 2026-09-20 rulings
     * an author RELEASES and a promoter PROMOTES (D5, D8) — the two roles hold different rows,
     * which is the whole reason [RolePermissions] writes each role's column out rather than a chain.
     */
    val isPromoter: Boolean
        get() = superAdmin || holds(Permission.PROMOTION_PROMOTE)

    /**
     * The workspace role this principal holds in the ACTIVE workspace, or null with no
     * reachable workspace. A super admin's is their EXPLICIT membership's role (viewer when
     * implicit) — ask [isSuperAdmin] for the instance authority, never this.
     */
    val workspaceRole: WorkspaceRole? get() = workspace?.role

    /**
     * "Does the promoter LENS apply to this principal" (roles design §3.1, D5; 178): a
     * promoter — and nothing else — in the active workspace. Since V29 a member holds one
     * role, so "promoter and also an author" cannot exist; the one conjunct that still
     * matters is the instance authority: a super admin whose explicit membership is a
     * promoter role reads everything, the way every other super-admin read does.
     *
     * Keys v2 (A13 — the key's identity holds its role EXACTLY as a member does): an `mcp`
     * key whose key role is the promoter role is lensed like the member it mirrors — an
     * agent's key is not a way around the lens. Deliberately NOT [isPromoter]: that predicate
     * answers "may this principal PROMOTE" and is true for admins; the lens is about what a
     * principal SEES, and admins see everything.
     */
    val isLensed: Boolean
        get() = !superAdmin && (workspaceRole == WorkspaceRole.PROMOTER || keyRole == KeyRole.PROMOTER)

    /**
     * Does this principal hold [permission] — the ONE question a service asks (#215 A.3), and
     * the one spelling every predicate above shares.
     *
     * An INSTANCE permission ([RolePermissions.INSTANCE] — workspaces, users, grants, server
     * keys) is the USER's authority, not a membership's, so it is judged on [superAdmin] alone,
     * with or without a reachable workspace (#113's empty-instance recovery) — exactly the
     * `isSuperAdmin` checks it replaced, including for a context resolved from a plain membership
     * row. Every other permission is judged over the active context; with none, it is the D-R5
     * 404 and is not held. Scopes are the credential axis and are not consulted here; the matrix
     * asks them at the route.
     */
    fun holds(permission: Permission): Boolean =
        when {
            keyRole != null -> permission in RolePermissions.of(keyRole)
            permission in RolePermissions.INSTANCE -> superAdmin
            else -> workspace?.permits(permission) ?: false
        }

    /**
     * The role this principal would be judged as — a refusal's informative `held` detail (#215
     * A.6): `super_admin` for the instance super admin, else the active membership's role, or
     * null with no reachable workspace.
     */
    val heldRole: String?
        get() =
            when {
                keyRole != null -> keyRole.wire
                superAdmin -> WorkspaceContext.SUPER_ADMIN_WIRE
                else -> workspace?.heldRole
            }

    /**
     * The resolved active workspace, or [WorkspaceMembershipRequiredException] (403)
     * when the principal has none — the "zero memberships" refusal every workspace-scoped
     * operation shares. This is the one place [WorkspaceErrorCodes.MEMBERSHIP_REQUIRED]
     * survived D-R5's 404 rule, and deliberately: no workspace was ADDRESSED here, so there
     * is no name whose existence a 403 could leak.
     */
    fun requireWorkspace(): WorkspaceContext =
        workspace ?: throw WorkspaceMembershipRequiredException("Principal has no active workspace (zero memberships)")
}
