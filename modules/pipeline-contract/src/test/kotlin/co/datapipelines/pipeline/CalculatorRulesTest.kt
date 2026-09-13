package co.datapipelines.pipeline

import co.datapipelines.calculators.CalculatorKind
import co.datapipelines.calculators.CalculatorRegistry
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * pipeline-contract §12.10 — the CALCULATOR-node rules.
 *
 * The ordering cases are the ones that matter most. Without them a pipeline whose SQL node binds
 * a calculator's key and forgets the `depends_on` edge is *right most of the time*: with four
 * parallel slots and two nodes it usually happens to run in the order the author imagined, and
 * the day it does not, the answer is silently a default. Every ordering test below therefore has
 * a matching one that makes the edge legal and expects a clean save — a rule that only ever
 * refuses is a rule nobody can tell apart from a bug.
 *
 * `LargeClass` is suppressed: this suite is §12.10's contract in one place — the single-output
 * rules and 121's named-set rules are one rule family, and splitting it would be the
 * ConfigValidator split in reverse (the same precedent PipelineRepositoryIntegrationTest set).
 */
@Suppress("LargeClass")
class CalculatorRulesTest {
    private val workspaceId = UUID.randomUUID()

    private companion object {
        /**
         * The template id [Fixtures.node] pins — NOT the node id, which is what an earlier draft
         * of this suite keyed the stub on. Every ordering assertion then passed vacuously,
         * including the positive one: the scan simply never ran. The `.single()` on the negative
         * case is what caught it — the argument for pairing every refusal with a case that must
         * NOT fire.
         */
        const val SQL_TEMPLATE = "test/fetch_orders.sql"
    }

    private fun validate(
        pipeline: Pipeline,
        templates: TemplateDryRenderer = StubTemplates(),
        orgContext: OrgContext = OrgContext.DEFAULTS,
        kinds: (String) -> CalculatorKind? = CalculatorRegistry::find,
    ): ValidationResult = Fixtures.validator(templates = templates, orgContext = orgContext, kinds = kinds).validate(pipeline, workspaceId)

    /** Validates with the multi-output fixture kind registered through the seam (121). */
    private fun validateMulti(
        pipeline: Pipeline,
        templates: TemplateDryRenderer = StubTemplates(),
    ): ValidationResult = validate(pipeline, templates = templates, kinds = Fixtures.windowKinds)

    // ---- shape ----

