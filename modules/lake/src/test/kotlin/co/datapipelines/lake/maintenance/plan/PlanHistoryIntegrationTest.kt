package co.datapipelines.lake.maintenance.plan

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.PartRecord
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames.CLEANUP
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames.COMPACTION
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames.MANIFEST_REWRITE
import co.datapipelines.lake.maintenance.plan.PlanHarness.FileSpec
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * N5a (brief 959 D5): the run-history projection, on runs persisted in 957's wire shape (`parts_json` as
 * `MaintenanceRunRepository.partsJson` writes it, plus 969's `committed` and `left_for_fresh_run` on compaction's
 * `capacity` and `deadline` entries). One statement selects three values per table; only FINISHED runs and ATTEMPTED
 * entries count.
 */
class PlanHistoryIntegrationTest {
    private val h = PlanHarness()
    private val mapper = JsonMapper.builder().build()

    @BeforeEach
    fun clean() = h.reset()

    @AfterEach
    fun close() = h.close()

    private data class Entry(
        val name: String,
        val outcome: MaintenanceOutcome,
        val attempted: Boolean = true,
        val committed: Boolean? = null,
        val left: Long? = null,
    )

    /** One run row as 957 writes it, ended [endedAgo] before [now], in [state]. Answers its end. */
    private fun run(
        table: MaintenanceTable,
        now: Instant,
        endedAgo: Duration,
        state: String,
        vararg entries: Entry,
    ): Instant {
        val parts = mapper.readTree(h.m.store.partsJson(entries.map { PartRecord(it.name, it.outcome, it.attempted) })) as ArrayNode
        entries.forEachIndexed { i, e ->
            val node = parts[i] as ObjectNode
            e.committed?.let { node.put("committed", it) }
            e.left?.let { node.put("left_for_fresh_run", it) }
        }
        val ended = now.minus(endedAgo)
        LakeTestDb.jdbc.update(
            "INSERT INTO lake_maintenance_runs (id, table_id, kind, state, deadline_at, parts_json, started_at, ended_at) " +
                "VALUES (?, ?, 'maintenance', ?, ?, CAST(? AS jsonb), ?, ?)",
            UUID.randomUUID(),
            table.id,
            state,
            Timestamp.from(ended),
            mapper.writeValueAsString(parts),
            Timestamp.from(ended.minus(Duration.ofMinutes(RUN_MINUTES))),
            Timestamp.from(ended),
        )
        return ended
    }

    private fun hours(n: Long): Duration = Duration.ofHours(n)

    /**
     * Measured under the whole-day window (development), five small files: compaction work the window never holds. The
     * assignment and the profile's save are dated a day back, BEFORE every seeded run, so neither lifts the wait.
     */
    private fun measuredTable(name: String): MaintenanceTable {
        val table = h.table(name)
        h.assign(table, "development")
        LakeTestDb.jdbc.update("UPDATE lake_tables_managed SET profile_assigned_at = now() - interval '1 day' WHERE id = ?", table.id)
        LakeTestDb.jdbc.update("UPDATE lake_profiles SET updated_at = now() - interval '1 day' WHERE name = 'development'")
        h.append(table, *Array(5) { FileSpec("2026-11-01", 1_048_576) })
        return table
    }

