package co.datapipelines.datasources

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The §6.3 in-memory metadata cache: datasource rows are low-churn and are read on every
 * pipeline validation and every `dialectOf` call, so the registry serves them from memory and
 * invalidates on create/update/delete.
 *
 * ## What is and is not cached
 *
 * - **Cached:** the per-name [Datasource] projection, always with `password = null`. Nothing that
 *   has ever held a plaintext credential enters this map — the pool-build path (§7.4) reloads
 *   from the repository and decrypts there, deliberately bypassing the cache.
 * - **Not cached:** `list()` results (a filtered, ordered projection whose invalidation would
 *   have to track every filter) and **misses**. Caching a miss would mean a datasource created
 *   through another path stayed invisible until eviction; a miss costs one indexed primary-key
 *   lookup, which is not worth that risk.
 *
 * ## Why entries expire (§6.3, v1.6 — DS-SEC-15)
 *
 * Invalidation is **local to one instance**. In the multi-instance deployment model (auth §8) an
 * operator repointing a datasource on instance A invalidates only A's map, so without expiry B and
 * C would serve the old `jdbc_url` — the old *host* — until they restarted. That is unbounded
 * staleness on a security-relevant field, so every entry also carries a [ttl] (default
 * [DEFAULT_TTL], matching the auth liveness cache): local invalidation stays the immediacy
 * optimization for the writing instance, and the TTL is what bounds cross-instance staleness.
 *
 * Expiry is **lazy** — an expired entry is re-read on the next [get] and replaced. There is no
 * sweeper thread: the map is bounded by the number of real datasources (misses are never cached),
 * so nothing accumulates that a sweeper would need to reclaim.
 *
 * Time comes from [ticker], a monotonic nanosecond source, so a wall-clock adjustment cannot make
 * an entry immortal or expire the whole map at once. Tests inject a fake ticker rather than sleep.
 *
 * Connection pools are cached separately and lazily ([pooling.ConnectionPoolManager], §5.2).
 *
 * Thread-safe by [ConcurrentHashMap]; `get`-then-`put` is deliberately not atomic, because two
 * threads loading the same unchanged row and both storing it is harmless.
 */
class DatasourceMetadataCache(
    private val ttl: Duration = DEFAULT_TTL,
    private val ticker: () -> Long = System::nanoTime,
) {
    /** A cached row and the [ticker] reading at which it stops being served. */
    private data class Entry(
        val datasource: Datasource,
        val expiresAt: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * The (workspaceId, name)-keyed §5.3 read (025 C4): `getVisible` is the control
     * plane's hot path (every REST GET, every save-time validation) and was the one
     * registry read with no cache. Same disciplines as [get]: misses never cached,
     * expiry bounds staleness, local invalidation on write.
     */
    private val visibleEntries = ConcurrentHashMap<Pair<UUID, String>, Entry>()

    /**
     * The cached datasource for [name], loading and storing it via [loader] on a miss **or on an
     * expired entry**. A load that returns null leaves nothing behind (§6.3: misses are never
     * cached), and also drops any expired entry that was still sitting in the map.
     */
    fun get(
        name: String,
        loader: (String) -> Datasource?,
    ): Datasource? = lookup(entries, name) { loader(name) }

    /**
     * The cached datasource for [name] VISIBLE to [workspaceId], loading via [loader] on a
     * miss or expiry. The key is the pair — two workspaces resolve one global datasource
     * through independent entries, so an invalidation or expiry in one cannot serve the
     * other a row it must not see or a row past its bound.
     */
    fun getVisible(
        workspaceId: UUID,
        name: String,
        loader: (String) -> Datasource?,
    ): Datasource? = lookup(visibleEntries, workspaceId to name) { loader(name) }

    /** The shared get-or-load over whichever [map] and [key] — one discipline, two keys. */
    private fun <K> lookup(
        map: ConcurrentHashMap<K, Entry>,
        key: K,
        loader: () -> Datasource?,
    ): Datasource? {
        val cached = map[key]
        if (cached != null && ticker() < cached.expiresAt) return cached.datasource
        val loaded = loader()
        if (loaded == null) {
            // The row is gone; the stale entry must not outlive it.
            if (cached != null) map.remove(key)
            return null
        }
        map[key] = Entry(loaded, ticker() + ttl.inWholeNanoseconds)
        return loaded
    }

    /**
     * Drops [name] — called on every create, update and delete (§6.3). Both maps: a write
     * invalidates the name for every workspace that had it cached, not just the writer's
     * own view of it.
     */
    fun invalidate(name: String) {
        entries.remove(name)
        visibleEntries.keys.removeIf { it.second == name }
    }

    /** Drops everything (key rotation, tests, and any bulk write the registry does not model). */
    fun invalidateAll() {
        entries.clear()
        visibleEntries.clear()
    }

    /**
     * Whether [name] is currently cached **and still live** — a test/observability aid, not a
     * lookup path. An expired entry reports false: it can no longer be served, which is what a
     * caller asking this question means by "cached".
     */
    fun isCached(name: String): Boolean = entries[name]?.let { ticker() < it.expiresAt } ?: false

    companion object {
        /**
         * §6.3: "a short TTL (default 60s, matching the auth liveness cache)". Long enough that a
         * hot pipeline validation loop still serves from memory, short enough that an operator
         * repointing a datasource sees it take effect fleet-wide within a minute.
         */
        val DEFAULT_TTL: Duration = 60.seconds
    }
}
