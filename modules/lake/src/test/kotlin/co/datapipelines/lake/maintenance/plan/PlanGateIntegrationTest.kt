package co.datapipelines.lake.maintenance.plan

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.MarkerPart
import co.datapipelines.lake.maintenance.SemaphoreMaintenanceSlots
import co.datapipelines.lake.maintenance.plan.PlanHarness.FileSpec
import co.datapipelines.lake.profiles.LakeProfileFixtures
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.scheduler.DbSchedulerSystemJobQueue
import co.datapipelines.scheduler.KeyedSystemJob
import co.datapipelines.scheduler.SchedulerAutoConfiguration
import co.datapipelines.scheduler.SchedulerTasks
import co.datapipelines.scheduler.SystemJobOutcome
import com.fasterxml.jackson.databind.JsonNode
import com.github.kagkarlsson.scheduler.Scheduler
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * N13 (M6 recording, through the REAL gate, [ProfileMaintenanceGate]) and N13b (a scheduler thread stays free; the
 * cut's L3, owner ruling 2026-11-13T21:36:39Z). 957's boot rule refusing 2 threads with one maintenance slot is 957's
 * R9 (`SchedulerThreadsRulesTest`); it is cited, not repeated.
 */
class PlanGateIntegrationTest {
    private val h = PlanHarness()
    private val json = PipelineJson.objectMapper()
    private val gate = ProfileMaintenanceGate(h.named)

    @BeforeEach
    fun clean() {
        h.reset()
        LakeTestDb.jdbc.execute("TRUNCATE scheduled_tasks")
    }

    @AfterEach
    fun close() = h.close()

    private fun parts(table: MaintenanceTable): Map<String, JsonNode> =
        json.readTree(h.m.rows(table).single().partsJson).associateBy { it["name"].asText() }

    private fun pause(
        table: MaintenanceTable,
        key: String,
        until: Instant,
    ) {
        LakeTestDb.jdbc.update(
            "UPDATE lake_tables_managed SET policy_json = jsonb_build_object('pause', jsonb_build_object(?, ?)) WHERE id = ?",
            key,
            until.toString(),
            table.id,
        )
    }

    private fun recordedRun(table: MaintenanceTable): Map<String, JsonNode> {
        h.m.worker(parts = listOf(MarkerPart("cleanup", 1), MarkerPart("compaction", 2)), gate = gate).run(table.id.toString())
        return parts(table)
    }

    @Test
    fun `N13 - outside the window the run does the cleanup and records compaction outside window`() {
        val now = h.m.dbNow()
        val hhmm = DateTimeFormatter.ofPattern("HH:mm")
        val start = LocalTime.ofInstant(now, ZoneOffset.UTC).plusHours(2)
        h.editProfile("production", LakeProfileFixtures.document("maintenance.window" to "\"${start.format(hhmm)}-${start.plusHours(1).format(hhmm)}\""))
        val table = h.m.table("outside")

        val recorded = recordedRun(table)

        recorded.getValue("cleanup")["outcome"].asText() shouldBe "done"
        recorded.getValue("compaction")["outcome"].asText() shouldBe "outside_window"
        recorded.getValue("compaction")["reason"].asText() shouldBe "outside window"
        recorded.getValue("compaction")["attempted"].asBoolean() shouldBe false
    }

    @Test
    fun `N13 - the compaction pause stops compaction only, recorded paused until its instant`() {
        h.editProfile("production", LakeProfileFixtures.document("maintenance.window" to "\"00:00-00:00\""))
        val table = h.m.table("compactionpaused")
        val until = h.m.dbNow().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS)
        pause(table, "compaction_until", until)

        val recorded = recordedRun(table)

        recorded.getValue("cleanup")["outcome"].asText() shouldBe "done"
        recorded.getValue("compaction")["outcome"].asText() shouldBe "paused_until"
        recorded.getValue("compaction")["reason"].asText() shouldBe "paused until $until"
        recorded.getValue("compaction")["until"].asText() shouldBe until.toString()
    }

    @Test
    fun `N13 - the cleanup pause stops cleanup only, recorded paused until its instant`() {
        h.editProfile("production", LakeProfileFixtures.document("maintenance.window" to "\"00:00-00:00\""))
        val table = h.m.table("cleanuppaused")
        val until = h.m.dbNow().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS)
        pause(table, "cleanup_until", until)

        val recorded = recordedRun(table)

        recorded.getValue("cleanup")["outcome"].asText() shouldBe "paused_until"
        recorded.getValue("cleanup")["reason"].asText() shouldBe "paused until $until"
        recorded.getValue("compaction")["outcome"].asText() shouldBe "done"
    }

    @Test
    fun `N13b - with 3 threads and one slot, an admitted run and a measuring planner hold two, an ordinary task still runs`() {
        val maintained = h.m.table("maintained")
        val measured = h.m.table("measured")
        h.append(measured, FileSpec("2026-11-01", 4_096))
        val runHeld = CountDownLatch(1)
        val runRelease = CountDownLatch(1)
        val part =
            MarkerPart("cleanup", 1, before = {
                runHeld.countDown()
                runRelease.await(WAIT_SECONDS, TimeUnit.SECONDS)
            })
        val worker = h.m.worker(parts = listOf(part), slots = SemaphoreMaintenanceSlots(1))
        val ordinary = OrdinaryJob()
        val stall = h.control.stall(measured.id)
        // the planner measures only the stalled table; the maintained table's row is attempted already
        h.repository.startAttempt(maintained.id, java.util.UUID.randomUUID())
        val planner = h.planner(pageSize = 1, tableTimeout = Duration.ofMinutes(1))
        val scheduler =
            Scheduler
                .create(
                    TransactionAwareDataSourceProxy(LakeTestDb.dataSource),
                    listOf(SchedulerTasks.keyedTask(worker), SchedulerTasks.keyedTask(ordinary)),
                ).startTasks(listOf(SchedulerTasks.recurringTask(planner)))
                .threads(THREADS)
                .pollingInterval(Duration.ofMillis(POLL_MILLIS))
                .serializer(SchedulerAutoConfiguration.JSON_SERIALIZER)
                .shutdownMaxWait(Duration.ofSeconds(WAIT_SECONDS))
                .build()
        val queue = DbSchedulerSystemJobQueue { scheduler }
        try {
            scheduler.start()
            queue.enqueueIfAbsent(worker, maintained.id.toString(), Instant.now())
            runHeld.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true // the one admitted run holds a thread
            stall.entered.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true // the planner holds another

            queue.enqueueIfAbsent(ordinary, "ordinary", Instant.now())

            ordinary.ran.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
        } finally {
            runRelease.countDown()
            h.control.release(measured.id)
            scheduler.stop()
        }
    }

    /** Any other system job: it only says it ran. */
    private class OrdinaryJob : KeyedSystemJob {
        override val name = "plan-test-ordinary"
        override val instances = "one instance per test key"
        val ran = CountDownLatch(1)

        override fun run(key: String): SystemJobOutcome {
            ran.countDown()
            return SystemJobOutcome.Done
        }
    }

    private companion object {
        const val THREADS = 3
        const val POLL_MILLIS = 50L
        const val WAIT_SECONDS = 20L
    }
}
