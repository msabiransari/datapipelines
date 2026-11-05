package co.datapipelines.datasources.pooling

import co.datapipelines.datasources.Fixtures
import co.datapipelines.datasources.ManagedLakeTable
import co.datapipelines.datasources.capturingLogs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * #818 (dp-lake P5, design record §10 RULING 4 item 1): the age bound inside [ConnectionPoolManager.poolFor].
 * A managed lake generation older than `reads.max-generation-age-seconds` is retired BEFORE a lease is taken
 * from it, with no tick and no message, and a new generation is built. Every other pool is untouched.
 *
 * #818c (RULING 4 amended 2026-11-05, item 3): the reaper's pass ([ConnectionPoolManager.reapRetiring]) retires an
 * over-age managed generation with NO lease after the build, no tick and no invalidation, through the lease path's
 * own judgement, so the ceiling's close follows on a later pass. The lease-time cases above stay unchanged.
 */
class ConnectionPoolManagerGenerationAgeTest {
    private class AgePool(
        override val name: String,
        override val managedGeneration: ManagedLakeGeneration?,
        val serial: Int,
        var active: Int = 0,
    ) : ConnectionPool {
        var softEvicted = false
            private set
        var closed = false
            private set

        override fun leaseConnection(): Connection = error("not used in this test")

        override fun softEvict() {
            softEvicted = true
        }

        override val activeConnections: Int get() = active

        override fun close() {
            closed = true
        }
    }

    private class Retirements : PoolLifecycleMetrics {
        val retired = mutableListOf<String>()

        override fun poolRetired(datasourceName: String) {
            retired += datasourceName
        }

        override fun poolHardClosed(
            datasourceName: String,
            activeConnections: Int,
        ) = Unit
    }

    private val start: Instant = Instant.parse("2026-11-04T08:00:00Z")
    private var now: Instant = start
    private val builds = AtomicInteger()
    private val metrics = Retirements()

    private fun manager(managed: Boolean) =
        ConnectionPoolManager(
            poolFactory = { ds ->
                val generation = if (managed) ManagedLakeGeneration(ds.name, listOf(table("s3://b/m1.json"))) else null
                AgePool(ds.name, generation, serial = builds.incrementAndGet())
            },
            retireCeiling = Duration.ofSeconds(CEILING_SECONDS),
            clock = { now },
            maxGenerationAge = Duration.ofSeconds(MAX_AGE_SECONDS),
            metrics = metrics,
        )

    @Test
    fun `a managed generation is served up to its bound and replaced one second past it`() {
        val manager = manager(managed = true)
        val first = manager.poolFor(LAKE) as AgePool
        manager.generationBuiltAt(LAKE.name) shouldBe start

        now = start.plusSeconds(MAX_AGE_SECONDS - 1)
        manager.poolFor(LAKE) shouldBeSameInstanceAs first
        now = start.plusSeconds(MAX_AGE_SECONDS)
        manager.poolFor(LAKE) shouldBeSameInstanceAs first
        manager.retiringCount() shouldBe 0

        now = start.plusSeconds(MAX_AGE_SECONDS + 1)
        val second = manager.poolFor(LAKE) as AgePool

        second shouldNotBeSameInstanceAs first
        second.serial shouldBe 2
        manager.generationBuiltAt(LAKE.name) shouldBe now
        // The old generation retired the 094 way: out of the map, soft-evicted, queued, not closed.
        first.softEvicted shouldBe true
        first.closed shouldBe false
        manager.retiringCount() shouldBe 1
        metrics.retired shouldBe listOf(LAKE.name)
    }

    @Test
    fun `a non-managed pool is never aged out`() {
        val manager = manager(managed = false)
        val first = manager.poolFor(LAKE)

        now = start.plusSeconds(MAX_AGE_SECONDS * 100)

        manager.poolFor(LAKE) shouldBeSameInstanceAs first
        builds.get() shouldBe 1
        manager.retiringCount() shouldBe 0
    }

    @Test
    fun `no configured bound ages nothing out, managed or not`() {
        val manager =
            ConnectionPoolManager(
                poolFactory = { ds -> AgePool(ds.name, ManagedLakeGeneration(ds.name, emptyList()), builds.incrementAndGet()) },
                clock = { now },
            )
        val first = manager.poolFor(LAKE)
        now = start.plusSeconds(MAX_AGE_SECONDS * 100)
        manager.poolFor(LAKE) shouldBeSameInstanceAs first
    }

