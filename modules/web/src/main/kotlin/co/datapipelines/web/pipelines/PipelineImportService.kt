package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.ContextKeys
import co.datapipelines.pipeline.NewPipeline
import co.datapipelines.pipeline.OrgContext
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineSerializer
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.dao.DuplicateKeyException
import java.time.Instant
import java.util.UUID

/**
 * The pipeline **import** act (rest-api.md §5.8, versioning §9), lifted out of
 * [PipelineTransferController] so a second caller can perform it.
 *
 * ## Two modes, decided by the payload
 *
 * - **Version-less** (the historical shape): allocate the next local version. An `id` that
 *   exists appends to that pipeline; otherwise the pipeline is created under that id.
 * - **Preserved-version** (versioning §9.2, D5): when the payload carries `version` —
 *   promotion always sends it, and so does export — the version number is **honored**:
 *   imports never renumber, because cross-environment renumbering silently breaks template
 *   pins (§9.1's verified latent defect). `body_hash` must ride along and is recomputed
 *   from the payload body (`pipeline.import.hash_mismatch` on a mismatch — the transfer-
 *   corruption and canonicalization-drift catcher). `released_at` is honored so §8's
 *   draft-run derivation stays truthful on the target.
 *
 * The D9 example seeder (`ExampleContentSeeder`) supplies the freshly provisioned personal
 * workspace and its owner through this same path; extracting it — rather than letting the
 * seeder re-implement an import — is what keeps the §12 validation, the id-collision
 * handling and the §13.2 error re-labelling identical on both paths.
 *
 * Everything HTTP stays in the controller: this returns [Imported] and lets the caller
 * decide between `201` and `200`.
 */
