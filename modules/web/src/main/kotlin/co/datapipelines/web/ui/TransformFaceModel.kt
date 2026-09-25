package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDeserializationOutcome
import co.datapipelines.templates.TemplateDeserializer
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateValidationFailure
import co.datapipelines.templates.TransformBlocks
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.core.util.Separators
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectWriter
import org.springframework.ui.Model

/**
 * The transform editor face (7d, transform-nodes design §9.3): four panes — body, contract,
 * invariants, tests — over one transform template version. Everything here is shaping; the
 * rules are 7b's: the panes bind through the same [TemplateDeserializer] `PUT
 * /api/v1/templates` uses, and validate through the same `TemplateValidator` bean.
 */
internal object TransformFace {
    /** The face fragment — the editor's source column for a transform template. */
    const val VIEW = "partials/template-transform-face"

    /** The face's result region: a save refusal or a run-suite result list. */
    const val RESULT_VIEW = "partials/template-transform-result"

    /** The panes, as the names the form posts and the refusals name. */
    const val BODY = "body"
    const val CONTRACT = "contract"
    const val INVARIANTS = "invariants"
    const val TESTS = "tests"

    /**
     * Fills the face's model for [displayed]. The face is editable only on a DRAFT that the
     * column rule ([TemplateSourceModel]) already made editable: a RELEASED working version is
     * shown read-only with the existing Edit verb, which copies it into a draft — so Save
     * always writes the draft in place and never has to create one behind the header's back.
     */
    fun fill(
        model: Model,
        displayed: Template,
        readOnly: Boolean,
    ) {
        model.addAttribute("faceEditable", !readOnly && displayed.status == PipelineVersionStatus.DRAFT)
        model.addAttribute("faceHash", displayed.bodyHash)
        model.addAttribute("faceHashShort", displayed.bodyHash.take(SHORT_HASH))
        model.addAttribute("panes", TransformPanes.of(displayed))
        model.addAttribute("faceLanguage", languageOf(displayed.type.wire))
    }

    /** The body pane's language label — the type IS the language (D-T2). */
    fun languageOf(typeWire: String): String =
        when (typeWire) {
            "jsonata" -> "JSONata"
            "javascript" -> "JavaScript"
            else -> typeWire
        }

    /** The fields of a draft the panes do not carry: who the template is. */
    data class Identity(
        val id: String,
        val type: String,
        val engine: String,
        val displayName: String,
        val description: String,
    ) {
        companion object {
            /** The face edits none of these: a save keeps the working version's own. */
            fun of(working: Template): Identity =
                Identity(working.id, working.type.wire, working.engine, working.displayName, working.description)
        }
    }

    /** [bind] over the [working] version's identity — the face's Save and Run suite. */
    fun bind(
        working: Template,
        panes: TransformPanes,
    ): Bound = bind(Identity.of(working), panes)

    /**
     * Binds the panes into the draft the write path takes, over [identity]. A pane that is not
     * JSON, or blocks that do not bind (7b's strict `unknown_field`), are refusals naming the
     * pane; nothing is validated here — that is the caller's next step, and it is 7b's
     * validator, so the face, the create modal and `PUT /api/v1/templates` refuse alike.
     */
    fun bind(
        identity: Identity,
        panes: TransformPanes,
    ): Bound {
        val parsed = linkedMapOf<String, JsonNode>()
        val refusals = mutableListOf<FaceRefusal>()
        listOf(CONTRACT to panes.contract, INVARIANTS to panes.invariants, TESTS to panes.tests).forEach { (pane, text) ->
            when (val node = parseJson(text)) {
                null -> refusals += notJson(pane, "The $pane pane is empty — it holds a JSON document.")
                is ParseFailure -> refusals += notJson(pane, "The $pane pane is not valid JSON: ${node.message}")
                is JsonNode -> parsed[pane] = node
            }
        }
        if (refusals.isNotEmpty()) return Bound.Refused(refusals)
        val tree =
            TransformBlocks.mapper.createObjectNode().apply {
                put("id", identity.id)
                put("type", identity.type)
                put("engine", identity.engine)
                put("display_name", identity.displayName)
                put("description", identity.description)
                put("body", panes.body)
                parsed.forEach { (pane, node) -> set<JsonNode>(pane, node) }
            }
        return when (val outcome = DESERIALIZER.fromTree(tree)) {
            is TemplateDeserializationOutcome.Parsed -> Bound.Draft(outcome.draft)
            is TemplateDeserializationOutcome.Rejected -> Bound.Refused(outcome.result.failures.map(::refusalOf))
        }
    }

