package co.datapipelines.web.parameters

import co.datapipelines.parameters.ParameterErrorCodes
import co.datapipelines.parameters.ParameterSetBody
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
 * builder and the receiver's validation-and-landing act, ONE collaborator beside [EndpointPromotion]
 * so both ends of the channel use one spelling of the rules.
 *
 * ## The payload
 * The batch entry is the `parameter_set` node of the §21.4 export envelope — the body with its
 * lifecycle fields (`version`, `body_hash`, `released_at`) and the KEPT id (P24). The pinned
 * templates do NOT ride the entry: the sender merges them into the batch's template closure
 * (they must be stored first), and the receiver's import relies on that order — a pin the batch
 * does not bring is `parameter.import.missing_template`.
 *
 * ## The receive's two acts (#302, record §18 C36)
 * The set's §4 validation (the selector probe among it — a CUSTOMER-datasource connection) runs
 * BEFORE the receive's one transaction opens ([validate], through [ParameterSetReceiveValidation]'s
 * batch template view); the transaction body LANDS the pre-validated entries ([land] → the
 * service's `importValidated`, no re-probe). A refusal at validation means nothing has landed;
 * a refusal at landing rolls the transaction back whole — templates included.
 */
class ParameterSetPromotion(
    private val repository: co.datapipelines.parameters.ParameterSetRepository,
    private val sets: co.datapipelines.parameters.ParameterSetService,
    private val templates: co.datapipelines.templates.TemplateRepository,
    /** The receive's out-of-transaction §4 validation over the batch's template view (#302, C36). */
    private val receiveValidation: ParameterSetReceiveValidation,
) {
    /** One bound batch entry: the shape-checked lifecycle fields and the body, not yet validated. */
    data class Bound(
        val id: UUID,
        val name: String,
        val version: Int?,
        val bodyHash: String?,
        val releasedAt: Instant?,
        val body: ParameterSetBody,
    )

    /** One validated entry: its binding and the CANONICAL body the transaction body lands. */
    data class Validated(
        val bound: Bound,
        val canonical: ParameterSetBody,
    )
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
     * The receiver's BIND of one batch entry — the #300 shape checks and the lifecycle parse (#299's
     * strict bind through [parameterSetBodyOf]), with NO validation: a refusal here is a shape
     * refusal (`body_invalid`, `details.path`/`details.reason`), echoed without the entry. The first
     * half of [validate]; public so the shape rules are testable without the validator's ports.
     */
    @Suppress("ThrowsCount") // each throw is a distinct catalogued shape refusal — path and reason are the wire
    fun bind(entry: JsonNode): Bound {
        // The entry's SHAPE is judged before anything is parsed or resolved (#300 — observation 8):
        // a missing id reached `UUID.fromString("")` as an uncatalogued 500 and a missing name was
        // read as "". The refusal echoes only `details.path`/`details.reason`, never the entry.
        val objectEntry = entry as? ObjectNode ?: throw ApiErrors.parameterSetBodyInvalid("parameter_set", ApiErrors.REASON_WRONG_TYPE)
        val idNode = objectEntry.get("id") ?: throw ApiErrors.parameterSetBodyInvalid("id", ApiErrors.REASON_MISSING)
        if (!idNode.isTextual) throw ApiErrors.parameterSetBodyInvalid("id", ApiErrors.REASON_WRONG_TYPE)
        val id =
            runCatching { UUID.fromString(idNode.asText()) }
                .getOrElse { throw ApiErrors.parameterSetBodyInvalid("id", ApiErrors.REASON_WRONG_TYPE) }
        val nameNode = objectEntry.get("name") ?: throw ApiErrors.parameterSetBodyInvalid("name", ApiErrors.REASON_MISSING)
        if (!nameNode.isTextual) throw ApiErrors.parameterSetBodyInvalid("name", ApiErrors.REASON_WRONG_TYPE)
        if (nameNode.asText().isBlank()) throw ApiErrors.parameterSetBodyInvalid("name", ApiErrors.REASON_MISSING)
        val version = objectEntry.get("version")?.takeIf(JsonNode::isInt)?.asInt()
        val bodyHash = objectEntry.get("body_hash")?.takeIf(JsonNode::isTextual)?.asText()
        val releasedAt =
            objectEntry
                .get("released_at")
                ?.takeIf(JsonNode::isTextual)
                ?.asText()
                ?.let(Instant::parse)
        val body =
            parameterSetBodyOf(objectEntry)
                ?: throw ApiErrors.malformedParameterSetBody()
        return Bound(id, nameNode.asText(), version, bodyHash, releasedAt, body)
    }

    /**
     * The receiver's VALIDATION of the batch's set entries — BEFORE the receive's one transaction
     * opens (#302, C36): the full record §4 against the receiver's own datasources (the selector
     * probe included — legal outside the transaction only), with the batch's template payloads
     * overlaying the receiver's registry, so a pin the SAME batch brings resolves and a pin neither
     * the batch brings nor the receiver holds is `parameter.import.missing_template`. Answers the
     * canonical bodies the transaction body lands; a refusal here has landed NOTHING.
     */
    fun validate(
        entries: List<JsonNode>,
        batchTemplates: List<JsonNode>,
        workspaceId: UUID,
    ): List<Validated> = receiveValidation.validate(workspaceId, batchTemplates, entries.map { bind(it) })

    /**
     * The receiver's LANDING of one validated entry — the transaction body's act (C36): the kept id,
     * the hash check and the §9.2 version rules through the service's `importValidated`, which does
     * NOT re-validate (the probe already ran, outside). Not an authoring write: the promotion
     * receiver accepts it.
     */
    fun land(
        validated: Validated,
        workspaceId: UUID,
        actor: UUID,
    ): ParameterSetImported =
        sets.importValidated(
            workspaceId,
            co.datapipelines.parameters.ParameterSetExport(
                id = validated.bound.id,
                name = validated.bound.name,
                version = validated.bound.version,
                bodyHash = validated.bound.bodyHash,
                releasedAt = validated.bound.releasedAt,
                body = validated.canonical,
            ),
            actor,
        )
}
