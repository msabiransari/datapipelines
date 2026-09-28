package co.datapipelines.web.parameters

import co.datapipelines.parameters.ParameterErrorCodes
import co.datapipelines.parameters.ParameterSetBody
import co.datapipelines.parameters.ParameterSetExport
import co.datapipelines.parameters.ParameterSetImported
import co.datapipelines.parameters.ParameterSetJson
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.templates.TemplateImportService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.dao.DuplicateKeyException
import java.time.Instant
import java.util.UUID

/**
 * The parameter-set **export/import** acts (record §8.3; the `PipelineImportService` /
 * `PipelineTransferController` shape, lifted from the controller so the promotion receive path
 * can perform the SAME import).
 *
 * ## The envelope
 * `GET /{id}/export` answers `{"parameter_set": body + lifecycle fields, "templates": [pinned
 * versions], "manifest": {…}}`. The templates ride with it — a set is not runnable on a target
 * whose pins are missing, so the bundle carries the pinned versions' transitive `imports`
 * closure exactly as a pipeline bundle does. `POST /import` reads the same envelope: the
 * `templates` array, when present, is imported FIRST (the promotion order — templates before
 * sets), then the set; a pin this deployment still lacks is `parameter.import.missing_template`.
 *
 * ## The id is kept (P24) — and C16/C29 decided
 * The export carries the set's id and the import keeps it, so the id is stable across
 * environments. `parameter_sets.id` is the PRIMARY KEY of the whole server, so a kept id already
 * held by ANOTHER workspace's set collides on the database's only atomic authority — the
 * repository maps that to `parameter.version.conflict` with `details.reason = id_taken`
 * (lane B's `mappingUniqueViolations`). **C29 decision: REFUSE, never re-issue.** Re-issuing
 * would break the identity P24 grants ("the id is stable across environments"); an operator who
 * sees `id_taken` learns the set already exists somewhere here — exactly what an accidental
 * re-push of a live set should say.
 */
