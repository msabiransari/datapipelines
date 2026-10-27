package co.datapipelines.scheduler

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * #725 — THE invariant of the last-run filter: for every [LastRunOutcome], the schedules §20.1's
 * `outcome` lists are the schedules the Schedules home counts in that bucket — by id, not by count,
 * unlensed and through a narrowed lens — and the five lists partition the visible set (every
 * schedule in exactly one). The home's side is [ScheduleService.summary]'s own definition,
 * `LastRunOutcome.of(latestStates[id])` over [ScheduleService.list], with the summary's counts
 * read beside it; the filter's side is the SQL predicate. They are written apart (Kotlin over a
 * `DISTINCT ON` read, SQL over a correlated subquery) and share only [LastRunOutcome.states] and
 * the newest-run ordering, which is what this pins.
 *
 * The fixture's newest runs cover every [RunState], each over an older run in ANOTHER bucket; one
 * schedule has no run; one's two runs share a `created_at`, so only the `id DESC` tie-break
 * decides its bucket. The last case EXPLAINs the statement [ScheduleRepository.listLive] issues
 * and requires each schedule's run probe to be an index scan keyed on `schedule_id`, never a scan
 * of the whole `schedule_runs` table. Which index serves it is the planner's: the newest-run
 * subquery reads `idx_schedule_runs_schedule` (`schedule_id, created_at DESC`), and `never-ran`'s
 * anti-join may equally take `uq_schedule_runs_occurrence` (`schedule_id, scheduled_at`) — measured.
 */
class LastRunOutcomeFilterInvariantIntegrationTest {
    private lateinit var h: SchedulerHarness

    @BeforeEach
    fun setUp() {
        SchedulerTestDb.reset()
        h = SchedulerHarness()
    }

    @Test
    fun `every bucket's filtered list is the set the summary counts in it - unlensed and narrowed - and the five partition the list`() {
        RunState.entries.forEachIndexed { i, state ->
            val schedule = h.create(h.request(name = "inv/${"%02d".format(i)}_${state.wire}", payload = FakeExecutor.payload(job(i))))
            // An older run in a DIFFERENT bucket: only the newest may decide.
            ScheduleRunRows.insert(schedule.id, olderThan(state), AT)
            ScheduleRunRows.insert(schedule.id, state, AT.plusSeconds(60))
        }
        h.create(h.request(name = "inv/never", payload = FakeExecutor.payload("a")))
        val tie = h.create(h.request(name = "inv/tie", payload = FakeExecutor.payload("a")))
        ScheduleRunRows.insert(tie.id, RunState.SUCCEEDED, AT, id = LOW_ID, scheduledAt = AT)
        ScheduleRunRows.insert(tie.id, RunState.FAILED, AT, id = HIGH_ID, scheduledAt = AT.plusSeconds(1))

        val buckets = assertInvariant(TargetViewer.EVERYONE)
        buckets.getValue(LastRunOutcome.NEVER_RAN) shouldBe setOf("inv/never")
        withClue("the created_at tie is broken by id DESC, as latestStates breaks it") {
            ("inv/tie" in buckets.getValue(LastRunOutcome.FAILED)) shouldBe true
        }
        LastRunOutcome.entries.forEach { o -> withClue("${o.wire} has a row to compare") { buckets.getValue(o).isEmpty() shouldBe false } }

        h.executor.lens = setOf("job:a")
        val narrowed = assertInvariant(NarrowedViewer)
        withClue("the lens hid some rows - the narrowed comparison is not the unlensed one again") {
            narrowed.values.sumOf { it.size } shouldBe buckets.values.sumOf { it.size } - RunState.entries.indices.count { job(it) == "b" }
        }
    }

    @Test
    fun `each schedule's run probe is an index scan keyed on schedule_id - never a scan of schedule_runs`() {
        repeat(PLAN_SCHEDULES) { i ->
            val schedule = h.create(h.request(name = "plan/s_${"%03d".format(i)}", payload = FakeExecutor.payload("a")))
            SchedulerTestDb.jdbc.batchUpdate(
                RUNS_SQL,
                Array(PLAN_RUNS_EACH) { n ->
                    val at = Timestamp.from(AT.plusSeconds(n.toLong() * 60))
                    mapOf(
                        "id" to UUID.randomUUID(),
                        "sid" to schedule.id,
                        "ws" to SchedulerTestDb.WORKSPACE,
                        "at" to at,
                        "executor" to FakeExecutor.ID,
                        "actor" to SchedulerTestDb.SYSTEM_ACTOR,
                        "state" to if (n % 2 == 0) "succeeded" else "failed",
                    )
                },
            )
        }
        SchedulerTestDb.jdbc.jdbcTemplate.execute("ANALYZE schedule_runs")
        SchedulerTestDb.jdbc.jdbcTemplate.execute("ANALYZE schedules")
        LastRunOutcome.entries.forEach { o ->
            val plan = planOf { repository -> repository.listLive(SchedulerTestDb.WORKSPACE, null, LIMIT, 0, outcome = o) }
            println("EXPLAIN listLive outcome=${o.wire}\n$plan")
            withClue("listLive(outcome = ${o.wire})'s plan:\n$plan") { assertKeyedProbe(plan) }
        }
        val keyset =
            planOf { repository ->
                repository.listLiveAfter(SchedulerTestDb.WORKSPACE, null, "plan/s_010", LIMIT, outcome = LastRunOutcome.FAILED)
            }
        println("EXPLAIN listLiveAfter outcome=failed\n$keyset")
        withClue("listLiveAfter(outcome = failed)'s plan:\n$keyset") { assertKeyedProbe(keyset) }
    }

