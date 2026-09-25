package co.datapipelines.auth

/**
 * A KEY's role — `api_keys.role` (V34, widened by keys v2 V35, #233). Pre-created and fixed:
 * no key can hold a role outside its kind's family (the `chk_api_keys_role` CHECK), and no key
 * is ever a super admin (B1) — `super_admin` is not a value here.
 *
 * Since keys v2 (A13/A14/B1) the enum is the UNION of the two key-role families, because
 * `api_keys.role` is one column and [AuthenticatedPrincipal.keyRole] is one answer:
 *
 * - the MEMBER key roles — [AUTHOR], [PROMOTER], [WORKSPACE_ADMIN] — are what an `mcp` key
 *   carries (A15: viewer is never a key role). Each is judged by the SAME [RolePermissions]
 *   column the member role holds, with no cap and no derivation: the key's identity holds the
 *   role in the key's workspace exactly as a member does.
 * - the TRANSPORT key roles — [API_CALLER] on an `endpoint` key, [PROMOTION_RECEIVER] on a
 *   `server` key — are unchanged from #215.
 *
 * One enum (rather than a second column or a wire-string parse at every read) keeps ONE
 * authorization path: `ScopeMatrix.allowed` judges a key by its `keyRole` column alone, for
 * every kind. Nothing compares ordinals; the roles are not a chain (A14 — promoter and author
 * are not comparable), which is why every question is asked of a [Permission].
 */
enum class KeyRole {
    /** An `endpoint` key: serve its bound published paths, read the executions it started (§3.2, A16). */
    API_CALLER,

    /** A `server` key: the promotion receiver's inventory and push, for any workspace (§3.2, B6). */
    PROMOTION_RECEIVER,

    /** An `mcp` key minted by (or for) an author: the author column, over `/mcp` (A13/A14). */
    AUTHOR,

    /** An `mcp` key minted by (or for) a promoter: the promoter column, over `/mcp`. */
    PROMOTER,

    /** An `mcp` key minted by (or for) a workspace admin: the workspace-admin column, over `/mcp`. */
    WORKSPACE_ADMIN,
    ;

    /** The wire and database token (`api_caller`, `author`, …) — snake_case everywhere (A5). */
    val wire: String get() = name.lowercase()

    /** The word a screen prints (A5: the UI shows a human label) — `api caller`, not `api_caller`. */
    val label: String get() = wire.replace('_', ' ')

    /** True for the three MEMBER key roles an `mcp` key may carry (A13); false for the transport roles. */
    val isMemberKeyRole: Boolean get() = this in MEMBER_KEY_ROLES

    /** The member role this key role mirrors — non-null exactly when [isMemberKeyRole]. */
    fun asMemberRole(): WorkspaceRole? =
        when (this) {
            AUTHOR -> WorkspaceRole.AUTHOR
            PROMOTER -> WorkspaceRole.PROMOTER
            WORKSPACE_ADMIN -> WorkspaceRole.WORKSPACE_ADMIN
            else -> null
        }

    companion object {
        /** The MEMBER roles an `mcp` key may carry, in dialog order — author first (A15: starting at author). */
        val MEMBER_KEY_ROLES: Set<KeyRole> = setOf(AUTHOR, PROMOTER, WORKSPACE_ADMIN)

        /** Every key role's wire token — what a refusal of an unknown one lists as supported. */
        val WIRE_VALUES: List<String> = entries.map { it.wire }

        /** A REQUEST's role token (trimmed, case-folded), or null when it names no key role — the caller refuses it. */
        fun find(token: String): KeyRole? = entries.firstOrNull { it.wire == token.trim().lowercase() }

        /**
         * The transport role a key of [kind] carries when its kind fixes the role — `endpoint`
         * and `server`. An `mcp` key's role is chosen at creation (A13): null here, the caller
         * supplies it.
         */
        fun forKind(kind: ApiKeyKind): KeyRole? =
            when (kind) {
                ApiKeyKind.MCP -> null
                ApiKeyKind.ENDPOINT -> API_CALLER
                ApiKeyKind.SERVER -> PROMOTION_RECEIVER
            }

        /** The member key role mirroring [role] — the values the create surface offers for an `mcp` key. */
        fun ofMemberRole(role: WorkspaceRole): KeyRole? =
            when (role) {
                WorkspaceRole.AUTHOR -> AUTHOR
                WorkspaceRole.PROMOTER -> PROMOTER
                WorkspaceRole.WORKSPACE_ADMIN -> WORKSPACE_ADMIN
                else -> null
            }

        /** Parses a stored token; null for null. Throws on an unknown one — a key that cannot be classified must not authenticate. */
        fun fromWireOrNull(token: String?): KeyRole? =
            token?.let { t -> entries.firstOrNull { it.wire == t } ?: throw IllegalArgumentException("Unknown key role: $t") }
    }
}