class ParameterSetTransferService(
    private val sets: ParameterSetService,
    private val repository: ParameterSetRepository,
    private val templates: co.datapipelines.templates.TemplateRepository,
    private val templateImport: TemplateImportService,
) {
    /** The export bundle for [id]'s CURRENT release (a set with no release has nothing to export). */
    fun export(
        workspaceId: UUID,
        id: UUID,
    ): Map<String, Any?> {
        val exported = releasedFor(workspaceId, id)
        val record = exported.record
        val detail = exported.detail
        val version = exported.version
        val pins =
            version
                .body
                .parameters
                .mapNotNull { it.source?.template }
                .distinct()
        val bundled = pinnedClosure(workspaceId, pins)
        val payload = fullPayload(record, version.body, detail)
        payload.put("version", exported.detail.version)
        payload.put("body_hash", detail.bodyHash)
        detail.releasedAt?.let { payload.put("released_at", it.toString()) }
        return mapOf(
            "parameter_set" to payload,
            "templates" to bundled,
            "manifest" to
                mapOf(
                    "parameter_set_id" to record.id.toString(),
                    "parameter_set_version" to detail.version,
                    "parameter_set_body_hash" to detail.bodyHash,
                    "template_pins" to pins.map { mapOf("id" to it.id, "version" to it.version) },
                    "exported_at" to Instant.now().toString(),
                ),
        )
    }

    /**
     * Imports [body] — the export envelope — into [workspaceId] on behalf of [actor].
     * Templates first (when the bundle carries them), then the set; the id is kept; the
     * same-version-same-hash re-import is the service's idempotent no-op.
     */
    fun import(
        body: String,
        workspaceId: UUID,
        actor: UUID,
    ): ParameterSetImported {
        val payload = importPayload(body)
        // Templates before sets (record §8.3): a pin the bundle brings must be stored before the
        // set's validation resolves it. Already-present versions are the template import's
        // idempotent no-op.
        importBundledTemplates(payload, workspaceId, actor)
        val export = exportPayload(payload) ?: throw ApiErrors.malformedParameterSetBody()
        return try {
            sets.import(workspaceId, export, actor)
        } catch (e: DuplicateKeyException) {
            // Defence in depth for C29: the repository maps the id PK collision itself; a raw
            // duplicate surfacing from another statement is the same refusal, never a 500.
            throw idTaken(export.id, e)
        }
    }

    /** The envelope's `parameter_set` object — every miss is the same malformed-envelope refusal. */
    private fun importPayload(body: String): ObjectNode {
        val tree = MAPPER.readTree(body) as? ObjectNode ?: throw ApiErrors.malformedParameterSetBody()
        return tree.get("parameter_set")?.takeIf(JsonNode::isObject) as? ObjectNode
            ?: throw ApiErrors.malformedParameterSetBody()
    }

    /** The bundle's optional `templates` array, imported FIRST (the promotion order — §8.3). */
    private fun importBundledTemplates(
        payload: ObjectNode,
        workspaceId: UUID,
        actor: UUID,
    ) {
        val bundled = MAPPER.readTree(payload.toString()).get("templates") ?: return
        if (!bundled.isArray) return
        val envelope = MAPPER.createObjectNode()
        envelope.set<JsonNode>("templates", bundled)
        templateImport.import(MAPPER.writeValueAsString(envelope), workspaceId, actor)
    }

    private data class Exported(
        val record: co.datapipelines.parameters.ParameterSetRecord,
        val detail: co.datapipelines.parameters.ParameterSetVersionDetail,
        val version: co.datapipelines.parameters.ParameterSetVersion,
    )

    /** The RELEASED current version an export needs; every miss is the catalogued 404 or the release refusal. */
    private fun releasedFor(
        workspaceId: UUID,
        id: UUID,
    ): Exported {
        val record = repository.findRecord(workspaceId, id) ?: throw ApiErrors.parameterNotFound(id.toString())
        val current = record.currentVersion ?: throw notReleased(record.name)
        return releasedVersion(workspaceId, record, current)
    }

    private fun releasedVersion(
        workspaceId: UUID,
        record: co.datapipelines.parameters.ParameterSetRecord,
        current: Int,
    ): Exported {
        val detail = detailOr404(workspaceId, record.id, current)
        releaseOr404(record.name, detail)
        return Exported(record, detail, versionOr404(workspaceId, record.id, current))
    }

    private fun detailOr404(
        workspaceId: UUID,
        id: UUID,
        current: Int,
    ): co.datapipelines.parameters.ParameterSetVersionDetail =
        repository.findVersionDetail(workspaceId, id, current)
            ?: throw ApiErrors.parameterNotFound(id.toString())

    private fun releaseOr404(
        name: String,
        detail: co.datapipelines.parameters.ParameterSetVersionDetail,
    ) {
        if (detail.status != PipelineVersionStatus.RELEASED) throw notReleased(name)
    }

    private fun versionOr404(
        workspaceId: UUID,
        id: UUID,
        current: Int,
    ): co.datapipelines.parameters.ParameterSetVersion =
        repository.findVersion(workspaceId, id, current)
            ?: throw ApiErrors.parameterNotFound(id.toString())

    private fun fullPayload(
        record: co.datapipelines.parameters.ParameterSetRecord,
        body: co.datapipelines.parameters.ParameterSetBody,
        detail: co.datapipelines.parameters.ParameterSetVersionDetail,
    ): ObjectNode = ParameterSetResponses.full(record, body, detail) as ObjectNode

    /** The `ParameterSetExport` the service imports, judged strictly (an unknown shape is refused, never guessed). */
    private fun exportPayload(payload: ObjectNode): ParameterSetExport? {
        val exported =
            Export(
                id = textual(payload, "id")?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                name = textual(payload, "name"),
                bodyHash = textual(payload, "body_hash"),
                releasedAt = textual(payload, "released_at")?.let(Instant::parse),
                body = runCatching { MAPPER.treeToValue(payload, ParameterSetBody::class.java) }.getOrNull(),
                version = payload.get("version")?.takeIf(JsonNode::isInt)?.asInt(),
            )
        return exported.asParameterExport()
    }

    /** The payload's fields as read; [asParameterExport] decides which are load-bearing. */
    private data class Export(
        val id: UUID?,
        val name: String?,
        val bodyHash: String?,
        val releasedAt: Instant?,
        val body: ParameterSetBody?,
        val version: Int?,
    ) {
        fun asParameterExport(): ParameterSetExport? {
            val complete =
                listOf(id, name, bodyHash, body).all { it != null }
            if (!complete) return null
            return ParameterSetExport(
                id = checkNotNull(id),
                name = checkNotNull(name),
                version = version,
                bodyHash = checkNotNull(bodyHash),
                releasedAt = releasedAt,
                body = checkNotNull(body),
            )
        }
    }

    /** The object's textual field, or null when absent or not a string. */
    private fun textual(
        payload: ObjectNode,
        field: String,
    ): String? = payload.get(field)?.takeIf(JsonNode::isTextual)?.asText()

    /** Direct pins plus the transitive `imports` closure, deduplicated — the pipeline bundle's walk. */
    private fun pinnedClosure(
        workspaceId: UUID,
        refs: List<TemplateRef>,
    ): List<co.datapipelines.templates.Template> {
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(refs)
        val found = mutableListOf<co.datapipelines.templates.Template>()
        while (queue.isNotEmpty()) {
            val ref = queue.removeFirst()
            if (seen.add(ref.key)) {
                visit(workspaceId, ref, queue, found)
            }
        }
        return found
    }

    private fun visit(
        workspaceId: UUID,
        ref: TemplateRef,
        queue: ArrayDeque<TemplateRef>,
        found: MutableList<co.datapipelines.templates.Template>,
    ) {
        val version = templates.lookupVersion(workspaceId, ref.id, ref.version) ?: return
        templates.findVersion(workspaceId, ref.id, ref.version)?.let(found::add)
        version.imports.forEach { queue.addLast(TemplateRef(it.id, it.version)) }
    }

    private fun notReleased(name: String): ApiException =
        ApiException(
            ParameterErrorCodes.NOT_FOUND,
            "Parameter set '${name.take(MAX_ECHOED_NAME_CHARS)}' has no released version to export. Release it first — " +
                "an export is what a promotion import consumes, and a draft never crosses environments.",
            mapOf("parameter_set" to name.take(MAX_ECHOED_NAME_CHARS)),
        )

    private fun idTaken(
        id: UUID,
        cause: Throwable,
    ): ApiException =
        ApiException(
            ParameterErrorCodes.VERSION_CONFLICT,
            "Parameter set id $id is already taken on this server — ids are globally unique (P24), so it belongs " +
                "to a set of another workspace. Nothing was imported.",
            mapOf("reason" to "id_taken", "id" to id.toString()),
            cause,
        )

    private companion object {
        val MAPPER = ParameterSetJson.mapper

        /** Reflected client input is bounded before it reaches a refusal's text. */
        const val MAX_ECHOED_NAME_CHARS = 64
    }
}
