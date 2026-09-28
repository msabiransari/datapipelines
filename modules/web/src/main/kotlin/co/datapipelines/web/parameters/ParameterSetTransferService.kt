package co.datapipelines.web.parameters

import co.datapipelines.parameters.ParameterErrorCodes
import co.datapipelines.parameters.ParameterSetBody
import co.datapipelines.parameters.ParameterSetExport
import co.datapipelines.parameters.ParameterSetJson
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.parameters.ParameterSetImported
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
        val record = repository.findRecord(workspaceId, id) ?: throw ApiErrors.parameterNotFound(id.toString())
        val current =
            record.currentVersion
                ?: throw notReleased(record.name)
        val detail =
            repository.findVersionDetail(workspaceId, id, current) ?: throw ApiErrors.parameterNotFound(id.toString())
        if (detail.status != PipelineVersionStatus.RELEASED) throw notReleased(record.name)
        val version = repository.findVersion(workspaceId, id, current) ?: throw ApiErrors.parameterNotFound(id.toString())
        val pins = version.body.parameters.mapNotNull { it.source?.template }.distinct()
        val bundled = pinnedClosure(workspaceId, pins)
        val payload = ParameterSetResponses.full(record, version.body, detail) as ObjectNode
        payload.put("version", current)
        payload.put("body_hash", detail.bodyHash)
        detail.releasedAt?.let { payload.put("released_at", it.toString()) }
        return mapOf(
            "parameter_set" to payload,
            "templates" to bundled,
            "manifest" to
                mapOf(
                    "parameter_set_id" to record.id.toString(),
                    "parameter_set_version" to current,
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
        val tree = MAPPER.readTree(body) as? ObjectNode ?: throw ApiErrors.malformedParameterSetBody()
        val payload =
            tree.get("parameter_set")?.takeIf(JsonNode::isObject) as? ObjectNode
                ?: throw ApiErrors.malformedParameterSetBody()
        // Templates before sets (record §8.3): a pin the bundle brings must be stored before the
        // set's validation resolves it. Already-present versions are the template import's
        // idempotent no-op.
        tree.get("templates")?.takeIf(JsonNode::isArray)?.let { bundled ->
            val envelope = MAPPER.createObjectNode()
            envelope.set<JsonNode>("templates", bundled)
            templateImport.import(MAPPER.writeValueAsString(envelope), workspaceId, actor)
        }
        val export = exportPayload(payload) ?: throw ApiErrors.malformedParameterSetBody()
        return try {
            sets.import(workspaceId, export, actor)
        } catch (e: DuplicateKeyException) {
            // Defence in depth for C29: the repository maps the id PK collision itself; a raw
            // duplicate surfacing from another statement is the same refusal, never a 500.
            throw idTaken(export.id, e)
        }
    }

    /** The `ParameterSetExport` the service imports, judged strictly (an unknown shape is refused, never guessed). */
    private fun exportPayload(payload: ObjectNode): ParameterSetExport? {
        val id =
            payload.get("id")?.takeIf(JsonNode::isTextual)?.asText()
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val name = payload.get("name")?.takeIf(JsonNode::isTextual)?.asText() ?: return null
        val version = payload.get("version")?.takeIf(JsonNode::isInt)?.asInt()
        val bodyHash = payload.get("body_hash")?.takeIf(JsonNode::isTextual)?.asText()
        val releasedAt = payload.get("released_at")?.takeIf(JsonNode::isTextual)?.asText()?.let(Instant::parse)
        val body = runCatching { MAPPER.treeToValue(payload, ParameterSetBody::class.java) }.getOrNull() ?: return null
        return ParameterSetExport(id = id, name = name, version = version, bodyHash = bodyHash, releasedAt = releasedAt, body = body)
    }

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
            if (!seen.add(ref.key)) continue
            val version = templates.lookupVersion(workspaceId, ref.id, ref.version) ?: continue
            templates.findVersion(workspaceId, ref.id, ref.version)?.let(found::add)
            version.imports.forEach { queue.addLast(TemplateRef(it.id, it.version)) }
        }
        return found
    }

    private fun notReleased(name: String): ApiException =
        ApiException(
            ParameterErrorCodes.NOT_FOUND,
            "Parameter set '${name.take(64)}' has no released version to export. Release it first — " +
                "an export is what a promotion import consumes, and a draft never crosses environments.",
            mapOf("parameter_set" to name.take(64)),
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
    }
}
