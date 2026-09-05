package co.datapipelines.auth

/**
 * What an API key IS (auth.md §7.7, published-endpoints design §5.2, ruling R-EP2) — stored in
 * `api_keys.kind`.
 *
 * A kind is not a scope and deliberately not modelled as one. Scopes answer "how much may this
 * credential do?" along one hierarchy; a kind answers "what kind of credential is this?", and the
 * two axes do not compose. An endpoint key is not "a user key with fewer scopes": its authority
 * comes from its rows in `endpoint_key_bindings`, its scopes are never consulted, and it is
 * refused everywhere the published-endpoint surface does not reach — including surfaces a `read`
 * scope would otherwise open.
 *
 * That refusal is the security property worth stating plainly: **an endpoint key with no binding
 * on any ancestor of the path it presents at authorises nothing.** The absence of a binding is
 * never a fall-through to the user-key rule.
 */
enum class ApiKeyKind {
    /**
     * Every key that existed before round 074, and the default for every key minted without an
     * explicit kind: scopes, a pinned workspace, and the whole API surface those scopes allow.
     */
    USER,

    /**
     * A credential for published endpoints only (design §5.2): no scopes are consulted,
     * workspace-pinned, and it authorises exactly the endpoints its bindings cover plus the
     * result cursor of executions it started.
     */
    ENDPOINT,
    ;

    /** Lowercase wire token, as stored in `api_keys.kind` and sent on the REST surface. */
    val wire: String get() = name.lowercase()

    companion object {
        /** The default for a key minted without a kind — and the V11 backfill for every older row. */
        val DEFAULT = USER

        /** Parses a wire token, or null when it is not one — the surface turns null into a 400. */
        fun fromWireOrNull(token: String?): ApiKeyKind? = entries.firstOrNull { it.wire == token?.lowercase()?.trim() }

        /** Parses a wire token. Throws on an unknown one, mirroring [Scope.fromWire]. */
        fun fromWire(token: String): ApiKeyKind = fromWireOrNull(token) ?: throw IllegalArgumentException("Unknown API key kind: $token")

        /** The wire values a payload may carry, for a validation message. */
        val WIRE_VALUES: List<String> = entries.map { it.wire }
    }
}
