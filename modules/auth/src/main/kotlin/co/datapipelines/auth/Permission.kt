package co.datapipelines.auth

/**
 * A permission is a ROW of the matrix, not a string (roles design 2026-09-20, D21 and the
 * owner's standing principle: "we don't have permissions but actions to put the check
 * on"). Each constant names the set of [WorkspaceRole]s that may perform the actions
 * mapped onto it in [ScopeMatrix]; a super admin (D7) holds every one of them in every
 * workspace, so [satisfiedBy] short-circuits on that flag first.
 *
 * This is the second axis of the §7.6 matrix. The first is [Scope], which travels with a
 * CREDENTIAL (an API key's grant); this one travels with a MEMBERSHIP, because the same
 * person is a viewer in one workspace and an author in another.
 *
 * ## Why role SETS and not a chain
 * [Scope] is a strict chain (`read ⊂ execute ⊂ author ⊂ admin`) and compares by ordinal.
 * The roles are not a chain: the promoter reads datasources and promotes, but executes
 * nothing and reads no executions (D5, ratified 2026-09-20), so it sits BELOW the viewer
 * on [EXECUTE] and ABOVE the author on [PROMOTE]. Each constant therefore lists its roles
 * outright — the §2 table's row, read left to right — and nothing infers one row from
 * another. `RoleMatrixTest` asserts the rows as behaviour; `ScopeMatrixSpecDriftTest`
 * asserts them against the doc's five role columns.
 *
 * Declaration order follows the doc's usual reading (broadest first) and carries no meaning.
 */
enum class Permission(
    /** The workspace roles this permission admits. A super admin is admitted regardless (D7). */
    val roles: Set<WorkspaceRole>,
) {
    /** Read the workspace's objects: every role. The promoter's LENS over what it reads is R2's. */
    VIEW(setOf(WorkspaceRole.VIEWER, WorkspaceRole.AUTHOR, WorkspaceRole.PROMOTER, WorkspaceRole.WORKSPACE_ADMIN)),

    /**
     * Execute pipelines, cancel runs, read executions and results, test a datasource
     * connection: every role EXCEPT the promoter (§2 rows 2, 3 and 9 — "the connection
     * test follows execute"). Execution reads are additionally narrowed to the caller's
     * OWN runs unless they administer the workspace (D11) — a read-path filter, not a
     * role: the matrix admits the viewer to the row, the query admits them to their rows.
     *
     * This is exactly where the two axes stop agreeing: `execute` is the second SCOPE,
     * while EXECUTE is a viewer-level PERMISSION. A `read`-scoped key may not execute; a
     * viewer session may.
     */
    EXECUTE(setOf(WorkspaceRole.VIEWER, WorkspaceRole.AUTHOR, WorkspaceRole.WORKSPACE_ADMIN)),

    /**
     * Create, edit, discard, restore, purge, RELEASE, switch the served version, publish
     * endpoints, register lake tables, record and retire learned facts (D4, D8). Release
     * moved here from the promoter on 2026-09-20 ("author releases, promoter promotes").
     */
    AUTHOR(setOf(WorkspaceRole.AUTHOR, WorkspaceRole.WORKSPACE_ADMIN)),

    /**
     * Read the promotion page (owner rule 13): the author who released sees what is
     * promotable, the promoter who promotes sees the same list. The promote ACTION is
     * [PROMOTE]; this row exists so the PAGE can be wider than the verb on it.
     */
    PROMOTION_READ(setOf(WorkspaceRole.AUTHOR, WorkspaceRole.PROMOTER, WorkspaceRole.WORKSPACE_ADMIN)),

    /** Promote to the higher environment (D5, D8): the promoter's one verb, and the admin's. */
    PROMOTE(setOf(WorkspaceRole.PROMOTER, WorkspaceRole.WORKSPACE_ADMIN)),

    /**
     * Members, roles and invitations, workspace-bound datasources, the workspaces page,
     * the workspace audit trail (D6, D12, D13).
     */
    WS_ADMIN(setOf(WorkspaceRole.WORKSPACE_ADMIN)),

    /** Instance verbs: workspaces, users, global datasources and their grants (D7). No workspace role holds it. */
    SUPER_ADMIN(emptySet()),
    ;

    /** The doc token for this permission — what `auth.md §7.6`'s prose and the refusal's `required` detail carry. */
    val wire: String get() = name.lowercase()

    /**
     * True when a member holding [role] — or any super admin — may perform this permission's
     * actions. The ONE place the §2 table is code; [ScopeMatrix.allowed] and the
     * principal's derived predicates are its callers.
     */
    fun satisfiedBy(
        role: WorkspaceRole,
        superAdmin: Boolean,
    ): Boolean = superAdmin || role in roles

    companion object {
        /** Parses a doc token (`view`, `ws_admin`). Throws on an unknown token. */
        fun fromWire(token: String): Permission =
            entries.firstOrNull { it.wire == token.lowercase() }
                ?: throw IllegalArgumentException("Unknown permission: $token")

        /** Every permission [role] holds — the row read as a column, for refusal details and the UI. */
        fun heldBy(
            role: WorkspaceRole,
            superAdmin: Boolean,
        ): Set<Permission> = entries.filterTo(mutableSetOf()) { it.satisfiedBy(role, superAdmin) }
    }
}
