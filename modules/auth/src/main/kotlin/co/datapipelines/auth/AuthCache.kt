package co.datapipelines.auth

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The short-TTL, read-through liveness cache (auth.md §11.4, D13, module-structure
 * §5.7). **In-process per instance, not Redis** — a cache of Postgres truth, not
 * shared state. Every authenticated request re-checks `users.is_active` and API-key
 * revocation through it, so deactivation/revocation takes effect within one TTL
 * (default 60s) rather than the full 8h JWT lifetime. Workspace resolution reads
 * (`workspace_members`, `workspaces`) go through the same maps-and-TTL discipline
 * (design §4: workspace revocation takes effect within the identical window).
 *
 * Local invalidation ([invalidateUser] / [invalidateKey]) is immediate on the
 * instance that performed the mutation; other instances converge at TTL expiry.
 *
 * The clock is injected ([nowNanos]) so tests exercise TTL expiry without sleeping.
 *
 * ## Memory safety (AUTH-SEC-4 / AUTH-SEC-14)
 * The maps are **bounded and self-evicting**, and misses are **never cached**:
 * - every access sweeps expired entries out of the map it touched, so a burst of
 *   distinct keys drains within one TTL instead of living until restart;
 * - a `null` load result is not stored, so an attacker enumerating key ids cannot
 *   convert lookups into permanent heap;
 * - at [MAX_ENTRIES] the cache stops admitting new entries rather than growing. It
 *   degrades to a direct database read — slower, never fatal. (The bound is a code
 *   constant, not a config key: [Configuration §3.4] defines the auth keys and does
 *   not carry one, and inventing an undocumented key would be spec drift.)
 *
 * ## Argon2 verification cache (AUTH-SEC-3)
 * [verifiedSecret] caches the *outcome* of a successful Argon2id verification, keyed
 * by key id + SHA-256 of the presented secret, so a busy agent pays the Argon2 cost
 * once per key per TTL instead of once per request. Only successes are cached — a
 * wrong secret is re-verified every time and can never be promoted to valid. This is
 * strictly a hash-cost cache: record staleness, revocation and owner liveness are
 * still re-read per request (D13), which is what keeps `RevocationTtlTest`'s
 * semantics intact.
 */
