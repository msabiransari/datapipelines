package co.datapipelines.web.dashboards.runtime

import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.parameters.ParameterSetVersion
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.templates.TemplateService
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.PipelineReleaseFacts
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.visualizations.ArtifactFamily
import java.security.MessageDigest
import java.util.UUID

/**
 * A served dashboard and everything it pins, resolved against the dependencies' CURRENT state (the implementation
 * spec's §8.1, §9 steps 1–2): the dashboard version (the current RELEASED one, or the DRAFT|RELEASED version the
 * caller names — #369 R2), each occurrence's visualization version, the parameter set version, and each source's
 * executable pipeline version. An explicitly selected DRAFT dashboard admits DRAFT dependencies;
 * a released dashboard continues to require RELEASED dependencies.
 */
class ResolvedDashboard(
    val served: ArtifactVersion<DashboardBody>,
    /** Occurrence name → its pinned visualization version. */
    val visualizations: Map<String, ArtifactVersion<VisualizationBody>>,
    val set: ParameterSetVersion?,
    /** Source name → its executable version. */
    val sources: Map<String, ResolvedSource>,
    /** `sha256(dashboard_id | version | body_hash | every pinned dependency's kind, name, version and body_hash)`. */
    val configurationId: String,
)

/** One source's pinned pipeline version, ready to execute. */
class ResolvedSource(
    val record: PipelineRecord,
    val version: Int,
    val executable: PipelineService.ExecutablePipeline,
)

/**
 * Resolves a dashboard for the runtime — and REFUSES, the way [co.datapipelines.visualization.DashboardValidator] does
 * at save, when a pin no longer holds: a dependency gone, lifecycle-ineligible, or no longer read-only is
 * `dashboard.runtime.dependency_missing` (409) naming the kind and the source — never a 500 and never a run against a
 * pipeline that could write (D38, checked TRANSITIVELY at every config read and every refresh through
 * [PipelineReleaseFacts], whose `readOnly` is `ReadOnlyPipelineRule` over the pinned body and its child pipelines).
 *
 * ## Isolation
 * Everything is workspace-scoped (the caller's workspace id is the first argument of every read) and the dashboard is
 * read through the caller's [lens]: a hidden dashboard is the family's ordinary 404, the same answer as an absent one.
 * The dashboard's PINS are read with the whole view — the lens already admitted the dashboard, and a promoter's lens
 * names dashboards, not their dependencies.
 *
 * ## The configuration id
 * Every direct dependency's body hash is in it; a draft also includes nested pipelines, templates and imports.
 * An edit at the same draft version changes the id, and a client holding the old one gets
 * `dashboard.runtime.configuration_stale`.
 */
