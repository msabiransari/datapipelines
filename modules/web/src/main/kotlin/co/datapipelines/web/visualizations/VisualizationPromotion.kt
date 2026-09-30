package co.datapipelines.web.visualizations

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.visualization.ArtifactExport
import co.datapipelines.visualization.ArtifactImported
import co.datapipelines.visualization.ArtifactTransferService
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.visualization.DashboardRepository
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationRepository
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.pipelines.PromotableView
import co.datapipelines.web.pipelines.PromotionWire
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.UUID

/** The batch entry's one shape precondition the reader cannot state: it must be a JSON object at all. */
private const val NOT_AN_OBJECT = "The batch entry is not a JSON object."

/**
 * The transfer families' half of promotion (the implementation spec's §12, D61; the
 * [co.datapipelines.web.parameters.ParameterSetPromotion] mould) — the SENDER's payload builder and
 * the RECEIVER's bind-and-landing act for both families, so both ends of the channel use one spelling
 * of the rules.
 *
 * ## The payload
 * The batch entry is the version's payload (the body with its lifecycle fields — the wire's
 * `visualization`/`dashboard` NODE, never the §22 export envelope): the id is KEPT (P24), the hash travels,
 * the transform templates do NOT — the sender merges them into the batch's template closure, and the receiver
 * lands them first. A dashboard's pinned pipelines and set travel BY REFERENCE: the receiver's validation judges
 * them against the rows its own transaction has just landed.
 *
 * ## The receive's act (D61's order, inside the transaction)
 * Unlike the parameter-set half (#302), NOTHING here opens a customer datasource — the validators' template facts
 * render dry and the pipeline facts take only a resolver — so bind AND land run INSIDE the receive's one
 * transaction, AFTER the batch's templates, sets, pipelines and endpoints have landed and the just-landed rows are
 * visible to the production ports. A refusal anywhere rolls the WHOLE batch back (C36's shape).
 */
class VisualizationPromotion(
    private val repository: VisualizationRepository,
    private val visualizations: VisualizationService,
    /** The ONE entry bind — the readers, so the document bounds hold on receive as on save (the L1c HIGH item). */
    private val transfer: ArtifactTransferService,
) {
    /**
     * The sender's entry for [name]'s current release, or null when it is not promotable. §10.3's guards over
     * the FRESH inventory (released AND newer), plus the promoter's view: a visualization the view does not
     * admit is refused like an unknown one — the promote verb never crosses what the lens hides (the brief's
     * roles rule; 76e8af98's refuse-not-drop, the families' catalogued 404 naming the NAME submitted).
     */
    fun entryFor(
        workspaceId: UUID,
        name: String,
        target: PromotionWire.Entry?,
        view: PromotableView,
    ): JsonNode? {
        if (!view.visualizationsLens.admits(name)) return null
        val version = promotableRelease(workspaceId, name, target) ?: return null
        return ArtifactTransferService.payloadOf(version)
    }

    /** The current release iff it is RELEASED and NEWER than the target's entry (§10.3's guards). */
    private fun promotableRelease(
        workspaceId: UUID,
        name: String,
        target: PromotionWire.Entry?,
    ): ArtifactVersion<VisualizationBody>? {
        val record = repository.findRecordByName(workspaceId, name) ?: return null
        val current = record.currentVersion ?: return null
        val detail = repository.findVersionDetail(workspaceId, record.id, current)
        if (detail?.status == PipelineVersionStatus.RELEASED && PromotableView.isNewer(current, detail.bodyHash, target)) {
            return repository.findVersion(workspaceId, record.id, current)
        }
        return null
    }

    /**
     * A dashboard's pinned visualization, at the version the dashboard PINS — a dependency, not a root: §10.4's
     * skip rule (already present at the same version AND hash is omitted, idempotent), no "newer" guard, the
     * released check only. A missing local release REFUSES — the sender cannot build the entry the batch
     * promises. Null (skip) is a normal answer; the exception is the refusal.
     */
    fun entryForPin(
        workspaceId: UUID,
        name: String,
        version: Int,
        target: PromotionWire.Entry?,
    ): JsonNode? {
        // A missing release, a non-RELEASED one and a missing body are the SAME refusal: the sender cannot
        // build the entry the batch promises, addressed by the name@version the dashboard pins.
        val address = "$name@$version"
        val record = repository.findRecordByName(workspaceId, name)
        val detail = record?.let { repository.findVersionDetail(workspaceId, it.id, version) }
        if (record == null || detail == null || detail.status != PipelineVersionStatus.RELEASED) {
            throw visualizationNotFound(address)
        }
        if (target != null && target.currentVersion == version && target.bodyHash == detail.bodyHash) return null
        val found = repository.findVersion(workspaceId, record.id, version) ?: throw visualizationNotFound(address)
        return ArtifactTransferService.payloadOf(found)
    }

    /** The transform pin of the visualization [entry] carries — merged into the batch's template closure. */
    fun templatePins(entry: JsonNode): List<TemplateRef> =
        entry
            .path("transform")
            .path("template")
            .takeIf { ref -> ref.has("name") }
            ?.let { listOf(TemplateRef(it.path("name").asText(), it.path("version").asInt())) }
            .orEmpty()

    /**
     * The receiver's BIND of one batch entry: the ONE entry bind through the family READER (the document bounds,
     * the key tables, the strict types) — a miss is the family's `body_invalid` with the reader's failure list.
     * Runs inside the transaction; a refusal rolls the batch back whole.
     */
    fun bind(entry: JsonNode): ArtifactExport<VisualizationBody> = transfer.visualizationEntry(objectOrRefuse(entry))

    /**
     * The receiver's LANDING of one bound entry — the transaction body's act (C36): the kept id, the hash check
     * and the §9.2 version rules through the service's `import`, whose `validateForImport` judges the transform
     * pin against the rows the SAME transaction has just landed. Not an authoring write: the promotion receiver
     * accepts it.
     */
    fun land(
        bound: ArtifactExport<VisualizationBody>,
        workspaceId: UUID,
        actor: UUID,
    ): ArtifactImported = visualizations.import(workspaceId, bound, actor)

    private fun objectOrRefuse(entry: JsonNode): ObjectNode =
        entry as? ObjectNode
            ?: throw ApiException(VisualizationErrorCodes.BODY_INVALID, NOT_AN_OBJECT, mapOf("reason" to ApiErrors.REASON_WRONG_TYPE))

    /** The family's catalogued 404, naming the NAME the caller submitted — never what the lens hides. */
    private fun visualizationNotFound(name: String): ApiException =
        ApiException(VisualizationErrorCodes.NOT_FOUND, "Visualization '$name' was not found.", mapOf("id" to name))
}

