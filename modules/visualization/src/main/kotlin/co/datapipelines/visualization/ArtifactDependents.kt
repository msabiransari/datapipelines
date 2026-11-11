package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.inverse.InverseRequest
import co.datapipelines.pipeline.inverse.InverseRow
import co.datapipelines.pipeline.inverse.InverseScope
import co.datapipelines.pipeline.inverse.InverseSql
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * One pin found by [ArtifactDependents]: the stored version of one visualization or dashboard ([name]@[version],
 * with its lifecycle [status]) that pins [pinnedVersion] of the artifact the question asked about.
 */
data class ArtifactPin(
    val artifactId: UUID,
    val name: String,
    val version: Int,
    val status: PipelineVersionStatus,
    val pinnedVersion: Int,
)

/**
 * Which stored versions of a dependent count when a reverse arrow is asked — the three questions the graph rules of
 * versioning §3.5 put to a dependent, the `ParameterSetTemplatePins` split carried over.
 */
enum class PinScope {
    /**
     * Each live dependent's WORKING version — its draft when one exists, else its pointer — so a draft that just adopted
     * the pin is counted. `templates_used_by` and the screens' in-use counts ask this.
     */
    WORKING,

    /** Every DRAFT or RELEASED version of a dependent — the discard and purge guards' exact-pin evidence (graph rule 1). */
    LIVE,

    /**
     * Every version ever stored, DISCARDED included, of a dependent that still has a live version — the entity purge's
     * evidence (graph rule 3, owner ruling R12: a discarded version can be restored and must keep what it pins).
     */
    ANY,
}

/**
 * The reverse arrows INTO other families from the two artifact families this module persists — "who pins this?"
 * for a script template (a visualization's `transform.template`), a pipeline release (a dashboard source's
 * `pipeline`) and a parameter-set release (a dashboard's `parameter_set`). #320: the design record §4.2 ("deletion and
 * purge guards keep referenced releases") for the dependencies OUTSIDE this module; [DashboardRepository.livePinsOf]
 * is the in-module arrow it sits beside.
 *
 * Declared here, over `visualization_versions` and `dashboard_versions`, because the JSONB predicate is the body
 * shape's property; the modules that own the pinned artifact reach it through a port `application` implements (no
 * module's SQL names another module's tables — graph rule 1's composition is `application`'s).
 *
 * **Every statement is workspace-scoped** — the pinned name is per workspace, so a same-named artifact in another
 * workspace never matches, and every value is a bind (no JSON path is built from input). #877: each arrow pages its
 * dependent repository's bounded inverse query ([VisualizationRepository.findTemplatePinOccurrences],
 * [DashboardRepository.findPipelinePinOccurrences], [DashboardRepository.findParameterSetPinOccurrences]) to exhaustion
 * — the same statement the provider arm reads, so the two directions share one definition of "pins".
 *
 * **Public and UNLENSED, by design (#340):** every row comes back, drafts and hidden names included, because the guards
 * that ask it are author-or-above verbs. A consumer that answers a caller whose lens narrows (a promoter) applies BOTH
 * halves itself — the name through the family's lens, and RELEASED rows only (178b: a draft's number never reaches a
 * promoter; see `TemplateUsage.visible` and the Usage tab's dashboards half) — or it leaks what the lens hides.
 */
class ArtifactDependents(
    jdbc: NamedParameterJdbcTemplate,
) {
    private val visualizations = VisualizationRepository(jdbc)
    private val dashboards = DashboardRepository(jdbc)

    /** The visualization versions that pin script template [templateId] — at [version], or at any version when null. */
    fun visualizationsPinningTemplate(
        workspaceId: UUID,
        templateId: String,
        version: Int?,
        scope: PinScope,
    ): List<ArtifactPin> = pins(workspaceId, templateId, version, scope, visualizations::findTemplatePinOccurrences)

    /** The dashboard versions with a source that pins pipeline [pipelineName] — at [version], or at any version when null. */
    fun dashboardsPinningPipeline(
        workspaceId: UUID,
        pipelineName: String,
        version: Int?,
        scope: PinScope,
    ): List<ArtifactPin> = pins(workspaceId, pipelineName, version, scope, dashboards::findPipelinePinOccurrences)

    /** The dashboard versions that pin parameter set [setName] — at [version], or at any version when null. */
    fun dashboardsPinningParameterSet(
        workspaceId: UUID,
        setName: String,
        version: Int?,
        scope: PinScope,
    ): List<ArtifactPin> = pins(workspaceId, setName, version, scope, dashboards::findParameterSetPinOccurrences)

    /**
     * #877 §B — every page of the dependent repository's bounded inverse query [arm] (the ONE definition the provider
     * arm shares), collapsed to one [ArtifactPin] per dependent version and pinned version (two placements of one pin
     * count once, as the former `SELECT DISTINCT` did; a pin storing no integral version reads as 0, as its `::int`
     * cast's NULL did), in the former order: name, then version newest first.
     */
    private fun pins(
        workspaceId: UUID,
        key: String,
        version: Int?,
        scope: PinScope,
        arm: (InverseRequest) -> List<InverseRow>,
    ): List<ArtifactPin> =
        InverseSql
            .exhaust(InverseSql.EXHAUST_PAGE) { after ->
                arm(InverseRequest(workspaceId, key, version, scope.inverse, after, InverseSql.EXHAUST_PAGE))
            }.map { row ->
                ArtifactPin(
                    artifactId = row.dependentUuid,
                    name = row.dependentName,
                    version = row.dependentVersion,
                    status = requireNotNull(row.status) { "a versioned dependent row carries its status" },
                    pinnedVersion = row.storedVersion ?: 0,
                )
            }.distinct()
            .sortedWith(BY_NAME_NEWEST_FIRST)

    private companion object {
        val BY_NAME_NEWEST_FIRST: Comparator<ArtifactPin> =
            compareBy<ArtifactPin> { it.name }.thenByDescending { it.version }.thenBy { it.pinnedVersion }
    }
}

/** The inverse query's scope for a [PinScope] — the same three questions under one definition. */
internal val PinScope.inverse: InverseScope
    get() =
        when (this) {
            PinScope.WORKING -> InverseScope.WORKING
            PinScope.LIVE -> InverseScope.LIVE
            PinScope.ANY -> InverseScope.ANY
        }
