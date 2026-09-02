package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Reads an inbound create/update payload into a [TemplateDraft] (templates.md §9).
 *
 * ## Why a pre-scan for `type` and `dialect`
 *
 * `type_invalid` / `dialect_invalid` (§7) are verdicts on **wire values** — a `type` of
 * `"csv"` or a `dialect` of `"DB2"` has no typed representation to validate once bound. Like
 * [PipelineDeserializer][co.datapipelines.pipeline]'s pre-scan of `type` / `output.target`,
 * this reads the raw strings and raises the catalog code *before* binding, rather than
 * letting Jackson surface a raw enum-coercion failure as a 500 for what is a 400 the author
 * can fix.
 *
 * Since 046 the two pre-scans are conditional on each other: a `type` of `"html"` takes no
 * `dialect` at all, and a `dialect` present beside it is refused with
 * `dialect_not_allowed` — presence is the offense, so its value is never even examined.
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
        val type = tree.get("type")?.let { node -> node.takeIf { it.isTextual }?.asText() ?: node.asText() }
        if (type != null && TemplateType.fromWire(type) == null) {
            return TemplateDeserializationOutcome.Rejected(
                TemplateValidationResult(
                    listOf(
                        TemplateValidationFailure(
                            code = PipelineErrorCodes.Template.TYPE_INVALID,
                            message =
                                "Type '${type.truncateForError()}' is not one of ${TemplateType.WIRE_VALUES}.",
                            details = mapOf("type" to type.truncateForError(), "supported" to TemplateType.WIRE_VALUES),
                        ),
                    ),
                ),
            )
        }
        val dialect = tree.get("dialect")?.takeIf { it.isTextual }?.asText()
        if (type == TemplateType.HTML.wire) {
            // An html template declares NO dialect; presence is the offense (046 §7), so the
            // value is irrelevant — including an invalid one, which could not make it "more
            // present".
            if (tree.has("dialect")) {
                return TemplateDeserializationOutcome.Rejected(
                    TemplateValidationResult(
                        listOf(
                            TemplateValidationFailure(
                                code = PipelineErrorCodes.Template.DIALECT_NOT_ALLOWED,
                                message =
                                    "A template of type 'html' declares no dialect, but the payload carries " +
                                        "'${dialect.truncateForError()}'.",
                                details = mapOf("type" to TemplateType.HTML.wire, "dialect" to dialect.truncateForError()),
                            ),
                        ),
                    ),
                )
            }
        } else if (dialect == null || Dialect.entries.none { it.wire == dialect }) {
            return TemplateDeserializationOutcome.Rejected(
                TemplateValidationResult(
                    listOf(
                        TemplateValidationFailure(
                            code = PipelineErrorCodes.Template.DIALECT_INVALID,
                            message =
                                "Dialect '${dialect.truncateForError()}' is not one of ${Dialect.entries.map { it.wire }}." +
                                    " A dialect is required unless the template's type is 'html'.",
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