/**
 * The dashboard twin of [VisualizationPromotion]. A dashboard ROOT carries its pinned visualizations WITH it —
 * the sender derives their dependency entries, and the receiver lands the batch's visualizations before any
 * dashboard (D61), so the dashboard's validation sees every pin RELEASED inside the transaction.
 */
class DashboardPromotion(
    private val repository: DashboardRepository,
    private val dashboards: DashboardService,
    private val transfer: ArtifactTransferService,
) {
    /**
     * The sender's entries for one dashboard root: each pinned visualization's dependency entry first, the
     * dashboard's own payload last. Null REFUSES — the dashboard is absent, not newer, not released, or the
     * view does not admit it (the same refuse-not-drop rule the other roots follow).
     */
    fun entriesForRoot(
        workspaceId: UUID,
        name: String,
        target: PromotionWire.Entry?,
        view: PromotableView,
        visualizationTargets: Map<String, PromotionWire.Entry>,
        visualizationPromotion: VisualizationPromotion,
    ): List<JsonNode>? {
        val version = promotableDashboardRelease(workspaceId, name, target, view) ?: return null
        val dependencies =
            version.body.visualizations
                .map { it.visualization }
                .distinct()
                .mapNotNull { pin ->
                    visualizationPromotion.entryForPin(workspaceId, pin.name, pin.version, visualizationTargets[pin.name])
                }
        return dependencies + ArtifactTransferService.payloadOf(version)
    }

    /** The admitted, released, newer-than-target current release of dashboard [name] — the root guards in one read. */
    private fun promotableDashboardRelease(
        workspaceId: UUID,
        name: String,
        target: PromotionWire.Entry?,
        view: PromotableView,
    ): ArtifactVersion<DashboardBody>? {
        val record = repository.findRecordByName(workspaceId, name)
        val current = record?.currentVersion
        val detail = current?.let { repository.findVersionDetail(workspaceId, record.id, it) }
        val promotable =
            view.dashboardsLens.admits(name) && record != null && current != null &&
                detail?.status == PipelineVersionStatus.RELEASED && PromotableView.isNewer(current, detail.bodyHash, target)
        if (!promotable || record == null || current == null) return null
        return repository.findVersion(workspaceId, record.id, current)
    }

    /** The receiver's BIND of one dashboard entry — the ONE entry bind through the dashboard READER. */
    fun bind(entry: JsonNode): ArtifactExport<DashboardBody> =
        transfer.dashboardEntry(
            entry as? ObjectNode
                ?: throw ApiException(DashboardErrorCodes.BODY_INVALID, NOT_AN_OBJECT, mapOf("reason" to ApiErrors.REASON_WRONG_TYPE)),
        )

    /**
     * The receiver's LANDING of one bound dashboard — inside the transaction, after every visualization it pins:
     * `validateForImport` judges the pinned set's, pipelines' and visualizations' statuses against the rows the
     * SAME transaction has just landed, and any refusal rolls the whole batch back.
     */
    fun land(
        bound: ArtifactExport<DashboardBody>,
        workspaceId: UUID,
        actor: UUID,
    ): ArtifactImported = dashboards.import(workspaceId, bound, actor)
}
