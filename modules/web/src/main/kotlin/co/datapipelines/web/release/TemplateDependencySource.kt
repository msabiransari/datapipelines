package co.datapipelines.web.release

import co.datapipelines.pipeline.release.DependencySource
import co.datapipelines.pipeline.release.PipelineDependencySource
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ResolvedNode
import co.datapipelines.templates.TemplateRepository

/**
 * The template family's read of the release graph (record §4.2): an exact template version's status, body hash and the
 * entity's current pointer. A template is identified by its name, which is its id (`templates.name`).
 *
 * S1 answers no outgoing pins: a template's own pins are its `imports`, and the import arm of the closure is S2's (#518,
 * `TemplateReleaseParticipant` extended with the import closure). Until then a template is a leaf of every pipeline's
 * closure, exactly as #142's cascade treated it.
 */
class TemplateDependencySource(
    private val templates: TemplateRepository,
) : DependencySource {
    override fun resolve(identity: ReleaseNodeIdentity): ResolvedNode? {
        val detail = templates.findVersionDetail(identity.workspaceId, identity.entityId, identity.version) ?: return null
        val current = templates.findCurrentVersions(identity.workspaceId, listOf(identity.entityId))[identity.entityId]
        return ResolvedNode(
            identity,
            identity.entityId,
            PipelineDependencySource.statusOf(detail.status),
            detail.bodyHash,
            current,
            emptyList(),
        )
    }
}
