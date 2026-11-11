package co.datapipelines.application.dependencies.dashboard

import co.datapipelines.application.dependencies.ArtifactReleaseProvider
import co.datapipelines.application.dependencies.StatementCeiling
import co.datapipelines.application.dependencies.StoredVersion
import co.datapipelines.dependencies.Constraint
import co.datapipelines.dependencies.Reference
import co.datapipelines.parameters.ParameterSetBody
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardRepository
import java.util.UUID

/**
 * The dashboard family's native provider (#873 G2; record §6 dashboard → parameter set / pipeline / visualization): a
 * dashboard version resolves by its name and exact version through ONE batch read
 * ([DashboardRepository.findVersionsByName]) and references, in body order, its parameter set at `parameter_set`, every
 * source pipeline at `sources[i].pipeline` and every visualization occurrence at `visualizations[i].visualization` — two
 * occurrences of one version are two edges to one node.
 *
 * The parameter-set edge carries the `parameter_names` constraint [R19; F8]: the distinct set-parameter names the
 * `{parameter}` source bindings use, in first-use order; a literal binding adds nothing, a repeated one adds its name once.
 * Record §6 constrains ONLY those bindings — `action_controls[].parameter`, `parameter_scopes`, `parameter_state` and the
 * layout's parameter placements name set parameters too and add no constraint (brief §What is verified).
 */
class DashboardReleaseProvider(
    private val dashboards: DashboardRepository,
    ceiling: StatementCeiling,
) : ArtifactReleaseProvider<DashboardBody>(ReleaseFamily.DASHBOARD, ceiling) {
    override fun read(
        workspaceId: UUID,
        keys: List<Pair<String, Int>>,
    ): List<StoredVersion<DashboardBody>> =
        dashboards.findVersionsByName(workspaceId, keys).map { stored ->
            StoredVersion(
                identity = ReleaseNodeIdentity(workspaceId, ReleaseFamily.DASHBOARD, stored.record.name, stored.detail.version),
                entityUuid = stored.record.id,
                name = stored.record.name,
                status = stored.detail.status,
                bodyHash = stored.detail.bodyHash,
                currentVersion = stored.record.currentVersion,
                body = stored.body,
            )
        }

    override fun references(stored: StoredVersion<DashboardBody>): List<Reference> {
        val workspaceId = stored.identity.workspaceId
        val body = stored.body
        val names = parameterNames(body)
        return pinsOf(workspaceId, body).map { (target, path) ->
            val constraints = if (target.family == ReleaseFamily.PARAMETER_SET && names.isNotEmpty()) listOf(Constraint.ParameterNames(names)) else emptyList()
            reference(target, path, constraints)
        }
    }

    companion object {
        const val PARAMETER_SET_PATH = "parameter_set"

        /** Every dependency occurrence of [body] in body order: the set, each source pipeline, each visualization. */
        fun pinsOf(
            workspaceId: UUID,
            body: DashboardBody,
        ): List<Pair<ReleaseNodeIdentity, String>> =
            listOfNotNull(
                body.parameterSet?.let { ReleaseNodeIdentity(workspaceId, ReleaseFamily.PARAMETER_SET, it.name, it.version) to PARAMETER_SET_PATH },
            ) +
                body.sources.mapIndexed { index, source ->
                    ReleaseNodeIdentity(workspaceId, ReleaseFamily.PIPELINE, source.pipeline.name, source.pipeline.version) to
                        "sources[$index].pipeline"
                } +
                body.visualizations.mapIndexed { index, occurrence ->
                    val ref = occurrence.visualization
                    ReleaseNodeIdentity(workspaceId, ReleaseFamily.VISUALIZATION, ref.name, ref.version) to "visualizations[$index].visualization"
                }

        /** The `parameter_names` constraint's names: every `{parameter}` source binding's set parameter, distinct, first-use order. */
        fun parameterNames(body: DashboardBody): List<String> =
            body.sources.flatMap { source -> source.parameters.values.mapNotNull { it.parameter } }.distinct()

        /**
         * A34 — the constraint names [set] does not declare: a dashboard edge whose set lacks a bound name violates its
         * constraint. The contract has no diagnostic slot on an edge yet (G4 projects one), so this is the evaluation G4
         * and the dialogs read; the save-time validator already refuses such a body (`DashboardValidator`).
         */
        fun violations(
            names: List<String>,
            set: ParameterSetBody,
        ): List<String> {
            val declared = set.parameters.map { it.name }.toSet()
            return names.filter { it !in declared }
        }
    }
}
