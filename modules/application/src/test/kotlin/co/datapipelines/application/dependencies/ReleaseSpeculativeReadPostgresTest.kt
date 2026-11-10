package co.datapipelines.application.dependencies

import co.datapipelines.application.SharedPostgres
import co.datapipelines.pipeline.release.DependencySource
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseClosure
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseGraph
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.util.UUID

/**
 * #868 R1, round 3, on a REAL PostgreSQL: a speculative read whose statement fails never aborts the transaction the walk
 * reads in, so it never replaces the old walk's first refusal — and a failure the old walk reaches still propagates.
 *
 * Every case is walked by the old [ReleaseGraph] and by the production [ReleasePlanningWalk] (its production
 * [ReleasePlanningWalk.speculativeReads] fence) through BOTH entry points, each in the transaction it runs in in
 * production: [ReleasePlanningWalk.plan] in its own planning transaction (the old walk in the same planning template),
 * and [ReleasePlanningWalk.walkInCurrentTransaction] inside an ordinary caller-owned write transaction, as the
 * coordinator's guarded re-walk (the old walk inside the same kind of caller transaction).
 *
 * Every source read executes real statements on the walk's transaction connection: the broken version `SELECT 1 / 0`
 * (SQLSTATE 22012), every other version `SELECT 1` and a read of its transaction's isolation and snapshot. Compared, old
 * to new: the rendered outcome (closure, or refusal code/message/details, or the exception's class and message), and in
 * the caller transaction whether it is still usable after the walk. Asserted on the new walk: the failing statement
 * really ran (22012 observed), no statement met an aborted transaction (no 25P02), and every planning read saw ONE
 * snapshot although another session committed mid-walk.
 */
class ReleaseSpeculativeReadPostgresTest {
    private val dataSource = SharedPostgres.dataSource()
    private val manager = DataSourceTransactionManager(dataSource)
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `a failing speculative sibling in the needed read's own call never overtakes the cycle - root and child pin each other`() {
        val cases =
            differential { g ->
                val root = g.id("root", 1, ReleaseFamily.PIPELINE)
                val child = g.node(g.id("child", 1, ReleaseFamily.PIPELINE), ReleaseNodeStatus.DRAFT, root to "nodes[0].pipeline")
                g.node(root, ReleaseNodeStatus.DRAFT, child to "nodes[0].pipeline", BROKEN_PIPELINE to "nodes[1].pipeline")
            }
        cases.forEach { (entry, run) ->
            withClue(entry) {
                run.old.outcome shouldContainCode "pipeline.release.dependency_cycle"
                run.old.reads.map { it.entityId } shouldContainExactly listOf("root", "child")
                // Non-vacuity: the new walk's speculative statement really failed in the database, and the needed request's
                // re-read after it (child, read twice) ran in a usable transaction.
                run.new.reads.map { it.entityId } shouldContainExactly listOf("root", "child", "broken", "child")
                run.new.sqlStates shouldContainExactly listOf(DIVISION_BY_ZERO)
            }
        }
    }

    @Test
    fun `a failing speculative-only call never aborts the transaction - the needed read after it and the cycle are the old walk's`() {
        val cases =
            differential { g ->
                val root = g.id("root", 1, ReleaseFamily.PIPELINE)
                val leaf = g.node(g.id("leaf", 1), ReleaseNodeStatus.RELEASED)
                val child =
                    g.node(
                        g.id("child", 1, ReleaseFamily.PIPELINE),
                        ReleaseNodeStatus.DRAFT,
                        leaf to "nodes[0].template",
                        root to "nodes[1].pipeline",
                    )
                g.node(root, ReleaseNodeStatus.DRAFT, child to "nodes[0].pipeline", BROKEN to "nodes[1].template")
            }
        cases.forEach { (entry, run) ->
            withClue(entry) {
                run.old.outcome shouldContainCode "pipeline.release.dependency_cycle"
                run.old.reads.map { it.entityId } shouldContainExactly listOf("root", "child", "leaf")
                run.new.reads.map { it.entityId } shouldContainExactly listOf("root", "child", "broken", "leaf")
                run.new.sqlStates shouldContainExactly listOf(DIVISION_BY_ZERO)
                // The needed read after the forgiven failure executed: leaf's statements returned.
                run.new.executed.map { it.entityId } shouldContainExactly listOf("root", "child", "leaf")
            }
        }
        val planned = cases.getValue(PLAN).new
        withClue("the planning snapshot is ONE snapshot across the savepoint rollback, though another session committed") {
            planned.isolations.distinct() shouldContainExactly listOf("repeatable read")
            planned.snapshots.distinct().size shouldBe 1
            planned.snapshotAfter shouldNotBe planned.snapshots.first()
        }
        val guarded = cases.getValue(GUARDED).new
        guarded.isolations.distinct() shouldContainExactly listOf("read committed")
    }

