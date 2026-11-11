package co.datapipelines.application.dependencies.parameterset

import co.datapipelines.application.dependencies.ArtifactReleaseProvider
import co.datapipelines.application.dependencies.StatementCeiling
import co.datapipelines.application.dependencies.StoredVersion
import co.datapipelines.dependencies.Reference
import co.datapipelines.parameters.ParameterSetBody
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import java.util.UUID

/**
 * The parameter-set family's native provider (#873 G2; record §6 parameter set → template `PARAMETER_SOURCE_TEMPLATE`): a
 * set version resolves by its name and exact version through ONE batch read
 * ([ParameterSetRepository.findVersionsByName]), and references the selector template of every parameter whose source
 * names one, at `parameters[i].source.template` (the path the set's restore and release refusals already spell). A
 * constant or datasource-only selector pins no template; the datasource edge is U2's (after #874).
 */
class ParameterSetReleaseProvider(
    private val sets: ParameterSetRepository,
    ceiling: StatementCeiling,
) : ArtifactReleaseProvider<ParameterSetBody>(ReleaseFamily.PARAMETER_SET, ceiling) {
    override fun read(
        workspaceId: UUID,
        keys: List<Pair<String, Int>>,
    ): List<StoredVersion<ParameterSetBody>> =
        sets.findVersionsByName(workspaceId, keys).map { stored ->
            StoredVersion(
                identity = ReleaseNodeIdentity(workspaceId, ReleaseFamily.PARAMETER_SET, stored.record.name, stored.detail.version),
                entityUuid = stored.record.id,
                name = stored.record.name,
                status = stored.detail.status,
                bodyHash = stored.detail.bodyHash,
                currentVersion = stored.record.currentVersion,
                body = stored.body,
            )
        }

    override fun references(stored: StoredVersion<ParameterSetBody>): List<Reference> =
        templatePins(stored.identity.workspaceId, stored.body).map { (target, path) -> reference(target, path) }

    companion object {
        /** Every selector-template occurrence of [body]: the exact template version and its field path, in parameter order. */
        fun templatePins(
            workspaceId: UUID,
            body: ParameterSetBody,
        ): List<Pair<ReleaseNodeIdentity, String>> =
            body.parameters.withIndex().mapNotNull { (index, parameter) ->
                parameter.source?.template?.let { template ->
                    ReleaseNodeIdentity(workspaceId, ReleaseFamily.TEMPLATE, template.id, template.version) to
                        "parameters[$index].source.template"
                }
            }
    }
}
