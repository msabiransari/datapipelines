package co.datapipelines.visualization

import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.TemplateLookup
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.pipeline.TransformContractView
import co.datapipelines.typesystem.LogicalType
import java.util.UUID

// The cross-aggregate facts the two validators judge through. Declared HERE (the module's own ports, the
// `TemplateVersionStatuses` pattern) because the facts live in modules this one does not — or must not —
// depend on; the surfaces wire the implementations (L1b), and the tests use fakes.

/**
 * A pinned template version as the visualization validator needs it (the spec's §3.1 `transform`): exists?
 * a transform? its contract? The production adapter is [over] — composed from pipeline-contract's two template
 * ports, which `templates` implements, so this module needs no `templates` edge.
 */
fun interface TemplateContractFacts {
    fun pinOf(
        workspaceId: UUID,
        ref: ArtifactRef,
    ): TemplatePin

    companion object {
        /** The production adapter over the registry's lookup, its contract view and its status read. */
        fun over(
            renderer: TemplateDryRenderer,
            statuses: TemplateVersionStatuses,
        ): TemplateContractFacts =
            TemplateContractFacts { workspaceId, ref ->
                val templateRef = TemplateRef(ref.name, ref.version)
                when (val lookup = renderer.lookup(workspaceId, templateRef)) {
                    TemplateLookup.TemplateNotFound -> {
                        TemplatePin.NotFound
                    }

                    TemplateLookup.VersionNotFound -> {
                        TemplatePin.VersionNotFound
                    }

                    is TemplateLookup.Found -> {
                        val status = statuses.statusOf(workspaceId, ref.name, ref.version)
                        val contract = renderer.transformContract(workspaceId, templateRef)
                        when {
                            status == null || status == PipelineVersionStatus.DISCARDED -> TemplatePin.VersionNotFound
                            !lookup.type.isTransform || contract == null -> TemplatePin.NotTransform(lookup.type)
                            else -> TemplatePin.Transform(status, contract)
                        }
                    }
                }
            }
    }
}

/** What a pinned template version is, for the validator. A DISCARDED version is [VersionNotFound] (§12.6's rule). */
sealed interface TemplatePin {
    data object NotFound : TemplatePin

    data object VersionNotFound : TemplatePin

    data class NotTransform(
        val type: TemplateType,
    ) : TemplatePin

    data class Transform(
        val status: PipelineVersionStatus,
        val contract: TransformContractView,
    ) : TemplatePin
}

/**
 * A pinned pipeline release as the dashboard validator needs it (the spec's §3.2 `sources[]`, D1, D38): its
 * status, whether it passes `ReadOnlyPipelineRule` transitively, its declared parameters and its CALLER output
 * columns. The rule and the output contract live in `application` / `pipeline-contract`'s composition, so
 * `application` implements this over `ReadOnlyPipelineRule` (L1b). Null when the workspace holds no such
 * pipeline version.
 */
fun interface PipelineReleaseFacts {
    fun releaseOf(
        workspaceId: UUID,
        ref: ArtifactRef,
    ): PipelineReleaseFact?
}

/** One pinned pipeline version's facts. */
data class PipelineReleaseFact(
    val status: PipelineVersionStatus,
    /** True when the release passes the read-only rule, child pipelines included (D38). */
    val readOnly: Boolean,
    val parameters: List<PipelineParameterFact>,
    /**
     * The caller output columns of this release — what a visualization input can be fed. EMPTY when the release has
     * no caller node (it returns no rows); NULL when the release returns rows whose columns it does not DECLARE (a
     * SQL caller node — only a transform caller node's contract names its columns): the save-time
     * `input_contract_mismatch` check is then skipped, never guessed, and the runtime judges the real columns (L2).
     */
    val outputColumns: List<OutputColumn>?,
)

/** One declared pipeline parameter: its name, and whether a caller MUST supply it (required and no default). */
data class PipelineParameterFact(
    val name: String,
    val required: Boolean,
)

/** One caller output column of a pipeline release. */
data class OutputColumn(
    val name: String,
    val type: LogicalType,
    val nullable: Boolean,
)

/**
 * A pinned parameter-set version as the dashboard validator needs it (D40, R1): its status and, per parameter,
 * its DIRECT dependents (the parameters whose `depends_on` names it) — a parameter with dependents is a PARENT.
 * The production adapter is [over], a read of `ParameterSetRepository` (this module's `parameters` edge).
 */
fun interface ParameterSetFacts {
    fun setOf(
        workspaceId: UUID,
        ref: ArtifactRef,
    ): ParameterSetFact?

    companion object {
        /** The production adapter: the set by name in the workspace, the pinned version's body, its dependents. */
        fun over(repository: ParameterSetRepository): ParameterSetFacts =
            ParameterSetFacts { workspaceId, ref ->
                repository.findRecordByName(workspaceId, ref.name)?.let { record ->
                    repository.findVersion(workspaceId, record.id, ref.version)?.let { version ->
                        val parameters = version.body.parameters
                        ParameterSetFact(
                            status = version.detail.status,
                            parameters =
                                parameters.map { parameter ->
                                    SetParameterFact(
                                        parameter.name,
                                        parameters.filter { parameter.name in it.dependsOn }.map { it.name }.toSet(),
                                    )
                                },
                        )
                    }
                }
            }
    }
}

/** One pinned set version's facts. */
data class ParameterSetFact(
    val status: PipelineVersionStatus,
    val parameters: List<SetParameterFact>,
)

/** One set parameter and its direct dependents. */
data class SetParameterFact(
    val name: String,
    val dependents: Set<String>,
) {
    /** R1: any parameter with dependents is a parent, whatever its control type. */
    val isParent: Boolean get() = dependents.isNotEmpty()
}

/**
 * A pinned visualization version as the dashboard validator needs it: its status and its body (the named
 * inputs a dashboard must map). Implemented in this module over [VisualizationRepository].
 */
fun interface VisualizationPins {
    fun pinOf(
        workspaceId: UUID,
        ref: ArtifactRef,
    ): PinnedVisualization?
}

/** One pinned visualization version. */
data class PinnedVisualization(
    val status: PipelineVersionStatus,
    val body: VisualizationBody,
)
