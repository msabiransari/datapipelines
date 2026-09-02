package co.datapipelines.web.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDeserializationOutcome
import co.datapipelines.templates.TemplateDeserializer
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateJson
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateTypeRule
import co.datapipelines.templates.TemplateValidationException
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID

/**
 * The template-library **import** act (rest-api.md §8.8, versioning §9), lifted out of
 * [TemplatesController] so a second caller can perform it.
 *
 * ## Two modes, decided per entry (the §9.2 mirror)
 *
 * - **Version-less** (the historical shape): each entry is created, or versioned onto its
 *   id when that id already exists.
 * - **Preserved-version**: an entry carrying `version` is honored at its EXACT number —
 *   imports never renumber (D5: cross-env renumbering breaks pipeline pins, §9.1's
 *   verified defect). `body_hash` must ride along and is recomputed from the entry's
 *   content fields; a mismatch refuses the entry (the catalogued code for the
 *   template-side recompute guard is `template.version.conflict` with
 *   `details.reason = "hash_mismatch"` — `template.import.hash_mismatch` does not exist
 *   in §13, and this round adds no rows; the gap is raised in the handback).
 *
 * Per-entry semantics are unchanged — a failure aborts at that entry leaving the earlier
 * ones (a library import is re-runnable).
 */
