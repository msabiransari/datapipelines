package co.datapipelines.auth

/**
 * A KEY's role (#215, record PK6 / §3.2, A1, A5) — `api_keys.role`, V34, CHECK
 * `chk_api_keys_role`. Pre-created and fixed: no key carries a member role (A1), and no key
 * can hold workspace admin or super admin (PK3) — neither is a value here.
 *
 * The MCP (`user`) key has NO key role: it acts as its member, with the member's role capped at
 * author (PK4), read per request. Only the two kinds that act as their own identity carry one,
 * and each kind carries exactly one ([forKind]) — so the choice a slice (c) dialog offers is a
 * function of the kind, which is what the database CHECK states.
 */
enum class KeyRole {
    /** An `endpoint` key: serve its bound published paths, read the executions it started (§3.2). */
    API_CALLER,

    /** A `server` key: the promotion receiver's inventory and push, for any workspace (§3.2, B6). */
    PROMOTION_RECEIVER,
    ;

    /** The wire and database token (`api_caller`, `promotion_receiver`) — snake_case everywhere (A5). */
    val wire: String get() = name.lowercase()

    /** The word a screen prints (A5: the UI shows a human label) — `api caller`, not `api_caller`. */
    val label: String get() = wire.replace('_', ' ')

    companion object {
        /** Every key role's wire token — what a refusal of an unknown one lists as supported. */
        val WIRE_VALUES: List<String> = entries.map { it.wire }

        /** A REQUEST's role token (trimmed, case-folded), or null when it names no key role — the caller refuses it. */
        fun find(token: String): KeyRole? = entries.firstOrNull { it.wire == token.trim().lowercase() }

        /** The one role a key of [kind] carries, or null for the MCP key, whose role is its member's (PK4). */
        fun forKind(kind: ApiKeyKind): KeyRole? =
            when (kind) {
                ApiKeyKind.USER -> null
                ApiKeyKind.ENDPOINT -> API_CALLER
                ApiKeyKind.SERVER -> PROMOTION_RECEIVER
            }

        /** Parses a stored token; null for null. Throws on an unknown one — a key that cannot be classified must not authenticate. */
        fun fromWireOrNull(token: String?): KeyRole? =
            token?.let { t -> entries.firstOrNull { it.wire == t } ?: throw IllegalArgumentException("Unknown key role: $t") }
    }
}
