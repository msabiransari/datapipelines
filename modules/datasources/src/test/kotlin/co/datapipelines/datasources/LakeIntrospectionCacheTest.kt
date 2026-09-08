package co.datapipelines.datasources

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import kotlin.time.Duration.Companion.seconds

/**
 * [LakeIntrospectionCache] — the 60 s serving cache for registry-derived LAKE introspection
 * (089 §C): TTL expiry on a fake ticker, per-datasource invalidation (the
 * `LakeTableRegistryService.refreshConnections` seam), and NONE's pass-through.
 */
class LakeIntrospectionCacheTest {
    private var now = 0L

    private val cache = LakeIntrospectionCache(ttl = 60.seconds, ticker = { now })

    @Test
    fun `a value is served until the TTL, then re-derived`() {
        var loads = 0

        fun load(): String {
            loads++
            return "v$loads"
        }

        assertAll(
            { cache.get("lake", "schemas", "") { load() } shouldBe "v1" },
            { cache.get("lake", "schemas", "") { load() } shouldBe "v1" },
            { loads shouldBe 1 },
            // 59 s in: still cached. 61 s in: expired and re-derived.
            {
                now += 59.seconds.inWholeNanoseconds
                cache.get("lake", "schemas", "") { load() } shouldBe "v1"
            },
            {
                now += 2.seconds.inWholeNanoseconds
                cache.get("lake", "schemas", "") { load() } shouldBe "v2"
            },
            { loads shouldBe 2 },
        )
    }

    @Test
    fun `invalidate drops one datasource's entries and no other's`() {
        cache.get("lake-a", "tables", "nyc") { "a1" }
        cache.get("lake-b", "tables", "nyc") { "b1" }

        cache.invalidate("lake-a")

        assertAll(
            { cache.get("lake-a", "tables", "nyc") { "a2" } shouldBe "a2" },
            // lake-b's entry was untouched — invalidation is per datasource.
            { cache.get("lake-b", "tables", "nyc") { "b2" } shouldBe "b1" },
        )
    }

    @Test
    fun `NONE never serves a cached value and never accumulates one`() {
        var loads = 0
        repeat(3) {
            LakeIntrospectionCache.NONE.get("lake", "columns", "nyc.t") { ++loads }
        }
        loads shouldBe 3
    }
}
