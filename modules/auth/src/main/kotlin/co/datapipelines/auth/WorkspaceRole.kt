package co.datapipelines.auth

/**
 * The ONE role a membership holds in a workspace (roles design D1, D21 — 2026-09-20;
 * `workspace_members.role`, V29). Four values, exclusive: a member is exactly one of
 * these per workspace, and the database CHECK (`chk_workspace_member_role`) admits
 * nothing else.
 *
 * Super admin is NOT a value here on purpose. It is a property of the USER
 * (`users.is_admin`, D7), held in every workspace at once, so it travels on the
 * principal and on [WorkspaceContext.superAdmin] rather than on the membership row.
 *
 * ## Why one role and not three additive flags
 * V23 had replaced the v1 `role` column with `author`/`promoter`/`admin` booleans so that
 * "an author who also releases" could be one row. The 2026-09-20 rulings took release
 * away from the promoter (D5/D8) and made the promoter an OPS role that authors nothing,
 * so the combination the flags existed to express no longer exists — and a row that can
 * say "author AND promoter" is a row the matrix has to reason about in eight states
 * instead of four. V29 folds the flags back into one value; the migration's precedence
 * is `admin → WORKSPACE_ADMIN, else promoter → PROMOTER, else author → AUTHOR, else VIEWER`.
 *
 * Declaration order is the doc's column order (auth.md §7.6: viewer | author | promoter |
 * ws_admin), so the enum, the members dropdown and the table read the same way. Nothing
 * compares ordinals: the roles are not a chain (a promoter is not "more" than an author —
 * it holds different rows), which is why every question is asked of a [Permission].
 */
enum class WorkspaceRole {
    /** Reads the workspace and executes pipelines (D3). Nothing else. */
    VIEWER,

    /** Viewer + creates, edits, releases, switches, publishes, registers lake tables, records facts (D4, D8). */
    AUTHOR,

    /**
     * The ops role (D5): reads datasources and promotes. Executes nothing, reads no
     * executions, authors nothing, releases nothing. R2 (#178) narrows what it SEES to
     * released-and-newer objects; R1 only takes away the verbs §2 says it lacks.
     */
    PROMOTER,

    /** Everything in the workspace, including members, roles, workspace datasources and promotion (D6). */
    WORKSPACE_ADMIN,
    ;

    /** The wire and database token (`viewer`, `workspace_admin`) — `workspace_members.role`, the REST `role` field, the dropdown value. */
    val wire: String get() = name.lowercase()

    /** The word a screen prints beside a member's name — `workspace admin`, not `workspace_admin`. */
    val label: String get() = wire.replace('_', ' ')

    companion object {
        /** Parses a wire token; null for anything outside the four values. */
        fun fromWireOrNull(token: String?): WorkspaceRole? = token?.let { t -> entries.firstOrNull { it.wire == t.lowercase() } }

        /** Parses a wire token. Throws on an unknown token — a role that does not exist is a defect, never a default. */
        fun fromWire(token: String): WorkspaceRole = fromWireOrNull(token) ?: throw IllegalArgumentException("Unknown workspace role: $token")
    }
}