    /** The run probe is an index scan on `schedule_runs` keyed by the outer schedule's id; no table scan. */
    private fun assertKeyedProbe(plan: String) {
        plan shouldNotContain "Seq Scan on schedule_runs"
        plan shouldContain Regex("Index (Only )?Scan using \\w+ on schedule_runs r")
        plan shouldContain "Index Cond: (schedule_id = schedules.id)"
    }

    /**
     * The filter's buckets for [viewer], each asserted equal (by name, so a failure names the rows)
     * to the summary's definition over the same admitted list, with the summary's own counts; and
     * the buckets partition that list.
     */
    private fun assertInvariant(viewer: TargetViewer): Map<LastRunOutcome, Set<String>> {
        val admitted = h.service.list(SchedulerTestDb.WORKSPACE, null, LIMIT, 0, viewer)
        val latest = h.runs.latestStates(SchedulerTestDb.WORKSPACE, admitted.map { it.id })
        val counted = admitted.groupBy({ LastRunOutcome.of(latest[it.id]) }, { it.name })
        val summary = h.service.summary(SchedulerTestDb.WORKSPACE, viewer)
        val filtered =
            LastRunOutcome.entries.associateWith { o ->
                h.service.list(SchedulerTestDb.WORKSPACE, null, LIMIT, 0, viewer, outcome = o.wire).map { it.name }.toSet()
            }
        LastRunOutcome.entries.forEach { o ->
            withClue("bucket ${o.wire} (${if (viewer.narrowed) "narrowed" else "unlensed"})") {
                filtered.getValue(o) shouldBe counted[o].orEmpty().toSet()
                filtered.getValue(o).size shouldBe summary.lastRun.getValue(o)
            }
        }
        withClue("every schedule in exactly one bucket's list") {
            filtered.values.flatten() shouldContainExactlyInAnyOrder admitted.map { it.name }
        }
        return filtered
    }

    /** EXPLAIN of exactly the statement [read] issues, on one connection over the shared container. */
    private fun planOf(read: (ScheduleRepository) -> Unit): String {
        val recording = RecordingJdbc(SchedulerTestDb.dataSource)
        read(ScheduleRepository(recording, SchedulerAutoConfiguration.JSON))
        val (sql, params) = requireNotNull(recording.last) { "the read issued no statement" }
        return SchedulerTestDb.dataSource.connection.use { connection ->
            NamedParameterJdbcTemplate(SingleConnectionDataSource(connection, true))
                .queryForList("EXPLAIN $sql", params, String::class.java)
                .joinToString("\n")
        }
    }

    /** Records the last named-parameter query a repository issued, then runs it. */
    private class RecordingJdbc(
        dataSource: DataSource,
    ) : NamedParameterJdbcTemplate(dataSource) {
        var last: Pair<String, Map<String, *>>? = null

        override fun <T : Any?> query(
            sql: String,
            paramMap: Map<String, *>,
            rowMapper: RowMapper<T>,
        ): List<T> {
            last = sql to paramMap
            return super.query(sql, paramMap, rowMapper)
        }
    }

    private companion object {
        const val LIMIT = 1000
        const val PLAN_SCHEDULES = 40
        const val PLAN_RUNS_EACH = 200
        val AT: Instant = Instant.parse("2026-09-25T11:00:00Z")
        val LOW_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val HIGH_ID: UUID = UUID.fromString("ffffffff-0000-0000-0000-000000000001")

        val RUNS_SQL =
            """
            INSERT INTO schedule_runs (id, schedule_id, workspace_id, origin, scheduled_at, reference_at, reference_timezone,
                                       admit_by, executor_id, payload_schema_version, payload_json, actor_user_id, state,
                                       created_at, updated_at)
            VALUES (:id, :sid, :ws, 'cron', :at, :at, 'UTC', :at, :executor, 1, '{}'::jsonb, :actor, :state, :at, :at)
            """.trimIndent()

        /** Every other schedule is outside the narrowed lens (`job:b`). */
        fun job(index: Int): String = if (index % 2 == 0) "a" else "b"

        /** A terminal state in another bucket than [state]'s — never active (one active run per schedule). */
        fun olderThan(state: RunState): RunState =
            if (LastRunOutcome.of(state) == LastRunOutcome.SUCCEEDED) RunState.FAILED else RunState.SUCCEEDED
    }
}
