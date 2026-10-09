package co.datapipelines.web.sse

import co.datapipelines.events.NodeProgress
import co.datapipelines.executor.OperationDestination
import co.datapipelines.executor.OperationKind
import co.datapipelines.executor.OperationPhase
import co.datapipelines.executor.OperationSnapshot
import co.datapipelines.executor.OperationState
import co.datapipelines.web.TestRedis
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.dao.DataAccessException
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * #487 A — **how long does the replay's full read take at the largest realistic log?**
 *
 * #482 sized the 2 s Redis command timeout against the slowest WRITE the suites exercise (the
 * 7,000-entry Lua append); the READ side — `LRANGE key 0 -1` over a whole execution's log, which
 * §10.3's replay issues once and the idempotent attach's follow issued on every 250 ms tick — was
 * never measured. A read slower than the timeout is a `DataAccessException`, which [SseEventLog.replay]
 * now answers as [ReplayRead.Unavailable] (before #487: as an expired log).
 *
 * Every entry is a real `node_progress` event: one [OperationSnapshot] projected through the shipped
 * [SseEventProjection] and serialised by the shipped [SseEventLog.entry] with [SseJson.mapper] — the
 * production wiring's mapper — and seeded through the shipped [SseEventLog.appendAll] in batches of
 * [SEED_BATCH] (inside `batch-max-events`' 7,000 bound). The shared [TestRedis] container, the 2 s
 * client posture.
 *
 * Two sweeps:
 *  - the PINNED sweep runs in every module test run (well under 30 s) and ASSERTS one thing:
 *    10,000 entries — four times the 2,400 periodic samples the shipped defaults allow one
 *    execution — read inside the command timeout, the line past which the read stops being a read;
 *  - the FULL sweep — 1,000 / 10,000 / 50,000 / 100,000 / 200,000 / 300,000 entries, three runs
 *    each — reports and never asserts, gated on `DP_MEASURE=1` like `dag`'s `measure` package.
 *
 * Each sweep writes its table to `build/reports/sse-replay-read/measurement-<sweep>.md`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SseReplayReadMeasurement {
    private val redis = TestRedis.template()
    private val eventLog = SseEventLog(redis, SseJson.mapper)
    private val seeded = mutableListOf<UUID>()

    @AfterAll
    fun dropSeededLogs() {
        seeded.forEach { redis.delete("$KEY_PREFIX$it") }
    }

    @Test
    fun `a pinned sweep reads 10,000 realistic entries well inside the command timeout`() {
        val row = measure(PINNED_ENTRIES, runs = 1)
        report("pinned", listOf(row))
        row.lrangeMs.single() shouldBeLessThan TestRedis.COMMAND_TIMEOUT.toMillis()
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DP_MEASURE", matches = "1")
    fun `the full sweep, 1k to 300k entries, three runs each`() {
        // One discarded pass first: the first LRANGE and the first parse of a JVM pay class loading
        // and JIT warm-up, which is not the cost of a read.
        measure(WARM_UP_ENTRIES, runs = 1)
        report("full", FULL_SWEEP.map { measure(it, runs = RUNS) })
    }

    /** Seeds one log of [entries] events and times [runs] reads of it, LRANGE alone and whole. */
    private fun measure(
        entries: Int,
        runs: Int,
    ): Row {
        val executionId = UUID.randomUUID()
        seeded += executionId
        val sample = entry(executionId, 1)
        (1..entries).chunked(SEED_BATCH).forEach { ids -> eventLog.appendAll(ids.map { entry(executionId, it) }) }
        val lrange = mutableListOf<Long>()
        val replay = mutableListOf<Long>()
        repeat(runs) {
            lrange += timed { redis.opsForList().range("$KEY_PREFIX$executionId", 0, -1)?.size shouldBe entries }
            replay +=
                timed {
                    eventLog
                        .replay(executionId)
                        .shouldBeInstanceOf<ReplayRead.Log>()
                        .events.size shouldBe entries
                }
        }
        return Row(entries, sample.json.toByteArray().size, lrange, replay)
    }

    /** Milliseconds of [block], or [TIMED_OUT] when Redis did not answer inside the command timeout. */
    private fun timed(block: () -> Unit): Long {
        val startedAt = System.nanoTime()
        return try {
            block()
            (System.nanoTime() - startedAt) / NANOS_PER_MILLI
        } catch (e: DataAccessException) {
            println("#487: read failed after ${(System.nanoTime() - startedAt) / NANOS_PER_MILLI} ms: ${e.message}")
            TIMED_OUT
        }
    }

    /** One `node_progress` sample as the recorder logs it: projected, then serialised by the log itself. */
    private fun entry(
        executionId: UUID,
        eventId: Int,
    ): ReplayLogEntry {
        val observedAt = STARTED_AT.plusMillis(eventId * SAMPLE_INTERVAL_MS)
        val snapshot =
            OperationSnapshot(
                nodeId = "load_daily_trips_by_zone",
                attempt = 1,
                sequence = eventId,
                kind = OperationKind.WRITEBACK,
                destination = OperationDestination.datasource("analytics-warehouse", "daily_trips_by_zone"),
                state = OperationState.WRITING,
                startedAt = STARTED_AT,
                observedAt = observedAt,
                elapsedMs = eventId * SAMPLE_INTERVAL_MS,
                timingsMs =
                    mapOf(
                        OperationPhase.CONNECTING to 41L,
                        OperationPhase.EXECUTING to 1_830L,
                        OperationPhase.FETCHING to 12_406L,
                        OperationPhase.WRITING to eventId * SAMPLE_INTERVAL_MS,
                    ),
                rowsFetched = eventId * ROWS_PER_SAMPLE,
                rowsWritten = eventId * ROWS_PER_SAMPLE,
                batchesWritten = eventId.toLong(),
                committed = null,
                rolledBack = null,
                childExecutionId = null,
            )
        val payload = SseEventProjection(CORRELATION_ID).payload(NodeProgress(executionId, snapshot))
        return eventLog.entry(executionId, LoggedSseEvent(eventId, "node_progress", payload))
    }

    private fun report(
        sweep: String,
        rows: List<Row>,
    ) {
        val text =
            buildString {
                appendLine("#487 replay read, $sweep sweep — bytes per entry: ${rows.first().bytesPerEntry}")
                appendLine("| entries | MB | LRANGE ms (runs) | replay ms (runs) |")
                appendLine("|---:|---:|---|---|")
                rows.forEach { row ->
                    val mb = "%.1f".format(row.entries.toDouble() * row.bytesPerEntry / BYTES_PER_MB)
                    appendLine("| ${row.entries} | $mb | ${row.lrangeMs.joinToString(" / ")} | ${row.replayMs.joinToString(" / ")} |")
                }
            }
        println(text)
        File("build/reports/sse-replay-read").apply { mkdirs() }.resolve("measurement-$sweep.md").writeText(text)
    }

    private data class Row(
        val entries: Int,
        val bytesPerEntry: Int,
        val lrangeMs: List<Long>,
        val replayMs: List<Long>,
    )

    private companion object {
        /** `SseEventLog`'s private key prefix (module-structure §5.9), read here as a literal like `SseEventLogTest`. */
        const val KEY_PREFIX = "dp:events:"

        const val PINNED_ENTRIES = 10_000
        const val WARM_UP_ENTRIES = 1_000
        val FULL_SWEEP = listOf(1_000, 10_000, 50_000, 100_000, 200_000, 300_000)
        const val RUNS = 3
        const val SEED_BATCH = 5_000

        /** `progress-sample-interval-seconds`' default: one periodic sample per second. */
        const val SAMPLE_INTERVAL_MS = 1_000L
        const val ROWS_PER_SAMPLE = 25_000L
        const val TIMED_OUT = -1L
        const val NANOS_PER_MILLI = 1_000_000L
        const val BYTES_PER_MB = 1_048_576.0

        val STARTED_AT: Instant = Instant.parse("2026-10-09T08:00:00Z")
        val CORRELATION_ID: UUID = UUID.fromString("5f0e6c1a-3d2b-4c7e-9a10-2b8f4d6e1c33")
    }
}
