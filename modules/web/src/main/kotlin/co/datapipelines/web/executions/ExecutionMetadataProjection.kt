package co.datapipelines.web.executions

import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.pipeline.PipelineRepository
import java.util.UUID

/**
 * The §10.2 execution-metadata projection — ONE spelling for every reader of
 * `GET …/executions/{id}`: the framework route ([ExecutionsController]) and the business-path
 * paging route (keys v2 A16/B3, [co.datapipelines.web.endpoints.PublishedExecutionPagingService]).
 * Extracted from the controller the day a second reader appeared, because two projections of
 * the same record is exactly the drift the cursor's visibility rule was extracted to prevent.
 */
class ExecutionMetadataProjection(
    private val executions: PipelineRepository,
    private val resultStore: ResultStore,
    private val resultUrls: ResultUrlFactory,
) {
    /** The §10.2 projection for ONE record, its draft marker computed here. */
    fun project(
        record: ExecutionRecord,
        workspaceId: UUID,
        includeResult: Boolean,
    ): Map<String, Any?> {
        val releasedAt = executions.releasedAtFor(workspaceId, listOf(record.pipelineId to record.pipelineVersion))
        return project(record, draftRun(record, releasedAt), includeResult)
    }

    /** The §10.2 projection with a precomputed draft marker — the listing's bulk read. */
    fun project(
        record: ExecutionRecord,
        draftRun: Boolean,
        includeResult: Boolean,
    ): Map<String, Any?> = record.toMetadata(includeResult, draftRun)

    /** versioning §8: draft run ⇔ `started_at < released_at`, or no `released_at` at all. */
    fun draftRun(
        record: ExecutionRecord,
        releasedAt: Map<Pair<UUID, Int>, java.time.Instant?>,
    ): Boolean {
        val at = releasedAt[record.pipelineId to record.pipelineVersion]
        return at == null || record.startedAt.isBefore(at)
    }

    private fun ExecutionRecord.toMetadata(
        includeResult: Boolean,
        draftRun: Boolean,
    ): Map<String, Any?> =
        buildMap {
            put("execution_id", executionId.toString())
            put("pipeline_id", pipelineId.toString())
            put("pipeline_version", pipelineVersion)
            put("status", status.name)
            // The §8 draft marker: this execution ran a version that was a draft at the time
            // (or still is / was discarded). Informational — a label in history, nothing more.
            put("draft_run", draftRun)
            put("parameters", ExecutorJson.mapper.readTree(parametersJson))
            put("started_at", startedAt.toString())
            put("completed_at", completedAt?.toString())
            put("duration_ms", durationMs)
            put("node_stats", nodeStatsJson?.let { ExecutorJson.mapper.readTree(it) })
            put("error", errorJson?.let { ExecutorJson.mapper.readTree(it) })
            put("failed_node_id", failedNodeId)
            put("correlation_id", correlationId?.toString())
            // D11 (2026-09-20, rest-api §10.2): `executed_by` replaced `triggered_by`; the key kind
            // says whether a key ran it and which kind (null = a signed-in session).
            put("executed_by", executedBy.toString())
            put("executed_by_key_kind", executedByKeyKind?.wire)
            put("triggered_via", triggeredVia.name)
            put("result_row_count", resultRowCount)
            put("result_size_bytes", resultSizeBytes)
            // The composition lineage V3 added (metadata-db §4.6). The history UI renders it and
            // the repository has always selected it; only this projection dropped it, so an API
            // client could see a child execution but never learn it was one (T1). Null parents mark
            // a root; `root_execution_id` is NOT NULL since V3's backfill and equals `execution_id`
            // for a root, so `?root_execution_id=` grouping needs no special case.
            put("parent_execution_id", parentExecutionId?.toString())
            put("parent_node_id", parentNodeId)
            put("root_execution_id", rootExecutionId?.toString())
            if (includeResult && status == ExecutionStatus.SUCCESS && resultRowCount != null) {
                // Present only while the result is actually fetchable (§10.2).
                resultStore.describe(resultStore.keyFor(executionId))?.let { view ->
                    put("result_url", resultUrls.urlFor(executionId))
                    put("result_expires_at", view.expiresAt.toString())
                }
            }
        }
}
