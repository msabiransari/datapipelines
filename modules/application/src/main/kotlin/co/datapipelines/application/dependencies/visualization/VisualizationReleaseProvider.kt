package co.datapipelines.application.dependencies.visualization

import co.datapipelines.application.dependencies.ArtifactReleaseProvider
import co.datapipelines.application.dependencies.StatementCeiling
import co.datapipelines.application.dependencies.StoredVersion
import co.datapipelines.dependencies.Operational
import co.datapipelines.dependencies.Reference
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.visualization.LatestRunFacts
import co.datapipelines.visualization.StoredEvidence
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationRepository
import java.time.Instant
import java.util.UUID

/**
 * The visualization family's native provider (#873 G2; record §6 visualization → template `TRANSFORM_TEMPLATE`): a
 * visualization version resolves by its name and exact version through ONE batch read
 * ([VisualizationRepository.findVersionsByName]), and references its transform script template at `transform.template`;
 * a chart with no transform references nothing.
 *
 * The node's operational fact is `evidence_ready` (record §11.1, §15.1; #873 R1): the version's LATEST stored test run,
 * joined into the same batch statement, classified by [StoredEvidence] — the read-side ladder the release gate shares —
 * READY exactly when that run is GREEN for this version's exact body hash. Stored facts only: no mechanical or customer
 * probe runs here; the gate's in-transaction verdict (lock, ladder, mechanical re-run) stays the participant's (brief
 * §B.2, A12), so a READY node can still be refused at commit.
 */
class VisualizationReleaseProvider(
    private val visualizations: VisualizationRepository,
    ceiling: StatementCeiling,
    private val now: () -> Instant = Instant::now,
) : ArtifactReleaseProvider<VisualizationBody>(ReleaseFamily.VISUALIZATION, ceiling) {
    override fun read(
        workspaceId: UUID,
        keys: List<Pair<String, Int>>,
    ): List<StoredVersion<VisualizationBody>> {
        val at = now()
        return visualizations.findVersionsWithLatestRun(workspaceId, keys).map { (stored, latest) ->
            StoredVersion(
                identity = ReleaseNodeIdentity(workspaceId, ReleaseFamily.VISUALIZATION, stored.record.name, stored.detail.version),
                entityUuid = stored.record.id,
                name = stored.record.name,
                status = stored.detail.status,
                bodyHash = stored.detail.bodyHash,
                currentVersion = stored.record.currentVersion,
                body = stored.body,
                operational = evidence(latest, stored.detail.bodyHash, at),
            )
        }
    }

    override fun references(stored: StoredVersion<VisualizationBody>): List<Reference> =
        listOfNotNull(transformPin(stored.identity.workspaceId, stored.body)).map { (target, path) -> reference(target, path) }

    companion object {
        /** `evidence_ready`: the latest stored run classifies [StoredEvidence.READY] for [bodyHash] at [now]. */
        fun evidence(
            latest: LatestRunFacts?,
            bodyHash: String,
            now: Instant,
        ): Operational.Visualization = Operational.Visualization(StoredEvidence.classify(latest, bodyHash, now) == StoredEvidence.READY)

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
