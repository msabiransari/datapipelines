package co.datapipelines.web.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDeserializationOutcome
import co.datapipelines.templates.TemplateDeserializer
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateJson
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidationException
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * The template-library **import** act (rest-api.md §8.8), lifted out of [TemplatesController] so
 * a second caller can perform it.
 *
 * Same reasoning as [co.datapipelines.web.pipelines.PipelineImportService]: the D9 workspace
 * seeder must import through the code the API imports through, or the two paths drift and only
 * one of them is tested. Per-entry semantics are unchanged — each entry is created, or versioned
 * when its id already exists, and a failure aborts at that entry leaving the earlier ones
 * (a library import is re-runnable).
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
        importEntries(body).map { entry ->
            val draft = validator.validateOrThrow(entry, workspaceId)
            val id = draft.id
            if (id != null && templates.existsId(workspaceId, id)) {
                templates.update(workspaceId, id, draft, actorId) ?: throw ApiErrors.templateNotFound(id)
            } else {
                templates.create(workspaceId, draft, actorId)
            }
        }

    /** The `templates` array of an §8.8 import body, each entry deserialized to a draft. */
    @Suppress("ThrowsCount") // a boundary maps each distinct failure to its own catalogued 4xx
    private fun importEntries(body: String): List<TemplateDraft> {
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
            when (val outcome = deserializer.fromTree(entry)) {
                is TemplateDeserializationOutcome.Parsed -> outcome.draft
                is TemplateDeserializationOutcome.Rejected -> throw TemplateValidationException(outcome.result)
            }
        }
    }

    private companion object {
        val MAPPER = TemplateJson.objectMapper()
    }
}