    @Test
    fun `N5a - the one statement selects exactly three values per table, and the rank and wait follow from them`() {
        val t = measuredTable("t")
        val u = measuredTable("u")
        val v = measuredTable("v")
        h.pass(h.planner())
        val now = h.repository.catalogNow()

        // T: every shape the brief lists, newest last
        val rewriteAndCapacity =
            run(t, now, hours(9), "finished", Entry(MANIFEST_REWRITE, MaintenanceOutcome.DONE), Entry(COMPACTION, MaintenanceOutcome.CAPACITY, committed = true, left = 0))
        run(t, now, hours(8), "finished", Entry(COMPACTION, MaintenanceOutcome.CAPACITY, committed = false, left = 0))
        val leftOne = run(t, now, hours(7), "finished", Entry(COMPACTION, MaintenanceOutcome.CAPACITY, committed = false, left = 1))
        val cleanup =
            run(
                t,
                now,
                hours(6),
                "finished",
                Entry(CLEANUP, MaintenanceOutcome.DONE),
                Entry(COMPACTION, MaintenanceOutcome.DEADLINE, attempted = false, committed = false, left = 0),
            )
        run(t, now, hours(5), "finished", Entry(CLEANUP, MaintenanceOutcome.BUSY), Entry(COMPACTION, MaintenanceOutcome.BUSY))
        listOf("failed" to hours(4), "abandoned" to hours(3)).forEach { (state, ago) ->
            run(
                t,
                now,
                ago,
                state,
                Entry(CLEANUP, MaintenanceOutcome.DONE),
                Entry(MANIFEST_REWRITE, MaintenanceOutcome.DONE),
                Entry(COMPACTION, MaintenanceOutcome.CAPACITY, committed = false, left = 0),
            )
        }
        // U: the latest attempt is `capacity`, nothing committed, nothing left: the long wait
        run(u, now, hours(2), "finished", Entry(CLEANUP, MaintenanceOutcome.DONE), Entry(COMPACTION, MaintenanceOutcome.CAPACITY, committed = false, left = 0))
        // V: a later finished rewrite than T's, a cleanup in the same run
        val vRewrite = run(v, now, hours(1), "finished", Entry(CLEANUP, MaintenanceOutcome.NOTHING_TO_DO), Entry(COMPACTION, MaintenanceOutcome.DONE))

        val inputs = h.repository.decisionInputs().associateBy { it.tableId }
        inputs.getValue(t.id).history shouldBe
            RunHistory(
                lastCleanup = cleanup,
                lastFinishedRewrite = rewriteAndCapacity,
                lastRewriteAttempt = RewriteAttempt(leftOne, MaintenanceOutcome.CAPACITY, committed = false, leftForFreshRun = 1),
            )
        inputs.getValue(u.id).history.lastRewriteAttempt shouldBe
            RewriteAttempt(now.minus(hours(2)), MaintenanceOutcome.CAPACITY, committed = false, leftForFreshRun = 0)
        inputs.getValue(v.id).history shouldBe RunHistory(vRewrite, vRewrite, RewriteAttempt(vRewrite, MaintenanceOutcome.DONE, null, null))

        val plan = MaintenanceDue(PlanHarness.GRACE).plan(inputs.values.toList(), PlanFixtures.ALL_PARTS, now)
        val answers = plan.tables.associateBy { it.tableId }
        // the L18 mixed case is due at the next plan; U waits; equal scores rank the older finished rewrite first
        answers.getValue(t.id).due shouldBe setOf(COMPACTION)
        answers.getValue(u.id).notDue[COMPACTION] shouldBe NotDueReason.CAPACITY_WAIT
        plan.order shouldContainExactly listOf(t.id, v.id)
    }

    /**
     * The `EXPLAIN` of the plan's run-history statement over 10,000 run rows (the handback's evidence): written to
     * `build/959-decision-explain.txt`. The statement must still answer one row per table.
     */
    @Test
    fun `the run-history statement over 10,000 run rows - its plan is recorded`() {
        val tables = (1..TABLES).map { h.table("e$it") }
        val now = h.repository.catalogNow()
        tables.forEach { table ->
            LakeTestDb.jdbc.update(
                "INSERT INTO lake_maintenance_runs (id, table_id, kind, state, deadline_at, parts_json, started_at, ended_at) " +
                    "SELECT gen_random_uuid(), ?, 'maintenance', CASE WHEN g % 10 = 0 THEN 'failed' ELSE 'finished' END, ?, " +
                    "CAST(? AS jsonb), CAST(? AS timestamptz) - make_interval(mins => g + 1), CAST(? AS timestamptz) - make_interval(mins => g) " +
                    "FROM generate_series(1, ?) g",
                table.id,
                Timestamp.from(now),
                SAMPLE_PARTS,
                Timestamp.from(now),
                Timestamp.from(now),
                RUNS / TABLES,
            )
        }
        LakeTestDb.jdbc.execute("ANALYZE lake_maintenance_runs")
        LakeTestDb.jdbc.queryForObject("SELECT count(*) FROM lake_maintenance_runs", Int::class.java) shouldBe RUNS

        val plan =
            h.named.query("EXPLAIN (ANALYZE, BUFFERS, COSTS) " + PlanRepository.DECISION_SQL, mapOf("catalogPurged" to PlanStatus.PURGED)) { rs, _ ->
                rs.getString(1)
            }
        File("build/959-decision-explain.txt").writeText(plan.joinToString("\n"))
        h.repository.decisionInputs().size shouldBe TABLES
    }

    private companion object {
        const val RUN_MINUTES = 10L
        const val TABLES = 10
        const val RUNS = 10_000
        const val SAMPLE_PARTS =
            """[{"name":"cleanup","outcome":"done","attempted":true},{"name":"manifest_rewrite","outcome":"nothing_to_do","attempted":true},""" +
                """{"name":"compaction","outcome":"capacity","attempted":true,"committed":false,"left_for_fresh_run":0}]"""
    }
}
