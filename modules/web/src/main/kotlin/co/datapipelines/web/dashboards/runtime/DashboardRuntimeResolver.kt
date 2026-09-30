package co.datapipelines.web.dashboards.runtime

import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.parameters.ParameterSetVersion
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
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
 * spec's §8.1, §9 steps 1–2): the released dashboard version, each occurrence's visualization version, the parameter
 * set version, and each source's executable pipeline release.
 */
class ResolvedDashboard(
    val served: ArtifactVersion<DashboardBody>,
    /** Occurrence name → its pinned visualization version. */
    val visualizations: Map<String, ArtifactVersion<VisualizationBody>>,
    val set: ParameterSetVersion?,
    /** Source name → its executable release. */
    val sources: Map<String, ResolvedSource>,
    /** `sha256(dashboard_id | version | body_hash | every pinned dependency's kind, name, version and body_hash)`. */
    val configurationId: String,
)

/** One source's pinned pipeline release, ready to execute. */
class ResolvedSource(
    val record: PipelineRecord,
    val version: Int,
    val executable: PipelineService.ExecutablePipeline,
)

/**
 * Resolves a dashboard for the runtime — and REFUSES, the way [co.datapipelines.visualization.DashboardValidator] does
 * at save, when a pin no longer holds: a dependency gone, un-released, or no longer read-only is
 * `dashboard.runtime.dependency_missing` (409) naming the kind and the source — never a 500 and never a run against a
 * pipeline that could write (D38, checked TRANSITIVELY at every config read and every refresh through
 * [PipelineReleaseFacts], whose `readOnly` is `ReadOnlyPipelineRule` over the release and its child pipelines).
 *
 * ## Isolation
 * Everything is workspace-scoped (the caller's workspace id is the first argument of every read) and the dashboard is
 * read through the caller's [lens]: a hidden dashboard is the family's ordinary 404, the same answer as an absent one.
 * The dashboard's PINS are read with the whole view — the lens already admitted the dashboard, and a promoter's lens
 * names dashboards, not their dependencies.
 *
 * ## The configuration id
 * Every dependency's body hash is in it, so ANY change to a pinned release (a re-release under the same number is
 * impossible; a purge and re-import is not) changes the id, and a client holding the old one gets
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
) {
    @Suppress("ThrowsCount") // each missing or unusable piece is its own refusal, in the spec's order
    fun resolve(
        workspaceId: UUID,
        lens: ReadLens,
        id: UUID,
    ): ResolvedDashboard {
        val served = dashboards.findServed(workspaceId, lens, id) ?: throw ArtifactFamily.DASHBOARD.notFound(id.toString())
        val body = served.body
        val vizByOccurrence =
            body.visualizations.associate { occurrence ->
                val pinned =
                    visualizations
                        .findVersionByName(
                            workspaceId,
                            ReadLens.Everything,
                            occurrence.visualization.name,
                            occurrence.visualization.version,
                        )?.takeIf { it.detail.status != PipelineVersionStatus.DISCARDED }
                        ?: throw missing("visualization", occurrence.name, NOT_FOUND)
                occurrence.name to pinned
            }
        val set =
            body.parameterSet?.let { ref ->
                sets
                    .findRecordByName(workspaceId, ref.name)
                    ?.let { record -> sets.findVersion(workspaceId, record.id, ref.version) }
                    ?.takeIf { it.detail.status != PipelineVersionStatus.DISCARDED }
                    ?: throw missing("parameter_set", ref.name, NOT_FOUND)
            }
        val sources = body.sources.associate { it.name to resolveSource(workspaceId, it) }
        return ResolvedDashboard(served, vizByOccurrence, set, sources, configurationId(workspaceId, served, vizByOccurrence, set, sources))
    }

    @Suppress("ThrowsCount") // one distinct refusal per way a pin can stop holding
    private fun resolveSource(
        workspaceId: UUID,
        source: co.datapipelines.visualization.DashboardSource,
    ): ResolvedSource {
        val fact = releaseFacts.releaseOf(workspaceId, source.pipeline) ?: throw missing("source", source.name, NOT_FOUND)
        if (fact.status != PipelineVersionStatus.RELEASED) throw missing("source", source.name, "not_released")
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
                    val hash = pipelineRepository.findVersionDetail(workspaceId, source.record.id, source.version)?.bodyHash.orEmpty()
                    add("pipeline|${source.record.name}|${source.version}|$hash")
                }
            }.sorted()
        val text = (listOf("${served.record.id}|${served.detail.version}|${served.detail.bodyHash}") + dependencies).joinToString("\n")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun missing(
        kind: String,
        name: String,
        reason: String,
    ) = ApiException(
        DashboardErrorCodes.RUNTIME_DEPENDENCY_MISSING,
        "A $kind this dashboard pins no longer holds.",
        mapOf("dependency" to kind, "name" to name, "reason" to reason),
    )

    private companion object {
        const val NOT_FOUND = "not_found"
    }
}
