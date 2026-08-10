package co.datapipelines.executor

import co.datapipelines.pipeline.NodeSource
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.util.UUID

/**
 * The executor's Micrometer instruments (dag-executor.md §15.3).
 *
 * Names and tag sets are normative in [Observability §4](../../../../../../../docs/observability.md);
 * nothing new is invented here. `pipeline_id` and `node_id` are deliberately bounded-cardinality
 * dimensions (a deployment has tens of pipelines, not millions), which is why the observability
 * spec allows them where it forbids per-execution tags.
 */
class ExecutorMetrics(
    private val registry: MeterRegistry,
) {
    /** `datapipelines.executions.total{status,pipeline_id}` + the duration timer. */
    fun executionFinished(
        pipelineId: UUID,
        status: ExecutionStatus,
        duration: Duration,
    ) {
        registry
            .counter(EXECUTIONS_TOTAL, "status", status.name.lowercase(), "pipeline_id", pipelineId.toString())
            .increment()
        registry.timer(EXECUTIONS_DURATION, "pipeline_id", pipelineId.toString()).record(duration)
    }

    /** `datapipelines.executions.aborted{reason}` — one per cancellation, by trigger. */
    fun executionAborted(reason: AbortReason) {
        registry.counter(EXECUTIONS_ABORTED, "reason", reason.wire).increment()
    }

    /**
     * `datapipelines.nodes.duration{pipeline_id,node_id,source}` + `datapipelines.nodes.rows_out`.
     *
     * The `source` tag is observability §4.1's, which the orchestrator adjudicated as winning over
     * dag-executor §15.3's shorter set (F10). It is bounded-cardinality by construction — the
     * literal `tempdb` or a registered datasource name, of which a deployment has tens.
     */
    fun nodeFinished(
        pipelineId: UUID,
        nodeId: String,
        source: NodeSource,
        duration: Duration,
        rowsOut: Long,
    ) {
        val node = Tags.of(Tag.of("pipeline_id", pipelineId.toString()), Tag.of("node_id", nodeId))
        // The two instruments carry DIFFERENT tag sets, and that is the catalogue's choice, not an
        // oversight: observability §4.1 gives `nodes.duration` a `source` tag and `nodes.rows_out`
        // only `pipeline_id`/`node_id`. Reusing one tag set for both would silently add an
        // uncatalogued dimension to the counter — the same drift class as an uncatalogued value.
        registry.timer(NODES_DURATION, node.and(Tag.of("source", source.wire))).record(duration)
        // Only real counts are published: `-1` is the §7.1 "not measured" sentinel, and adding it
        // to a counter would quietly walk `nodes.rows_out` backwards.
        if (rowsOut > 0) registry.counter(NODES_ROWS_OUT, node).increment(rowsOut.toDouble())
    }

    /**
     * The result-store instruments D9 asks for: bytes written and one outcome-tagged write counter.
     *
     * `outcome` is `success` | `too_large` | `storage_unavailable` — the three ways
     * `ResultStore.materialize` can end, so a dashboard can tell "results are being rejected for
     * size" from "Redis is down" without reading logs.
     */
    fun resultWritten(
        outcome: String,
        bytes: Long,
    ) {
        registry.counter(RESULT_WRITES, "outcome", outcome).increment()
        if (bytes > 0) registry.counter(RESULT_BYTES_WRITTEN).increment(bytes.toDouble())
    }

    /** `datapipelines.staging.rows` — total rows staged across all executions. */
    fun rowsStaged(rows: Long) {
        registry.counter(STAGING_ROWS).increment(rows.toDouble())
    }

    /** Binds `datapipelines.executions.concurrent` to the live slot count. */
    fun bindConcurrency(slots: ExecutionSlots) {
        Gauge
            .builder(EXECUTIONS_CONCURRENT, slots) { it.inFlight.toDouble() }
            .description("Executions currently holding an execution slot")
            .register(registry)
    }

    companion object {
        const val EXECUTIONS_TOTAL = "datapipelines.executions.total"
        const val EXECUTIONS_DURATION = "datapipelines.executions.duration"
        const val EXECUTIONS_CONCURRENT = "datapipelines.executions.concurrent"
        const val EXECUTIONS_ABORTED = "datapipelines.executions.aborted"
        const val NODES_DURATION = "datapipelines.nodes.duration"
        const val NODES_ROWS_OUT = "datapipelines.nodes.rows_out"
        const val STAGING_ROWS = "datapipelines.staging.rows"
        const val RESULT_BYTES_WRITTEN = "datapipelines.result.bytes_written"
        const val RESULT_WRITES = "datapipelines.result.writes"

        /**
         * `outcome` tag values for [resultWritten].
         *
         * `stored`, not `success`: observability.md §4.1 is the single authority for metric tag
         * values and catalogues `result.writes{outcome}` as `stored`/`too_large`/
         * `storage_unavailable`. A dashboard or alert written against the doc would have matched
         * nothing.
         */
        const val OUTCOME_STORED = "stored"
        const val OUTCOME_TOO_LARGE = "too_large"
        const val OUTCOME_STORAGE_UNAVAILABLE = "storage_unavailable"

        /**
         * A registry that records into memory and exports nothing — the default when `app` has
         * not wired a real one, and what unit tests assert against.
         */
        fun inMemory(): ExecutorMetrics = ExecutorMetrics(SimpleMeterRegistry())
    }
}
