package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointPinValidator
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.EndpointVersionService
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.release.ClosureNode
import co.datapipelines.pipeline.release.ReleaseBlockers
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.release.ReleaseCoordinator
import java.time.Instant
import java.util.UUID

/**
 * The API versions page and its lifecycle dialogs, as data (#786 B.3, record §24 UX). Every read goes through the same
 * services the REST routes call — [EndpointPublishService.getById] for the entity under the caller's lens,
 * [EndpointVersionService.versions] for the versions — so the page shows exactly what `GET /api/v1/endpoints/{id}/versions`
 * answers, and a dialog's pre-read is the guard the POST re-runs, never a copy of it.
 *
 * Two facts the page keeps apart, because the record does: the VERSION fields (the exact pipeline pin, the timeout, the
 * description, the status) belong to the selected version; the ENTITY fields (`is_enabled` and the keys bound at the
 * path) belong to the API and are the same whichever version is viewed. Viewing a version never moves the API's current
 * version — only the switch dialog does.
 */
@Suppress("LongParameterList") // one collaborator per concern the page shows
class EndpointVersionsPageModel(
    private val publishing: EndpointPublishService,
    private val versions: EndpointVersionService,
    private val bindings: EndpointKeyBindingRepository,
    private val apiKeys: ApiKeyRepository,
    private val pipelines: PipelineRepository,
    private val pins: EndpointPinValidator,
    private val authoring: AuthoringGuard,
    private val coordinator: ReleaseCoordinator,
) {
    /** The page: the entity card, every version newest first, and the one being viewed. */
    data class Page(
        val id: UUID,
        val url: String,
        val enabled: Boolean,
        val boundKeyNames: List<String>,
        val versions: List<Row>,
        val selected: Row?,
        val currentVersion: Int?,
        val hasDraft: Boolean,
        val authoringEnabled: Boolean,
        val canPublish: Boolean,
        val canRelease: Boolean,
        val canManage: Boolean,
    )

    /** One version as the page renders it. */
    data class Row(
        val version: Int,
        val status: String,
        val pipeline: String,
        val pipelineVersion: Int,
        val timeoutSeconds: Int,
        val description: String,
        val bodyHash: String,
        val isCurrent: Boolean,
        val eligible: Boolean,
        val createdAt: Instant,
    ) {
        val pinLabel: String get() = "$pipeline@$pipelineVersion"
        val isDraft: Boolean get() = status == PipelineVersionStatus.DRAFT.name
        val isReleased: Boolean get() = status == PipelineVersionStatus.RELEASED.name
        val isDiscarded: Boolean get() = status == PipelineVersionStatus.DISCARDED.name
    }

    /** One exact pipeline version the draft dialog's picker offers, with its status and eligibility here. */
    data class PinOption(
        val version: Int,
        val status: String,
        val eligible: Boolean,
        val isCurrent: Boolean,
    )

    /** The draft dialog: a new draft from the current version, or the working draft itself. */
    data class DraftDialog(
        val id: UUID,
        val isNew: Boolean,
        val ifMatch: String,
        val pipelines: List<String>,
        val pipeline: String,
        val options: List<PinOption>,
        val selectedVersion: Int?,
        val timeoutSeconds: Int,
        val description: String,
    )

    /** A dependency row of the release dialog. */
    data class Dependency(
        val nodeId: String,
        val label: String,
        val family: String,
        val status: String,
    )

    /** The release dialog: the draft, the consent group of DRAFT dependencies, and the blockers no consent passes. */
    data class ReleaseDialog(
        val id: UUID,
        val version: Int,
        val pinLabel: String,
        val bodyHash: String,
        val treeHash: String,
        val drafts: List<Dependency>,
        val blockers: List<Dependency>,
    )

    /** A single-version dialog (discard, restore, purge, switch). */
    data class VersionDialog(
        val id: UUID,
        val url: String,
        val version: Int,
        val status: String,
        val pinLabel: String,
        val bodyHash: String,
        val isCurrent: Boolean,
        val eligible: Boolean,
        val isSoleVersion: Boolean,
    )

    /** The page for [id], viewing [requested] (default: the current version, else the newest); null when not visible. */
    fun page(
        principal: AuthenticatedPrincipal,
        id: UUID,
        requested: Int?,
    ): Page? {
        val endpoint = publishing.getById(principal, id) ?: return null
        val rows = versions.versions(principal, id).orEmpty().map(::row)
        val workspace = principal.requireWorkspace()
        val keyNames = apiKeys.findByWorkspaceAndKind(workspace.id, ApiKeyKind.ENDPOINT).associate { it.id to it.name }
        val bound =
            bindings
                .findByWorkspace(workspace.id)
                .filter { it.pathPrefix == endpoint.pathPattern }
                .mapNotNull { keyNames[it.apiKeyId] }
                .sorted()
        val selected =
            rows.firstOrNull { it.version == requested }
                ?: rows.firstOrNull { it.isCurrent }
                ?: rows.firstOrNull()
        return Page(
            id = endpoint.id,
            url = PUBLISHED_PREFIX + endpoint.pathPattern,
            enabled = endpoint.isEnabled,
            boundKeyNames = bound,
            versions = rows,
            selected = selected,
            currentVersion = endpoint.current?.version,
            hasDraft = rows.any { it.isDraft },
            authoringEnabled = authoring.developmentPosture,
            canPublish = workspace.permits(Permission.ENDPOINT_PUBLISH),
            canRelease = workspace.permits(Permission.ENDPOINT_RELEASE),
            canManage = workspace.permits(Permission.ENDPOINT_VERSION_MANAGE),
        )
    }

    /**
     * The draft dialog. It edits the working draft when one exists, else starts one from the current version; the picker
     * lists [pipeline]'s live versions (default: the base's own pipeline) with status and eligibility under this
     * deployment's posture, the base's pin preselected — so reopening a draft keeps a noncurrent pin.
     */
    fun draft(
        principal: AuthenticatedPrincipal,
        id: UUID,
        pipeline: String?,
    ): DraftDialog {
        val endpoint = visible(principal, id)
        val listed = versions.versions(principal, id).orEmpty()
        val draft = listed.firstOrNull { it.version.status == PipelineVersionStatus.DRAFT }
        val base = draft ?: listed.firstOrNull { it.isCurrent } ?: throw noBase(endpoint)
        val workspaceId = principal.requireWorkspace().id
        val names = pipelines.findCurrentVersions(workspaceId).map { it.name }.sorted()
        val chosen = pipeline?.takeIf { it in names } ?: base.version.body.pipelineName
        val record = pipelines.findByName(workspaceId, chosen)
        val options =
            record
                ?.let { pipelines.listVersions(workspaceId, it.id) }
                .orEmpty()
                .filter { it.status != PipelineVersionStatus.DISCARDED }
                .map { PinOption(it.version, it.status.name, pins.eligible(it.status), it.version == record?.currentVersion) }
        val keep = base.version.body.pipelineVersion.takeIf { chosen == base.version.body.pipelineName }
        return DraftDialog(
            id = id,
            isNew = draft == null,
            ifMatch = base.version.bodyHash,
            pipelines = (names + base.version.body.pipelineName).distinct().sorted(),
            pipeline = chosen,
            options = options,
            selectedVersion = keep ?: options.firstOrNull { it.isCurrent }?.version,
            timeoutSeconds = base.version.body.timeoutSeconds,
            description = base.version.body.description,
        )
    }

    /** The release dialog for the working draft: the shared planner's closure, its tree hash and the DRAFTs to consent to. */
    fun release(
        principal: AuthenticatedPrincipal,
        id: UUID,
    ): ReleaseDialog {
        val endpoint = visible(principal, id)
        val draft =
            versions.versions(principal, id).orEmpty().firstOrNull { it.version.status == PipelineVersionStatus.DRAFT }
                ?: throw DatapipelinesException(
                    code = PipelineErrorCodes.Endpoint.VERSION_NOT_DRAFT,
                    message = "This API has no draft to release.",
                    details = mapOf("endpoint_id" to endpoint.id.toString()),
                )
        val preview = coordinator.previewRoot(ReleaseFamily.ENDPOINT, endpoint.workspaceId, endpoint.id, principal.userId)
        val beneath = preview.closure.nodes.drop(1)
        val drafts = beneath.filter { it.status == ReleaseNodeStatus.DRAFT }
        val blockers = ReleaseBlockers.blockers(preview.closure, drafts.map { it.identity }.toSet()).map { it.first }
        return ReleaseDialog(
            id = id,
            version = draft.version.version,
            pinLabel = draft.version.body.pipelineName + "@" + draft.version.body.pipelineVersion,
            bodyHash = draft.version.bodyHash,
            treeHash = preview.treeHash,
            drafts = drafts.map(::dependency),
            blockers = blockers.map(::dependency),
        )
    }

    /** A single-version dialog for [version]; refused as not-found when the API or the version is not visible. */
    fun version(
        principal: AuthenticatedPrincipal,
        id: UUID,
        version: Int,
    ): VersionDialog {
        val endpoint = visible(principal, id)
        val listed = versions.versions(principal, id).orEmpty()
        val view =
            listed.firstOrNull { it.version.version == version }
                ?: throw DatapipelinesException(
                    code = PipelineErrorCodes.Endpoint.VERSION_NOT_FOUND,
                    message = "This API has no version $version.",
                    details = mapOf("endpoint_id" to endpoint.id.toString(), "version" to version),
                )
        return VersionDialog(
            id = id,
            url = PUBLISHED_PREFIX + endpoint.pathPattern,
            version = version,
            status = view.version.status.name,
            pinLabel = view.version.body.pipelineName + "@" + view.version.body.pipelineVersion,
            bodyHash = view.version.bodyHash,
            isCurrent = view.isCurrent,
            eligible = view.eligible,
            isSoleVersion = listed.size == 1,
        )
    }

    private fun visible(
        principal: AuthenticatedPrincipal,
        id: UUID,
    ): PublishedEndpoint =
        publishing.getById(principal, id)
            ?: throw DatapipelinesException(
                code = PipelineErrorCodes.Endpoint.NOT_FOUND,
                message = "No API with that id in this workspace.",
                details = mapOf("endpoint_id" to id.toString()),
            )

    private fun noBase(endpoint: PublishedEndpoint) =
        DatapipelinesException(
            code = PipelineErrorCodes.Endpoint.VERSION_NOT_FOUND,
            message = "This API has no current version to start a draft from.",
            details = mapOf("endpoint_id" to endpoint.id.toString()),
        )

    private fun row(view: EndpointVersionService.VersionView) =
        Row(
            version = view.version.version,
            status = view.version.status.name,
            pipeline = view.version.body.pipelineName,
            pipelineVersion = view.version.body.pipelineVersion,
            timeoutSeconds = view.version.body.timeoutSeconds,
            description = view.version.body.description,
            bodyHash = view.version.bodyHash,
            isCurrent = view.isCurrent,
            eligible = view.eligible,
            createdAt = view.version.createdAt,
        )

    private fun dependency(node: ClosureNode) =
        Dependency(
            nodeId = node.nodeId,
            label = node.name + "@" + node.identity.version,
            family = node.identity.family.name.lowercase(),
            status = node.status.name,
        )

    private companion object {
        /** `PublishedEndpointController.ROOT`, restated as the display prefix (the console's own constant). */
        const val PUBLISHED_PREFIX = "/api"
    }
}
