package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.typesystem.DatapipelinesException
import java.util.UUID

/**
 * The RELEASE rules an import judges its pins by (O2 of the L1c pass) — the import-time rule class beside
 * the two validators, both transfer surfaces' one spelling: an import or a promotion receive lands the
 * artifact RELEASED, so its pins are judged by the RELEASE rules ("a RELEASED dashboard pins only
 * RELEASED"), never by the save rules' DRAFT tolerance (a draft may be released WITH its pin at a human
 * release, whose consent an import never gives). A pin that is present but not RELEASED — a DRAFT, or a
 * discarded version — refuses with the family's release code, `details` naming every blocking pin in the
 * release refusals' shape.
 *
 * A pin the importing workspace does not hold AT ALL is deliberately not named here: the save rules' run
 * inside the landing refuses it with the precise `import.missing_template` / `import.missing_dependency`
 * (null means absent, and the null falls through). Sources (pipeline pins) are likewise not named: the
 * save rules already refuse a non-RELEASED source (`source_not_released`).
 */
class ArtifactImportReleaseRules(
    private val templateStatuses: TemplateVersionStatuses,
    private val sets: ParameterSetFacts,
    private val visualizations: VisualizationPins,
) {
    /** A visualization import lands RELEASED: its transform pin, if held here, must be RELEASED. */
    fun judgeVisualization(
        workspaceId: UUID,
        body: VisualizationBody,
    ) {
        val pin = body.transform?.template ?: return
        val status = templateStatuses.statusOf(workspaceId, pin.name, pin.version)
        if (status == null || status == PipelineVersionStatus.RELEASED) return
        throw DatapipelinesException(
            VisualizationErrorCodes.RELEASE_DEPENDENCY_NOT_RELEASED,
            "A visualization import lands RELEASED; its transform template '${pin.name.safeEcho()}' version " +
                "${pin.version} is ${status.name} here. Release it, or import with its released version.",
            mapOf("pins_not_released" to listOf(blocking("template", pin.name, pin.version, status))),
        )
    }

    /** A dashboard import lands RELEASED: its pinned set and every pinned visualization must be RELEASED. */
    fun judgeDashboard(
        workspaceId: UUID,
        body: DashboardBody,
    ) {
        val blocking = mutableListOf<Map<String, Any?>>()
        body.parameterSet?.let { ref ->
            val status = sets.setOf(workspaceId, ref)?.status
            if (status != null && status != PipelineVersionStatus.RELEASED) {
                blocking += blocking("parameter_set", ref.name, ref.version, status)
            }
        }
        body.visualizations
            .map { it.visualization }
            .distinct()
            .forEach { ref ->
                val status = visualizations.pinOf(workspaceId, ref)?.status
                if (status != null && status != PipelineVersionStatus.RELEASED) {
                    blocking += blocking("visualization", ref.name, ref.version, status)
                }
            }
        if (blocking.isNotEmpty()) {
            throw DatapipelinesException(
                DashboardErrorCodes.RELEASE_DEPENDENCY_NOT_RELEASED,
                "A dashboard import lands RELEASED; ${blocking.size} pinned dependency(ies) are not released here.",
                blocking.first() + ("dependencies_not_released" to blocking),
            )
        }
    }

    private fun blocking(
        kind: String,
        name: String,
        version: Int,
        status: PipelineVersionStatus,
    ): Map<String, Any?> = mapOf("kind" to kind, "name" to name.safeEcho(), "version" to version, "status" to status.name)
}
