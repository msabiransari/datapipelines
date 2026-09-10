package co.datapipelines.auth

/**
 * What a principal may DO in one workspace — the second axis of the §7.6 matrix
 * (RBAC design §1/§2, D-R1/D-R2).
 *
 * The first axis is [Scope], which travels with a CREDENTIAL (an API key's grant).
 * This one travels with a MEMBERSHIP: the same person is a viewer in one workspace
 * and an author in another, so capability cannot live on the user (D-R1).
 *
 * ## Why this is not an ordinal hierarchy
 * [Scope] is a strict chain (`read ⊂ execute ⊂ author ⊂ admin`) and compares by
 * ordinal. Capability is **not** a chain and must not pretend to be: an author may
 * not `release` and a promoter may not author, so neither dominates the other
 * (D-R2 — "DevOps guys who can only release"). Each constant therefore states its
 * own predicate over the membership flags, read directly off the row. Declaration
 * order follows the design's §1 table so the doc and this enum read the same way;
 * nothing compares ordinals.
 *
 * ## The `admin → author` invariant
 * A workspace admin can author (design §1). The flag is explicit on the row and the
 * database enforces it (`chk_workspace_member_admin_authors`, V23), so [AUTHOR] can
 * test `author` alone rather than `author || admin` — one invariant, asserted once,
 * instead of an implication re-spelled at every predicate.
 */
enum class Capability {
    /** Read everything in the workspace. Any member (D-R3). */
    VIEW,

    /**
     * Execute pipelines, read results, cancel own runs, probe read-only SQL.
     * **Viewer-level** (D-R3, the owner: "executing them should be fine") — which is
     * exactly where the two axes stop agreeing: an `execute` SCOPE is above `read`,
     * while the EXECUTE capability is at the bottom with [VIEW]. A `read`-scoped key
     * may not execute; a viewer session may.
     */
    EXECUTE,

    /** Create/edit drafts, discard, restore, purge, publish endpoints, register lake tables, issue own keys (D-R4). */
    AUTHOR,

    /**
     * Switch the served version — the rollback lever (O-1). Its own capability because
     * it is the one verb the author and the promoter BOTH hold and neither implies:
     * `author || promoter`. Expressing it as either alone would take the lever away
     * from the other, which is the operational half of promotion (O-1).
     */
    SWITCH,

    /** `release` and `promote` — the DevOps verbs (D-R2). `promoter || admin`. */
    PROMOTE,

    /** Members and roles, datasource registration bound to this workspace, the workspace audit trail. */
    WS_ADMIN,

    /** Instance verbs: create/deactivate workspaces, users, config, datasource grants (D-R8). */
    SUPER_ADMIN,
    ;

    /** The doc token for this capability — what `auth.md §7.6`'s capability column carries. */
    val wire: String get() = name.lowercase()

    /**
     * True when a membership carrying [flags] holds this capability. The ONE place the
     * design's §1 table is written as code; [ScopeMatrix.allowed] is its only caller.
     */
    fun satisfiedBy(flags: MembershipFlags): Boolean =
        when (this) {
            VIEW, EXECUTE -> true
            AUTHOR -> flags.author
            SWITCH -> flags.author || flags.promoter
            PROMOTE -> flags.promoter || flags.admin
            WS_ADMIN -> flags.admin
            SUPER_ADMIN -> flags.superAdmin
        }

    companion object {
        /** Parses a doc token (`view`, `ws_admin`). Throws on an unknown token. */
        fun fromWire(token: String): Capability =
            entries.firstOrNull { it.wire == token.lowercase() }
                ?: throw IllegalArgumentException("Unknown capability: $token")
    }
}

/**
 * One membership row's capability flags (metadata-db §4.12), plus whether the holder is
 * a SUPER admin — which is a property of the user, not of the row (D-R1: `users.is_admin`
 * stays and means super admin).
 *
 * A super admin is an implicit member of every workspace (D-R8), so [superAdmin] forces
 * every flag true; [implicit] records whether they hold an EXPLICIT membership, because
 * an action taken without one is audited with `acting_via=super_admin`.
 */
data class MembershipFlags(
    val author: Boolean = false,
    val promoter: Boolean = false,
    val admin: Boolean = false,
    val superAdmin: Boolean = false,
    val implicit: Boolean = false,
) {
    /** The capabilities this membership holds — the row read as the design's §1 column. */
    fun held(): Set<Capability> = Capability.entries.filterTo(mutableSetOf()) { it.satisfiedBy(this) }

    companion object {
        /** A member with no flags: the viewer (design §1). */
        val VIEWER = MembershipFlags()

        /** A super admin acting in a workspace they hold no explicit membership in (D-R8). */
        val IMPLICIT_SUPER_ADMIN =
            MembershipFlags(author = true, promoter = true, admin = true, superAdmin = true, implicit = true)

        /** [flags] as a super admin holds them: every capability, membership kept for the audit flag. */
        fun superAdminOver(flags: MembershipFlags?): MembershipFlags =
            flags?.copy(author = true, promoter = true, admin = true, superAdmin = true, implicit = false)
                ?: IMPLICIT_SUPER_ADMIN
    }
}
