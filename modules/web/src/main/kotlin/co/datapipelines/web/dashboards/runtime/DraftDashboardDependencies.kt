package co.datapipelines.web.dashboards.runtime

import co.datapipelines.parameters.ParameterSetVersion
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.templates.TemplateService
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.VisualizationBody
import java.security.MessageDigest
import java.util.UUID

/** Traverses the exact draft dependency graph for runtime invalidation and lifecycle admission. */
internal class DraftDashboardDependencies(
    private val pipelineRepository: PipelineRepository,
    private val pipelines: PipelineService,
    private val templates: TemplateService,
    private val missing: (String, String, String) -> RuntimeException,
) {
    /**
     * Draft pins are mutable. Include nested pipelines, node/selector/transform templates and their imports
     * so a dependency edit invalidates an already mounted configuration even when its version number is unchanged.
     * Iterative walks deduplicate exact pins and terminate on cycles; the source's read-only walk owns its depth limit.
     */
    fun hashes(
        workspaceId: UUID,
        visualizations: Map<String, ArtifactVersion<VisualizationBody>>,
        set: ParameterSetVersion?,
        sources: Map<String, ResolvedSource>,
    ): List<String> {
        val hashes = mutableListOf<String>()
        val templatePins = ArrayDeque<TemplateRef>()
        visualizations.values.forEach { visualization ->
            visualization.body.transform
                ?.template
                ?.let { templatePins.addLast(TemplateRef(it.name, it.version)) }
        }
        set?.body?.parameters?.forEach { parameter -> parameter.source?.template?.let(templatePins::addLast) }
        val pipelinePins = ArrayDeque<ResolvedSource>()
        sources.values.forEach(pipelinePins::addLast)
        val seenPipelines = mutableSetOf<Pair<UUID, Int>>()
        while (pipelinePins.isNotEmpty()) {
            val source = pipelinePins.removeFirst()
            if (!seenPipelines.add(source.record.id to source.version)) continue
            val detail =
                pipelineRepository.findVersionDetail(workspaceId, source.record.id, source.version)
                    ?: throw missing("source", source.record.name, NOT_FOUND)
            if (!admitted(detail.status, allowDraft = true)) throw missing("source", source.record.name, NOT_RELEASED)
            hashes += "pipeline|${source.record.name}|${source.version}|${detail.bodyHash}"
            // Bind the identity to the body actually loaded, even if a writer changes the detail between reads.
            val loadedHash =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(source.executable.bodyJson.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            hashes += "pipeline_loaded|${source.record.name}|${source.version}|$loadedHash"
            source.executable.pipeline.nodes.forEach { node ->
                if (node.template.id.isNotBlank()) templatePins.addLast(node.template)
                node.pipeline?.let { ref ->
                    val record =
                        pipelineRepository.findByNameAnyStatus(workspaceId, ref.name)
                            ?: throw missing("source", ref.name, NOT_FOUND)
                    val executable =
                        pipelines.findExecutable(workspaceId, ReadLens.Everything, record, ref.version)
                            ?: throw missing("source", ref.name, NOT_FOUND)
                    pipelinePins.addLast(ResolvedSource(record, ref.version, executable))
                }
            }
        }
        val seenTemplates = mutableSetOf<TemplateRef>()
        while (templatePins.isNotEmpty()) {
            val ref = templatePins.removeFirst()
            if (!seenTemplates.add(ref)) continue
            val template =
                templates.findVersion(workspaceId, ReadLens.Everything, ref.id, ref.version)
                    ?: throw missing("template", ref.id, NOT_FOUND)
            if (!admitted(template.status, allowDraft = true)) throw missing("template", ref.id, NOT_RELEASED)
            hashes += "template|${ref.id}|${ref.version}|${template.bodyHash}"
            template.imports.forEach { templatePins.addLast(TemplateRef(it.id, it.version)) }
        }
        return hashes
    }

    private fun admitted(
        status: PipelineVersionStatus,
        allowDraft: Boolean,
    ): Boolean = PipelineVersionStatus.eligibleForPointer(status, draftsEligible = allowDraft)

    private companion object {
        const val NOT_FOUND = "not_found"
        const val NOT_RELEASED = "not_released"
    }
}
