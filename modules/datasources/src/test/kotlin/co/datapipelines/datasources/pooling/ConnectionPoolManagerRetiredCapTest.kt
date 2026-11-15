package co.datapipelines.datasources.pooling

import co.datapipelines.datasources.Fixtures
import co.datapipelines.datasources.ManagedLakeErrorCodes
import co.datapipelines.logcapture.capturingLogs
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** A case that hangs (a plant that loops, a latch never released) is RED here rather than a stuck build. */
private const val CLASS_TIMEOUT_SECONDS = 120L

/**
 * #819 (owner ruling 2026-11-15, design record §6 amendment; brief r2 D1–D8) — the retired-generation cap in
 * [ConnectionPoolManager]: K = `reads.max-retired-generations`, R = the retired managed generations of a name whose
 * pool still holds a physical handle. Invariant D6: live-or-building + R ≤ 1 + K at every instant.
 *
 * Every pool here is a [CapPool]: it counts as an engine from its factory call until its close has RETURNED and
 * released it ([alive], with its high-water mark [peak] raised at the only moment the count can rise). A "stuck"
 * pool's close blocks on a latch the test may never release — the duckdb-httpfs#519 property round 1's inventory
 * (U1) stacked 64 engines with. Closes run on their own threads ([asyncCloser]) as the production closer runs them.
 * Synchronisation is latches and joins; nothing sleeps. A case that hangs is RED at the class timeout.
 */
@Timeout(CLASS_TIMEOUT_SECONDS)
class ConnectionPoolManagerRetiredCapTest {
    private inner class CapPool(
        override val name: String,
        managed: Boolean,
        val serial: Int,
    ) : ConnectionPool {
        override val managedGeneration: ManagedLakeGeneration? = if (managed) ManagedLakeGeneration(name, emptyList()) else null
        override val generation: String = UUID.randomUUID().toString()
        override val connectionTimeout: Duration get() = closeWaitBound

        @Volatile var active = 0

        /** Set: this pool's close blocks until [releaseClose] (a reader that never returns holds the driver). */
        @Volatile var stuck = false
        private val gate = CountDownLatch(1)
        private val released = AtomicBoolean(false)
        val closeStarted = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)

        @Volatile var softEvicted = false

        override val activeConnections: Int get() = active
        override val holdsPhysicalHandles: Boolean get() = !released.get()

        override fun leaseConnection(): Connection = error("not used in this test")

        override fun softEvict() {
            softEvicted = true
        }

        fun releaseClose() = gate.countDown()