    /** The result of [bind]: the draft to validate, or the pane-level refusals. */
    sealed interface Bound {
        data class Draft(
            val draft: TemplateDraft,
        ) : Bound

        data class Refused(
            val refusals: List<FaceRefusal>,
        ) : Bound
    }

    /** One of 7b's failures, as the face shows it: the pane it belongs to, the code, the words. */
    fun refusalOf(failure: TemplateValidationFailure): FaceRefusal =
        FaceRefusal(
            pane = paneOf(failure),
            code = failure.code,
            message = failure.message,
            detail =
                failure.details
                    .filterKeys { it != "rule" }
                    .takeIf { it.isNotEmpty() }
                    ?.entries
                    ?.joinToString(" · ") { (key, value) -> "$key: $value" },
        )

    /**
     * Which pane a failure belongs to — the rule names are 7b's (`TemplateValidator`), and a
     * failure no pane owns (a stale hash, the JavaScript round-two refusal) names none.
     */
    fun paneOf(failure: TemplateValidationFailure): String? {
        val rule = failure.details["rule"] as? String
        return when (failure.code) {
            PipelineErrorCodes.Template.TEST_FAILED -> {
                TESTS
            }

            PipelineErrorCodes.Template.INVARIANT_INVALID -> {
                INVARIANTS
            }

            PipelineErrorCodes.Template.CONTRACT_INVALID -> {
                when (rule) {
                    in TEST_RULES -> TESTS
                    "unknown_field" -> paneOfPath(failure.details["path"] as? String) ?: CONTRACT
                    else -> CONTRACT
                }
            }

            PipelineErrorCodes.Template.SYNTAX_ERROR,
            PipelineErrorCodes.Template.FREEMARKER_FORBIDDEN,
            -> {
                BODY
            }

            else -> {
                null
            }
        }
    }

    private fun paneOfPath(path: String?): String? = listOf(CONTRACT, INVARIANTS, TESTS).firstOrNull { path?.contains("\"$it\"") == true }

    private fun notJson(
        pane: String,
        message: String,
    ) = FaceRefusal(pane = pane, code = PipelineErrorCodes.Template.CONTRACT_INVALID, message = message, detail = null)

    private class ParseFailure(
        val message: String,
    )

    /** The pane's JSON tree, null for a blank pane, or the parser's complaint. */
    private fun parseJson(text: String): Any? {
        if (text.isBlank()) return null
        return try {
            TransformBlocks.mapper.readTree(text)
        } catch (err: JsonProcessingException) {
            ParseFailure(err.originalMessage)
        }
    }

    /** The head's hash chip: enough of `body_hash` to see that a save changed it. */
    private const val SHORT_HASH = 12

    /** 7b's `contract_invalid` rules that are about the TESTS block, not the contract. */
    private val TEST_RULES = setOf("empty_case_missing", "row_case_lists_table", "expect_shape")

    private val DESERIALIZER = TemplateDeserializer()
}

/** One refusal as the face renders it — every string is user- or server-supplied text (`th:text` only). */
data class FaceRefusal(
    val pane: String?,
    val code: String,
    val message: String,
    val detail: String?,
) {
    /** One line, for a surface that shows a single message (the create modal's slot). */
    val line: String get() = listOfNotNull(pane?.let { "[$it]" }, code, "—", message, detail?.let { "($it)" }).joinToString(" ")
}

/**
 * The create modal's starting point for a transform type (7d): the design record's §2.2
 * example — a `row` contract with rejects, its two invariants and its three cases (empty,
 * right, wrong data), with the body that satisfies them. The create runs the suite, so a
 * skeleton that failed would make the modal unusable; `TransformFaceModelTest` saves it
 * through the real gate. JSONata: `javascript` is refused at save until round two.
 */
