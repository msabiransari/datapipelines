package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Reads an inbound create/update payload into a [TemplateDraft] (templates.md §9).
 *
 * ## Why a pre-scan for `dialect`
 *
 * `dialect_invalid` (§7) is a verdict on a **wire value** — a `dialect` of `"DB2"` has no typed
 * representation to validate once bound. Like [PipelineDeserializer][co.datapipelines.pipeline]'s
 * pre-scan of `type` / `output.target`, this reads the raw `dialect` string and raises the
 * catalog code *before* binding, rather than letting Jackson surface a raw enum-coercion
 * failure as a 500 for what is a 400 the author can fix.
 *
 * Server-assigned fields (`version`, `created_at`, `created_by`) are simply not on
 * [TemplateDraft] (the DTO rule), so a payload that carries them binds without them — they are
 * absent from the target shape, not filtered after the fact.
 *
 * ## Malformed JSON is not this class's error
 *
 * A syntax error, or a `body` that is an object rather than a string, propagates as Jackson's
 * own exception. Neither §7 nor pipeline-contract §13.9 has a code for "this is not template
 * JSON" — that is the transport-level concern the REST layer answers, and inventing a code here
 * would put a second, drifting catalog in the codebase. Same call as [PipelineDeserializer].
 */
class TemplateDeserializer(
    private val mapper: ObjectMapper = TemplateJson.objectMapper(),
) {
    /**
     * Binds [json] to a [TemplateDraft], or reports the wire-value failures that stop it being
     * a template at all.
     *
     * The returned draft is *syntactically* sound only — the §7 check set is [TemplateValidator]'s
     * job and runs next. Returning a rejection rather than throwing is what lets a caller merge
     * these failures with the validator's, so an author sees every problem in one response
     * (§7, D2: the whole check set runs before anything is written).
     */
    fun read(json: String): TemplateDeserializationOutcome = fromTree(mapper.readTree(json))

    /** As [read], for a document already parsed into a tree. */
    fun fromTree(tree: JsonNode): TemplateDeserializationOutcome {
        val dialect = tree.get("dialect")?.takeIf { it.isTextual }?.asText()
        if (dialect == null || Dialect.entries.none { it.wire == dialect }) {
            return TemplateDeserializationOutcome.Rejected(
                TemplateValidationResult(
                    listOf(
                        TemplateValidationFailure(
                            code = PipelineErrorCodes.Template.DIALECT_INVALID,
                            message = "Dialect '${dialect.truncateForError()}' is not one of ${Dialect.entries.map { it.wire }}.",
                            details = mapOf("dialect" to dialect.truncateForError()),
                        ),
                    ),
                ),
            )
        }
        return TemplateDeserializationOutcome.Parsed(mapper.treeToValue(tree, TemplateDraft::class.java))
    }

    /** As [read], but throws [TemplateValidationException] instead of returning a rejection. */
    fun readOrThrow(json: String): TemplateDraft =
        when (val outcome = read(json)) {
            is TemplateDeserializationOutcome.Parsed -> outcome.draft
            is TemplateDeserializationOutcome.Rejected -> throw TemplateValidationException(outcome.result)
        }
}

/** The result of binding an inbound template payload — mirrors pipeline-contract's outcome shape. */
sealed interface TemplateDeserializationOutcome {
    data class Parsed(
        val draft: TemplateDraft,
    ) : TemplateDeserializationOutcome

    data class Rejected(
        val result: TemplateValidationResult,
    ) : TemplateDeserializationOutcome
}