    @Test
    fun `an aged-out generation still running a statement is hard-closed at the ceiling - proof (c)'s step`() {
        // A8's manager-level twin: the generation the bound retired keeps its running statement
        // until the ceiling, then the reaper closes it regardless. The integration twin asserts it
        // on a real managed DuckDB generation.
        val manager = manager(managed = true)
        val first = manager.poolFor(LAKE) as AgePool
        first.active = 1
        now = start.plusSeconds(MAX_AGE_SECONDS + 1)
        manager.poolFor(LAKE)

        now = now.plusSeconds(CEILING_SECONDS - 1)
        manager.reapRetiring() shouldBe ReapOutcome.NOTHING
        first.closed shouldBe false

        now = now.plusSeconds(1)
        manager.reapRetiring() shouldBe ReapOutcome(drained = 0, hardClosed = 1)
        first.closed shouldBe true
    }

    @Test
    fun `the reaper's pass retires an over-age managed generation with no lease, and a later pass hard-closes it`() {
        val manager = manager(managed = true)
        val first = manager.poolFor(LAKE) as AgePool
        first.active = 1

        now = start.plusSeconds(MAX_AGE_SECONDS + 1)
        val logs = capturingLogs { manager.reapRetiring() shouldBe ReapOutcome(drained = 0, hardClosed = 0, expired = 1) }

        // Retired by the pass, not by a lease: nothing called poolFor after the build.
        builds.get() shouldBe 1
        manager.hasPool(LAKE.name) shouldBe false
        manager.generationBuiltAt(LAKE.name) shouldBe null
        manager.retiringCount() shouldBe 1
        first.softEvicted shouldBe true
        first.closed shouldBe false
        metrics.retired shouldBe listOf(LAKE.name)
        val expired = logs.filter { it.startsWith("event=lake.generation_expired") }
        expired shouldHaveSize 1
        expired.single() shouldContain "trigger=reaper"

        now = now.plusSeconds(CEILING_SECONDS - 1)
        manager.reapRetiring() shouldBe ReapOutcome.NOTHING
        first.closed shouldBe false
        now = now.plusSeconds(1)
        manager.reapRetiring() shouldBe ReapOutcome(drained = 0, hardClosed = 1)
        first.closed shouldBe true
    }

    @Test
    fun `a managed generation exactly at its bound is left to serve by the reaper's pass`() {
        val manager = manager(managed = true)
        val first = manager.poolFor(LAKE) as AgePool

        now = start.plusSeconds(MAX_AGE_SECONDS)
        manager.reapRetiring() shouldBe ReapOutcome.NOTHING
        manager.retireExpiredGenerations() shouldBe 0

        manager.generationBuiltAt(LAKE.name) shouldBe start
        manager.retiringCount() shouldBe 0
        first.softEvicted shouldBe false
        // One millisecond past it, the same pass retires it: the boundary is the lease path's (served while age <= bound).
        now = start.plusSeconds(MAX_AGE_SECONDS).plusMillis(1)
        manager.retireExpiredGenerations() shouldBe 1
        manager.generationBuiltAt(LAKE.name) shouldBe null
    }

    @Test
    fun `an over-age generation that has drained is retired and closed in one pass`() {
        val manager = manager(managed = true)
        val first = manager.poolFor(LAKE) as AgePool

        now = start.plusSeconds(MAX_AGE_SECONDS + 1)
        manager.reapRetiring() shouldBe ReapOutcome(drained = 1, hardClosed = 0, expired = 1)

        first.closed shouldBe true
        manager.retiringCount() shouldBe 0
    }

