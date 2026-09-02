package co.datapipelines.executor

import co.datapipelines.events.ExecutionAborted
import co.datapipelines.events.SseEventType
import co.datapipelines.pipeline.NodeOutput
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * dag-executor.md §14: "run 100 executions back-to-back (mixing success, failure, and
 * cancellation), verify staging instances, connections, statements, and semaphore permits are all
 * released (no leaks)."
 *
 * Every counter here is observable rather than inferred: leased-vs-closed connections come from the
 * fixture pool, permits from [ExecutionSlots], registrations from the registry, and staging
 * databases from H2's own `INFORMATION_SCHEMA` — a staging database that survived `close()` still
 * has its `STAGING_EXEC` user, which is the falsifiable signal staging.md §14 names.
 */
class ResourceLeakTest {
    @Test
    fun `a hundred mixed executions leak no slot, connection, registration or staging database`() =
        runBlocking<Unit> {
            val good = h2Datasource("leak_ok", listOf("CREATE TABLE leak (n INT)", "INSERT INTO leak VALUES (1), (2)"))
            val registry = FakeDatasourceRegistry(mapOf("leak_ok" to good))
            val executionIds = mutableListOf<UUID>()

            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(SQL),
                registry = registry,
                config = ExecutorConfig(maxParallelNodes = 2, executionTimeoutSeconds = TIMEOUT_SECONDS),
            ).use { h ->
                repeat(RUNS) { i ->
                    val outcome = Outcome.entries[i % Outcome.entries.size]
                    executionIds += runOne(h, outcome)
                }

                // Permits: every acquire had its release, and no per-user entry was retained.
                h.slots.inFlight shouldBe 0
                h.slots.trackedUsers shouldBe 0

                // Registrations: the `finally` deregisters on success, failure and cancellation.
                h.cancellations.liveExecutions shouldBe 0

                // Connections: every lease came back, on every path.
                registry.leased.get() shouldBe registry.closed.get()
                (registry.leased.get() > 0).shouldBeTrue()

                // Staging: each per-execution database really died with its execution.
                executionIds.forEach { stagingDatabaseIsGone(it).shouldBeTrue() }
                executionIds.size shouldBe RUNS
                executionIds.toSet().size shouldBe RUNS

                // C3: the cancelled third really cancelled. Without this floor the suite happily
                // passed while every "cancelled" run was in fact a plain success, so the
                // cancellation cleanup path — the one most likely to leak — went unexercised.
                h.emitter.allOf<ExecutionAborted>().size shouldBeGreaterThanOrEqual MIN_ABORTS

                // §10's exactly-one-terminal-event-per-execution, asserted as a sum rather than as
                // three fixed counts. An exact `pipeline_completed == successArms` would CONTRADICT
                // the floor above: the floor exists precisely because a cancel can lose its race on
                // a fast box, and every cancel that loses produces one more `pipeline_completed`.
                // Two assertions that cannot both hold turn correct code red on the wrong machine.
                val completed = h.emitter.count(SseEventType.PIPELINE_COMPLETED)
                val failed = h.emitter.count(SseEventType.PIPELINE_FAILED)
                val aborted = h.emitter.count(SseEventType.EXECUTION_ABORTED)
                completed + failed + aborted shouldBe RUNS

                // The failure arm is deterministic — bad SQL never wins a race.
                failed shouldBe armCount(Outcome.FAILURE)
                // The success arm is a floor: it gains one per cancel that arrived too late.
                completed shouldBeGreaterThanOrEqual armCount(Outcome.SUCCESS)
                completed shouldBeLessThanOrEqual armCount(Outcome.SUCCESS) + armCount(Outcome.CANCELLED) - MIN_ABORTS
            }
        }

    @Test
    fun `concurrent executions release every permit even when some are rejected`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 4, maxPerInstance = 8)
            val users = (1..4).map { UUID.randomUUID() }

            (1..CONCURRENT)
                .map { i ->
                    async {
                        runCatching { slots.withSlot(users[i % users.size]) { delay(1) } }
                    }
                }.awaitAll()

            slots.inFlight shouldBe 0
            slots.trackedUsers shouldBe 0
            users.forEach { slots.inFlightFor(it) shouldBe 0 }
        }

    // ------------------------------------------------------------------ helpers

    private enum class Outcome { SUCCESS, FAILURE, CANCELLED }

    /**
     * How many of [RUNS] take [outcome], derived from the **same** expression the loop uses.
     *
     * Deriving rather than hard-coding: `RUNS / 3 + 1` was only right because 100 % 3 == 1, and it
     * would have gone quietly wrong the moment either constant moved.
     */
    private fun armCount(outcome: Outcome): Int = (0 until RUNS).count { Outcome.entries[it % Outcome.entries.size] == outcome }

    /**
     * Runs one execution of the requested shape and returns its id.
     *
     * The cancelled arm uses a **slow** node (C3). It used to reuse the ~2ms success node and wait
     * for a live registration, which meant the execution had almost always finished before the
     * cancel arrived: roughly a third of the run was labelled "cancelled" while exercising the
     * plain success path, so the cancellation cleanup this suite exists to check was never
     * actually taken. With a node that takes [SLOW_NODE_MS] the cancel lands mid-flight, which the
     * `ExecutionAborted` floor in the caller then asserts rather than assumes.
     */
    private suspend fun runOne(
        h: ExecutorHarness,
        outcome: Outcome,
    ): UUID {
        val nodeId =
            when (outcome) {
                Outcome.SUCCESS -> "ok"
                Outcome.FAILURE -> "bad"
                Outcome.CANCELLED -> "slow"
            }
        val nodes = listOf(Fixtures.node(nodeId, source = "leak_ok", output = NodeOutput.Caller))
        val before = h.emitter.events.size

        runCatching {
            if (outcome == Outcome.CANCELLED) {
                coroutineScope {
                    val run = async { h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))) }
                    // Wait for the node to be genuinely in flight, then cancel. Bounded, because a
                    // node that failed early would otherwise spin this loop forever.
                    var waited = 0
                    while (h.emitter.events.size <= before + 1 && waited < CANCEL_WAIT_TICKS && run.isActive) {
                        delay(1)
                        waited++
                    }
                    h.cancellations.cancelAll(AbortReason.SHUTDOWN)
                    run.await()
                }
            } else {
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
            }
        }

        return h.emitter.events
            .drop(before)
            .first()
            .executionId
    }

    /**
     * True when the per-execution staging database is gone.
     *
     * Connecting to `jdbc:h2:mem:exec_{id}` after the fact creates a *fresh empty* database if the
     * old one died — so emptiness proves nothing. The `STAGING_EXEC` user, which only the factory's
     * bootstrap creates and which dies with the database, is the signal that discriminates.
     */
    private fun stagingDatabaseIsGone(executionId: UUID): Boolean =
        DriverManager.getConnection("jdbc:h2:mem:exec_$executionId", "sa", "").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.USERS WHERE USER_NAME = 'STAGING_EXEC'").use { rs ->
                    rs.next()
                    rs.getInt(1) == 0
                }
            }
        }

    private companion object {
        const val RUNS = 100
        const val CONCURRENT = 60
        const val TIMEOUT_SECONDS = 60L

        /** Upper bound on the 1ms ticks spent looking for a live registration to cancel. */
        const val CANCEL_WAIT_TICKS = 200

        /** ~50ms of real query work — long enough for a cancel to land mid-flight. */
        const val SLOW_NODE_MS = 50

        /**
         * A floor, not an equality: a cancel can still lose the race on a fast box. One third of
         * [RUNS] are cancelled arms, so anything near zero means the arm is not working — which is
         * exactly the regression this replaced.
         */
        const val MIN_ABORTS = 10

        val SQL =
            mapOf(
                "ok" to "SELECT n FROM leak",
                "bad" to "SELECT n FROM no_such_table",
                "slow" to """SELECT COUNT(*) AS c FROM SYSTEM_RANGE(1, 900) a, SYSTEM_RANGE(1, 900) b WHERE MOD(a."X" + b."X", 7) = 0""",
            )
    }
}
