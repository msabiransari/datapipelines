package co.datapipelines.datasources

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [DatasourceMetadataCache] — the §6.3 in-memory metadata cache: a hit must not reach the loader,
 * a miss must not be remembered, invalidation must actually drop the entry, and an entry past its
 * TTL must be re-read.
 *
 * Time is injected ([clock]), never slept on: a test that slept for a real TTL would be a slow test
 * that still could not prove the boundary, because it could not step time to exactly the expiry.
 */
class DatasourceMetadataCacheTest {
    /** A fake monotonic ticker. Nanoseconds, like [System.nanoTime], so no unit conversion hides here. */
    private var nanos = 0L
    private val clock: () -> Long = { nanos }

    private fun advance(duration: Duration) {
        nanos += duration.inWholeNanoseconds
    }

    private val cache = DatasourceMetadataCache(ticker = clock)
    private val loads = AtomicInteger(0)

    private fun load(name: String): Datasource? {
        loads.incrementAndGet()
        return if (name == "known") Fixtures.h2(name = "known") else null
    }

    @Test
    fun `the first get loads and the second is served from memory`() {
        cache.get("known", ::load)?.name shouldBe "known"
        cache.get("known", ::load)?.name shouldBe "known"

        loads.get() shouldBe 1
        cache.isCached("known") shouldBe true
    }

    @Test
    fun `a miss is not cached - a datasource created later must become visible`() {
        cache.get("absent", ::load).shouldBeNull()
        cache.get("absent", ::load).shouldBeNull()

        loads.get() shouldBe 2
        cache.isCached("absent") shouldBe false
    }

    @Test
    fun `invalidate drops the entry so the next get reloads`() {
        cache.get("known", ::load)
        cache.invalidate("known")

        cache.isCached("known") shouldBe false
        cache.get("known", ::load)
        loads.get() shouldBe 2
    }

    @Test
    fun `invalidateAll clears everything`() {
        cache.get("known", ::load)
        cache.invalidateAll()

        cache.isCached("known") shouldBe false
    }

    @Test
    fun `DS-SEC-15 - an entry past its TTL is re-read, so a sibling instance cannot serve it forever`() {
        // Invalidation is local to the instance that made the write (§6.3). Without expiry, an
        // operator repointing a datasource on instance A would be invisible to B and C until
        // restart — unbounded staleness on the jdbc_url, i.e. on the HOST being connected to.
        cache.get("known", ::load)
        loads.get() shouldBe 1

        // Still inside the TTL: served from memory.
        advance(59.seconds)
        cache.get("known", ::load)
        loads.get() shouldBe 1

        // Past it: re-read, and the fresh value is cached again.
        advance(2.seconds)
        cache.get("known", ::load)?.name shouldBe "known"
        loads.get() shouldBe 2
        cache.isCached("known") shouldBe true
    }

    @Test
    fun `DS-SEC-15 - an expired entry is not reported as cached`() {
        cache.get("known", ::load)
        cache.isCached("known") shouldBe true

        advance(DatasourceMetadataCache.DEFAULT_TTL + 1.seconds)

        // It is still in the map, but it can no longer be served — which is what "cached" means to
        // any caller asking. Reporting true here would make the TTL invisible to observability.
        cache.isCached("known") shouldBe false
    }

    @Test
    fun `DS-SEC-15 - the TTL is configurable and defaults to the 60s the spec names`() {
        DatasourceMetadataCache.DEFAULT_TTL shouldBe 60.seconds

        val shortLived = DatasourceMetadataCache(ttl = 5.seconds, ticker = clock)
        shortLived.get("known", ::load)
        advance(6.seconds)

        shortLived.get("known", ::load)
        loads.get() shouldBe 2
    }

    @Test
    fun `DS-SEC-15 - expiry does not start caching misses`() {
        // The negative-caching guarantee the reviewer verified sound must survive the TTL change:
        // a miss stays uncached, so the map is still bounded by the number of REAL datasources and
        // cannot be grown by GETs for names that do not exist.
        cache.get("absent", ::load)
        advance(DatasourceMetadataCache.DEFAULT_TTL + 1.seconds)
        cache.get("absent", ::load)

        loads.get() shouldBe 2
        cache.isCached("absent") shouldBe false
    }

    @Test
    fun `DS-SEC-15 - a deleted row drops its still-live cache entry rather than being served on`() {
        // A load that comes back null after the entry expired means the row is gone. Leaving the
        // stale entry behind would let a DELETED datasource keep being served until someone called
        // invalidate — the exact cross-instance failure the TTL exists to bound.
        cache.get("known", ::load)
        advance(DatasourceMetadataCache.DEFAULT_TTL + 1.seconds)

        cache.get("known") { null }.shouldBeNull()
        cache.isCached("known") shouldBe false
    }

    @Test
    fun `DS-SEC-15 - invalidate-on-write is still immediate, not deferred to the TTL`() {
        // The TTL bounds OTHER instances; the writing instance must still see its own change at
        // once. A regression that replaced invalidation with expiry would pass every test above.
        cache.get("known", ::load)
        cache.invalidate("known")

        cache.isCached("known") shouldBe false
        cache.get("known", ::load)
        loads.get() shouldBe 2
    }
}
