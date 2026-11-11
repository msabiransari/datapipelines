package co.datapipelines.web.release

import co.datapipelines.application.dependencies.template.TemplateReleaseProvider
import co.datapipelines.pipeline.release.DependencySource
import co.datapipelines.pipeline.release.PipelineDependencySource
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleasePin
import co.datapipelines.pipeline.release.ResolvedNode
import co.datapipelines.templates.TemplateRepository

/**
 * The template family's per-identity read of the release graph (record §4.2): an exact template version's status, body
 * hash, the entity's current pointer and — since #873 (record §23.3) — its `IMPORT` pins, one per `imports[i]` at field
 * path `imports[i]` (a DISCARDED version answers none: its pins are never walked, as a pipeline's).
 *
 * Since #873 production resolves templates through the native [TemplateReleaseProvider] (one batch read per frontier);
 * this source is kept as the PARITY ORACLE only (brief §A.2): `TemplateProviderParityTest` walks the same fixtures through
 * both and asserts identical closures. Its pins are computed by the provider's own [TemplateReleaseProvider.importsOf],
 * so the two cannot drift apart by spelling.
 */
class TemplateDependencySource(
    private val templates: TemplateRepository,
) : DependencySource {
    override fun resolve(identity: ReleaseNodeIdentity): ResolvedNode? {
        val entityUuid = templates.findEntityUuid(identity.workspaceId, identity.entityId) ?: return null
        val detail = templates.findVersionDetail(identity.workspaceId, identity.entityId, identity.version) ?: return null
        val current = templates.findCurrentVersions(identity.workspaceId, listOf(identity.entityId))[identity.entityId]
        val status = PipelineDependencySource.statusOf(detail.status)
        val pins =
            if (status == ReleaseNodeStatus.DISCARDED) {
                emptyList()
            } else {
                val template = templates.findVersion(identity.workspaceId, identity.entityId, identity.version) ?: return null
                TemplateReleaseProvider.importsOf(identity.workspaceId, template).map { (target, path) ->
                    ReleasePin(target, target.entityId, path)
                }
            }
        return ResolvedNode(identity, identity.entityId, status, detail.bodyHash, current, pins, entityUuid)
    }
}
