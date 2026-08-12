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
 */
class PipelineBodies(
    private val repository: PipelineRepository,
) {
    fun scan(
        ownerId: UUID? = null,
        datasourceName: String? = null,
    ): Scan = Scan(repository, ownerId, datasourceName)

    fun pipelinesReferencing(datasourceName: String): List<String> = repository.findAllByDatasource(datasourceName).map { it.name }

    class Scan(
        private val repository: PipelineRepository,
        private val ownerId: UUID?,
        private val datasourceName: String?,
    ) {
        val records: List<PipelineRecord> by lazy {
            if (datasourceName != null) {
                repository.findAllByDatasource(datasourceName, ownerId)
            } else {
                repository.findAll(ownerId)
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