class TemplateImportService(
    private val templates: TemplateRepository,
    private val validator: TemplateValidator,
    private val deserializer: TemplateDeserializer = TemplateDeserializer(),
) {
    /**
     * Imports the `{"templates": [...]}` [body] into [workspaceId] on behalf of [actorId].
     *
     * @return the stored templates, in body order.
     */
    fun import(
        body: String,
        workspaceId: UUID,
        actorId: UUID,
    ): List<Template> =
        importEntries(body).map { (entry, preserved) ->
            val draft = validator.validateOrThrow(entry, workspaceId)
            if (preserved != null) {
                importPreserved(workspaceId, draft, preserved, actorId)
            } else {
                importNextLocal(workspaceId, draft, actorId)
            }
        }

    /** The `templates` array, each entry deserialized to a draft beside its preserved-version fields. */
    @Suppress("ThrowsCount") // a boundary maps each distinct failure to its own catalogued 4xx
    private fun importEntries(body: String): List<Pair<TemplateDraft, Preserved?>> {
        val tree =
            MAPPER.readTree(body) as? ObjectNode
                ?: throw ApiException(
                    PipelineErrorCodes.Template.SCHEMA_VERSION_UNSUPPORTED,
                    "The import request body must be a JSON object.",
                    mapOf(ApiErrors.REASON to ApiErrors.MALFORMED_JSON),
                )
        val array =
            tree.get("templates")?.takeIf { it.isArray }
                ?: throw ApiException(
                    PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                    "The import request requires a 'templates' array.",
                    mapOf(ApiErrors.REASON to "templates_missing"),
                )
        return array.map { entry ->
            val outcome = deserializer.fromTree(entry)
            when (outcome) {
                is TemplateDeserializationOutcome.Parsed -> outcome.draft to preservedOf(entry as? ObjectNode)
                is TemplateDeserializationOutcome.Rejected -> throw TemplateValidationException(outcome.result)
            }
        }
    }

    /** The §9.2 per-entry payload: `version` plus the `body_hash` / `released_at` riding with it. */
    private data class Preserved(
        val version: Int,
        val bodyHash: String?,
        val releasedAt: Instant?,
    )

    private fun preservedOf(entry: ObjectNode?): Preserved? {
        if (entry == null) return null
        val versionNode = entry.get("version") ?: return null
        if (!versionNode.isInt || versionNode.asInt() < 1) {
            throw ApiException(
                PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                "'version', when present on a template import entry, must be a positive integer.",
                mapOf("version" to versionNode.asText().take(MAX_ECHOED_VALUE_CHARS)),
            )
        }
        val textual: (String) -> String? = { field -> entry.get(field)?.takeIf { it.isTextual }?.asText() }

        return Preserved(
            version = versionNode.asInt(),
            bodyHash = textual("body_hash"),
            releasedAt = textual("released_at")?.let(Instant::parse),
        )
    }

    /** Version-less import — today's create-or-version behavior. */
    private fun importNextLocal(
        workspaceId: UUID,
        draft: TemplateDraft,
        actorId: UUID,
    ): Template {
        val id = draft.id
        return if (id != null && templates.existsId(workspaceId, id)) {
            // 046 §5.3: importing onto an existing template inherits its established type; a
            // differing payload type is the same refusal a PUT would get.
            val resolved = resolvedAgainstExisting(workspaceId, id, draft)
            templates.appendReleasedVersion(workspaceId, id, resolved, actorId) ?: throw ApiErrors.templateNotFound(id)
        } else {
            templates.create(workspaceId, draft, actorId)
        }
    }

    /** Preserved-version import — §9.2's table, mirrored. */
    @Suppress("ThrowsCount") // each §9.2 row is its own refusal
    private fun importPreserved(
        workspaceId: UUID,
        draft: TemplateDraft,
        preserved: Preserved,
        actorId: UUID,
    ): Template {
        val id =
            draft.id
                ?: throw ApiException(
                    PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                    "A preserved-version template import entry requires an 'id'.",
                    mapOf("reason" to "id_missing", "template_version" to preserved.version),
                )
        val recomputed =
            templates.computeBodyHash(
                engine = draft.engine,
                dialect = draft.dialect?.wire,
                isLibrary = draft.isLibrary,
                importsJson = TemplateJson.writeImports(draft.imports),
                body = draft.body,
            )
        val declared = preserved.bodyHash
        if (declared == null || declared != recomputed) {
            // The transfer-corruption / canonicalization-drift catcher (§9.2). The
            // template-side code for it does not exist in §13; this round adds no rows —
            // the internally-inconsistent payload surfaces as the mirrored conflict.
            throw ApiException(
                PipelineErrorCodes.Template.VERSION_CONFLICT,
                if (declared == null) {
                    "A template import entry carrying 'version' must also carry its 'body_hash'."
                } else {
                    "The import entry's declared body_hash does not match its content."
                },
                mapOf(
                    "reason" to "hash_mismatch",
                    "template_id" to id,
                    "template_version" to preserved.version,
                    "declared_body_hash" to (declared?.take(MAX_ECHOED_VALUE_CHARS) ?: ""),
                    "recomputed_body_hash" to recomputed.take(MAX_ECHOED_VALUE_CHARS),
                ),
            )
        }

        if (!templates.existsId(workspaceId, id)) {
            return templates.importTemplateVersion(workspaceId, draft, preserved.version, declared, preserved.releasedAt, actorId)
        }
        val target = templates.findVersionDetail(workspaceId, id, preserved.version)
        return when {
            target == null -> {
                // 046 §5.3: an exact-version import onto an existing template inherits its
                // established type — the promotion copy of the immutability rule (a promoted
                // html template re-importing onto its sql namesake is refused, not coerced).
                val resolved = resolvedAgainstExisting(workspaceId, id, draft)
                templates.insertReleasedVersion(workspaceId, id, resolved, preserved.version, declared, preserved.releasedAt, actorId)
                templates.findVersion(workspaceId, id, preserved.version) ?: throw ApiErrors.templateNotFound(id)
            }

            target.status == PipelineVersionStatus.RELEASED && target.bodyHash == declared -> {
                // Idempotent no-op: re-importing an old export is safe (§9.2).
                templates.findVersion(workspaceId, id, preserved.version) ?: throw ApiErrors.templateNotFound(id)
            }

            else -> {
                throw versionConflict(id, target, preserved)
            }
        }
    }

    /**
     * The §5.3 (046) existing-template resolution for imports: the payload inherits the
     * template's established type or is refused. Reads the working version's type — identical
     * across every version of the template by rule, so "latest" is as authoritative as any.
     */
    private fun resolvedAgainstExisting(
        workspaceId: UUID,
        id: String,
        draft: TemplateDraft,
    ): TemplateDraft {
        val established =
            templates.findLatest(workspaceId, id)?.type
                ?: throw ApiErrors.templateNotFound(id)
        return TemplateTypeRule.forExisting(draft, established)
    }

    private fun versionConflict(
        id: String,
        target: co.datapipelines.templates.TemplateVersionDetail,
        preserved: Preserved,
    ): ApiException =
        ApiException(
            PipelineErrorCodes.Template.VERSION_CONFLICT,
            when (target.status) {
                PipelineVersionStatus.DRAFT -> {
                    "Version ${preserved.version} of '$id' exists as a local draft; a local draft is never clobbered."
                }

                PipelineVersionStatus.DISCARDED -> {
                    "Version ${preserved.version} of '$id' was discarded here; consumed version numbers are never reused."
                }

                PipelineVersionStatus.RELEASED -> {
                    "Version ${preserved.version} of '$id' exists with different content; never overwrite."
                }
            },
            mapOf(
                "template_id" to id,
                "template_version" to preserved.version,
                "declared_body_hash" to (preserved.bodyHash?.take(MAX_ECHOED_VALUE_CHARS) ?: ""),
                "target_body_hash" to target.bodyHash.take(MAX_ECHOED_VALUE_CHARS),
                "target_status" to target.status.name,
            ),
        )

    private companion object {
        val MAPPER = TemplateJson.objectMapper()

        /** Reflected client input is bounded before it reaches an error message. */
        const val MAX_ECHOED_VALUE_CHARS = 64
    }
}
