package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.DatasourceRef
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import java.util.UUID

/**
 * Bounded access to the pipeline table for the listing and reference-scan paths.
 *
 * Datasource filtering is pushed down to SQL via [PipelineRepository.findAllByDatasource]; the
 * per-request memoization stopgap (carry-forward #6) is removed. The `q` search remains
 * in-memory because it matches across `name`, `display_name` and `description` columns.
 *
 * Every entry point takes the request's active workspace explicitly (design §5) — listings and
 * reference scans see exactly one workspace's pipelines.
 */
class PipelineBodies(
    private val repository: PipelineRepository,
) {
    fun scan(
        workspaceId: UUID,
        ownerId: UUID? = null,
        datasourceName: String? = null,
    ): Scan = Scan(repository, workspaceId, ownerId, datasourceName)

    /**
     * The `datasource.in_use` delete guard's scan (061/T79): every node of every stored
     * version — not just `current_version` — of every live pipeline in the workspace that
     * references [datasourceName].
     *
     * The listing filter above keeps the working-version scan
     * ([PipelineRepository.findAllByDatasource]); this is the second, deliberately different
     * one. Two questions, two scans, exactly as 040 split them for templates.
     */
    fun anyVersionReferences(
        workspaceId: UUID,
        datasourceName: String,
    ): List<DatasourceRef> = repository.findAnyVersionDatasourceRefs(workspaceId, datasourceName)

    class Scan(
        private val repository: PipelineRepository,
        private val workspaceId: UUID,
        private val ownerId: UUID?,
        private val datasourceName: String?,
    ) {
        val records: List<PipelineRecord> by lazy {
            if (datasourceName != null) {
                repository.findAllByDatasource(workspaceId, datasourceName, ownerId)
            } else {
                repository.findAll(workspaceId, ownerId)
            }
        }

        fun matchesQuery(
            record: PipelineRecord,
            query: String,
        ): Boolean {
            val needle = query.lowercase()
            return record.name.lowercase().contains(needle) ||
                record.displayName.lowercase().contains(needle) ||
                record.description.lowercase().contains(needle)
        }
    }
}
