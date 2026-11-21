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
    fun read(json: String): TemplateDeserializationOutcome = fromTree(mapper.readTree(json), exactJson = json)

    /**
     * As [read], for a document already parsed into a tree. [exactJson] is the text the tree was
     * parsed from, when the caller has it (#1038): the `tests` block is then bound from that text
     * rather than the tree, so an authored `body` fixture keeps its exact decimals and its repeated
     * members for the save-time runner to judge ([FixtureInputs]). The tree's other members are
     * bound as before — a caller may have completed them ([TemplateDeserializer] callers fold the
     * working version's `type` and `dialect` in).
     */
    fun fromTree(
        tree: JsonNode,
        exactJson: String? = null,
    ): TemplateDeserializationOutcome {
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
        val typed = type?.let { TemplateType.fromWire(it) }
        dialectPreScan(typed, tree)?.let { return it }
        return when (val bound = bindDraft(tree, typed, exactJson)) {
            is TemplateDeserializationOutcome.Rejected -> bound
            is TemplateDeserializationOutcome.Parsed -> bound
        }
    }

    /** Binds the tree — Parsed (with the §2.1 engine default resolved) or the strict-block Rejected. */
    private fun bindDraft(
        tree: JsonNode,
        typed: TemplateType?,
        exactJson: String?,
    ): TemplateDeserializationOutcome {
        val parsed =
            try {
                withExactTests(mapper.treeToValue(tree, TemplateDraft::class.java), exactJson)
            } catch (err: com.fasterxml.jackson.databind.JsonMappingException) {
                // Two author errors reach here, and BindFailure tells them apart. The script
                // blocks are strict interior (the model's KDoc): a typo is a refusal, never a
                // silently dropped section - Jackson reports it as an unknown key directly, or as
                // the creator parameter the typo leaves missing; both are `unknown_field`. A JSON
                // number or boolean where the contract declares a string (#333) is `wrong_type`,
                // worded without the value. Whatever is reflected is clipped.
                val failure = BindFailure.of(err)
                return TemplateDeserializationOutcome.Rejected(
                    TemplateValidationResult(
                        listOf(
                            TemplateValidationFailure(
                                code = PipelineErrorCodes.Template.CONTRACT_INVALID,
                                message =
                                    failure.messageFor(
                                        "The script blocks do not bind",
                                        " A typo is a refusal, never a silent drop.",
                                    ),
                                details = failure.details(),
                            ),
                        ),
                    ),
                )
            }
        // An omitted engine on a script type means 'none' (transform-nodes design §2.1) —
        // the DTO's own default is freemarker, which would refuse a create that simply omitted
        // the field. Stated values still go through the validator's type-conditional rule.
        val normalized =
            if (typed?.isScript == true && !tree.has("engine")) {
                parsed.copy(engine = Template.NONE_ENGINE)
            } else {
                parsed
            }
        return TemplateDeserializationOutcome.Parsed(normalized)
    }

    /**
     * [draft] with its `tests` rebound from the exact text of [exactJson]'s top-level `tests` member
     * (the last one, as the tree kept), or [draft] itself when there is no text or no tests.
     */
    private fun withExactTests(
        draft: TemplateDraft,
        exactJson: String?,
    ): TemplateDraft {
        if (draft.tests == null || exactJson == null) return draft
        val text = ExactJsonText.topLevelMember(exactJson, "tests") ?: return draft
        val tests: List<ScriptTestCase> =
            mapper.readValue(text, mapper.typeFactory.constructCollectionType(List::class.java, ScriptTestCase::class.java))
        return draft.copy(tests = tests)
    }

    /** The type/dialect wire-value verdicts, or null when the pair is sound (046 §7, 7b §2.1). */
    private fun dialectPreScan(
        typed: TemplateType?,
        tree: JsonNode,
    ): TemplateDeserializationOutcome.Rejected? {
        val dialect = tree.get("dialect")?.takeIf { it.isTextual }?.asText()
        // Presence of a NON-NULL dialect is the offense: an explicit `"dialect": null` carries
        // no value and reads as absent (an export serialized by a mapper that writes nulls must
        // re-import cleanly).
        val dialectPresent = tree.get("dialect")?.let { !it.isNull } == true
        if (typed != null && !TemplateTypeBehaviour.of(typed).requiresDialect) {
            // html and the script types declare NO dialect; presence is the offense, so the
            // value is irrelevant — including an invalid one, which could not make it "more present".
            if (dialectPresent) {
                return TemplateDeserializationOutcome.Rejected(
                    TemplateValidationResult(
                        listOf(
                            TemplateValidationFailure(
                                code = PipelineErrorCodes.Template.DIALECT_NOT_ALLOWED,
                                message =
                                    "A template of type '${typed.wire}' declares no dialect, but the payload carries " +
                                        "'${dialect.truncateForError()}'.",
                                details = mapOf("type" to typed.wire, "dialect" to dialect.truncateForError()),
                            ),
                        ),
                    ),
                )
            }
            return null
        }
        if (dialect == null || Dialect.entries.none { it.wire == dialect }) {
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
        return null
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