class AuthCache(
    authProperties: AuthProperties,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val ttlNanos: Long = authProperties.apiKeys.cacheTtlSeconds * 1_000_000_000L

    private val users = ConcurrentHashMap<UUID, Entry<User>>()
    private val keyRecords = ConcurrentHashMap<String, Entry<ApiKey>>()

    // Workspace resolution (design §4/§5): the same 60s liveness discipline as
    // `users.is_active` — membership revocation takes effect within one TTL, immediately
    // on the instance that performed the mutation. Keyed by user / workspace name so one
    // entry serves every check a request makes.
    private val memberships = ConcurrentHashMap<UUID, Entry<List<WorkspaceMembership>>>()
    private val workspacesByName = ConcurrentHashMap<String, Entry<Workspace>>()

    /** key id → SHA-256 digest of the secret whose Argon2id verification succeeded. */
    private val verifiedSecrets = ConcurrentHashMap<String, Entry<ByteArray>>()

    private class Entry<T>(
        val value: T,
        val expiresAtNanos: Long,
    )

    /**
     * The cached `users` snapshot for [userId] (the hot per-request read,
     * metadata-db §4.1), loading through [loader] on miss/expiry. Both the JWT
     * liveness gate ([isUserActive]) and the API-key principal read through this,
     * so a valid request costs zero user queries within the TTL.
     */
    fun user(
        userId: UUID,
        loader: (UUID) -> User?,
    ): User? = readThrough(users, userId, loader)

    /** Convenience over [user]: `users.is_active` (absent/gone user → not active). */
    fun isUserActive(
        userId: UUID,
        loader: (UUID) -> User?,
    ): Boolean = user(userId, loader)?.isActive == true

    /**
     * The current `api_keys` record for [keyId] (including `is_revoked`/`expires_at`),
     * loading through [loader] on miss/expiry. Caching the record avoids a DB round
     * trip per request while D13's revocation re-check stays correct within the TTL;
     * a locally revoked key is evicted immediately via [invalidateKey].
     */
    fun keyRecord(
        keyId: String,
        loader: (String) -> ApiKey?,
    ): ApiKey? = readThrough(keyRecords, keyId, loader)

    /**
     * True when [secret] is the verified secret for [keyId] — from cache within the
     * TTL, otherwise by running [verifier] (the Argon2id verification). A successful
     * outcome is cached; a failure never is, so a wrong secret is neither cheap to
     * retry nor able to poison the cache.
     */
    fun verifiedSecret(
        keyId: String,
        secret: String,
        verifier: () -> Boolean,
    ): Boolean {
        val digest = sha256(secret)
        val cached = verifiedSecrets[keyId]
        if (cached != null) {
            if (cached.expiresAtNanos <= nowNanos()) {
                verifiedSecrets.remove(keyId)
            } else if (MessageDigest.isEqual(cached.value, digest)) {
                return true
            }
        }
        val verified = verifier()
        if (verified) admit(verifiedSecrets, keyId, digest)
        return verified
    }

    /**
     * [userId]'s workspace memberships (workspace id + name + role), loading through
     * [loader] on miss/expiry. Cached per user so both per-request questions — "is this
     * principal a member of X?" (the `DP-Workspace` switch) and "what is their first
     * membership?" (login stamping) — cost zero queries within the TTL.
     */
    fun memberships(
        userId: UUID,
        loader: (UUID) -> List<WorkspaceMembership>,
    ): List<WorkspaceMembership> = readThrough(memberships, userId, loader) ?: emptyList()

    /** The workspace row for [name] (the `DP-Workspace` header / JWT claim value), cached per TTL. */
    fun workspaceByName(
        name: String,
        loader: (String) -> Workspace?,
    ): Workspace? = readThrough(workspacesByName, name, loader)

    /** Immediate local eviction after deactivating/updating a user (auth.md §11.4). */
    fun invalidateUser(userId: UUID) {
        users.remove(userId)
    }

    /**
     * Immediate local eviction of [userId]'s membership snapshot after a membership
     * mutation (provisioning today; member management in 021) — revocation takes effect
     * on this instance's very next request rather than at TTL expiry (the D13 contract).
     */
    fun invalidateMemberships(userId: UUID) {
        memberships.remove(userId)
    }

    /** Immediate local eviction after a workspace row mutation (rename does not exist in v1; delete lands with 021). */
    fun invalidateWorkspace(name: String) {
        workspacesByName.remove(name)
    }

    /**
     * Immediate local eviction after revoking a key (auth.md §11.4) — the record
     * *and* its cached verification outcome, so a revoked key cannot keep passing on
     * a warm Argon2 result.
     */
    fun invalidateKey(keyId: String) {
        keyRecords.remove(keyId)
        verifiedSecrets.remove(keyId)
    }

    /** Live entry count across all maps — the bound this cache promises, observable in tests. */
    fun size(): Int = users.size + keyRecords.size + verifiedSecrets.size + memberships.size + workspacesByName.size

    private fun <K : Any, V : Any> readThrough(
        map: ConcurrentHashMap<K, Entry<V>>,
        k: K,
        loader: (K) -> V?,
    ): V? {
        val cached = map[k]
        if (cached != null) {
            if (cached.expiresAtNanos > nowNanos()) return cached.value
            map.remove(k, cached)
        }
        val fresh = loader(k)
        // Negative results are deliberately NOT cached (AUTH-SEC-4): an unknown id is
        // the attacker-controlled case, and caching it is how the map grows unbounded.
        if (fresh != null) admit(map, k, fresh)
        return fresh
    }

    private fun <K : Any, V : Any> admit(
        map: ConcurrentHashMap<K, Entry<V>>,
        k: K,
        value: V,
    ) {
        if (map.size >= MAX_ENTRIES) {
            sweepExpired(map)
            if (map.size >= MAX_ENTRIES) return
        }
        map[k] = Entry(value, nowNanos() + ttlNanos)
    }

    private fun <K : Any, V : Any> sweepExpired(map: ConcurrentHashMap<K, Entry<V>>) {
        val cutoff = nowNanos()
        map.entries.removeIf { it.value.expiresAtNanos <= cutoff }
    }

    private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private companion object {
        /**
         * Per-map ceiling. Sized far above any real deployment's live principal count
         * (this is a self-hosted, internal-users-only product) yet small enough that a
         * hostile flood cannot exhaust the heap.
         */
        const val MAX_ENTRIES = 10_000
    }
}
