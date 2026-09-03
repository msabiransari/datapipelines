package co.datapipelines.web.pipelines

import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceNotFoundException
import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.TemplateRepository
import java.util.UUID

/**
 * The RECEIVER's answer to "what do you already have?" (versioning §10.2, rest-api §18.1).
 *
 * Everything a sender needs to compute the delta, in ONE read: the per-pipeline and
 * per-template `(name, current_version, body_hash)` triples, the datasource names §10.5's
 * pre-validation checks against, and the target's own posture.
 *
 * ## Why `current_version` needs no status filter
 * The version lifecycle's invariant is that `pipelines.current_version` IS the latest RELEASED
 * version — a draft never moves it (versioning §3.1). So the current version's body hash is
 * the hash of what this deployment SERVES, which is exactly what "same hash ⇒ nothing to push"
 * has to compare against. A draft on the receiver is a separate problem, and
 * `AuthoringStartupCheck` refuses to start a receiver that holds one.
 *
 * ## Workspace by NAME
 * Workspace names are a global namespace (§13.12 `duplicate_name`), so a name is the one
 * identifier that means the same thing on both deployments — the ids do not. An unknown name
 * is `workspace.not_found` (404): the promotion peer is a trusted deployment, not a principal
 * probing for existence, so the no-oracle rule that hides unknown workspaces from ordinary
 * callers does not apply to it.
 */
class PromotionInventoryService(
    private val workspaces: WorkspaceRepository,
    private val pipelines: PipelineRepository,
    private val templates: TemplateRepository,
    private val datasources: DatasourceRegistry,
    private val deploymentName: String,
    private val authoringEnabled: Boolean,
) {
    /** The inventory of [workspaceName], or [WorkspaceNotFoundException] when this deployment has no such workspace. */
    fun inventoryOf(workspaceName: String): PromotionWire.Inventory {
        val workspaceId = workspaceIdOf(workspaceName)
        return PromotionWire.Inventory(
            deployment = deploymentName,
            authoringEnabled = authoringEnabled,
            workspace = workspaceName,
            pipelines = pipelineEntries(workspaceId),
            templates = templateEntries(workspaceId),
            datasources = datasources.listVisible(workspaceId = workspaceId).map { it.name }.sorted(),
        )
    }

    /** The workspace id behind [workspaceName], for callers that then read it directly. */
    fun workspaceIdOf(workspaceName: String): UUID = contextFor(workspaceName).id

    /**
     * The resolved workspace, as the context a principal carries.
     *
     * The receiver stamps this onto the promotion principal for the duration of an import
     * (see [PromotionReceiveService]) — several resolvers on the write path read the ACTIVE
     * workspace off the principal rather than taking it as a parameter, and a promotion
     * payload's workspace is the honest answer for them.
     */
    fun contextFor(workspaceName: String): WorkspaceContext =
        workspaces.findByName(workspaceName)?.let { WorkspaceContext(it.id, it.name) }
            ?: throw WorkspaceNotFoundException(workspaceName)

    /**
     * One entry per live pipeline. The per-pipeline version read is a second query each — the
     * inventory is workspace-scale and answered once per promotion, and the alternative is a
     * new repository join in `pipeline-contract`, which this round's fence deliberately keeps
     * to the error codes.
     */
    private fun pipelineEntries(workspaceId: UUID): List<PromotionWire.Entry> =
        pipelines
            .findAll(workspaceId)
            .mapNotNull { record ->
                pipelines.findCurrentVersionDetail(workspaceId, record.id)?.let { version ->
                    PromotionWire.Entry(record.name, record.currentVersion, version.bodyHash)
                }
            }.sortedBy { it.name }

    /** One entry per live template — `list` already returns each template's CURRENT version. */
    private fun templateEntries(workspaceId: UUID): List<PromotionWire.Entry> {
        val entries = mutableListOf<PromotionWire.Entry>()
        var offset = 0
        while (true) {
            val page = templates.list(workspaceId = workspaceId, offset = offset, limit = PAGE)
            page.forEach { entries += PromotionWire.Entry(it.id, it.version, it.bodyHash) }
            if (page.size < PAGE) break
            offset += PAGE
        }
        return entries.sortedBy { it.name }
    }

    private companion object {
        /** `TemplateRepository.MAX_PAGE_LIMIT`; the loop above pages rather than assuming one page. */
        const val PAGE = 200
    }
}
