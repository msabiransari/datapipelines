package co.datapipelines.web.release

import co.datapipelines.pipeline.release.DependencySource
import co.datapipelines.pipeline.release.PipelineDependencySource
import co.datapipelines.pipeline.release.ReleaseFamily
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
 * Since #873 production resolves templates through the native `TemplateReleaseProvider` (one batch read per frontier);
 * this source is kept as the PARITY ORACLE only (brief §A.2): `ArtifactReleaseProvidersPostgresTest` walks the same
 * fixtures through both and asserts identical closures. Its reads (entity, version detail, current pointer, body) and its
 * pin mapping are its own, written independently of the provider's, so a defect in either is a parity difference.
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
                template.imports.mapIndexed { index, import ->
                    val target = ReleaseNodeIdentity(identity.workspaceId, ReleaseFamily.TEMPLATE, import.id, import.version)
                    ReleasePin(target, import.id, "imports[$index]")
                }
            }
        return ResolvedNode(identity, identity.entityId, status, detail.bodyHash, current, pins, entityUuid)
    }
}
