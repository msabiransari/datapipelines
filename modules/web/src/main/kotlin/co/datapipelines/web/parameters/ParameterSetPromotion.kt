package co.datapipelines.web.parameters

import co.datapipelines.parameters.ParameterErrorCodes
import co.datapipelines.parameters.ParameterSetImported
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.pipelines.PromotionWire
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID

/**
 * The parameter-set half of promotion (the record's §8.3, #194 lane D) — the sender's payload
 * builder and the receiver's import act, ONE collaborator beside [EndpointPromotion] so both
 * ends of the channel use one spelling of the rules.
 *
 * ## The payload
 * The batch entry is the `parameter_set` node of the §21.4 export envelope — the body with its
 * lifecycle fields (`version`, `body_hash`, `released_at`) and the KEPT id (P24). The pinned
 * templates do NOT ride the entry: the sender merges them into the batch's template closure
 * (they must be stored first), and the receiver's import relies on that order — a pin the
 * batch does not bring is `parameter.import.missing_template`.
 */
class ParameterSetPromotion(
    private val repository: co.datapipelines.parameters.ParameterSetRepository,
    private val sets: co.datapipelines.parameters.ParameterSetService,
    private val templates: co.datapipelines.templates.TemplateRepository,
) {
    /**
     * The sender's entry for [name]'s current release, or null when it is not promotable
     * (no release). §10.3's guards: released and NEWER than the target's entry — the same
     * rule the pipeline roots get, the page's rule verbatim.
     */
    fun entryFor(
        workspaceId: UUID,
        name: String,
        target: PromotionWire.Entry?,
    ): JsonNode? {
        val record = repository.findRecordByName(workspaceId, name) ?: return null
        val current = record.currentVersion ?: return null
        val detail = detailOr404less(workspaceId, record.id, current) ?: return null
        val promotable = detail.status == PipelineVersionStatus.RELEASED && newer(detail, current, target)
        val version = if (promotable) repository.findVersion(workspaceId, record.id, current) else null
        return payloadOf(record, detail, version, current)
    }

    private fun detailOr404less(
        workspaceId: UUID,
        id: UUID,
        current: Int,
    ): co.datapipelines.parameters.ParameterSetVersionDetail? = repository.findVersionDetail(workspaceId, id, current)

    /** §10.2: same hash or not newer is nothing to push (hash is for machines). */
    private fun newer(
        detail: co.datapipelines.parameters.ParameterSetVersionDetail,
        current: Int,
        target: PromotionWire.Entry?,
    ): Boolean = target == null || (target.bodyHash != detail.bodyHash && current > target.currentVersion)

    private fun payloadOf(
        record: co.datapipelines.parameters.ParameterSetRecord,
        detail: co.datapipelines.parameters.ParameterSetVersionDetail,
        version: co.datapipelines.parameters.ParameterSetVersion?,
        current: Int,
    ): JsonNode? {
        if (version == null) return null
        val payload = ParameterSetResponses.full(record, version.body, detail) as ObjectNode
        payload.put("version", current)
        payload.put("body_hash", detail.bodyHash)
        detail.releasedAt?.let { payload.put("released_at", it.toString()) }
        return payload
    }

    /** The pinned template versions of the set [entry] carries — merged into the batch's template closure. */
    fun templatePins(entry: JsonNode): List<TemplateRef> =
        entry
            .path("parameters")
            .asSequence()
            .asIterable()
            .mapNotNull { parameter ->
                parameter
                    .path("source")
                    .path("template")
                    .takeIf { ref -> ref.has("id") }
            }.map { ref -> TemplateRef(ref.path("id").asText(), ref.path("version").asInt()) }
            .distinct()
            .toList()

    /**
     * The receiver's import of one batch entry: the set is validated against the TARGET's
     * templates and datasources (the service's §4 steps 4–6 re-run — a pin the batch did not
     * bring is `parameter.import.missing_template`), lands RELEASED at the exported version
     * with the id KEPT (P24), and an id taken by another workspace's set is refused `id_taken`
     * (C29 — never re-issued).
     *
     * The body binds through [parameterSetBodyOf] — the lifecycle keys stripped by name, the
     * strict mapper still refusing every other undeclared key (#299; the same bind the REST
     * import makes).
     *
     * Not an authoring write: the promotion receiver accepts it.
     */
    fun apply(
        entry: JsonNode,
        workspaceId: UUID,
        actor: UUID,
    ): ParameterSetImported {
        val id = UUID.fromString(entry.path("id").asText())
        val name = entry.path("name").asText()
        val version = entry.get("version")?.takeIf(JsonNode::isInt)?.asInt()
        val bodyHash = entry.get("body_hash")?.takeIf(JsonNode::isTextual)?.asText()
        val releasedAt =
            entry
                .get("released_at")
                ?.takeIf(JsonNode::isTextual)
                ?.asText()
                ?.let(Instant::parse)
        val body =
            parameterSetBodyOf(entry as? ObjectNode ?: throw ApiErrors.malformedParameterSetBody())
                ?: throw ApiErrors.malformedParameterSetBody()
        return sets.import(
            workspaceId,
            co.datapipelines.parameters.ParameterSetExport(
                id = id,
                name = name,
                version = version,
                bodyHash = bodyHash,
                releasedAt = releasedAt,
                body = body,
            ),
            actor,
        )
    }
}
