package co.datapipelines.web.pipelines

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

    fun pipelinesReferencing(
        workspaceId: UUID,
        datasourceName: String,
    ): List<String> = repository.findAllByDatasource(workspaceId, datasourceName).map { it.name }

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