internal object TransformSkeleton {
    val body: String =
        """
        {
          "rows": [ rows[customer_id != null].{
            "order_id": order_id, "amount": amount_cents / 100, "customer_id": customer_id } ],
          "rejects": [ rows[customer_id = null].{ "row": ${'$'}, "reason": "customer_id missing" } ]
        }
        """.trimIndent()

    val contract: String =
        """
        {
          "mode": "row",
          "inputs": {
            "orders": { "kind": "table", "columns": [
              { "name": "order_id", "type": "INTEGER" },
              { "name": "amount_cents", "type": "INTEGER" },
              { "name": "customer_id", "type": "STRING", "nullable": true } ] },
            "tz": { "kind": "value", "type": "STRING" },
            "min_total": { "kind": "value", "type": "DECIMAL", "precision": 12, "scale": 2 }
          },
          "output": { "kind": "table", "columns": [
            { "name": "order_id", "type": "INTEGER" },
            { "name": "amount", "type": "DECIMAL", "precision": 12, "scale": 2 },
            { "name": "customer_id", "type": "STRING" } ] },
          "rejects": true
        }
        """.trimIndent()

    val invariants: String =
        """
        [
          { "name": "customer_present",
            "expr": "${'$'}count(rows[customer_id = null]) = 0",
            "message": "every accepted row carries a customer id" },
          { "name": "one_to_one",
            "expr": "${'$'}count(rows) + ${'$'}count(rejects) = ${'$'}count(inputs.orders)",
            "message": "every input row is accepted or rejected, never lost" }
        ]
        """.trimIndent()

    val tests: String =
        """
        [
          { "name": "empty input",
            "input": { "rows": [], "inputs": { "tz": "UTC", "min_total": 0.00 } },
            "expect": { "output": { "rows": [], "rejects": [] } } },
          { "name": "missing customer is rejected",
            "input": { "rows": [ { "order_id": 1, "amount_cents": 1250, "customer_id": null } ],
                       "inputs": { "tz": "UTC", "min_total": 0.00 } },
            "expect": { "output": { "rows": [],
                                    "rejects": [ { "row": { "order_id": 1, "amount_cents": 1250, "customer_id": null },
                                                   "reason": "customer_id missing" } ] } } },
          { "name": "wrong shape is refused",
            "input": { "rows": [ { "order_id": "x" } ], "inputs": { "tz": "UTC", "min_total": 0.00 } },
            "expect": { "refusal": "pipeline.transform.input_contract_violation" } }
        ]
        """.trimIndent()
}

/**
 * The four panes' text. [of] pretty-prints a stored version's blocks LOSSLESSLY: an absent
 * optional (`precision`, `meta`, `refusal`) is left out so the pane reads like the record's
 * example, but a null INSIDE the data — a test row's `"customer_id": null`, an expected output
 * that is JSON `null` — is kept, because there it is a value. [TransformFaceModelTest] pins the
 * round trip: the text binds back to exactly the stored blocks.
 */
data class TransformPanes(
    val body: String,
    val contract: String,
    val invariants: String,
    val tests: String,
) {
    companion object {
        fun of(template: Template): TransformPanes =
            TransformPanes(
                body = template.body,
                contract = pretty(template.contract),
                invariants = pretty(template.invariants),
                tests = pretty(template.tests),
            )

        fun pretty(value: Any?): String = if (value == null) "" else WRITER.writeValueAsString(value)

        /**
         * Value-level NON_NULL (a null PROPERTY is an absent optional), content-level ALWAYS (a
         * null map entry is data). A plain `setSerializationInclusion(NON_NULL)` sets both, and
         * would silently drop `"customer_id": null` from every test row.
         */
        private val WRITER: ObjectWriter =
            TransformBlocks.mapper
                .copy()
                .setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
                .writer(
                    DefaultPrettyPrinter(
                        Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER),
                    ).withArrayIndenter(DefaultIndenter("  ", "\n"))
                        .withObjectIndenter(DefaultIndenter("  ", "\n")),
                )
    }
}