    @Test
    fun `control - a SQL failure the old walk reaches propagates unchanged, directly and after a forgiven speculation`() {
        val direct =
            differential { g ->
                g.node(g.id("root", 1, ReleaseFamily.PIPELINE), ReleaseNodeStatus.DRAFT, BROKEN to "nodes[0].template")
            }
        direct.forEach { (entry, run) ->
            withClue(entry) {
                run.old.outcome shouldStartWith "org.springframework.dao."
                run.old.sqlStates shouldContainExactly listOf(DIVISION_BY_ZERO)
                run.new.sqlStates shouldContainExactly listOf(DIVISION_BY_ZERO)
            }
        }
        // The old walk's own failure aborts the caller's transaction; the new walk's does exactly the same.
        direct.getValue(GUARDED).old.callerAfter shouldBe IN_FAILED_TRANSACTION

        val later =
            differential { g ->
                val a = g.node(g.id("a", 1), ReleaseNodeStatus.RELEASED)
                val root = g.id("root", 1, ReleaseFamily.PIPELINE)
                g.node(root, ReleaseNodeStatus.DRAFT, a to "nodes[0].template", BROKEN to "nodes[1].template")
            }
        later.forEach { (entry, run) ->
            withClue(entry) {
                run.old.outcome shouldStartWith "org.springframework.dao."
                run.old.reads.map { it.entityId } shouldContainExactly listOf("root", "a", "broken")
                // a's call carried broken and failed (rolled back); a alone, then broken alone — the old walk's failure.
                run.new.reads.map { it.entityId } shouldContainExactly listOf("root", "a", "broken", "a", "broken")
                run.new.sqlStates shouldContainExactly listOf(DIVISION_BY_ZERO, DIVISION_BY_ZERO)
            }
        }
    }

    @Test
    fun `the savepoint fence refuses to run outside a transaction - NESTED would begin a transaction of its own`() {
        var ran = false
        shouldThrow<IllegalStateException> { ReleasePlanningWalk.speculativeReads(manager).isolate { ran = true } }.message shouldBe
            "a speculative release read is fenced by a savepoint, so it runs only inside a transaction"
        ran shouldBe false
    }

    // ---- the differential ---------------------------------------------------------------------------------------

    /** One walk's observations: its rendered outcome and every statement fact its sources recorded. */
    private class Observed(
        val outcome: String,
        val reads: List<ReleaseNodeIdentity>,
        val executed: List<ReleaseNodeIdentity>,
        val sqlStates: List<String>,
        val isolations: List<String>,
        val snapshots: List<String>,
        val snapshotAfter: String?,
        val callerAfter: String?,
    )

    private class Run(
        val old: Observed,
        val new: Observed,
    )

    /**
     * Walks [build]'s graph four times on fresh sources — old and new, through the planning path and the guarded path —
     * and asserts, per entry point, the shared contract: equal outcome, equal caller state, the new walk's reads a
     * superset of the old walk's, and no statement in either walk met an aborted transaction.
     */
    private fun differential(build: (GoldenGraph) -> ReleaseNodeIdentity): Map<String, Run> {
        val runs =
            mapOf(
                PLAN to
                    Run(
                        planned(build) { sources, root ->
                            ReleasePlanningWalk.planningTransaction(manager).execute { ReleaseGraph(sources, BOUNDS).walk(root) }
                        },
                        planned(build) { sources, root -> newWalk(sources).plan(root, ACTOR) },
                    ),
                GUARDED to
                    Run(
                        guarded(build) { sources, root -> ReleaseGraph(sources, BOUNDS).walk(root) },
                        guarded(build) { sources, root -> newWalk(sources).walkInCurrentTransaction(root, ACTOR) },
                    ),
            )
        runs.forEach { (entry, run) ->
            withClue("$entry: the new walk answers what the old walk answered, on a real database") {
                run.new.outcome shouldBe run.old.outcome
                run.new.callerAfter shouldBe run.old.callerAfter
                run.new.reads shouldContainAll run.old.reads
                run.old.sqlStates shouldNotContain IN_FAILED_TRANSACTION
                run.new.sqlStates shouldNotContain IN_FAILED_TRANSACTION
            }
        }
        runs.getValue(PLAN).new.reads shouldBe runs.getValue(GUARDED).new.reads
        return runs
    }

    /**
     * The planning path: [walk] opens its planning transaction itself — the old walk inside the production planning
     * template, the new one through [ReleasePlanningWalk.plan].
     */
    private fun planned(
        build: (GoldenGraph) -> ReleaseNodeIdentity,
        walk: (Map<ReleaseFamily, DependencySource>, ReleaseNodeIdentity) -> ReleaseClosure?,
    ): Observed {
        val db = Database(build)
        val outcome = db.render { root -> walk(db.sources, root) }
        return db.observed(outcome, snapshotAfter = currentSnapshot(), callerAfter = null)
    }