@Suppress("LongParameterList") // the dashboard's dependencies ARE its ports
class DashboardRuntimeResolver(
    private val dashboards: DashboardService,
    private val visualizations: VisualizationService,
    private val sets: ParameterSetRepository,
    private val releaseFacts: PipelineReleaseFacts,
    private val pipelineRepository: PipelineRepository,
    private val pipelines: PipelineService,
    private val templates: TemplateService,
) {
    @Suppress("ThrowsCount") // each missing or unusable piece is its own refusal, in the spec's order
    fun resolve(
        workspaceId: UUID,
        lens: ReadLens,
        id: UUID,
        version: Int? = null,
    ): ResolvedDashboard {
        // Null = today's read: the CURRENT RELEASED version (findServed). A value names a version the caller asked
        // for — DRAFT or RELEASED under the whole view, RELEASED only under a narrowing lens (#369 R2); an absent,
        // discarded or lens-hidden version is the family's 404 naming the version they named.
        val served =
            if (version == null) {
                dashboards.findServed(workspaceId, lens, id) ?: throw ArtifactFamily.DASHBOARD.notFound(id.toString())
            } else {
                dashboards.findServedVersion(workspaceId, lens, id, version)
                    ?: throw ArtifactFamily.DASHBOARD.notFound(id.toString(), version)
            }
        val body = served.body
        val allowDraft = served.detail.status == PipelineVersionStatus.DRAFT
        val vizByOccurrence =
            body.visualizations.associate { occurrence ->
                val pinned =
                    visualizations.findVersionByName(
                        workspaceId,
                        ReadLens.Everything,
                        occurrence.visualization.name,
                        occurrence.visualization.version,
                    ) ?: throw missing("visualization", occurrence.name, NOT_FOUND)
                if (!admitted(pinned.detail.status, allowDraft)) throw missing("visualization", occurrence.name, NOT_RELEASED)
                pinned.body.transform?.template?.let { transform ->
                    val status = templates.findVersionStatus(workspaceId, ReadLens.Everything, transform.name, transform.version)
                    if (status == null) throw missing("transform", transform.name, NOT_FOUND)
                    if (!admitted(status, allowDraft)) throw missing("transform", transform.name, NOT_RELEASED)
                }
                occurrence.name to pinned
            }
        val set =
            body.parameterSet?.let { ref ->
                val pinned =
                    sets.findRecordByName(workspaceId, ref.name)?.let { record -> sets.findVersion(workspaceId, record.id, ref.version) }
                        ?: throw missing("parameter_set", ref.name, NOT_FOUND)
                if (!admitted(pinned.detail.status, allowDraft)) throw missing("parameter_set", ref.name, NOT_RELEASED)
                pinned
            }
        val sources = body.sources.associate { it.name to resolveSource(workspaceId, it, allowDraft) }
        return ResolvedDashboard(served, vizByOccurrence, set, sources, configurationId(workspaceId, served, vizByOccurrence, set, sources))
    }

    @Suppress("ThrowsCount") // one distinct refusal per way a pin can stop holding
    private fun resolveSource(
        workspaceId: UUID,
        source: co.datapipelines.visualization.DashboardSource,
        allowDraft: Boolean,
    ): ResolvedSource {
        val fact = releaseFacts.releaseOf(workspaceId, source.pipeline) ?: throw missing("source", source.name, NOT_FOUND)
        if (!admitted(fact.status, allowDraft)) throw missing("source", source.name, NOT_RELEASED)
        if (!fact.readOnly) throw missing("source", source.name, "not_read_only")
        val record =
            pipelineRepository.findByNameAnyStatus(workspaceId, source.pipeline.name) ?: throw missing("source", source.name, NOT_FOUND)
        val executable =
            pipelines.findExecutable(workspaceId, ReadLens.Everything, record, source.pipeline.version)
                ?: throw missing("source", source.name, NOT_FOUND)
        return ResolvedSource(record, source.pipeline.version, executable)
    }

    private fun configurationId(
        workspaceId: UUID,
        served: ArtifactVersion<DashboardBody>,
        visualizations: Map<String, ArtifactVersion<VisualizationBody>>,
        set: ParameterSetVersion?,
        sources: Map<String, ResolvedSource>,
    ): String {
        val dependencies =
            buildList {
                visualizations.values.forEach { add("visualization|${it.record.name}|${it.detail.version}|${it.detail.bodyHash}") }
                set?.let { add("parameter_set|${it.record.name}|${it.detail.version}|${it.detail.bodyHash}") }
                sources.values.forEach { source ->
                    val hash =
                        pipelineRepository.findVersionDetail(workspaceId, source.record.id, source.version)?.bodyHash
                            ?: throw missing("source", source.record.name, NOT_FOUND)
                    add("pipeline|${source.record.name}|${source.version}|$hash")
                }
                if (served.detail.status == PipelineVersionStatus.DRAFT) {
                    addAll(
                        DraftDashboardDependencies(
                            pipelineRepository,
                            pipelines,
                            templates,
                            ::missing,
                        ).hashes(workspaceId, visualizations, set, sources),
                    )
                }
            }.sorted()
        val text = (listOf("${served.record.id}|${served.detail.version}|${served.detail.bodyHash}") + dependencies).joinToString("\n")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun admitted(
        status: PipelineVersionStatus,
        allowDraft: Boolean,
    ): Boolean = status == PipelineVersionStatus.RELEASED || (allowDraft && status == PipelineVersionStatus.DRAFT)

    private fun missing(
        kind: String,
        name: String,
        reason: String,
    ) = ApiException(
        DashboardErrorCodes.RUNTIME_DEPENDENCY_MISSING,
        if (reason == NOT_RELEASED) {
            // Released boards keep the release hint; draft previews admit live drafts and refuse discarded pins.
            "A $kind this dashboard pins is not released — release it first, then try this board again."
        } else {
            "A $kind this dashboard pins no longer holds."
        },
        mapOf("dependency" to kind, "name" to name, "reason" to reason),
    )

    private companion object {
        const val NOT_FOUND = "not_found"
        const val NOT_RELEASED = "not_released"
    }
}
