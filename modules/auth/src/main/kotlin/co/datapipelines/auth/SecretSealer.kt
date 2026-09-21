package co.datapipelines.auth

/**
 * Seals a plaintext secret for at-rest storage and opens it again (roles design 2026-09-20,
 * D16 / §3.3).
 *
 * The login-minted MCP key is never shown to anyone at mint time — there is no screen in a
 * login redirect — so its plaintext is stored SEALED in `api_keys.secret_sealed` and opened
 * only when the owner asks the top bar to copy it. The Argon2id hash stays the
 * authentication half (auth.md §7.2); this port is the recovery half.
 *
 * Declared in `auth` because that is where the key aggregate lives; `auth` may depend on
 * `typesystem` only (module-structure §4.2), so the AES-256-GCM `CredentialEncryptor` in
 * `datasources` cannot be named here and the aggregation layer (`DomainConfiguration`)
 * binds this port to it — the same shape as [WorkspaceContentCheck]. The AAD is the key id,
 * so a sealed blob lifted onto another row fails the authentication tag instead of opening.
 *
 * An interface with two methods, not a `fun interface`: the port is a PAIR — an
 * implementation that can seal but not open is half a contract.
 */
interface SecretSealer {
    /** Seals [plaintext] bound to [aad] (the key id), for storage as `BYTEA`. */
    fun seal(
        plaintext: String,
        aad: String,
    ): ByteArray

    /**
     * Opens a value produced by [seal]. A wrong key, a tampered blob, or a mismatched [aad]
     * throws — the caller treats that as "this row cannot be opened", never as a wrong
     * plaintext.
     */
    fun open(
        sealed: ByteArray,
        aad: String,
    ): String
}
