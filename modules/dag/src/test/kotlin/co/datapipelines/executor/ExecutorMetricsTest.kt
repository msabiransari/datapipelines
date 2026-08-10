package co.datapipelines.executor

import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeSource
import co.datapipelines.pipeline.NodeType
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The executor's instruments, pinned against **observability.md §4.1** — the single authority for
 * metric names, tag keys and closed-set tag *values*.
 *
 * This exists because that authority is enforced by nothing else. A metric whose value drifts from
 * the catalogue fails silently and completely: the code is fine, the dashboard is fine, and the
 * alert written against the documented value simply matches nothing, forever. `outcome = "success"`
 * where the doc says `stored` was exactly that, and so was an uncatalogued `source` tag on
 * `nodes.rows_out`.
 */
class ExecutorMetricsTest {
    @Test
    fun `result_writes uses the catalogued outcome values, not success`() {
        val registry = SimpleMeterRegistry()
        val metrics = ExecutorMetrics(registry)

        metrics.resultWritten(ExecutorMetrics.OUTCOME_STORED, bytes = 512)
        metrics.resultWritten(ExecutorMetrics.OUTCOME_TOO_LARGE, bytes = 0)
        metrics.resultWritten(ExecutorMetrics.OUTCOME_STORAGE_UNAVAILABLE, bytes = 0)

        // observability §4.1: outcome ∈ {stored, too_large, storage_unavailable}.
        ExecutorMetrics.OUTCOME_STORED shouldBe "stored"
        ExecutorMetrics.OUTCOME_TOO_LARGE shouldBe "too_large"
        ExecutorMetrics.OUTCOME_STORAGE_UNAVAILABLE shouldBe "storage_unavailable"

        registry
            .find(ExecutorMetrics.RESULT_WRITES)
            .counters()
            .map { it.id.getTag("outcome") } shouldContainExactlyInAnyOrder
            listOf("stored", "too_large", "storage_unavailable")

        // Bytes are only counted for a write that stored something.
        registry
            .find(ExecutorMetrics.RESULT_BYTES_WRITTEN)
            .counter()
            .shouldNotBeNull()
            .count() shouldBe 512.0
    }

    @Test
    fun `nodes_duration carries source but nodes_rows_out does not`() {
        val registry = SimpleMeterRegistry()
        val metrics = ExecutorMetrics(registry)

        metrics.nodeFinished(UUID.randomUUID(), "fetch", NodeSource.Tempdb, Duration.ofMillis(5), rowsOut = 7)

        // observability §4.1 gives the two instruments DIFFERENT tag sets on purpose.
        registry
            .find(ExecutorMetrics.NODES_DURATION)
            .timer()
            .shouldNotBeNull()
            .id.tags
            .map { it.key } shouldContainExactlyInAnyOrder listOf("pipeline_id", "node_id", "source")
        registry
            .find(ExecutorMetrics.NODES_ROWS_OUT)
            .counter()
            .shouldNotBeNull()
            .id.tags
            .map { it.key } shouldContainExactlyInAnyOrder listOf("pipeline_id", "node_id")
    }

    @Test
    fun `a not-measured row count never decrements the counter`() {
        val registry = SimpleMeterRegistry()
        val metrics = ExecutorMetrics(registry)

        metrics.nodeFinished(UUID.randomUUID(), "ddl", NodeSource.Tempdb, Duration.ofMillis(1), rowsOut = NodeResult.NOT_MEASURED)

        // `-1` is the §7.1 sentinel; publishing it would walk the counter backwards.
        registry.find(ExecutorMetrics.NODES_ROWS_OUT).counter() shouldBe null
    }

    /**
     * `staging.rows` counts **both** staging paths.
     *
     * The tempdb→tempdb `CREATE TABLE … AS` stages just as surely as `stage()` does — it simply
     * does it inside H2 rather than through a cursor. Counting only the cursor path left the metric
     * blind to the commonest multi-node shape, and a half-populated counter reads as a real number.
     */
    @Test
    fun `both staging paths feed staging_rows`() =
        runBlocking<Unit> {
            val registry = SimpleMeterRegistry()
            val source = h2Datasource("m_src", listOf("CREATE TABLE m (n INT)", "INSERT INTO m VALUES (1), (2), (3)"))
            val nodes =
                listOf(
                    // cursor path: datasource -> tempdb via staging.stage
                    Fixtures.node("fetch", source = "m_src", output = NodeOutput.Tempdb("stg")),
                    // CTAS path: tempdb -> tempdb via withConnection
                    Fixtures.node("derive", output = NodeOutput.Tempdb("derived"), dependsOn = listOf("fetch")),
                )
            val sql = mapOf("fetch" to "SELECT n FROM m", "derive" to "SELECT n FROM stg WHERE n > 1")

            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(sql),
                registry = FakeDatasourceRegistry(mapOf("m_src" to source)),
                metrics = ExecutorMetrics(registry),
            ).use { h ->
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS

                // 3 staged by the cursor path + 2 by the CTAS path. Asserting the sum rather than
                // either half is what makes a regression on one path visible.
                registry
                    .find(ExecutorMetrics.STAGING_ROWS)
                    .counter()
                    .shouldNotBeNull()
                    .count() shouldBe 5.0
            }
        }

    @Test
    fun `a DDL node stages nothing and leaves staging_rows alone`() =
        runBlocking<Unit> {
            val registry = SimpleMeterRegistry()
            val nodes = listOf(Fixtures.node("mk", type = NodeType.DDL))

            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("mk" to """CREATE TABLE "z" (n INT)""")),
                metrics = ExecutorMetrics(registry),
            ).use { h ->
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS

                registry.find(ExecutorMetrics.STAGING_ROWS).counter()?.count() ?: 0.0 shouldBe 0.0
            }
        }

    @Test
    fun `execution counters use the catalogued status and reason values`() {
        val registry = SimpleMeterRegistry()
        val metrics = ExecutorMetrics(registry)
        val pipelineId = UUID.randomUUID()

        ExecutionStatus.entries.forEach { metrics.executionFinished(pipelineId, it, Duration.ofMillis(1)) }
        AbortReason.entries.forEach { metrics.executionAborted(it) }

        registry
            .find(ExecutorMetrics.EXECUTIONS_TOTAL)
            .counters()
            .map { it.id.getTag("status") } shouldContainExactlyInAnyOrder listOf("running", "success", "failed", "aborted")
        // observability §4.1: reason ∈ {client_disconnect, cancelled, shutdown} — the wire values.
        registry
            .find(ExecutorMetrics.EXECUTIONS_ABORTED)
            .counters()
            .map { it.id.getTag("reason") } shouldContainExactlyInAnyOrder listOf("client_disconnect", "cancelled", "shutdown")
        registry
            .find(ExecutorMetrics.EXECUTIONS_DURATION)
            .timer()
            .shouldNotBeNull()
            .totalTime(TimeUnit.MILLISECONDS) shouldBeGreaterThan 0.0
    }
}
