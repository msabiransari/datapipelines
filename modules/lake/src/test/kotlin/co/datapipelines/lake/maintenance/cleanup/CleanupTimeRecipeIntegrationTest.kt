package co.datapipelines.lake.maintenance.cleanup

import co.datapipelines.lake.maintenance.Guarded
import co.datapipelines.lake.maintenance.MaintenanceDeclaration
import co.datapipelines.lake.maintenance.MaintenanceHarness
import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.MaintenancePart
import co.datapipelines.lake.maintenance.MaintenanceRun
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.PartResult
import co.datapipelines.lake.maintenance.remove.CatalogClock
import co.datapipelines.lake.maintenance.remove.TestClock
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.scheduler.SystemJobOutcome
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Brief 962 B, the time recipe (C8, C9, C9a; the record's §14 E1 (o)) on the classes that ship. Settings: job timeout
 * J = 3585 s, statement timeout S = 5 s, grace G = 2 h — `LakeRulesTest` (`962 - the cleanup time recipe's settings…`)
 * shows the deployment rule admits them and refuses one hour less. Three things are controlled, each by its own means:
 *
 * 1. skew s only in the remover's clock function ([CleanupHarness.removerClock]); the probe's marker is a real PUT with
 *    MinIO's own `LastModified`, never aged;
 * 2. time already elapsed A: the producer's run is admitted by a production worker with job timeout J − A (its row's
 *    `deadline_at` IS the run's deadline, asserted), and the listing decorator moves every listed key back by A
 *    except `dp/clock/` keys;
 * 3. real time t, really waited; every "before / after the deadline" claim is the catalog's `now()` minus that one
 *    deadline instant.
 *
 * While the producer's row is live the cleanup is the production part called directly with a run the test opens from
 * 957's class (its own id, deadline J ahead, the real lease, probe and remover; no second row): an injected concurrent
 * remover. Once the producer's deadline has passed, the cleanup comes through the ordinary worker.
 */
class CleanupTimeRecipeIntegrationTest {
    private val h = CleanupHarness()

    @BeforeEach
    fun reset() = h.reset()

    @AfterEach
    fun close() = h.close()

    @Test
    fun `C8 inside grace - outputs written before the commit lease stay, and the resumed part commits them before its deadline`() {
        val t = h.table("c8i")
        h.store.age = Duration.ofSeconds(JOB_TIMEOUT_S - INSIDE_LIFETIME_S) // A = J − 25
        val producer = Producer(t).start(Duration.ofSeconds(INSIDE_LIFETIME_S))

        val cleanup = h.cleanup().run(h.run(t, lifetime = J))
        val keptAfterCleanup = h.exists(producer.outputKey)
        producer.resume()

        println(
            "(962 C8 inside) A=${h.store.age.seconds}s cleanup=${cleanup.outcome} deleted=${cleanup.figures.files} expected=0 " +
                "output kept=$keptAfterCleanup commit=${producer.committed} commit_at−deadline=${producer.commitMargin()}",
        )
        keptAfterCleanup shouldBe true
        cleanup.figures.files shouldBe 0
        producer.committed.shouldBeInstanceOf<Guarded.Done<*>>()
        (requireNotNull(producer.commitAt) < producer.deadline) shouldBe true
        h.exists(producer.outputKey) shouldBe true
    }

    @Test
    fun `C8 past grace - the ordinary worker's cleanup deletes the outputs and the resumed part's acquire is refused deadline`() {
        val t = h.table("c8p")
        h.store.age = Duration.ofHours(CleanupHarness.GRACE_HOURS.toLong()).plusSeconds(PAST_GRACE_EXTRA_S) // A = G + 60
        val producer = Producer(t).start(Duration.ofSeconds(SHORT_LIFETIME_S))
        h.m.awaitDbPast(producer.deadline)

        h.worker(parts = listOf(h.cleanup()), jobTimeout = J).run(t.id.toString()) shouldBe SystemJobOutcome.Done
        val deleted = !h.exists(producer.outputKey)
        producer.resume()

        val successor = h.m.rows(t).single { it.id != producer.run.runId }
        val cleanup = h.parts(successor).single()
        println(
            "(962 C8 past) A=${h.store.age.seconds}s deleted=${cleanup["files"]} expected=1 (the output) commit=${producer.committed} " +
                "producer row=${h.m.rows(t).single { it.id == producer.run.runId }.state}",
        )
        deleted shouldBe true
        cleanup["files"] shouldBe 1
        producer.committed shouldBe DEADLINE_REFUSED
    }

    @Test
    fun `C9 with shifted clocks - store behind deletes only after the margin, store ahead deletes nothing`() {
        val behindClock = TestClock(CatalogClock.of(h.jdbc)).also { it.shift(Duration.ofSeconds(SHIFT_S)) }
        val aheadClock = TestClock(CatalogClock.of(h.jdbc)).also { it.shift(Duration.ofSeconds(-SHIFT_S)) }
        val behind = h.table("c9b")
        val ahead = h.table("c9a")
        h.store.age = Duration.ofSeconds(JOB_TIMEOUT_S - SHORT_LIFETIME_S) // A = J − 2: each producer lives 2 s
        val pb = Producer(behind).start(Duration.ofSeconds(SHORT_LIFETIME_S))
        val pa = Producer(ahead).start(Duration.ofSeconds(SHORT_LIFETIME_S))
        val deletes = CopyOnWriteArrayList<Pair<String, Instant>>()
        h.faults.onDelete = { _, request, _ ->
            request.delete().objects().forEach { o -> deletes += o.key() to h.m.dbNow() }
            null
        }

        fun pass(
            producer: Producer,
            clock: TestClock,
        ): Instant {
            val at = h.m.dbNow()
            h.worker(parts = listOf(h.cleanupWith(clock)), jobTimeout = J).run(producer.table.id.toString()) shouldBe SystemJobOutcome.Done
            return at
        }

        // t = 2 + S: the deadline S past; the output measures J + S + 3500 = 7090 < G and stays.
        awaitPastDeadline(pb, Duration.ofSeconds(STATEMENT_TIMEOUT_S))
        val early = pass(pb, behindClock)
        pass(pa, aheadClock)
        (Duration.between(pb.deadline, early) >= Duration.ofSeconds(STATEMENT_TIMEOUT_S)) shouldBe true
        h.exists(pb.outputKey) shouldBe true
        h.exists(pa.outputKey) shouldBe true

        // t = 110: E = 3693, the output measures 7193 < G and stays (a bound below the first deleting pass).
        h.partClock.shift(Duration.ofHours(DUE_AGAIN_HOURS)) // the last cleanup finished: due again only after its interval
        awaitPastDeadline(pb, Duration.ofSeconds(BELOW_MARGIN_S))
        pass(pb, behindClock)
        h.exists(pb.outputKey) shouldBe true

        // t ≥ 120 (E ≥ G − 3500 + 1 = 3701 from t = 118): the first pass that deletes it.
        h.partClock.shift(Duration.ofHours(DUE_AGAIN_HOURS * 2))
        awaitPastDeadline(pb, Duration.ofSeconds(DELETING_S))
        awaitPastDeadline(pa, Duration.ofSeconds(DELETING_S))
        pass(pb, behindClock)
        pass(pa, aheadClock)
        val deletedAt = deletes.single { it.first == pb.outputKey }.second

        pb.resume()
        pa.resume()
        val margin = Duration.between(pb.deadline, deletedAt)
        val outputs = setOf(pb.outputKey, pa.outputKey)
        val outputDeletions = deletes.count { it.first in outputs }
        println(
            "(962 C9) store behind 3500 s: kept at t=2+S and t=110, deleted at delete_call−deadline=${margin.toMillis()} ms " +
                "(> S = ${STATEMENT_TIMEOUT_S}s); store ahead 3500 s: kept=${h.exists(pa.outputKey)}; resumed acquires " +
                "behind=${pb.committed} ahead=${pa.committed}; output deletions=$outputDeletions expected=1",
        )
        h.exists(pb.outputKey) shouldBe false
        (margin > Duration.ofSeconds(STATEMENT_TIMEOUT_S)) shouldBe true
        pb.committed shouldBe DEADLINE_REFUSED
        h.exists(pa.outputKey) shouldBe true
        deletes.none { it.first == pa.outputKey } shouldBe true
    }

    @Test
    fun `C9a past the bound - the probe refuses with clock skew, no candidate is deleted, the producer commits before its deadline`() {
        val t = h.table("c9x")
        h.removerClock.shift(Duration.ofSeconds(PAST_BOUND_SKEW_S)) // store behind by 3600 + S + 60
        h.store.age = Duration.ofSeconds(JOB_TIMEOUT_S - INSIDE_LIFETIME_S) // A = J − 25
        val deletes = CopyOnWriteArrayList<Pair<String, Instant>>()
        h.faults.onDelete = { _, request, _ ->
            request.delete().objects().forEach { o -> deletes += o.key() to h.m.dbNow() }
            null
        }
        val producer = Producer(t).start(Duration.ofSeconds(INSIDE_LIFETIME_S))

        val outcome = runCatching { h.cleanup().run(h.run(t, lifetime = J)) }
        producer.resume()

        val deletedAt = deletes.firstOrNull { it.first == producer.outputKey }?.second
        val named =
            h
                .metadata(t)
                .currentSnapshot()
                ?.let { s -> h.snapshotFiles(t, s.snapshotId()) }
                .orEmpty()
        val cleanupEnd =
            outcome.exceptionOrNull()?.let { (it as? DatapipelinesException)?.code ?: it::class.simpleName }
                ?: outcome.getOrNull()?.outcome
        val deleteMargin = deletedAt?.let { Duration.between(producer.deadline, it).toMillis() }
        println(
            "(962 C9a) skew=${PAST_BOUND_SKEW_S}s A=${h.store.age.seconds}s cleanup=$cleanupEnd " +
                "output present=${h.exists(producer.outputKey)} delete_call−deadline=$deleteMargin ms commit=${producer.committed} " +
                "commit_at−deadline=${producer.commitMargin()} snapshot names output=${producer.outputKey in named}",
        )
        h.exists(producer.outputKey) shouldBe true
        deletedAt shouldBe null
        (outcome.exceptionOrNull() as DatapipelinesException).code shouldBe PipelineErrorCodes.Lake.MAINTENANCE_CLOCK_SKEW
        producer.committed.shouldBeInstanceOf<Guarded.Done<*>>()
        (requireNotNull(producer.commitAt) < producer.deadline) shouldBe true
        (producer.outputKey in named) shouldBe true
    }

    /** Waits, really, until the catalog's `now()` is past [producer]'s deadline plus [by] (at most [LONG_WAIT_S]). */
    private fun awaitPastDeadline(
        producer: Producer,
        by: Duration,
    ) {
        val instant = producer.deadline + by
        val end = System.nanoTime() + Duration.ofSeconds(LONG_WAIT_S).toNanos()
        while (!h.m.dbNow().isAfter(instant)) {
            check(System.nanoTime() < end) { "the catalog clock did not pass $instant" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /**
     * The producing run (C8 step 2): a production worker with job timeout J − A admits it; its one part writes one output
     * lease-free at admission and pauses before its commit until [resume].
     */
    private inner class Producer(
        val table: MaintenanceTable,
    ) : MaintenancePart {
        override val name: String = "rewrite"
        override val order: Int = 1
        val location = h.outputLocation(table)
        val outputKey: String = requireNotNull(h.target(table).keyOf(location))
        private val written = CountDownLatch(1)
        private val go = CountDownLatch(1)
        private var done: CompletableFuture<SystemJobOutcome>? = null

        @Volatile
        lateinit var run: MaintenanceRun

        @Volatile
        var committed: Guarded<Unit>? = null

        @Volatile
        var commitAt: Instant? = null

        val deadline: Instant get() = run.deadline

        override fun declare(table: MaintenanceTable) = MaintenanceDeclaration(expectsDue = true, scratchBytes = 0)

        override fun dueNow(run: MaintenanceRun): Boolean = true

        override fun run(run: MaintenanceRun): PartResult {
            this.run = run
            h.put(outputKey)
            written.countDown()
            check(go.await(PAUSE_BOUND_S, TimeUnit.SECONDS)) { "the producer was never resumed" }
            commitAt = h.m.dbNow()
            val guarded = run.commit { t -> t.newAppend().appendFile(h.dataFileAt(t, location)) }
            committed = guarded
            return when (guarded) {
                is Guarded.Done -> PartResult(MaintenanceOutcome.DONE)
                is Guarded.Refused -> PartResult(guarded.outcome)
            }
        }

        fun start(lifetime: Duration): Producer {
            val worker = h.worker(parts = listOf(this), jobTimeout = lifetime)
            done = CompletableFuture.supplyAsync { worker.run(table.id.toString()) }
            check(written.await(MaintenanceHarness.WAIT_SECONDS, TimeUnit.SECONDS)) { "the producer did not write its output" }
            // The ONE admission statement gives both: the row's deadline_at is the run's deadline (957 D5).
            h.m
                .rows(table)
                .single { it.id == run.runId }
                .deadline shouldBe run.deadline
            return this
        }

        fun resume() {
            go.countDown()
            checkNotNull(done) { "start() first" }.get(MaintenanceHarness.WAIT_SECONDS, TimeUnit.SECONDS)
        }

        fun commitMargin(): String = commitAt?.let { "${Duration.between(deadline, it).toMillis()} ms" } ?: "none"
    }

    private companion object {
        const val JOB_TIMEOUT_S = 3585L
        const val STATEMENT_TIMEOUT_S = 5L
        val J: Duration = Duration.ofSeconds(JOB_TIMEOUT_S)
        const val INSIDE_LIFETIME_S = 25L
        const val SHORT_LIFETIME_S = 2L
        const val PAST_GRACE_EXTRA_S = 60L
        const val SHIFT_S = 3500L
        const val PAST_BOUND_SKEW_S = 3600L + STATEMENT_TIMEOUT_S + 60L
        const val DUE_AGAIN_HOURS = 25L
        const val BELOW_MARGIN_S = 108L // t = 110
        const val DELETING_S = 118L // t = 120
        const val LONG_WAIT_S = 180L

        /** A producer pauses at most this long: longer than C9's real two minutes plus its passes. */
        const val PAUSE_BOUND_S = 300L
        const val POLL_MILLIS = 200L

        /** The resumed part's acquire past its run's deadline (957 D5). */
        val DEADLINE_REFUSED = Guarded.Refused(MaintenanceOutcome.DEADLINE, PipelineErrorCodes.Lake.MAINTENANCE_DEADLINE)
    }
}
