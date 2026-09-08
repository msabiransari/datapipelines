package co.datapipelines.datasources

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Phase C's cache for registry-derived LAKE introspection (089 §C): `schemas()`, `tables()` and
 * `columns()` on a LAKE datasource read the registry (and, for columns, run a zero-row scan over
 * the view — a Parquet footer read, which on S3 is a NETWORK round trip), so the LAKE branches
 * of [SchemaIntrospector] serve repeated reads from memory.
 *
 * Keyed by (datasource, operation, qualifier) and held for [ttl] — the SAME 60 s default as
 * [DatasourceMetadataCache] (§6.3), for the same reason: invalidation is local to one instance,
 * and the TTL is what bounds cross-instance staleness when a table is registered on a peer. The
 * writing instance's immediacy comes from `LakeTableRegistryService.refreshConnections`, which
 * invalidates this cache beside the pool eviction on every registry mutation — the two are
 * rebuilt from the same committed row on the next read.
 *
 * Entries expire lazily on read (no sweeper thread), exactly as [DatasourceMetadataCache]; the
 * map is bounded by the number of lake datasources × their introspected tables. [NONE] serves no
 * caller — it is the default for constructions that never introspect a LAKE datasource, and its
 * pass-through behaviour keeps the cache from being load-bearing in tests that do not care.
 */
class LakeIntrospectionCache(
    private val ttl: Duration = DEFAULT_TTL,
    private val ticker: () -> Long = System::nanoTime,
) {
    private data class Entry(
        val value: Any?,
        val expiresAt: Long,
    )

    private val entries = ConcurrentHashMap<Key, Entry>()

    /** The cache key: one datasource, one operation, one qualifier (a namespace, a table, or ""). */
    private data class Key(
        val datasource: String,
        val operation: String,
        val qualifier: String,
    )

    /**
     * The cached value for the triple, loading via [loader] on a miss or an expired entry.
     * Misses-as-null are cached here UNLIKE [DatasourceMetadataCache]: an empty columns listing
     * for an unregistered table costs the same zero-row scan to re-derive, and the registry
     * mutation that would make it non-empty invalidates the datasource's whole entry set.
     */
    @Suppress("UNCHECKED_CAST") // one cache, three value types — the caller's key decides which
    fun <T> get(
        datasource: String,
        operation: String,
        qualifier: String,
        loader: () -> T,
    ): T {
        val key = Key(datasource, operation, qualifier)
        val cached = entries[key]
        if (cached != null && ticker() < cached.expiresAt) return cached.value as T
        val loaded = loader()
        // NONE (zero TTL) never stores: a pass-through must not accumulate entries it will
        // never serve.
        if (ttl > Duration.ZERO) entries[key] = Entry(loaded, ticker() + ttl.inWholeNanoseconds)
        return loaded
    }

    /** Drops every entry of one datasource — the registry-mutation seam (see the class KDoc). */
    fun invalidate(datasource: String) {
        entries.keys.removeIf { it.datasource == datasource }
    }

    companion object {
        /** §6.3's 60 s, shared with [DatasourceMetadataCache.DEFAULT_TTL]. */
        val DEFAULT_TTL: Duration = 60.seconds

        /**
         * The pass-through default: every call loads. Used where no cache was wired (tests, and
         * modules that construct a [SchemaIntrospector] for non-LAKE datasources only) — the
         * LAKE branches stay correct, just uncached.
         */
        val NONE: LakeIntrospectionCache = LakeIntrospectionCache(ttl = Duration.ZERO)
    }
}
