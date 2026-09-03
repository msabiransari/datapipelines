package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.DatasourceRef
import co.datapipelines.pipeline.PipelineRepository
import java.util.UUID

/**
 * The datasource delete guard's reference scan — `datasources`' port over the pipeline table.
 *
 * It used to carry the pipelines LISTING too; 056 moved that to `PipelineService.list`, where
 * REST, MCP and both UI screens share one copy of the owner/datasource/`q` rules (ARCH-AUDIT
 * S2/D2). What is left is the one question that is not a listing: *what would this delete break*.
 *
 * The entry point takes the workspace explicitly (design §5) — a reference scan sees exactly one
 * workspace's pipelines, and the caller loops the workspaces it means to cover.
 */
class PipelineBodies(
    private val repository: PipelineRepository,
) {
    /**
     * The `datasource.in_use` delete guard's scan (061/T79): every node of every stored
     * version — not just `current_version` — of every live pipeline in the workspace that
     * references [datasourceName].
     *
     * `PipelineService.list` keeps the working-version scan
     * ([PipelineRepository.findAllByDatasource]); this is the second, deliberately different
     * one. Two questions, two scans, exactly as 040 split them for templates.
     */
    fun anyVersionReferences(
        workspaceId: UUID,
        datasourceName: String,
    ): List<DatasourceRef> = repository.findAnyVersionDatasourceRefs(workspaceId, datasourceName)
}
