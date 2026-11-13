package co.datapipelines.web.dependencies

import co.datapipelines.application.dependencies.PostgresStatementCeiling
import co.datapipelines.application.dependencies.StatementBoundReached
import co.datapipelines.dependencies.DeadlineExceeded
import co.datapipelines.dependencies.DependencyProvider
import co.datapipelines.dependencies.FamilyDescriptor
import co.datapipelines.dependencies.Lifecycle
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.Reference
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.dependencies.SelectorKind
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.config.RecordingTimeoutDataSource
import co.datapipelines.web.config.ReleaseDependencyProperties
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import java.time.Duration

/**
 * **What the tree walk sends to the metadata database** (#875 G4 §D) — the JDBC calls made, observed on a relaxed double
 * ([RecordingTimeoutDataSource]); this is NOT evidence of a PostgreSQL setting or of cancellation
 * (`DependenciesBlockedStatementE2eTest` is). The synthetic `test_widget` family issues ONE statement per `resolveMany`
 * call through the production [PostgresStatementCeiling], inside the production tree transaction
 * ([DependenciesConfiguration.treeTransaction]), under limits built by the production derivation
 * ([DependenciesConfiguration.treeLimits]) from [ReleaseDependencyProperties].
 *
 * The bound the production ceiling applies is `SET LOCAL statement_timeout` (`set_config('statement_timeout', '<n>ms',
 * true)`) on the statement's own connection, in whole MILLISECONDS rounded up and at least one, read again before each
 * statement — never the JDBC query timeout (every statement's `setQueryTimeout` stays unset). What it bounds a statement
 * by is `min(statement-timeout-seconds, the time left)`, which only a context built with the statement limit carries.
 */
class DependenciesStatementCountTest {
    private val recording = RecordingTimeoutDataSource(recordPlainStatements = true)
    private val ceiling = PostgresStatementCeiling(recording)
    private val clock = MovingTreeClock()
    private val stub = StubFamily(TEST_WIDGET)
    private val widgets = BoundedWidgets(stub, ceiling)

    private fun service(properties: ReleaseDependencyProperties = ReleaseDependencyProperties()) =
        treeService(
            families = emptyList(),
            rows = listOf(stubRow(TEST_WIDGET)),
            limits = DependenciesConfiguration.treeLimits(properties.toBounds(), properties),
            transactions = DependenciesConfiguration.treeTransaction(DataSourceTransactionManager(recording)),
            clock = clock,
            descriptors = listOf(widgets.descriptor()),
        )

    /** A root pinning [width] widgets: one frontier of [width] requests below the root's own. */
    private fun fanOut(width: Int) {
        val children = (1..width).map { exact(TEST_WIDGET, "acme/leaf_$it", 1) }
        children.forEach { stub.put(it) }
        stub.put(exact(TEST_WIDGET, ROOT, 1), Lifecycle.RELEASED, *children.toTypedArray())
    }

    @Test
    fun `a 300-wide frontier resolves in two batches of the configured 256, each statement bounded by the statement limit`() {
        fanOut(WIDTH)

        val tree = service().tree(member(), TEST_WIDGET, ROOT_QUERY)

        tree.nodes.size shouldBe WIDTH + 1
        tree.complete shouldBe true
        stub.calls.map { it.size } shouldBe listOf(1, 256, 44)
        withClue("one widget statement per resolveMany call: ${recording.firstLines()}") {
            widgetStatements().size shouldBe stub.calls.size
        }
        // A fixed clock: the time left is the whole 30 s, so every statement's bound is the 5 s statement limit.
        boundsSet() shouldBe List(stub.calls.size) { "5000ms" }
        withClue("the JDBC query timeout is not the mechanism: no statement calls setQueryTimeout") {
            recording.everyTimeout() shouldBe setOf(RecordingTimeoutDataSource.UNBOUNDED)
        }
    }

