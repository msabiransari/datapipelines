package co.datapipelines.application.dependencies.visualization

import co.datapipelines.application.dependencies.ArtifactReleaseProvider
import co.datapipelines.application.dependencies.StatementCeiling
import co.datapipelines.application.dependencies.StoredVersion
import co.datapipelines.dependencies.Reference
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationRepository
import java.util.UUID

/**
 * The visualization family's native provider (#873 G2; record §6 visualization → template `TRANSFORM_TEMPLATE`): a
 * visualization version resolves by its name and exact version through ONE batch read
 * ([VisualizationRepository.findVersionsByName]), and references its transform script template at `transform.template`;
 * a chart with no transform references nothing. The release evidence gate is NOT read here — it stays the participant's
 * in-transaction verdict (brief §B.2, A12).
 */
class VisualizationReleaseProvider(
    private val visualizations: VisualizationRepository,
    ceiling: StatementCeiling,
) : ArtifactReleaseProvider<VisualizationBody>(ReleaseFamily.VISUALIZATION, ceiling) {
    override fun read(
        workspaceId: UUID,
        keys: List<Pair<String, Int>>,
    ): List<StoredVersion<VisualizationBody>> =
        visualizations.findVersionsByName(workspaceId, keys).map { stored ->
            StoredVersion(
                identity = ReleaseNodeIdentity(workspaceId, ReleaseFamily.VISUALIZATION, stored.record.name, stored.detail.version),
                entityUuid = stored.record.id,
                name = stored.record.name,
                status = stored.detail.status,
                bodyHash = stored.detail.bodyHash,
                currentVersion = stored.record.currentVersion,
                body = stored.body,
            )
        }

    override fun references(stored: StoredVersion<VisualizationBody>): List<Reference> =
        listOfNotNull(transformPin(stored.identity.workspaceId, stored.body)).map { (target, path) -> reference(target, path) }

    companion object {
        /** The field path of the transform pin. */
        const val TRANSFORM_PATH = "transform.template"

        /** The transform template [body] pins and its path, or null for a transform-free visualization. */
        fun transformPin(
            workspaceId: UUID,
            body: VisualizationBody,
        ): Pair<ReleaseNodeIdentity, String>? =
            body.transform?.template?.let { ref ->
                ReleaseNodeIdentity(workspaceId, ReleaseFamily.TEMPLATE, ref.name, ref.version) to TRANSFORM_PATH
            }
    }
}