class PipelineImportService(
    private val pipelines: PipelineRepository,
    private val validator: PipelineValidator,
    private val deserializer: PipelineDeserializer = PipelineDeserializer(),
    private val serializer: PipelineSerializer = PipelineSerializer(),
    /**
     * The RECEIVING deployment's org tier (calculators design §0.5). Defaulted so the many
     * direct constructions keep compiling; production wires the bound one, which is the whole
     * point — the check below is about what THIS deployment provides, not what the sender did.
     */
    private val orgContext: OrgContext = OrgContext.DEFAULTS,
    /** Null disables the §0.5 bind scan — the seeder path, whose content is authored here. */
    private val templates: TemplateDryRenderer? = null,
) {
    /** What the import stored, plus whether the pipeline row was created (`201`) or gained a version (`200`). */
    data class Imported(
        val record: PipelineRecord,
        val canonical: String,
        val created: Boolean,
    )

    /**
     * Imports [body] — the portable pipeline JSON — into [workspaceId] on behalf of [actorId].
     *
     * @throws ApiException / `PipelineValidationException` exactly as the endpoint always has.
     */
    @Suppress("ThrowsCount", "LongMethod") // a boundary maps each distinct failure to its own catalogued 4xx
    fun import(
        body: String,
        workspaceId: UUID,
        actorId: UUID,
    ): Imported {
        val tree = MAPPER.readTree(body) as? ObjectNode ?: throw ApiErrors.malformedPipelineBody(IllegalArgumentException("not an object"))
        val requestedId =
            tree.remove("id")?.takeIf { it.isTextual }?.let { raw ->
                runCatching { UUID.fromString(raw.asText()) }.getOrNull()
                    ?: throw ApiException(
                        PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                        "The import payload's 'id' is not a UUID.",
                        mapOf("id" to raw.asText().take(MAX_ECHOED_ID_CHARS)),
                    )
            }
        val preserved = preservedVersion(tree)
        SERVER_FIELDS.forEach(tree::remove)

        val pipeline = deserializer.readOrThrow(MAPPER.writeValueAsString(tree))
        // BEFORE §12: a body that binds an org key this deployment does not define is a
        // configuration conflict (409), not an authoring defect (400), and the two have different
        // people fixing them. The validator would also refuse it — as an unknown reference — so
        // the order here is what decides which answer the operator gets.
        refuseMissingContextKeys(pipeline, workspaceId)
        importValidation(pipeline.name, validator.validate(pipeline, workspaceId)).orThrow()
        val canonical = serializer.write(pipeline)

        return if (preserved != null) {
            importPreserved(workspaceId, requestedId, pipeline, canonical, preserved, actorId)
        } else {
            importNextLocal(workspaceId, requestedId, pipeline, canonical, actorId)
        }
    }

    /** The §9.2 payload: `version` plus the `body_hash` / `released_at` that ride with it. */
    private data class Preserved(
        val version: Int,
        val bodyHash: String?,
        val releasedAt: Instant?,
    )

    /** The textual value of [field] when present as a JSON string, else null. */
    private fun textual(
        tree: ObjectNode,
        field: String,
    ): String? = tree.get(field)?.takeIf { it.isTextual }?.asText()

    private fun preservedVersion(tree: ObjectNode): Preserved? {
        val versionNode = tree.get("version") ?: return null
        if (!versionNode.isInt || versionNode.asInt() < 1) {
            throw ApiException(
                PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                "'version', when present on an import payload, must be a positive integer.",
                mapOf("version" to versionNode.asText().take(MAX_ECHOED_ID_CHARS)),
            )
        }
        return Preserved(
            version = versionNode.asInt(),
            bodyHash = textual(tree, "body_hash"),
            releasedAt = textual(tree, "released_at")?.let(Instant::parse),
        )
    }

    /** Version-less import — today's allocate-next-local behavior (§9.2: "when absent"). */
    private fun importNextLocal(
        workspaceId: UUID,
        requestedId: UUID?,
        pipeline: co.datapipelines.pipeline.Pipeline,
        canonical: String,
        actorId: UUID,
    ): Imported {
        val existing = requestedId?.let { pipelines.findById(workspaceId, it) }
        val record =
            if (existing != null) {
                pipelines.appendReleasedVersion(workspaceId, existing.id, pipeline, canonical, actorId)
                    ?: throw ApiErrors.pipelineNotFound(existing.id.toString())
            } else {
                try {
                    pipelines.create(
                        workspaceId,
                        NewPipeline.from(pipeline, ownerId = actorId, id = requestedId ?: UUID.randomUUID()),
                        canonical,
                        actorId,
                    )
                } catch (e: DuplicateKeyException) {
                    throw idAlreadyTaken(requestedId, e)
                }
            }
        return Imported(record = record, canonical = canonical, created = existing == null)
    }

    /**
     * Preserved-version import — §9.2's table, row by row. Version numbers are global
     * identities (D5): gaps below `current_version` are expected and harmless, a local
     * DRAFT is never clobbered, consumed (DISCARDED) numbers are never reused, and a
     * same-hash re-import of an old export is an idempotent no-op.
     */
    @Suppress("ThrowsCount", "LongMethod") // each §9.2 row is its own refusal; the length is the conflict table made literal
    private fun importPreserved(
        workspaceId: UUID,
        requestedId: UUID?,
        pipeline: co.datapipelines.pipeline.Pipeline,
        canonical: String,
        preserved: Preserved,
        actorId: UUID,
    ): Imported {
        // Hash recompute guard — catches transfer corruption and canonicalization drift in
        // one place, before any classification.
        val declared =
            preserved.bodyHash
                ?: throw ApiException(
                    PipelineErrorCodes.Import.HASH_MISMATCH,
                    "An import payload carrying 'version' must also carry its 'body_hash'.",
                    mapOf("reason" to "body_hash_missing", "pipeline_version" to preserved.version),
                )
        val recomputed = pipelines.computeBodyHash(canonical)
        if (recomputed != declared) {
            throw ApiException(
                PipelineErrorCodes.Import.HASH_MISMATCH,
                "The import payload's declared body_hash does not match its body.",
                mapOf(
                    "declared_body_hash" to declared.take(MAX_ECHOED_ID_CHARS),
                    "recomputed_body_hash" to recomputed.take(MAX_ECHOED_ID_CHARS),
                    "pipeline_version" to preserved.version,
                ),
            )
        }

        val existing = requestedId?.let { pipelines.findById(workspaceId, it) }
        if (existing == null) {
            // Absent: insert as RELEASED at the exact version, released_at from source.
            try {
                val record =
                    pipelines.importPipelineVersion(
                        workspaceId,
                        NewPipeline.from(pipeline, ownerId = actorId, id = requestedId ?: UUID.randomUUID()),
                        preserved.version,
                        canonical,
                        declared,
                        preserved.releasedAt,
                        actorId,
                    )
                return Imported(record, canonical, created = true)
            } catch (e: DuplicateKeyException) {
                throw idAlreadyTaken(requestedId, e)
            }
        }

        val target = pipelines.findVersionDetail(workspaceId, existing.id, preserved.version)
        return when {
            target == null -> {
                val inserted =
                    insertAbsentVersion(workspaceId, existing.id, preserved, pipeline, canonical, declared, actorId)
                if (inserted == null) {
                    // The number was taken between the read and the insert — classify per §9.2.
                    throw versionConflict(
                        pipelines.findVersionDetail(workspaceId, existing.id, preserved.version),
                        preserved,
                        null,
                    )
                }
                Imported(existing, canonical, created = false)
            }

            target.status == PipelineVersionStatus.RELEASED && target.bodyHash == declared -> {
                // Idempotent no-op: re-importing an old export is safe (§9.2).
                Imported(existing, canonical, created = false)
            }

            else -> {
                throw versionConflict(target, preserved, null)
            }
        }
    }

    /**
     * The absent-row insert; null when the number was taken between the read and the write
     * (sequential), the DuplicateKeyException when it was taken mid-statement (concurrent).
     */
    private fun insertAbsentVersion(
        workspaceId: UUID,
        pipelineId: UUID,
        preserved: Preserved,
        pipeline: co.datapipelines.pipeline.Pipeline,
        canonical: String,
        declared: String,
        actorId: UUID,
    ): co.datapipelines.pipeline.PipelineVersionDetail? =
        try {
            pipelines.insertReleasedVersion(
                workspaceId,
                pipelineId,
                preserved.version,
                pipeline.name,
                pipeline.displayName,
                pipeline.description,
                canonical,
                declared,
                preserved.releasedAt,
                actorId,
            )
        } catch (e: DuplicateKeyException) {
            // The row appeared between the read and the insert — classify by what is there now.
            throw versionConflict(pipelines.findVersionDetail(workspaceId, pipelineId, preserved.version), preserved, e)
        }

    /**
     * The catalogued answer to "that id is taken" (021/F3).
     *
     * `pipelines.id` is a **global** primary key while every read on this path is
     * workspace-scoped (`findById(workspaceId, id)`), so the insert can collide on a row this
     * workspace cannot see: another workspace's pipeline, or a deleted pipeline whose id is
     * retained. The two are indistinguishable from here and the remedy is the same, so the
     * message names both rather than asserting the one this seam cannot know. A NAME collision
     * never arrives here — `PipelineRepository.create` matches `uq_pipelines_workspace_name`
     * first and raises `pipeline.validation.duplicate_name`, rethrowing anything else — so this
     * really is about the id. It matters most
     * on the D9 seeding path: an examples file carrying pipeline ids imports into the FIRST
     * personal workspace and then fails every subsequent user's first login — which is why the
     * shipped `examples.json` carries none.
     */
    private fun idAlreadyTaken(
        requestedId: UUID?,
        cause: DuplicateKeyException,
    ): ApiException =
        ApiException(
            PipelineErrorCodes.Import.VERSION_CONFLICT,
            "Pipeline id '$requestedId' is already taken. Pipeline ids are globally unique, so it belongs either " +
                "to another workspace or to a deleted pipeline whose id is retained; import the payload without " +
                "its 'id' to create this workspace's own copy.",
            mapOf("pipeline_id" to requestedId.toString()),
            cause,
        )

    private fun versionConflict(
        target: co.datapipelines.pipeline.PipelineVersionDetail?,
        preserved: Preserved,
        cause: Throwable?,
    ): ApiException =
        ApiException(
            PipelineErrorCodes.Import.VERSION_CONFLICT,
            when (target?.status) {
                PipelineVersionStatus.DRAFT -> {
                    "Version ${preserved.version} exists as a local draft; a local draft is never clobbered."
                }

                PipelineVersionStatus.DISCARDED -> {
                    "Version ${preserved.version} was discarded here; consumed version numbers are never reused."
                }

                else -> {
                    "Version ${preserved.version} exists with different content; never overwrite."
                }
            },
            buildMap {
                put("pipeline_version", preserved.version)
                put("declared_body_hash", preserved.bodyHash?.take(MAX_ECHOED_ID_CHARS) ?: "")
                put("target_body_hash", target?.bodyHash?.take(MAX_ECHOED_ID_CHARS) ?: "")
                put("target_status", target?.status?.name ?: "ABSENT")
            },
            cause,
        )

    /**
     * Re-labels environment-dependency failures with the §13.2 import codes; every other failure
     * keeps its §13.1 code and the exhaustive list (§17.2).
     *
     * ## Mixed failures (gate C, F10)
     * When missing-datasource AND missing-template failures occur together, the primary code is
     * `pipeline.import.missing_datasource` and `details` carries **both** sets —
     * `missing_datasources` and `missing_templates` — so one response tells the author everything
     * the environment lacks. The all-same-kind case keeps the single mapped code with its set in
     * details under the matching key.
     */
    /**
     * §13.2 `pipeline.import.context_key_missing` (calculators design §0.5) — refuse a body that
     * reads an `org_*` Context key this deployment does not define.
     *
     * ## Why the check is scoped to `org_`, and why it is at import
     *
     * Org keys are the one Context tier that legitimately DIFFERS between deployments: a sender
     * may define `org_region` and a receiver may not. Every other tier travels with the body
     * (parameters, calculator outputs) or is universal (the platform keys). So `org_` is exactly
     * the prefix where "the body reads a key that is not here" is possible, and restricting the
     * scan to it means no pipeline that imports today can start failing tomorrow.
     *
     * At import rather than at first run because the failure is otherwise INVISIBLE in the shape
     * that matters: a promoted pipeline whose `:org_currency_symbol` silently resolved to a
     * default would produce plausible, wrong numbers with no error anywhere. Versioning's
     * promotion story is the same argument (§11.3): re-run the environment-dependent rules on
     * the receiver, refuse there, and never let a deployment difference become a data difference.
     */
    private fun refuseMissingContextKeys(
        pipeline: Pipeline,
        workspaceId: UUID,
    ) {
        val provided =
            orgContext.keys + ContextKeys.PLATFORM + pipeline.parameters.keys +
                pipeline.nodes.mapNotNull { it.contextKey }
        val referenced =
            pipeline.nodes.flatMap { node ->
                val fromInputs =
                    node.inputs
                        .orEmpty()
                        .values
                        .flatMap { value -> if (value.isArray) value.toList() else listOf(value) }
                        .mapNotNull { it.takeIf(JsonNode::isTextual)?.asText() }
                        .filter { it.startsWith("$") }
                        .map { it.substring(1) }
                val fromSql =
                    templates
                        ?.takeIf { node.template.id.isNotBlank() }
                        ?.boundParameters(workspaceId, node.template)
                        .orEmpty()
                fromInputs + fromSql
            }
        val missing = referenced.filter { it.startsWith(ORG_PREFIX) && it !in provided }.distinct().sorted()
        if (missing.isEmpty()) return
        throw ApiException(
            PipelineErrorCodes.Import.CONTEXT_KEY_MISSING,
            "Imported pipeline '${pipeline.name}' reads organisation Context key(s) this deployment does not " +
                "define: ${missing.joinToString()}. Configure them under datapipelines.org.* (Configuration §3.21) " +
                "or import a body that does not read them.",
            mapOf("missing_context_keys" to missing, "provided_context_keys" to provided.filter { it.startsWith(ORG_PREFIX) }),
        )
    }

    private fun importValidation(
        name: String,
        result: ValidationResult,
    ): ValidationResult {
        if (result.isValid) return result
        val missingDatasource = result.withCode(PipelineErrorCodes.Validation.UNKNOWN_DATASOURCE)
        val missingTemplate =
            result.withCode(PipelineErrorCodes.Validation.TEMPLATE_NOT_FOUND) +
                result.withCode(PipelineErrorCodes.Validation.TEMPLATE_VERSION_NOT_FOUND)
        val rest = result.failures - missingDatasource.toSet() - missingTemplate.toSet()
        if (rest.isNotEmpty()) return result
        val primaryCode =
            if (missingDatasource.isNotEmpty()) {
                PipelineErrorCodes.Import.MISSING_DATASOURCE
            } else {
                PipelineErrorCodes.Import.MISSING_TEMPLATE
            }
        throw ApiException(
            primaryCode,
            "Imported pipeline '$name' has unmet dependencies in this environment.",
            buildMap {
                // `path` is the one field every ValidationFailure guarantees; detail keys vary by rule.
                if (missingDatasource.isNotEmpty()) put("missing_datasources", missingDatasource.map { it.path })
                if (missingTemplate.isNotEmpty()) put("missing_templates", missingTemplate.map { it.path })
                put(
                    "failures",
                    (missingDatasource + missingTemplate).map { mapOf("code" to it.code, "path" to it.path, "message" to it.message) },
                )
            },
        )
    }

    private companion object {
        /** §0.2 tier 1's key prefix — the one tier that legitimately differs between deployments. */
        const val ORG_PREFIX = "org_"

        val MAPPER = PipelineJson.objectMapper()

        /**
         * Server-assigned fields an import payload may carry from an export; never
         * authoritative. The lifecycle fields (`status`, `body_hash`, `released_at`) ride
         * this list too — they were read (and honored) BEFORE the strip, per §9.2.
         */
        val SERVER_FIELDS =
            listOf(
                "version",
                "owner",
                "created_at",
                "updated_at",
                "status",
                "body_hash",
                "released_at",
                "current_version",
                "draft",
            )

        /** Reflected client input is bounded before it reaches an error message. */
        const val MAX_ECHOED_ID_CHARS = 64
    }
}