        override fun close() {
            closeStarted.countDown()
            if (stuck) gate.await()
            if (released.compareAndSet(false, true)) alive.decrementAndGet()
            closeReturned.countDown()
        }
    }

    private val start: Instant = Instant.parse("2026-11-15T08:00:00Z")

    @Volatile private var now: Instant = start
    private val alive = AtomicInteger()
    private val peak = AtomicInteger()
    private val builds = AtomicInteger()
    private val built = CopyOnWriteArrayList<CapPool>()
    private val closeThreads = CopyOnWriteArrayList<Thread>()

    /** What a [CapPool] answers as its `connectionTimeout`, so the bound of D7's wait for an idle close. */
    @Volatile private var closeWaitBound: Duration = Duration.ofSeconds(BOUND_SECONDS)

    /** Set: each build parks on [buildGate] after it counted itself (the race case). */
    @Volatile private var buildGate: CountDownLatch? = null
    private val ticksCapped = AtomicInteger()

    private val asyncCloser = PoolCloser { pool -> closeThreads += thread(isDaemon = true, name = "test-close") { pool.close() } }

    private val metrics =
        object : PoolLifecycleMetrics {
            override fun poolRetired(datasourceName: String) = Unit

            override fun poolHardClosed(
                datasourceName: String,
                activeConnections: Int,
            ) = Unit

            override fun freshnessTickCapped(datasourceName: String) {
                ticksCapped.incrementAndGet()
            }
        }

    private fun manager(
        cap: Int? = 1,
        managed: Boolean = true,
        pins: ManagedGenerationPins = ManagedGenerationPins.NONE,
        closer: PoolCloser = asyncCloser,
    ) = ConnectionPoolManager(
        poolFactory = { ds ->
            val count = alive.incrementAndGet()
            peak.accumulateAndGet(count, ::maxOf)
            buildGate?.await(GATE_SECONDS, TimeUnit.SECONDS)
            CapPool(ds.name, managed, builds.incrementAndGet()).also { built += it }
        },
        retireCeiling = Duration.ofSeconds(CEILING_SECONDS),
        managedRetireCeiling = Duration.ofSeconds(CEILING_SECONDS),
        clock = { now },
        maxGenerationAge = Duration.ofSeconds(MAX_AGE_SECONDS),
        maxRetiredGenerations = cap,
        closer = closer,
        pins = pins,
        metrics = metrics,
    )

    @AfterEach
    fun releaseEverything() {
        built.forEach { it.releaseClose() }
        closeThreads.forEach { it.join(TimeUnit.SECONDS.toMillis(GATE_SECONDS)) }
    }

    private fun lease(manager: ConnectionPoolManager): CapPool? =
        try {
            manager.poolFor(LAKE) as CapPool
        } catch (e: DatapipelinesException) {
            e.code shouldBe ManagedLakeErrorCodes.UNAVAILABLE
            null
        }

    /** Every step's sample: the engines alive now, and the high-water mark the factory raised. */
    private fun sample(cap: Int) {
        withClue("engines alive=${alive.get()} peak=${peak.get()} must stay within 1 + K = ${1 + cap}") {
            alive.get() shouldBeLessThanOrEqual 1 + cap
            peak.get() shouldBeLessThanOrEqual 1 + cap
        }
    }

    /** Joins every close handed off so far (only for a case where no close is stuck). */
    private fun joinCloses() {
        closeThreads.forEach { it.join(TimeUnit.SECONDS.toMillis(GATE_SECONDS)) }
        closeThreads.forEach { withClue("a close thread ended") { it.isAlive shouldBe false } }
    }

    /**
     * Round 1's U1 interleaving, every round: a lease, a reader on its generation that never returns, the age bound
     * (the reaper retires it regardless), the ceiling (the reaper hands it to the closer, whose close never returns).
     */
    private fun stuckRounds(
        rounds: Int,
        cap: Int,
    ) {
        val manager = manager(cap = cap)
        var refused = 0
        repeat(rounds) {
            val pool = lease(manager)
            sample(cap)
            if (pool == null) {
                refused++
            } else {
                pool.active = 1
                pool.stuck = true
            }
            now = now.plusSeconds(MAX_AGE_SECONDS + 1)
            manager.reapRetiring()
            sample(cap)
            now = now.plusSeconds(CEILING_SECONDS + 1)
            manager.reapRetiring()
            built.filter { it.stuck }.forEach { it.closeStarted.await(GATE_SECONDS, TimeUnit.SECONDS) shouldBe true }
            sample(cap)
        }
        assertSoftly {
            withClue("builds: one live plus K retired stuck generations") { builds.get() shouldBe 1 + cap }
            withClue("every lease above the cap is refused lake.unavailable") { refused shouldBe rounds - (1 + cap) }
            manager.retiredGenerationsHolding(LAKE.name) shouldBe 1 + cap
        }
        // One stuck close returns: R drops to K, and the next lease builds.
        val first = built.first()
        first.releaseClose()
        first.closeReturned.await(GATE_SECONDS, TimeUnit.SECONDS) shouldBe true
        val next = lease(manager)
        withClue("after one stuck close returned, the next lease builds") { next?.serial shouldBe 2 + cap }
        sample(cap)
    }

    @Test
    fun `U1 interleaving, 8 rounds - engines alive never exceed 1 + K, leases above the cap refused`() {
        stuckRounds(8, cap = 1)
    }

    @Test
    fun `U1 interleaving, 64 rounds - engines alive never exceed 1 + K, leases above the cap refused`() {
        stuckRounds(64, cap = 1)
    }

    @Test
    fun `K = 2 - the bound is 3`() {
        stuckRounds(16, cap = 2)
    }

    @Test
    fun `the tick at R at least K does not retire, logs its WARN, the live generation serves, and the age bound retires it`() {
        val manager = manager(cap = 1)
        val retired = holdOneRetired(manager)
        val live = lease(manager)!!
        manager.retiredGenerationsHolding(LAKE.name) shouldBe 1

        lateinit var outcome: StaleRetirement
        val logs = capturingLogs { outcome = manager.retireStaleGeneration(LAKE.name, live, staleTables = 3) }

        outcome shouldBe StaleRetirement.Capped(retiredHolding = 1, maxRetiredGenerations = 1)
        lease(manager) shouldBeSameInstanceAs live
        live.softEvicted shouldBe false
        ticksCapped.get() shouldBe 1
        val line = logs.single { it.contains("event=lake.freshness_tick_capped") }
        line shouldContain "datasource=${LAKE.name}"
        line shouldContain "tables_stale=3"
        line shouldContain "retired_generations=1"
        line shouldContain "max_retired_generations=1"
        line shouldContain "key=${ConnectionPoolManager.MAX_RETIRED_GENERATIONS_KEY}"
        line shouldContain "the live generation serves until its age bound"

        // Its age bound retires it regardless (RULING 4 item 1); it is idle, so D7 closes it and the lease builds.
        now = now.plusSeconds(MAX_AGE_SECONDS + 1)
        val next = lease(manager)
        live.softEvicted shouldBe true
        next shouldNotBeSameInstanceAs live
        next?.serial shouldBe 3
        retired.holdsPhysicalHandles shouldBe true
        sample(1)
    }

    @Test
    fun `the tick below the cap retires as before`() {
        val manager = manager(cap = 2)
        holdOneRetired(manager)
        val live = lease(manager)!!
        manager.retireStaleGeneration(LAKE.name, live, staleTables = 1) shouldBe StaleRetirement.Retired
        live.softEvicted shouldBe true
        manager.retireStaleGeneration(LAKE.name, live, staleTables = 1) shouldBe StaleRetirement.NotLive
        ticksCapped.get() shouldBe 0
    }

    @Test
    fun `the age bound at R at least K retires regardless, on the lease path and on the reaper`() {
        val manager = manager(cap = 1)
        holdOneRetired(manager)
        val busy = lease(manager)!!
        busy.active = 1
        now = now.plusSeconds(MAX_AGE_SECONDS + 1)

        // The lease path: the over-age generation is retired although R = K, and with a reader still on it the
        // build above the cap is refused.
        lease(manager) shouldBe null
        busy.softEvicted shouldBe true
        manager.hasPool(LAKE.name) shouldBe false
        manager.retiredGenerationsHolding(LAKE.name) shouldBe 2
        sample(1)

        // The reaper's half: a second manager, its over-age generation retired with no borrower at R = K.
        val reaped = manager(cap = 1)
        val base = built.size
        holdOneRetired(reaped)
        val idleBusy = lease(reaped)!!
        idleBusy.active = 1
        now = now.plusSeconds(MAX_AGE_SECONDS + 1)
        reaped.retireExpiredGenerations() shouldBe 1
        idleBusy.softEvicted shouldBe true
        reaped.hasPool(LAKE.name) shouldBe false
        built.size shouldBe base + 2
    }

    @Test
    fun `a lost pin and an invalidation at R at least K retire - the next build is refused while R is above K`() {
        val retireCallbacks = CopyOnWriteArrayList<() -> Unit>()
        val pins =
            object : ManagedGenerationPins {
                override fun pin(
                    generation: UUID,
                    datasource: String,
                    locations: Map<String, String>,
                    builtAt: Instant,
                    holdsHandles: () -> Boolean,
                    retire: () -> Unit,
                ): Boolean = retireCallbacks.add(retire)

                override fun unpin(generation: UUID) = Unit

                override fun valid(generation: UUID): Boolean = true
            }
        val manager = manager(cap = 1, pins = pins)
        manager.poolFor(LAKE)
        built.last().active = 1
        manager.retire(LAKE.name) shouldBe true // R = 1 = K
        manager.poolFor(LAKE)
        built.last().active = 1

        // The lost pin: the keeper's callback (built in buildProtected) retires the live generation at R = K.
        val lostPin: () -> Unit = retireCallbacks[retireCallbacks.size - 1]
        lostPin()
        manager.hasPool(LAKE.name) shouldBe false
        manager.retiredGenerationsHolding(LAKE.name) shouldBe 2
        shouldThrow<DatapipelinesException> { manager.poolFor(LAKE) }.apply {
            code shouldBe ManagedLakeErrorCodes.UNAVAILABLE
            details["cause"].toString() shouldContain "${ConnectionPoolManager.MAX_RETIRED_GENERATIONS_KEY}=1"
            details["cause"].toString() shouldContain "still holding their engine: 2"
        }
        sample(1)

        // The invalidation (save, delete, peer message, re-sync: all `retire(name)`), once one close returned:
        // at the ceiling both are handed off; the first one's close returns, the second one's never does.
        built[1].stuck = true
        now = now.plusSeconds(CEILING_SECONDS + 1)
        manager.reapRetiring()
        built.first().closeReturned.await(GATE_SECONDS, TimeUnit.SECONDS) shouldBe true
        manager.poolFor(LAKE)
        built.last().active = 1
        manager.retiredGenerationsHolding(LAKE.name) shouldBe 1
        manager.retire(LAKE.name) shouldBe true
        manager.retiredGenerationsHolding(LAKE.name) shouldBe 2
        shouldThrow<DatapipelinesException> { manager.poolFor(LAKE) }.code shouldBe ManagedLakeErrorCodes.UNAVAILABLE
        sample(1)
    }

    @Test
    fun `healthy operation - many table changes over idle generations and K long queries on retired ones refuse nothing`() {
        val cap = 1
        val manager = manager(cap = cap)
        var refused = 0
        var capped = 0

        fun tableChange(reap: Boolean) {
            val live =
                lease(manager) ?: run {
                    refused++
                    null
                }
            if (live != null && manager.retireStaleGeneration(LAKE.name, live, staleTables = 1) is StaleRetirement.Capped) capped++
            sample(cap)
            if (reap) {
                manager.reapRetiring()
                joinCloses()
            }
        }
        // Many table changes, every generation idle: tick, lease, reaper pass, as production interleaves them.
        repeat(TABLE_CHANGES) { tableChange(reap = true) }
        withClue("with idle generations the tick never waits") { capped shouldBe 0 }

        // K long queries on retired generations. Inside their ceiling: table changes (the tick waits), then the live
        // generation ages out idle and the lease that retires it builds the next one.
        val long = lease(manager)!!
        long.active = 1
        manager.retireStaleGeneration(LAKE.name, long, staleTables = 1) shouldBe StaleRetirement.Retired
        repeat(TABLE_CHANGES) { tableChange(reap = false) }
        withClue("at R = K the tick waited on every change") { capped shouldBe TABLE_CHANGES }
        now = now.plusSeconds(MAX_AGE_SECONDS + 1)
        lease(manager) ?: refused++
        sample(cap)
        withClue("the long query's generation is retained, never closed under its reader") { long.holdsPhysicalHandles shouldBe true }

        // The long query ends: the reaper closes its generation and the tick retires again.
        long.active = 0
        manager.reapRetiring()
        joinCloses()
        long.holdsPhysicalHandles shouldBe false
        capped = 0
        repeat(TABLE_CHANGES) { tableChange(reap = true) }
        capped shouldBe 0
        withClue("healthy operation is never refused") { refused shouldBe 0 }
    }

    @Test
    fun `an idle generation whose close is in flight is waited for, not refused - and the wait is bounded`() {
        val manager = manager(cap = 1)
        holdOneRetired(manager)
        val idle = lease(manager)!!
        idle.stuck = true // its close is in flight until the test releases it
        manager.retire(LAKE.name) shouldBe true
        manager.reapRetiring() // drained: handed to the closer now
        idle.closeStarted.await(GATE_SECONDS, TimeUnit.SECONDS) shouldBe true

        var leased: CapPool? = null
        val leaser = thread(name = "test-lease") { leased = lease(manager) }
        awaitWaiting(leaser)
        idle.releaseClose()
        leaser.join(TimeUnit.SECONDS.toMillis(GATE_SECONDS))
        withClue("the lease waited for the idle close and built") { leased?.serial shouldBe 3 }
        sample(1)

        // The same state with a close that never returns: the lease waits at most the bound, then is refused.
        val second = manager(cap = 1)
        closeWaitBound = Duration.ofMillis(SHORT_BOUND_MILLIS)
        holdOneRetired(second)
        val stuckIdle = lease(second)!!
        stuckIdle.stuck = true
        second.retire(LAKE.name) shouldBe true
        val started = System.nanoTime()
        lease(second) shouldBe null
        val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        withClue("the lease waited for the idle close, and the wait ended at its bound: $waitedMillis ms") {
            (waitedMillis >= SHORT_BOUND_MILLIS) shouldBe true
            (waitedMillis <= TimeUnit.SECONDS.toMillis(GATE_SECONDS)) shouldBe true
        }
    }

    @Test
    fun `D7 round 3 - idle live generation retired before the lease's atomic step refuses nothing, closed on the lease thread`() {
        idleRetiredBetweenReadAndStep(PoolCloser.SYNCHRONOUS)
    }

    @Test
    fun `D7 round 3 - idle live generation retired before the lease's atomic step refuses nothing, closed asynchronously`() {
        idleRetiredBetweenReadAndStep(asyncCloser)
    }

    /**
     * The lander's interleaving (round 2 refusal R1), forced with latches: R = K with a reader running on the retired
     * generation, the live generation idle. The lease is parked at the entry of its atomic step (`computeIfAbsent` on
     * the live map, before the bin lock), after every read it made before it; then the live generation is retired, as
     * D4 allows at any R, so R = K + 1 and one of the two is idle. The lease must not be refused: the idle one is
     * closed and the lease builds; the one with a reader is never closed under it.
     */
    private fun idleRetiredBetweenReadAndStep(closer: PoolCloser) {
        val manager = manager(cap = 1, closer = closer)
        val busy = holdOneRetired(manager)
        val idle = lease(manager)!!
        val paused = AtStepPausingMap.installIn(manager, LEASE_THREAD)
        var outcome: Result<ConnectionPool>? = null
        val leaser = thread(name = LEASE_THREAD) { outcome = runCatching { manager.poolFor(LAKE) } }
        try {
            withClue("the lease reached its atomic step") { paused.atStep.await(GATE_SECONDS, TimeUnit.SECONDS) shouldBe true }
            manager.retire(LAKE.name) shouldBe true
            manager.retiredGenerationsHolding(LAKE.name) shouldBe 2
        } finally {
            paused.resume.countDown()
        }
        leaser.join(TimeUnit.SECONDS.toMillis(GATE_SECONDS))
        leaser.isAlive shouldBe false
        assertSoftly {
            withClue("the lease was not refused: ${outcome?.exceptionOrNull()}") { outcome?.isSuccess shouldBe true }
            withClue("the lease built a third generation") { (outcome?.getOrNull() as? CapPool)?.serial shouldBe 3 }
            withClue("the idle retired generation was closed") { idle.holdsPhysicalHandles shouldBe false }
            withClue("the generation with a reader was never closed under it") { busy.holdsPhysicalHandles shouldBe true }
            manager.retiredGenerationsHolding(LAKE.name) shouldBe 1
        }
        sample(1)
    }

    /**
     * The manager's live map, replaced by reflection with a copy that parks the thread named [leaseThread] once, at
     * the ENTRY of `computeIfAbsent` (before the real call takes the bin lock), until [resume]. Every result and every
     * other operation is the real map's; only the timing of one call is chosen.
     */
    private class AtStepPausingMap(
        private val leaseThread: String,
    ) : ConcurrentHashMap<String, Any>() {
        val atStep = CountDownLatch(1)
        val resume = CountDownLatch(1)
        private val once = AtomicBoolean(false)

        override fun computeIfAbsent(
            key: String,
            mappingFunction: java.util.function.Function<in String, out Any>,
        ): Any {
            if (Thread.currentThread().name == leaseThread && once.compareAndSet(false, true)) {
                atStep.countDown()
                check(resume.await(GATE_SECONDS, TimeUnit.SECONDS)) { "the test never resumed the lease" }
            }
            return super.computeIfAbsent(key, mappingFunction)
        }

        companion object {
            fun installIn(
                manager: ConnectionPoolManager,
                leaseThread: String,
            ): AtStepPausingMap {
                val field = ConnectionPoolManager::class.java.getDeclaredField("pools").apply { isAccessible = true }

                @Suppress("UNCHECKED_CAST")
                val original = field.get(manager) as ConcurrentHashMap<String, Any>
                return AtStepPausingMap(leaseThread).also {
                    it.putAll(original)
                    field.set(manager, it)
                }
            }
        }
    }

    @Test
    fun `two leases racing with no live generation and R = K build exactly once`() {
        val manager = manager(cap = 1)
        holdOneRetired(manager)
        val buildsBefore = builds.get()
        val gate = CountDownLatch(1)
        buildGate = gate
        val results = CopyOnWriteArrayList<CapPool?>()
        val first = thread(name = "test-lease-1") { results += lease(manager) }
        // The first lease is inside its build (counted as an engine, parked on the gate) ...
        awaitUntil { alive.get() == 2 }
        val second = thread(name = "test-lease-2") { results += lease(manager) }
        // ... and the second is blocked on the same key's bin lock before the first build is released.
        awaitUntil { second.state == Thread.State.BLOCKED }
        gate.countDown()
        first.join(TimeUnit.SECONDS.toMillis(GATE_SECONDS))
        second.join(TimeUnit.SECONDS.toMillis(GATE_SECONDS))
        builds.get() shouldBe buildsBefore + 1
        results.size shouldBe 2
        results[0] shouldBeSameInstanceAs results[1]
        sample(1)
    }

    @Test
    fun `a pool that is not the managed lake's is never capped - D8`() {
        val manager = manager(cap = 1, managed = false)
        repeat(UNMANAGED_ROUNDS) {
            val pool = manager.poolFor(LAKE) as CapPool
            pool.active = 1
            pool.stuck = true
            manager.retire(LAKE.name) shouldBe true
        }
        manager.poolFor(LAKE).shouldBeInstanceOf<CapPool>()
        builds.get() shouldBe UNMANAGED_ROUNDS + 1
        manager.retiredGenerationsHolding(LAKE.name) shouldBe 0
    }

    @Test
    fun `K below 1 is refused at construction, naming the key`() {
        listOf(0, -1).forEach { k ->
            shouldThrow<IllegalArgumentException> { manager(cap = k) }.message shouldContain
                ConnectionPoolManager.MAX_RETIRED_GENERATIONS_KEY
        }
    }

    /** One retired managed generation with a reader that never returns: R = 1 from here on. */
    private fun holdOneRetired(manager: ConnectionPoolManager): CapPool {
        val pool = manager.poolFor(LAKE) as CapPool
        pool.active = 1
        pool.stuck = true
        manager.retire(LAKE.name) shouldBe true
        return pool
    }

    /** Spins (no sleep) until [thread] is parked in a wait, bounded by [GATE_SECONDS]. */
    private fun awaitWaiting(thread: Thread) =
        awaitUntil { thread.state == Thread.State.WAITING || thread.state == Thread.State.TIMED_WAITING }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GATE_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not reached in $GATE_SECONDS s" }
            Thread.onSpinWait()
        }
    }

    private companion object {
        const val MAX_AGE_SECONDS = 900L
        const val CEILING_SECONDS = 930L
        const val BOUND_SECONDS = 10L
        const val SHORT_BOUND_MILLIS = 200L
        const val GATE_SECONDS = 30L
        const val TABLE_CHANGES = 40
        const val UNMANAGED_ROUNDS = 10
        const val LEASE_THREAD = "test-lease-819-r3"
        val LAKE = Fixtures.h2(name = "lake")
    }
}
