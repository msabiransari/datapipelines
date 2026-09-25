package co.datapipelines.auth

/**
 * What an API key IS (auth.md §7.7, published-endpoints design §5.2, ruling R-EP2) — stored in
 * `api_keys.kind`. Keys v2 (#233, A19): **kind is the transport and the word says which** — the
 * word says nothing about WHO the key acts as any more.
 *
 * WHERE each kind may be presented (#215 B2, A16): the MCP key ([MCP]) reaches `/mcp` and
 * nothing else; an [ENDPOINT] key reaches the published paths bound to it and their result-paging
 * routes and nothing else; a [SERVER] key reaches the promotion route family and nothing else —
 * all refused everywhere else, centrally, so a new route cannot become reachable to a key by
 * someone forgetting a check.
 *
 * WHO each kind acts as (keys v2, A13/B1): every kind acts as its OWN `service` identity (PK5) —
 * the identity holds the key's [KeyRole] in the key's workspace exactly as a member does. There
 * is no derivation from a membership and no cap: the role is chosen at creation (A14's subset
 * rule) and changes only when the key is replaced.
 *
 * The security property worth stating plainly: **an endpoint key with no binding on any ancestor
 * of the path it presents at authorises nothing.** The absence of a binding is never a fall-through.
 */
enum class ApiKeyKind {
    /**
     * The MCP key (keys v2 A13): created on the Keys page by anyone holding `mcp_key.create`,
     * acting as its own identity with a MEMBER role (`author` | `promoter` | `workspace_admin` —
     * A15: viewer is never a key role; `super_admin` is never a key role, B1) over `/mcp` only.
     */
    MCP,

    /**
     * A credential for published endpoints only (design §5.2): an `api_caller` identity,
     * workspace-pinned, authorising exactly the endpoints its bindings cover plus the result
     * paging of the executions it started (A16 — under the business path, never the framework's).
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
        /**
         * The kinds that act as their OWN identity (PK5) — every kind, since keys v2 (A13): the
         * MCP key's role is its own, held by its identity, never derived from a member.
         */
        val IDENTITY_KINDS: Set<ApiKeyKind> = entries.toSet()

        /** Parses a wire token, or null when it is not one — the surface turns null into a 400. */
        fun fromWireOrNull(token: String?): ApiKeyKind? = entries.firstOrNull { it.wire == token?.lowercase()?.trim() }

        /** Parses a wire token. Throws on an unknown one. */
        fun fromWire(token: String): ApiKeyKind = fromWireOrNull(token) ?: throw IllegalArgumentException("Unknown API key kind: $token")

        /** The wire values a payload may carry, for a validation message. */
        val WIRE_VALUES: List<String> = entries.map { it.wire }
    }
}