    @Test
    fun `a statement's bound is the time left when that is smaller, rounded up to whole milliseconds and never zero`() {
        fanOut(2)
        // metadata 5 s, statement 2 s. The root's read leaves 1.5004 s (→ 1501 ms, rounded UP); the leaves' read starts
        // with 0.4 ms left (→ 1 ms: below a millisecond is one, never PostgreSQL's unbounded 0).
        widgets.beforeStatement = { batch -> if (batch == 0) clock.advance(Duration.ofNanos(3_499_600_000L)) }
        widgets.afterStatement = { batch -> if (batch == 0) clock.advance(Duration.ofNanos(1_500_000_000L)) }

        val properties = ReleaseDependencyProperties(metadataTimeoutSeconds = 5, statementTimeoutSeconds = 2)
        service(properties).tree(member(), TEST_WIDGET, ROOT_QUERY)

        boundsSet() shouldBe listOf("1501ms", "1ms")
    }

    @Test
    fun `a malformed request sends no statement and calls no provider`() {
        fanOut(1)

        val refused = shouldThrow<DatapipelinesException> { service().tree(member(), TEST_WIDGET, "name=acme/root&version=0") }

        refused.code shouldBe PipelineErrorCodes.Dependencies.REQUEST_INVALID
        recording.statementCount() shouldBe 0
        stub.calls shouldBe emptyList()
    }

    @Test
    fun `a configured batch size of 2 splits the 300-wide frontier into 150 calls`() {
        fanOut(WIDTH)

        service(ReleaseDependencyProperties(maxResolveBatchSize = 2)).tree(member(), TEST_WIDGET, ROOT_QUERY)

        stub.calls.map { it.size } shouldBe listOf(1) + List(WIDTH / 2) { 2 }
        widgetStatements().size shouldBe 1 + WIDTH / 2
    }

    /** The widget SELECTs, in order. */
    private fun widgetStatements(): List<String> = recording.firstLines().filter { it.startsWith(WIDGET_SQL) }

    /**
     * The bound set for each widget statement: the production ceiling prepares the widget statement, then — in
     * `applyStatementSettings`, before it executes — one `set_config('statement_timeout', ?, true)` on the same connection,
     * so the SET recorded right after each widget statement carries that statement's bound.
     */
    private fun boundsSet(): List<String> {
        val trace = recording.trace()
        return trace.indices
            .filter { trace[it].first.startsWith(WIDGET_SQL) }
            .map { i ->
                val (sql, parameters) = trace[i + 1]
                withClue("the statement after widget statement $i is its bound: $sql") { sql.startsWith(SET_SQL) shouldBe true }
                parameters.single()
            }
    }

    /**
     * The synthetic family's provider: each call is ONE parameterised statement, run through the production ceiling with
     * the context's LIVE bound — the shape of G3's `BoundedGraphRead` (a spent bound is the walk's [DeadlineExceeded]).
     */
    private class BoundedWidgets(
        private val stub: StubFamily,
        private val ceiling: PostgresStatementCeiling,
    ) : DependencyProvider<List<Reference>> {
        var beforeStatement: (Int) -> Unit = {}
        var afterStatement: (Int) -> Unit = {}
        private var batches = 0

        override fun resolveMany(
            requests: List<ObjectRequest>,
            context: ResolveContext,
        ): Map<ObjectRequest, Outcome<List<Reference>>> {
            val batch = batches++
            beforeStatement(batch)
            try {
                ceiling.within(context::statementBound) {
                    ceiling.jdbc.jdbcTemplate.queryForList("$WIDGET_SQL = ?", String::class.java, requests.size)
                }
            } catch (reached: StatementBoundReached) {
                throw DeadlineExceeded(TEST_WIDGET).apply { initCause(reached) }
            }
            afterStatement(batch)
            return stub.resolveMany(requests, context)
        }

        override fun outgoing(resolved: Outcome.Resolved<List<Reference>>): List<Reference> = stub.outgoing(resolved)

        fun descriptor(): FamilyDescriptor<List<Reference>> =
            FamilyDescriptor(TEST_WIDGET, SelectorKind.entries.toSet(), stub.capabilities, 1, this, FamilyDescriptor.NO_SAFE_SUMMARY)
    }

    private companion object {
        const val WIDTH = 300
        const val ROOT = "acme/root"
        const val ROOT_QUERY = "name=$ROOT&version=1"
        const val WIDGET_SQL = "SELECT name FROM widgets WHERE batch_size"
        const val SET_SQL = "SELECT set_config('statement_timeout'"
    }
}