    /** The guarded path: both walks inside one ordinary caller-owned write transaction, as the coordinator holds it. */
    private fun guarded(
        build: (GoldenGraph) -> ReleaseNodeIdentity,
        walk: (Map<ReleaseFamily, DependencySource>, ReleaseNodeIdentity) -> ReleaseClosure?,
    ): Observed {
        val db = Database(build)
        var outcome = ""
        var after: String? = null
        TransactionTemplate(manager).executeWithoutResult { status ->
            outcome = db.render { root -> walk(db.sources, root) }
            // Is the caller's transaction still usable after the walk? Its next statement answers.
            after = statement("SELECT 'usable'")
            status.setRollbackOnly() // the test writes nothing; never commit a transaction the walk may have aborted
        }
        return db.observed(outcome, snapshotAfter = null, callerAfter = after)
    }

    private fun newWalk(sources: Map<ReleaseFamily, DependencySource>): ReleasePlanningWalk =
        ReleasePlanningWalk(
            ReleaseDependencyFamilies.registry(sources),
            BOUNDS,
            ReleasePlanningWalk.planningTransaction(manager),
            ReleasePlanningWalk.speculativeReads(manager),
            ReleasePlanningDeadline.NONE,
        )

    /** [sql]'s single text value, or the SQLSTATE it failed with. */
    private fun statement(sql: String): String =
        try {
            jdbc.queryForObject(sql, String::class.java) ?: "null"
        } catch (failure: DataAccessException) {
            sqlState(failure)
        }

    /** A snapshot taken by a fresh session after the walk: differs from the walk's when another session committed. */
    private fun currentSnapshot(): String = statement("SELECT pg_current_snapshot()::text")

    /**
     * A fresh graph whose sources run real statements on the walk's transaction connection and record what happened. The
     * first read also lets ANOTHER session commit a transaction, so a snapshot taken later would differ.
     */
    private inner class Database(
        build: (GoldenGraph) -> ReleaseNodeIdentity,
    ) {
        private val graph = GoldenGraph()
        private val root = build(graph)
        private val source = graph.source()
        val reads = ArrayList<ReleaseNodeIdentity>()
        val executed = ArrayList<ReleaseNodeIdentity>()
        val sqlStates = ArrayList<String>()
        val isolations = ArrayList<String>()
        val snapshots = ArrayList<String>()

        val sources: Map<ReleaseFamily, DependencySource> =
            ReleaseFamily.entries.associateWith {
                DependencySource { identity ->
                    reads += identity
                    check(TransactionSynchronizationManager.isActualTransactionActive()) { "a release read outside a transaction" }
                    try {
                        jdbc.queryForObject(if (identity.entityId == BROKEN.entityId) "SELECT 1 / 0" else "SELECT 1", Int::class.java)
                        val state =
                            jdbc.queryForObject(
                                "SELECT current_setting('transaction_isolation') || '|' || pg_current_snapshot()::text",
                                String::class.java,
                            )
                        requireNotNull(state).split('|').let { (isolation, snapshot) ->
                            isolations += isolation
                            snapshots += snapshot
                        }
                    } catch (failure: DataAccessException) {
                        sqlStates += sqlState(failure)
                        throw failure
                    }
                    if (executed.isEmpty()) commitElsewhere()
                    executed += identity
                    source.resolve(identity)
                }
            }

        fun render(walk: (ReleaseNodeIdentity) -> ReleaseClosure?): String =
            try {
                ReleaseGoldens.render(GoldenCase("postgres", graph, root, BOUNDS, sources), walk)
            } catch (failure: DataAccessException) {
                "${failure.javaClass.name}: ${failure.message}"
            }

        fun observed(
            outcome: String,
            snapshotAfter: String?,
            callerAfter: String?,
        ) = Observed(
            outcome = outcome,
            reads = reads.toList(),
            executed = executed.toList(),
            sqlStates = sqlStates.toList(),
            isolations = isolations.toList(),
            snapshots = snapshots.toList(),
            snapshotAfter = snapshotAfter,
            callerAfter = callerAfter,
        )

        /** Another session (its own physical connection, autocommit) commits a transaction that takes a transaction id. */
        private fun commitElsewhere() {
            dataSource.connection.use { connection ->
                connection.autoCommit = true
                connection.createStatement().use { it.execute("SELECT pg_current_xact_id()") }
            }
        }
    }

    private infix fun String.shouldContainCode(code: String) {
        withClue(this) { ("\"code\" : \"$code\"" in this) shouldBe true }
    }

    private companion object {
        val BOUNDS = ReleaseBounds()
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-00000000a868")
        private val WS: UUID = UUID.fromString("00000000-0000-0000-0000-000000000868")
        val BROKEN = ReleaseNodeIdentity(WS, ReleaseFamily.TEMPLATE, "broken", 1)
        val BROKEN_PIPELINE = ReleaseNodeIdentity(WS, ReleaseFamily.PIPELINE, "broken", 1)
        const val PLAN = "plan"
        const val GUARDED = "walkInCurrentTransaction"
        const val DIVISION_BY_ZERO = "22012"
        const val IN_FAILED_TRANSACTION = "25P02"

        /** The SQLSTATE of the SQLException Spring wrapped directly (the first in the cause chain). */
        fun sqlState(failure: DataAccessException): String =
            generateSequence(failure as Throwable) { it.cause }
                .filterIsInstance<SQLException>()
                .firstOrNull()
                ?.sqlState ?: "none"
    }
}
