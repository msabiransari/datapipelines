package co.datapipelines.auth

/**
 * What an API key IS (auth.md §7.7, published-endpoints design §5.2, ruling R-EP2) — stored in
 * `api_keys.kind`.
 *
 * A kind decides WHO the key acts as and WHERE it may be presented (#215): the MCP key ([USER])
 * acts as its member over `/mcp` and nowhere else (B2); an [ENDPOINT] and a [SERVER] key act as
 * their own `service` identity with a [KeyRole] — `api_caller` on the published paths bound to it,
 * `promotion_receiver` on the promotion route family — and are refused everywhere else, centrally,
 * so a new route cannot become reachable to a key by someone forgetting a check.
 *
 * The security property worth stating plainly: **an endpoint key with no binding on any ancestor
 * of the path it presents at authorises nothing.** The absence of a binding is never a fall-through.
 */
enum class ApiKeyKind {
    /**
     * The MCP key (D16): minted at login, one per member per workspace, acting as that member —
     * their role capped at author (PK4) — over `/mcp` only (B2).
     */
    USER,

    /**
     * A credential for published endpoints only (design §5.2): an `api_caller` identity,
     * workspace-pinned, authorising exactly the endpoints its bindings cover plus the executions
     * it started.
     */
    ENDPOINT,

    /**
     * The promotion peer's credential (auth.md §7.7, versioning §10.6): minted by a super admin,
     * presented as `DP-Promotion-Key` by a SENDING deployment, and accepted by
     * `PromotionServerKeyFilter` on the promotion receiver's routes and nowhere else. It acts as
     * its own `promotion_receiver` identity (record C4) — for any workspace a batch names (B6).
     * What it adds over the deprecated config value is everything a credential needs: an
     * identity, an expiry, a revocation flag, a last-used stamp, rotation without a restart.
     */
    SERVER,
    ;

    /** Lowercase wire token, as stored in `api_keys.kind` and sent on the REST surface. */
    val wire: String get() = name.lowercase()

    companion object {
        /** The default for a key minted without a kind — and the V11 backfill for every older row. */
        val DEFAULT = USER

        /** The kinds that act as their OWN identity (PK5) — every kind but the MCP key. */
        val IDENTITY_KINDS: Set<ApiKeyKind> = setOf(ENDPOINT, SERVER)

        /** Parses a wire token, or null when it is not one — the surface turns null into a 400. */
        fun fromWireOrNull(token: String?): ApiKeyKind? = entries.firstOrNull { it.wire == token?.lowercase()?.trim() }

        /** Parses a wire token. Throws on an unknown one. */
        fun fromWire(token: String): ApiKeyKind = fromWireOrNull(token) ?: throw IllegalArgumentException("Unknown API key kind: $token")

        /** The wire values a payload may carry, for a validation message. */
        val WIRE_VALUES: List<String> = entries.map { it.wire }
    }
}