    @Test
    fun `a well-formed calculator feeding a SQL node that depends on it is valid`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.calculatorNode(),
                            Fixtures.node("report", dependsOn = listOf("fiscal_q")),
                        ),
                ),
                templates = StubTemplates(bound = mapOf(SQL_TEMPLATE to listOf("run_fiscal_quarter"))),
            )

        result.failures.map { "${it.code} ${it.path}" }.shouldBeEmpty()
    }

    @Test
    fun `a calculator node missing any of the three required fields names all of them`() {
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(Fixtures.calculatorNode(kind = null, inputs = null, contextKey = null))),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(PipelineErrorCodes.Validation.CALCULATOR_NODE_INCOMPLETE)
        val failure = result.withCode(PipelineErrorCodes.Validation.CALCULATOR_NODE_INCOMPLETE).single()
        failure.details["missing"] shouldBe listOf("kind", "inputs", "context_key")
    }

    @Test
    fun `the three fields on a non-calculator node are refused, naming which`() {
        val node = Fixtures.node("q").copy(kind = "fiscal_quarter", contextKey = "x")
        val result = validate(Fixtures.pipeline(nodes = listOf(node)))

        result.codes shouldContainExactlyInAnyOrder listOf(PipelineErrorCodes.Validation.CALCULATOR_FIELDS_ON_NON_CALCULATOR)
        result
            .withCode(PipelineErrorCodes.Validation.CALCULATOR_FIELDS_ON_NON_CALCULATOR)
            .single()
            .details["fields"] shouldBe listOf("kind", "context_key")
    }

    @Test
    fun `a calculator node carrying source, template or output is refused in one failure`() {
        val node =
            Fixtures.calculatorNode().copy(
                source = "warehouse",
                template = TemplateRef("test/q", 1),
                output = NodeOutput.Caller,
            )
        val result = validate(Fixtures.pipeline(nodes = listOf(node)))

        // ONE failure naming all three, not three failures: it is one authoring mistake.
        val failures = result.withCode(PipelineErrorCodes.Validation.CALCULATOR_NODE_HAS_SQL_FIELDS)
        failures.size shouldBe 1
        failures.single().details["fields"] shouldBe listOf("source", "template", "output")
    }

    @Test
    fun `an unknown kind is refused and points at the catalog`() {
        val result = validate(Fixtures.pipeline(nodes = listOf(Fixtures.calculatorNode(kind = "fiscal_fortnight"))))

        result.codes shouldContainExactlyInAnyOrder listOf(PipelineErrorCodes.Validation.CALCULATOR_UNKNOWN)
        result
            .withCode(PipelineErrorCodes.Validation.CALCULATOR_UNKNOWN)
            .single()
            .message
            .contains("calculators_list") shouldBe true
    }

    // ---- inputs ----

    @Test
    fun `a missing required input is named with the kind's own description of it`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes = listOf(Fixtures.calculatorNode(inputs = mapOf("date" to Fixtures.ref("current_date")))),
                ),
            )

        val failure = result.withCode(PipelineErrorCodes.Validation.CALCULATOR_INPUT_MISSING).single()
        failure.details["input"] shouldBe "fiscal_start"
    }

    @Test
    fun `an input the kind does not declare is refused, listing the ones it does`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.calculatorNode(
                                inputs =
                                    mapOf(
                                        "date" to Fixtures.ref("current_date"),
                                        "fiscal_start" to Fixtures.literal("09-15"),
                                        "fiscal_start_month" to Fixtures.literal(9),
                                    ),
                            ),
                        ),
                ),
            )

        val failure = result.withCode(PipelineErrorCodes.Validation.CALCULATOR_INPUT_UNKNOWN).single()
        failure.details["reason"] shouldBe "input"
        failure.details["declared_inputs"] shouldBe listOf("date", "fiscal_start")
    }

    @Test
    fun `a reference to nothing is refused, and org, platform and parameter keys are all accepted`() {
        val unknown =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.calculatorNode(
                                inputs =
                                    mapOf("date" to Fixtures.ref("as_of"), "fiscal_start" to Fixtures.ref("org_fiscal_start_date")),
                            ),
                        ),
                ),
            )
        unknown.withCode(PipelineErrorCodes.Validation.CALCULATOR_INPUT_UNKNOWN).single().details["reason"] shouldBe "reference"

        // The same body, with `as_of` declared: every tier of §0.2 is a legal reference target.
        val declared =
            validate(
                Fixtures.pipeline(
                    parameters = mapOf("as_of" to Parameter(LogicalType.DATE)),
                    nodes =
                        listOf(
                            Fixtures.calculatorNode(
                                inputs =
                                    mapOf("date" to Fixtures.ref("as_of"), "fiscal_start" to Fixtures.ref("org_fiscal_start_date")),
                            ),
                        ),
                ),
            )
        declared.failures.shouldBeEmpty()
    }

    @Test
    fun `a literal that contradicts the input's declared type is refused`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.calculatorNode(
                                kind = "add_days",
                                inputs = mapOf("date" to Fixtures.literal("2026-08-14"), "days" to Fixtures.literal("thirty")),
                                contextKey = "target_date",
                            ),
                        ),
                ),
            )

        val failure = result.withCode(PipelineErrorCodes.Validation.CALCULATOR_INPUT_TYPE_MISMATCH).single()
        failure.details["input"] shouldBe "days"
        failure.details["declared_type"] shouldBe "INTEGER"
    }

    @Test
    fun `a LIST input takes an array and refuses a bare scalar`() {
        val scalar =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.calculatorNode(
                                kind = "coalesce",
                                inputs = mapOf("values" to Fixtures.literal("GLOBAL")),
                                contextKey = "region",
                            ),
                        ),
                ),
            )
        scalar.codes shouldContainExactlyInAnyOrder listOf(PipelineErrorCodes.Validation.CALCULATOR_INPUT_TYPE_MISMATCH)

        val array =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.calculatorNode(
                                kind = "coalesce",
                                inputs = mapOf("values" to Fixtures.literals("EU", "GLOBAL")),
                                contextKey = "region",
                            ),
                        ),
                ),
            )
        array.failures.shouldBeEmpty()
    }

    // ---- reference typing: a `$reference` is type-checked like a literal ----

    @Test
    fun `a platform key of the wrong type feeding a typed input is refused at save`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.calculatorNode(
                                kind = "quarter_of_year",
                                inputs = mapOf("date" to Fixtures.ref("current_timestamp")),
                                contextKey = "run_quarter",
                            ),
                        ),
                ),
            )

        val failure = result.withCode(PipelineErrorCodes.Validation.CALCULATOR_INPUT_TYPE_MISMATCH).single()
        failure.details["declared_type"] shouldBe "DATE"
        failure.details["reference"] shouldBe "current_timestamp"
        failure.message shouldContain "\$current_timestamp"
    }

    @Test
    fun `an org key of the wrong type feeding a typed input is refused - org values are STRING`() {
        // `org_fiscal_start_date` is an `MM-DD` STRING on purpose (§0.2): quarter_of_year's `date`
        // input wants a DATE, and the run would be the first to notice.
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.calculatorNode(
                                kind = "quarter_of_year",
                                inputs = mapOf("date" to Fixtures.ref("org_fiscal_start_date")),
                                contextKey = "run_quarter",
                            ),
                        ),
                ),
            )

        val failure = result.withCode(PipelineErrorCodes.Validation.CALCULATOR_INPUT_TYPE_MISMATCH).single()
        failure.details["declared_type"] shouldBe "DATE"
        failure.details["reference"] shouldBe "org_fiscal_start_date"
    }

    @Test
    fun `a declared parameter of the wrong type feeding a typed input is refused at save`() {
        val result =
            validate(
                Fixtures.pipeline(
                    parameters = mapOf("as_of" to Parameter(LogicalType.TIMESTAMP)),
                    nodes =
                        listOf(
                            Fixtures.calculatorNode(
                                kind = "quarter_of_year",
                                inputs = mapOf("date" to Fixtures.ref("as_of")),
                                contextKey = "run_quarter",
                            ),
                        ),
                ),
            )

        result.withCode(PipelineErrorCodes.Validation.CALCULATOR_INPUT_TYPE_MISMATCH).single().details["reference"] shouldBe "as_of"
    }

    @Test
    fun `another calculator's output of the wrong type is refused even when the ordering is right`() {
        // fiscal_quarter outputs INTEGER; quarter_of_year's `date` input takes DATE. The depends_on
        // edge is present, so before reference typing this body saved and failed at run.
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.calculatorNode(id = "fiscal_q"),
                            Fixtures.calculatorNode(
                                id = "classify",
                                kind = "quarter_of_year",
                                inputs = mapOf("date" to Fixtures.ref("run_fiscal_quarter")),
                                contextKey = "run_quarter",
                                dependsOn = listOf("fiscal_q"),
                            ),
                        ),
                ),
            )

        val failure = result.withCode(PipelineErrorCodes.Validation.CALCULATOR_INPUT_TYPE_MISMATCH).single()
        failure.details["reference"] shouldBe "run_fiscal_quarter"
        failure.message shouldContain "INTEGER"
    }

    @Test
    fun `correctly-typed references pass for every tier - parameter, platform and calculator output`() {
        // period_start outputs DATE, so its key can feed quarter_of_year's DATE input through the
        // edge; the DATE parameter feeding period_start's own `date` input is the same happy path
        // the suite's first test pins for the platform and org tiers.
        val result =
            validate(
                Fixtures.pipeline(
                    parameters = mapOf("as_of" to Parameter(LogicalType.DATE)),
                    nodes =
                        listOf(
                            Fixtures.calculatorNode(
                                id = "period",
                                kind = "period_start",
                                inputs = mapOf("date" to Fixtures.ref("as_of"), "unit" to Fixtures.literal("month")),
                                contextKey = "run_period_start",
                            ),
                            Fixtures.calculatorNode(
                                id = "classify",
                                kind = "quarter_of_year",
                                inputs = mapOf("date" to Fixtures.ref("run_period_start")),
                                contextKey = "run_quarter",
                                dependsOn = listOf("period"),
                            ),
                        ),
                ),
            )

        result.failures.shouldBeEmpty()
    }

    @Test
    fun `an ANY-typed input takes a reference of any type - the kind does not look at the value`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.calculatorNode(
                                kind = "if_null",
                                inputs =
                                    mapOf("value" to Fixtures.ref("current_timestamp"), "default" to Fixtures.literal("fallback")),
                                contextKey = "run_value",
                            ),
                        ),
                ),
            )

        result.failures.shouldBeEmpty()
    }

    @Test
    fun `a reference to an ANY-output calculator feeding a typed input is accepted - unknowable at save`() {
        // if_null's output type is null (ANY): what `$run_date` holds is the run's answer, so the
        // save-time check skips rather than guesses.
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.calculatorNode(
                                id = "fallback",
                                kind = "if_null",
                                inputs = mapOf("value" to Fixtures.ref("current_date"), "default" to Fixtures.ref("current_date")),
                                contextKey = "run_date",
                            ),
                            Fixtures.calculatorNode(
                                id = "classify",
                                kind = "quarter_of_year",
                                inputs = mapOf("date" to Fixtures.ref("run_date")),
                                contextKey = "run_quarter",
                                dependsOn = listOf("fallback"),
                            ),
                        ),
                ),
            )

        result.failures.shouldBeEmpty()
    }

    // ---- ordering: the rule with two sides ----

    @Test
    fun `a reference to a calculator that is NOT an ancestor is refused, and adding the edge fixes it`() {
        fun pipelineWith(dependsOn: List<String>) =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.calculatorNode(id = "fiscal_q"),
                        Fixtures.calculatorNode(
                            id = "label",
                            kind = "add_days",
                            inputs = mapOf("date" to Fixtures.ref("current_date"), "days" to Fixtures.ref("run_fiscal_quarter")),
                            contextKey = "run_label",
                            dependsOn = dependsOn,
                        ),
                    ),
            )

        val unordered = validate(pipelineWith(emptyList()))
        val failure = unordered.withCode(PipelineErrorCodes.Validation.CALCULATOR_INPUT_UNORDERED).single()
        failure.details["written_by"] shouldBe "fiscal_q"
        withClue("the message must name the fix, not just the fault") {
            failure.message.contains("depends_on") shouldBe true
        }

        validate(pipelineWith(listOf("fiscal_q"))).failures.shouldBeEmpty()
    }

    @Test
    fun `a TRANSITIVE dependency is enough - the rule is reachability, not adjacency`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.calculatorNode(id = "fiscal_q"),
                            Fixtures.node("middle", dependsOn = listOf("fiscal_q"), output = null),
                            Fixtures.calculatorNode(
                                id = "label",
                                kind = "add_days",
                                inputs =
                                    mapOf("date" to Fixtures.ref("current_date"), "days" to Fixtures.ref("run_fiscal_quarter")),
                                contextKey = "run_label",
                                dependsOn = listOf("middle"),
                            ),
                        ),
                ),
            )

        result.withCode(PipelineErrorCodes.Validation.CALCULATOR_INPUT_UNORDERED).shouldBeEmpty()
    }

    @Test
    fun `a SQL node binding a calculator key without depending on it is the same refusal`() {
        fun pipelineWith(dependsOn: List<String>) =
            Fixtures.pipeline(
                nodes = listOf(Fixtures.calculatorNode(), Fixtures.node("report", dependsOn = dependsOn)),
            )
        val templates = StubTemplates(bound = mapOf(SQL_TEMPLATE to listOf("run_fiscal_quarter")))

        val unordered = validate(pipelineWith(emptyList()), templates = templates)
        val failure = unordered.withCode(PipelineErrorCodes.Validation.CALCULATOR_INPUT_UNORDERED).single()
        failure.path shouldBe "nodes[1].template"
        failure.details["context_key"] shouldBe "run_fiscal_quarter"

        validate(pipelineWith(listOf("fiscal_q")), templates = templates).failures.shouldBeEmpty()
    }

    @Test
    fun `a SQL node binding an ordinary parameter is untouched by the ordering rule`() {
        // The scan must not turn every `:name` in every template into an ordering question.
        val result =
            validate(
                Fixtures.pipeline(
                    parameters = mapOf("start_date" to Parameter(LogicalType.DATE)),
                    nodes = listOf(Fixtures.calculatorNode(), Fixtures.node("report")),
                ),
                templates = StubTemplates(bound = mapOf(SQL_TEMPLATE to listOf("start_date", "org_currency_symbol"))),
            )

        result.failures.shouldBeEmpty()
    }

    // ---- the output key ----

    @Test
    fun `a context_key colliding with a declared parameter is refused - a calculator may not shadow one`() {
        val result =
            validate(
                Fixtures.pipeline(
                    parameters = mapOf("run_fiscal_quarter" to Parameter(LogicalType.INTEGER)),
                    nodes = listOf(Fixtures.calculatorNode()),
                ),
            )

        val failure = result.withCode(PipelineErrorCodes.Validation.CALCULATOR_OUTPUT_COLLISION).single()
        failure.details["collides_with"] shouldBe "parameter"
    }

    @Test
    fun `a context_key may shadow an org or platform key - that is tier 5 doing its job`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.calculatorNode(
                                kind = "date_format",
                                inputs =
                                    mapOf("date" to Fixtures.ref("current_date"), "format" to Fixtures.literal("yyyy-MM-dd")),
                                contextKey = "org_currency_symbol",
                            ),
                        ),
                ),
            )

        result.failures.shouldBeEmpty()
    }

    @Test
    fun `two nodes writing one context_key is refused - one writer per key`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes = listOf(Fixtures.calculatorNode(id = "a"), Fixtures.calculatorNode(id = "b")),
                ),
            )

        // Once, on the node that came SECOND, naming the one that already owns the key — not
        // twice, and not on the first writer, which did nothing wrong.
        val failure = result.withCode(PipelineErrorCodes.Validation.CALCULATOR_OUTPUT_COLLISION).single()
        failure.path shouldBe "nodes[1].context_key"
        failure.message.contains("'a'") shouldBe true
    }

    @Test
    fun `a context_key that is not a legal identifier is refused`() {
        val result = validate(Fixtures.pipeline(nodes = listOf(Fixtures.calculatorNode(contextKey = "Run Fiscal Quarter"))))

        result.codes shouldContainExactlyInAnyOrder listOf(PipelineErrorCodes.Validation.CALCULATOR_OUTPUT_NAME_INVALID)
    }

    @Test
    fun `a deployment whose org block lacks a key refuses the reference to it`() {
        // The same body validates on a deployment that defines `org_region` and fails on one that
        // does not — which is exactly the difference `pipeline.import.context_key_missing` exists
        // to catch at promotion time, seen here from the save-time side.
        val body =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.calculatorNode(
                            kind = "if_null",
                            inputs = mapOf("value" to Fixtures.ref("org_region"), "default" to Fixtures.literal("GLOBAL")),
                            contextKey = "run_region",
                        ),
                    ),
            )

        validate(body).codes shouldContainExactlyInAnyOrder listOf(PipelineErrorCodes.Validation.CALCULATOR_INPUT_UNKNOWN)

        val extended = OrgContext.ofValues(OrgContext.DEFAULTS.values + ("org_region" to "EU"))
        validate(body, orgContext = extended).failures.shouldBeEmpty()
    }

    // ---- 078 A1: a calculator output key is in §12.6's declared set ----

    @Test
    fun `a template interpolating a calculator output key is refused exactly like a declared parameter`() {
        // Before A1 the declared set was org + platform + parameters, so the scan never saw a
        // calculator key: the bare shape failed with the WRONG code (template_render_failed) and
        // the `${x!}` shape passed save and interpolated a derived value at run.
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.calculatorNode(),
                            Fixtures.node("report", dependsOn = listOf("fiscal_q")),
                        ),
                ),
                templates = StubTemplates(interpolated = mapOf(SQL_TEMPLATE to setOf("run_fiscal_quarter"))),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(PipelineErrorCodes.Template.PARAMETER_INTERPOLATED)
        val failure = result.withCode(PipelineErrorCodes.Template.PARAMETER_INTERPOLATED).single()
        failure.details["parameter"] shouldBe "run_fiscal_quarter"
        failure.details["bind_form"] shouldBe ":run_fiscal_quarter"
        failure.message shouldContain "\${run_fiscal_quarter}"
        failure.message shouldContain ":run_fiscal_quarter"
    }

    @Test
    fun `a calculator output key gating a template conditional is refused at save`() {
        // `<#if run_fiscal_quarter??>` writes no value, but it gates SQL STRUCTURE on a derived
        // value — the interpolation hole one directive earlier. A declared parameter in the
        // same position stays legal (042 B1); the calculator key is refused with the same code.
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.calculatorNode(),
                            Fixtures.node("report", dependsOn = listOf("fiscal_q")),
                        ),
                ),
                templates = StubTemplates(conditioned = mapOf(SQL_TEMPLATE to setOf("run_fiscal_quarter"))),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(PipelineErrorCodes.Template.PARAMETER_INTERPOLATED)
    }

    @Test
    fun `the dry render's context carries every calculator output key typed by the kind's output`() {
        // fiscal_quarter's output is INTEGER, so the sample is 1 — the same table §7.4's
        // parameter samples come from. This is the line A1's falsification reverts: without it
        // the key never reaches the declared set and the two refusals above go silent.
        val templates = StubTemplates()
        validate(
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.calculatorNode(),
                        Fixtures.node("report", dependsOn = listOf("fiscal_q")),
                    ),
            ),
            templates = templates,
        )

        templates.renderedContexts.getValue(SQL_TEMPLATE)["run_fiscal_quarter"] shouldBe 1
    }

    // `:run_fiscal_quarter` as a bind stays valid — pinned by the suite's first test
    // ("a well-formed calculator feeding a SQL node that depends on it is valid"), which stubs
    // exactly that bind and expects a clean save. The A1 set tightens `${}` and `<#if>` only.

    // ---- 121: one key, or a named set ----

    @Test
    fun `a multi-output node with every output mapped feeding a SQL node is valid - the happy path`() {
        val result =
            validateMulti(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.windowNode(),
                            Fixtures.node("report", dependsOn = listOf("window")),
                        ),
                ),
                templates = StubTemplates(bound = mapOf(SQL_TEMPLATE to listOf("window_start", "window_end"))),
            )

        result.failures.map { "${it.code} ${it.path}" }.shouldBeEmpty()
    }

    @Test
    fun `context_keys on a single-output kind is a shape mismatch`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes = listOf(Fixtures.calculatorNode(contextKey = null, contextKeys = mapOf("start" to "run_fiscal_quarter"))),
                ),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(PipelineErrorCodes.Validation.CALCULATOR_OUTPUT_SHAPE_MISMATCH)
        result
            .withCode(PipelineErrorCodes.Validation.CALCULATOR_OUTPUT_SHAPE_MISMATCH)
            .single()
            .details["reason"] shouldBe "single_output_kind"
    }

    @Test
    fun `context_key on a multi-output kind is a shape mismatch`() {
        val result =
            validateMulti(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.calculatorNode(
                                kind = Fixtures.WINDOW_KIND.kind,
                                inputs = mapOf("date" to Fixtures.ref("current_date")),
                                contextKey = "window_start",
                            ),
                        ),
                ),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(PipelineErrorCodes.Validation.CALCULATOR_OUTPUT_SHAPE_MISMATCH)
        result
            .withCode(PipelineErrorCodes.Validation.CALCULATOR_OUTPUT_SHAPE_MISMATCH)
            .single()
            .details["reason"] shouldBe "multi_output_kind"
    }

    @Test
    fun `both key fields present is a shape mismatch - never both`() {
        val result =
            validateMulti(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.calculatorNode(
                                kind = Fixtures.WINDOW_KIND.kind,
                                inputs = mapOf("date" to Fixtures.ref("current_date")),
                                contextKey = "window_start",
                                contextKeys = mapOf("start" to "window_start", "end" to "window_end"),
                            ),
                        ),
                ),
            )

        result
            .withCode(PipelineErrorCodes.Validation.CALCULATOR_OUTPUT_SHAPE_MISMATCH)
            .single()
            .details["reason"] shouldBe "both_fields"
    }

    @Test
    fun `neither key field present is a shape mismatch - never neither`() {
        // Before 121 this body failed `calculator_node_incomplete` with missing=context_key; D3
        // gives the key-shape verdicts their own code, so kind + inputs present + no mapping at
        // all is a shape mismatch whose message names both legal fields.
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(Fixtures.calculatorNode(contextKey = null))),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(PipelineErrorCodes.Validation.CALCULATOR_OUTPUT_SHAPE_MISMATCH)
        result
            .withCode(PipelineErrorCodes.Validation.CALCULATOR_OUTPUT_SHAPE_MISMATCH)
            .single()
            .details["reason"] shouldBe "neither_field"
    }

    @Test
    fun `a mapping naming an output the kind does not declare is refused, listing the ones it does`() {
        val result =
            validateMulti(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.windowNode(
                                contextKeys = mapOf("start" to "window_start", "middle" to "window_mid", "end" to "window_end"),
                            ),
                        ),
                ),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(PipelineErrorCodes.Validation.CALCULATOR_OUTPUT_UNKNOWN)
        val failure = result.withCode(PipelineErrorCodes.Validation.CALCULATOR_OUTPUT_UNKNOWN).single()
        failure.details["output"] shouldBe "middle"
        failure.details["known_outputs"] shouldBe listOf("start", "end")
    }

    @Test
    fun `a declared output left unmapped is refused - no partial mapping`() {
        val result =
            validateMulti(
                Fixtures.pipeline(
                    nodes = listOf(Fixtures.windowNode(contextKeys = mapOf("start" to "window_start"))),
                ),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(PipelineErrorCodes.Validation.CALCULATOR_OUTPUTS_INCOMPLETE)
        result
            .withCode(PipelineErrorCodes.Validation.CALCULATOR_OUTPUTS_INCOMPLETE)
            .single()
            .details["missing"] shouldBe listOf("end")
    }

    @Test
    fun `a mapped key obeys every key rule - name, parameter collision and one writer per key`() {
        val invalidName =
            validateMulti(
                Fixtures.pipeline(
                    nodes = listOf(Fixtures.windowNode(contextKeys = mapOf("start" to "Window Start", "end" to "window_end"))),
                ),
            )
        invalidName
            .withCode(PipelineErrorCodes.Validation.CALCULATOR_OUTPUT_NAME_INVALID)
            .single()
            .path shouldBe "nodes[0].context_keys.start"

        val parameterCollision =
            validateMulti(
                Fixtures.pipeline(
                    parameters = mapOf("window_end" to Parameter(LogicalType.DATE)),
                    nodes = listOf(Fixtures.windowNode()),
                ),
            )
        parameterCollision
            .withCode(PipelineErrorCodes.Validation.CALCULATOR_OUTPUT_COLLISION)
            .single()
            .details["collides_with"] shouldBe "parameter"

        val twoWriters =
            validateMulti(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.windowNode(id = "a"),
                            Fixtures.windowNode(id = "b", contextKeys = mapOf("start" to "other_start", "end" to "window_end")),
                        ),
                ),
            )
        val collision = twoWriters.withCode(PipelineErrorCodes.Validation.CALCULATOR_OUTPUT_COLLISION).single()
        collision.path shouldBe "nodes[1].context_keys.end"
        collision.details["context_key"] shouldBe "window_end"
    }

    @Test
    fun `a SQL node binding only one of a multi node's keys still needs the edge - transitively`() {
        fun pipelineWith(dependsOn: List<String>) =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.windowNode(),
                        // A DIFFERENT template than the report's, so the stub's `:window_end`
                        // bind belongs to the report alone — the refusal must name the reader
                        // that lacks the edge, once.
                        Fixtures.node(
                            "middle",
                            template = TemplateRef("test/middle.sql", 1),
                            dependsOn = dependsOn,
                            output = NodeOutput.Tempdb("stg_middle"),
                        ),
                        Fixtures.node("report", dependsOn = listOf("middle")),
                    ),
            )
        // Only `:window_end` is bound — a partial read is still a read of the node's write.
        val templates = StubTemplates(bound = mapOf(SQL_TEMPLATE to listOf("window_end")))

        val unordered = validateMulti(pipelineWith(emptyList()), templates = templates)
        val failure = unordered.withCode(PipelineErrorCodes.Validation.CALCULATOR_INPUT_UNORDERED).single()
        failure.details["context_key"] shouldBe "window_end"
        failure.details["written_by"] shouldBe "window"

        // The TRANSITIVE edge is enough: report → middle → window is reachability, not adjacency.
        validateMulti(pipelineWith(listOf("window")), templates = templates).failures.shouldBeEmpty()
    }

    @Test
    fun `a reference to a multi node's key resolves to that output's type`() {
        fun pipelineWith(readerInputs: Map<String, JsonNode>) =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.windowNode(),
                        Fixtures.calculatorNode(
                            id = "classify",
                            kind = "quarter_of_year",
                            inputs = readerInputs,
                            contextKey = "run_quarter",
                            dependsOn = listOf("window"),
                        ),
                    ),
            )

        // window_end is DATE, quarter_of_year's `date` takes DATE: the happy path.
        validateMulti(pipelineWith(mapOf("date" to Fixtures.ref("window_end")))).failures.shouldBeEmpty()

        // window_start is DATE too — feeding it to an INTEGER input is the mismatch the type
        // resolution exists to catch at save.
        val mismatch =
            validateMulti(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.windowNode(),
                            Fixtures.calculatorNode(
                                id = "shift",
                                kind = "add_days",
                                inputs = mapOf("date" to Fixtures.ref("current_date"), "days" to Fixtures.ref("window_start")),
                                contextKey = "run_target",
                                dependsOn = listOf("window"),
                            ),
                        ),
                ),
            )
        mismatch
            .withCode(PipelineErrorCodes.Validation.CALCULATOR_INPUT_TYPE_MISMATCH)
            .single()
            .details["reference"] shouldBe "window_start"
    }

    @Test
    fun `a multi node's key gating a template conditional is refused - every key is guarded`() {
        // 078 A1's guarded set comes from `calculatorOutputs()`, which now lists every key a
        // multi node writes — so `<#if window_end??>` is refused exactly as a single key is.
        val result =
            validateMulti(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            Fixtures.windowNode(),
                            Fixtures.node("report", dependsOn = listOf("window")),
                        ),
                ),
                templates = StubTemplates(conditioned = mapOf(SQL_TEMPLATE to setOf("window_end"))),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(PipelineErrorCodes.Template.PARAMETER_INTERPOLATED)
    }

    @Test
    fun `the dry render's context carries every key a multi node writes`() {
        val templates = StubTemplates()
        validateMulti(
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.windowNode(),
                        Fixtures.node("report", dependsOn = listOf("window")),
                    ),
            ),
            templates = templates,
        )

        val context = templates.renderedContexts.getValue(SQL_TEMPLATE)
        context.containsKey("window_start") shouldBe true
        context.containsKey("window_end") shouldBe true
    }
}
