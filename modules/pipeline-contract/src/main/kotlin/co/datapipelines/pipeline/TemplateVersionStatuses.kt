package co.datapipelines.pipeline

import java.util.UUID

/**
 * "What is the lifecycle status of this template version?" — the ONE fact the pipeline
 * aggregate needs from `templates`, and the release gate (versioning §6: templates lock
 * first) is the only thing that asks.
 *
 * Declared here as a port rather than taken as a dependency because the module arrow runs
 * the other way: `templates` depends on `pipeline-contract`, never the reverse
 * (module-structure §4.2). The aggregation layer supplies the implementation over
 * `TemplateRepository.findVersionStatus`, exactly as it already supplies
 * [DatasourceRegistry] and [PipelineResolver] — the established pattern in this module,
 * not a new one invented for 056.
 *
 * Returns null when the template or that version does not exist; the caller reports the
 * absence as `MISSING` rather than guessing a status.
 */
fun interface TemplateVersionStatuses {
    fun statusOf(
        workspaceId: UUID,
        templateId: String,
        version: Int,
    ): PipelineVersionStatus?
}