    @Test
    fun `the reaper's pass never ages out a non-managed pool, nor anything without a bound`() {
        val plain = manager(managed = false)
        val first = plain.poolFor(LAKE)
        now = start.plusSeconds(MAX_AGE_SECONDS * 100)

        val logs = capturingLogs { plain.reapRetiring() shouldBe ReapOutcome.NOTHING }

        plain.generationBuiltAt(LAKE.name) shouldBe start
        plain.poolFor(LAKE) shouldBeSameInstanceAs first
        plain.retiringCount() shouldBe 0
        logs.filter { it.startsWith("event=lake.generation_expired") }.shouldBeEmpty()

        val unbounded =
            ConnectionPoolManager(
                poolFactory = { ds -> AgePool(ds.name, ManagedLakeGeneration(ds.name, emptyList()), builds.incrementAndGet()) },
                clock = { now },
            )
        val managed = unbounded.poolFor(LAKE)
        now = now.plusSeconds(MAX_AGE_SECONDS * 100)
        unbounded.retireExpiredGenerations() shouldBe 0
        unbounded.poolFor(LAKE) shouldBeSameInstanceAs managed
    }

    @Test
    fun `the reaper's pass leaves a generation a lease already replaced, and logs the lease as the trigger`() {
        val manager = manager(managed = true)
        val first = manager.poolFor(LAKE) as AgePool
        first.active = 1

        now = start.plusSeconds(MAX_AGE_SECONDS + 1)
        val logs = capturingLogs { manager.poolFor(LAKE) }
        val second = manager.poolFor(LAKE)
        logs.single { it.startsWith("event=lake.generation_expired") } shouldContain "trigger=lease"

        // The fresh generation is young: the pass retires nothing more, and the one already queued stays queued once.
        manager.reapRetiring() shouldBe ReapOutcome.NOTHING
        manager.poolFor(LAKE) shouldBeSameInstanceAs second
        manager.retiringCount() shouldBe 1
        metrics.retired shouldBe listOf(LAKE.name)
    }

    @Test
    fun `retireGeneration retires only the generation the caller compared`() {
        val manager = manager(managed = true)
        val first = manager.poolFor(LAKE)
        manager.liveManagedGenerations() shouldBe mapOf(LAKE.name to first)

        // A peer path already replaced it: the stale judgement of `first` must not retire `second`.
        manager.retire(LAKE.name) shouldBe true
        val second = manager.poolFor(LAKE)
        manager.retireGeneration(LAKE.name, first) shouldBe false
        manager.poolFor(LAKE) shouldBeSameInstanceAs second

        manager.retireGeneration(LAKE.name, second) shouldBe true
        manager.hasPool(LAKE.name) shouldBe false
    }

    @Test
    fun `liveManagedGenerations lists managed generations only`() {
        val managed = manager(managed = true)
        managed.poolFor(LAKE)
        managed.liveManagedGenerations().keys shouldBe setOf(LAKE.name)

        val plain = manager(managed = false)
        plain.poolFor(LAKE)
        plain.liveManagedGenerations() shouldBe emptyMap()
    }

    @Test
    fun `staleTableCount counts a moved location, an added table and a removed one`() {
        val built = ManagedLakeGeneration("lake", listOf(table("s3://b/m1.json"), table("s3://b/x1.json", name = "x")))

        built.staleTableCount(listOf(table("s3://b/m1.json"), table("s3://b/x1.json", name = "x"))) shouldBe 0
        built.staleTableCount(listOf(table("s3://b/m2.json"), table("s3://b/x1.json", name = "x"))) shouldBe 1
        built.staleTableCount(listOf(table("s3://b/m1.json"))) shouldBe 1
        built.staleTableCount(
            listOf(table("s3://b/m1.json"), table("s3://b/x1.json", name = "x"), table("s3://b/y.json", name = "y")),
        ) shouldBe 1
        // An availability change is a change; a recorded view error is not (the generation wrote it).
        built.staleTableCount(listOf(table("s3://b/m1.json", status = "archived"), table("s3://b/x1.json", name = "x"))) shouldBe 1
        built.staleTableCount(
            listOf(table("s3://b/m1.json").copy(lastError = "boom"), table("s3://b/x1.json", name = "x")),
        ) shouldBe 0
    }

    private fun table(
        location: String,
        name: String = "orders",
        status: String = "active",
    ) = ManagedLakeTable(
        namespace = listOf("lake", "sales"),
        name = name,
        format = "iceberg",
        metadataLocation = location,
        status = status,
    )

    private companion object {
        const val MAX_AGE_SECONDS = 900L
        const val CEILING_SECONDS = 90L
        val LAKE = Fixtures.h2(name = "lake")
    }
}
