package co.datapipelines.parameters

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.ValidationFailure
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import java.util.UUID

/**
 * Record §4, step by step — every §13.20 validation code reached by a fixture, through the REAL read
 * path (the reader binds the JSON, the validator judges it). The ports are fakes; the probe is a
 * RECORDING fake so steps 5–6 are proven called, never assumed.
 */
class ParameterSetValidatorTest {
    private val workspace = UUID.fromString("defa0000-0000-0000-0000-000000000001")
    private val states = TemplateRef("acme/sales/states_of_country.sql", 3)
    private val templates = FakeTemplates().also { it[states] = FakeTemplate(binds = listOf("country")) }
    private val probe = RecordingProbe()

    private fun validator(
        probe: SelectorProbe = this.probe,
        config: ParametersConfig = ParametersConfig(),
    ) = ParameterSetValidator(config, templates, templates, FakeDatasources(), probe)

    private fun document(json: String): ParameterSetDocument = ParameterSetReader().readOrThrow(ParameterSetFixtures.tree(json))

    private fun set(vararg parameters: String) = document(ParameterSetFixtures.setJson(*parameters))

    private fun validate(
        document: ParameterSetDocument,
        validator: ParameterSetValidator = validator(),
    ) = validator.validate(workspace, document)

    private fun failures(
        document: ParameterSetDocument,
        validator: ParameterSetValidator = validator(),
    ): List<ValidationFailure> = validate(document, validator).shouldBeInstanceOf<ParameterSetValidation.Invalid>().result.failures

    private fun codes(vararg parameters: String): List<String> = failures(set(*parameters)).map { it.code }

    private fun param(
        name: String = "p",
        extra: String = "",
        type: String = "STRING",
        kind: String = "INPUT",
    ) = """{ "name": "$name", "label": "P", "type": "$type", "kind": "$kind" $extra }"""

    @Nested
    inner class Valid {
        @Test
        fun `the record's cascade validates, and the stored form is canonical - expressions reprinted, a null default absent`() {
            probe[states.id] = ProbeScript.rows(listOf("NY", "New York", true), listOf("NJ", "New Jersey", false))
            val valid =
                validate(
                    document(
                        ParameterSetFixtures.setJson(
                            ParameterSetFixtures.countryJson(),
                            ParameterSetFixtures.stateJson(),
                            ParameterSetFixtures.amountJson(),
                        ),
                    ),
                ).shouldBeInstanceOf<ParameterSetValidation.Valid>()
            val state = valid.document.body.parameters[1]
            state.disabledExpression shouldBe ExpressionPrinter.print(Expr.IsNull(Ref("country")))
            state.defaultValue shouldBe null
        }

        @Test
        fun `a constants-only set saves without ever calling the probe`() {
            val constants = set(ParameterSetFixtures.countryJson(), ParameterSetFixtures.amountJson())
            validate(constants).shouldBeInstanceOf<ParameterSetValidation.Valid>()
            probe.renders shouldBe emptyList()
            probe.probes shouldBe emptyList()
        }
    }

    @Nested
    inner class Step1Set {
        @Test
        fun `the set name follows the folder grammar - folder_required and grammar reasons`() {
            failures(document(ParameterSetFixtures.setJson(ParameterSetFixtures.textInput("a"), name = "flat"))).single().let {
                it.code shouldBe ParameterErrorCodes.NAME_INVALID
                it.details["reason"] shouldBe "folder_required"
            }
            failures(
                document(ParameterSetFixtures.setJson(ParameterSetFixtures.textInput("a"), name = "Acme/Sales")),
            ).single().details["reason"] shouldBe
                "grammar"
        }

        @Test
        fun `display_name is a label and description is capped - the set's own fields`() {
            val blank = ParameterSetFixtures.fullSet().put("display_name", " ").put("description", "x".repeat(2001))
            failures(ParameterSetReader().readOrThrow(blank)).map { it.code to it.path } shouldContainExactlyInAnyOrder
                listOf(ParameterErrorCodes.LABEL_INVALID to "display_name", ParameterErrorCodes.DESCRIPTION_TOO_LONG to "description")
        }

        @Test
        fun `two parameters with one name - duplicate_parameter at the second`() {
            failures(set(param("a"), param("a"))).single().let {
                it.code shouldBe ParameterErrorCodes.DUPLICATE_PARAMETER
                it.path shouldBe "parameters[1].name"
            }
        }
    }

    @Nested
    inner class Step1Parameter {
        @Test
        fun `names - the §6-1 grammar, and the reserved _count and __digits suffixes`() {
            codes(param("1st")) shouldBe listOf(ParameterErrorCodes.NAME_INVALID)
            codes(param("regions_count")) shouldBe listOf(ParameterErrorCodes.NAME_RESERVED)
            failures(set(param("regions__1"))).single().details["reason"] shouldBe "slice_suffix"
            // Only the exact suffixes are reserved: `regions_counter` is an ordinary name.
            validate(set(param("regions_counter"))).shouldBeInstanceOf<ParameterSetValidation.Valid>()
        }

        @Test
        fun `label, description, precision, scale and an INPUT's cardinality`() {
            codes("""{ "name": "p", "label": " ", "type": "STRING", "kind": "INPUT" }""") shouldBe listOf(ParameterErrorCodes.LABEL_INVALID)
            codes("""{ "name": "p", "label": "${"L".repeat(121)}", "type": "STRING", "kind": "INPUT" }""") shouldBe
                listOf(ParameterErrorCodes.LABEL_INVALID)
            codes(param(extra = """, "description": "${"d".repeat(2001)}" """)) shouldBe listOf(ParameterErrorCodes.DESCRIPTION_TOO_LONG)
            codes(param(type = "DECIMAL")) shouldBe listOf(ParameterErrorCodes.PRECISION_MISSING)
            codes(param(type = "BIGDECIMAL")) shouldBe listOf(ParameterErrorCodes.SCALE_MISSING)
            codes(param(extra = """, "cardinality": "MULTI" """)) shouldBe listOf(ParameterErrorCodes.CARDINALITY_INVALID)
        }

        @Test
        fun `default_value - a textual BIGDECIMAL over the digit cap is refused unparsed (#278)`() {
            // The 194b security pass F3, at the parameter set's own save step: the textual default
            // reaches the shared validator's coercion, so the §6.3 digit cap answers here too —
            // `too_many_digits` in the refusal message, before any parse can spend seconds.
            val megabyte = "9".repeat(1_000_000)
            assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                failures(
                    set(param(type = "BIGDECIMAL", extra = """, "scale": 2, "default_value": "$megabyte" """)),
                ).single().let {
                    it.code shouldBe ParameterErrorCodes.DEFAULT_TYPE_MISMATCH
                    it.message shouldContain "too_many_digits"
                }
            }
        }

        @Test
        fun `source - missing on a SELECT, ambiguous three ways, constants refused on an INPUT`() {
            codes(param(kind = "SELECT")) shouldBe listOf(ParameterErrorCodes.SOURCE_MISSING)
            codes(
                param(
                    kind = "SELECT",
                    extra =
                        """, "source": { "constants": [ { "value": "a", "display_value": "A" } ],
                            "template": { "id": "acme/sales/states_of_country.sql", "version": 3 }, "datasource": "warehouse" }""",
                ),
            ) shouldContain ParameterErrorCodes.SOURCE_AMBIGUOUS
            codes(
                param(
                    kind = "SELECT",
                    extra = """, "source": { "template": { "id": "acme/sales/states_of_country.sql", "version": 3 } }""",
                ),
            ) shouldBe
                listOf(ParameterErrorCodes.SOURCE_AMBIGUOUS)
            codes(param(kind = "SELECT", extra = """, "source": { "datasource": "warehouse" }""")) shouldBe
                listOf(ParameterErrorCodes.SOURCE_AMBIGUOUS)
            codes(param(extra = """, "source": { "constants": [ { "value": "a", "display_value": "A" } ] }""")) shouldBe
                listOf(ParameterErrorCodes.SOURCE_NOT_ALLOWED)
        }

        @Test
        fun `constraints - a SELECT's are refused, an INPUT's judged by the shared validator`() {
            codes(
                ParameterSetFixtures
                    .constantsSelect(
                        "c",
                        listOf("a"),
                    ).replace("\"depends_on\"", "\"constraints\": { \"min_length\": 1 }, \"depends_on\""),
            ) shouldBe
                listOf(ParameterErrorCodes.CONSTRAINTS_ON_SELECT)
            codes(param(type = "INTEGER", extra = """, "constraints": { "pattern": "a+" }""")) shouldBe
                listOf(ParameterErrorCodes.CONSTRAINT_NOT_APPLICABLE)
            codes(param(type = "INTEGER", extra = """, "constraints": { "min": 5, "max": 1 }""")) shouldBe
                listOf(ParameterErrorCodes.CONSTRAINT_INVALID)
            codes(param(extra = """, "constraints": { "pattern": "(a)\\1" }""")) shouldBe listOf(ParameterErrorCodes.PATTERN_INVALID)
        }

        @Test
        fun `default_value - a MULTI default longer than max-multi-bind-values is refused before the validator sees it`() {
            // The 194b security pass, F2: the default reached the shared validator's quadratic duplicate
            // check uncapped; a default longer than a selection may ever carry is refused first.
            val wide =
                ParameterSetFixtures
                    .constantsSelect("c", listOf("a", "b", "c"), cardinality = "MULTI")
                    .replace("\"depends_on\"", "\"default_value\": [\"a\", \"b\", \"c\"], \"depends_on\"")
            failures(set(wide), validator(config = ParametersConfig(maxMultiBindValues = 2))).single().let {
                it.code shouldBe ParameterErrorCodes.DEFAULT_INVALID
                it.details["reason"] shouldBe "too_many_values"
            }
        }

        @Test
        fun `default_value - its type, its own rules, and a constants select's options`() {
            codes(param(type = "INTEGER", extra = """, "default_value": "5" """)) shouldBe listOf(ParameterErrorCodes.DEFAULT_TYPE_MISMATCH)
            failures(set(param(type = "INTEGER", extra = """, "default_value": -1, "constraints": { "min": 0 }"""))).single().let {
                it.code shouldBe ParameterErrorCodes.DEFAULT_INVALID
                it.details["reason"] shouldBe "min"
            }
            // The engine's max_length default (max-input-length) binds an INPUT that states none (P34).
            failures(
                set(param(extra = """, "default_value": "${"x".repeat(11)}" """)),
                validator(config = ParametersConfig(maxInputLength = 10)),
            ).single()
                .details["reason"] shouldBe "max_length"
            codes(
                ParameterSetFixtures
                    .constantsSelect(
                        "c",
                        listOf("a", "b"),
                    ).replace("\"depends_on\"", "\"default_value\": \"z\", \"depends_on\""),
            ) shouldBe
                listOf(ParameterErrorCodes.DEFAULT_NOT_AN_OPTION)
            codes(
                ParameterSetFixtures
                    .constantsSelect("c", listOf("a", "b"), cardinality = "MULTI")
                    .replace("\"depends_on\"", "\"default_value\": [\"a\", \"z\"], \"depends_on\""),
            ) shouldBe listOf(ParameterErrorCodes.DEFAULT_NOT_AN_OPTION)
            codes(
                ParameterSetFixtures
                    .constantsSelect(
                        "c",
                        listOf("a"),
                        cardinality = "MULTI",
                    ).replace("\"depends_on\"", "\"default_value\": \"a\", \"depends_on\""),
            ) shouldBe
                listOf(ParameterErrorCodes.DEFAULT_TYPE_MISMATCH)
        }

        @Test
        fun `constants - option values typed, bounded, unique by canonical value, one default at most, never empty`() {
            val decimal = """{ "name": "rate", "label": "Rate", "type": "DECIMAL", "precision": 5, "scale": 2, "kind": "SELECT",
                "source": { "constants": [ { "value": 1.0, "display_value": "one" }, { "value": 1.00, "display_value": "also one" },
                                           { "value": 1.234, "display_value": "too fine" }, { "value": "2", "display_value": "wrong form" },
                                           { "value": 3, "display_value": " " } ] } }"""
            failures(set(decimal)).map { it.code to (it.details["reason"] ?: it.path) } shouldContainExactlyInAnyOrder
                listOf(
                    ParameterErrorCodes.OPTION_DUPLICATE to "parameters[0].source.constants[1].value",
                    ParameterErrorCodes.OPTION_INVALID to "scale",
                    ParameterErrorCodes.OPTION_INVALID to "type",
                    ParameterErrorCodes.OPTION_INVALID to "empty_label",
                )
            val small = validator(config = ParametersConfig(maxOptionValueChars = 3, maxOptionLabelChars = 3))
            failures(
                set(ParameterSetFixtures.constantsSelect("c", listOf("abcd"))),
                small,
            ).map { it.details["reason"] } shouldContainExactlyInAnyOrder
                listOf("value_too_long", "label_too_long")
            codes(
                """{ "name": "c", "label": "C", "type": "STRING", "kind": "SELECT", "source": { "constants": [
                    { "value": "a", "display_value": "A", "is_default": true },
                    { "value": "b", "display_value": "B", "is_default": true } ] } }""",
            ) shouldBe listOf(ParameterErrorCodes.MULTIPLE_DEFAULTS)
            codes("""{ "name": "c", "label": "C", "type": "STRING", "kind": "SELECT", "source": { "constants": [] } }""") shouldBe
                listOf(ParameterErrorCodes.TOO_MANY_OPTIONS)
        }

        @Test
        fun `presentation - a control outside its row, a format outside its family, a temporal pattern that cannot hold`() {
            codes(param(type = "DATE", extra = """, "presentation": { "control": "dropdown" }""")) shouldBe
                listOf(ParameterErrorCodes.CONTROL_NOT_APPLICABLE)
            codes(
                ParameterSetFixtures
                    .constantsSelect(
                        "c",
                        listOf("a"),
                    ).replace("\"depends_on\"", "\"presentation\": { \"control\": \"checkboxes\" }, \"depends_on\""),
            ) shouldBe
                listOf(ParameterErrorCodes.CONTROL_NOT_APPLICABLE)
            codes(param(extra = """, "presentation": { "format": { "kind": "plain" } }""")) shouldBe
                listOf(ParameterErrorCodes.FORMAT_INVALID)
            codes(param(type = "INTEGER", extra = """, "presentation": { "format": { "pattern": "#,##0" } }""")) shouldBe
                listOf(ParameterErrorCodes.FORMAT_INVALID)
            codes(param(type = "DATE", extra = """, "presentation": { "format": { "kind": "plain" } }""")) shouldBe
                listOf(ParameterErrorCodes.FORMAT_INVALID)
            listOf(
                """"DATE", "presentation": { "format": { "pattern": "dd MMM yyyy HH:mm" } }""" to "time_field_on_date",
                """"TIME", "presentation": { "format": { "pattern": "yyyy HH:mm" } }""" to "date_field_on_time",
                """"DATE", "presentation": { "format": { "pattern": "{{" } }""" to "syntax",
                """"DATE", "presentation": { "format": { "pattern": "${"d".repeat(65)}" } }""" to "too_long",
            ).forEach { (typeAndFormat, reason) ->
                failures(set("""{ "name": "p", "label": "P", "type": $typeAndFormat, "kind": "INPUT" }""")).single().let {
                    it.code shouldBe ParameterErrorCodes.FORMAT_PATTERN_INVALID
                    it.details["reason"] shouldBe reason
                }
            }
            validate(
                set(
                    param(type = "DATE", extra = """, "presentation": { "control": "calendar", "format": { "pattern": "dd MMM yyyy" } }"""),
                ),
            ).shouldBeInstanceOf<ParameterSetValidation.Valid>()
        }

        @Test
        fun `an expression that does not parse is refused where it sits, the parser's path under the field`() {
            failures(set(param("a"), param("b", extra = """, "depends_on": ["a"], "hidden_expression": { "op": "xor" }"""))).single().let {
                it.code shouldBe ParameterErrorCodes.EXPRESSION_INVALID
                it.path shouldBe "parameters[1].hidden_expression.op"
            }
        }
    }

    @Nested
    inner class Step2Graph {
        @Test
        fun `a dependency on nothing, on itself, and a cycle with its path`() {
            codes(param("a", extra = """, "depends_on": ["ghost"]""")) shouldBe listOf(ParameterErrorCodes.DEPENDENCY_UNKNOWN)
            codes(param("a", extra = """, "depends_on": ["a"]""")) shouldBe listOf(ParameterErrorCodes.DEPENDENCY_SELF)
            failures(
                set(
                    param("a", extra = """, "depends_on": ["c"]"""),
                    param("b", extra = """, "depends_on": ["a"]"""),
                    param("c", extra = """, "depends_on": ["b"]"""),
                ),
            ).single()
                .let {
                    it.code shouldBe ParameterErrorCodes.DEPENDENCY_CYCLE
                    (it.details["cycle"] as List<*>).size shouldBe 4 // a -> c -> b -> a (the path closes on its start)
                }
        }
    }

    @Nested
    inner class Step3Expressions {
        @Test
        fun `a ref outside depends_on is refused by the static rules`() {
            failures(
                set(param("a"), param("b", extra = """, "disabled_expression": { "op": "is_null", "arg": { "ref": "a" } }""")),
            ).single().let {
                it.code shouldBe ParameterErrorCodes.REF_UNDECLARED
                it.path shouldBe "parameters[1].disabled_expression.arg.ref"
            }
        }

        @Test
        fun `an expression literal over the digit cap is refused unparsed (#278)`() {
            // The same inheritance through the OTHER coercion caller: ExpressionSemantics checks a
            // literal against its ref's type through ParameterCoercion, so the §6.3 cap answers at
            // save with `expression_literal_type` and the same `too_many_digits` token.
            val megabyte = "9".repeat(1_000_000)
            val withLiteral =
                """, "depends_on": ["big"], "disabled_expression": """ +
                    """{ "op": "eq", "left": { "ref": "big" }, "right": { "literal": "$megabyte" } } """
            assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                failures(
                    set(
                        param("big", type = "BIGDECIMAL", extra = """, "scale": 2 """),
                        param("p", extra = withLiteral),
                    ),
                ).single().let {
                    it.code shouldBe ParameterErrorCodes.EXPRESSION_LITERAL_TYPE
                    it.message shouldContain "too_many_digits"
                }
            }
        }
    }

    @Nested
    inner class Step4Pins {
        private fun templated(
            ref: String = """{ "id": "acme/sales/states_of_country.sql", "version": 3 }""",
            datasource: String = "warehouse",
            dependsOn: String = """["country"]""",
            kind: String = "SELECT",
        ) = """{ "name": "state", "label": "State", "type": "STRING", "kind": "$kind",
                "source": { "template": $ref, "datasource": "$datasource" }, "depends_on": $dependsOn }"""

        private fun pinCodes(state: String): List<ValidationFailure> = failures(set(ParameterSetFixtures.countryJson(), state))

        @Test
        fun `the pin resolves - not found, version not found, a discarded version, a non-sql type, a dialect mismatch`() {
            pinCodes(templated(ref = """{ "id": "acme/none.sql", "version": 1 }""")).single().code shouldBe
                ParameterErrorCodes.TEMPLATE_NOT_FOUND
            pinCodes(templated(ref = """{ "id": "acme/sales/states_of_country.sql", "version": 9 }""")).single().code shouldBe
                ParameterErrorCodes.TEMPLATE_VERSION_NOT_FOUND
            templates[TemplateRef("acme/old.sql", 1)] = FakeTemplate(status = PipelineVersionStatus.DISCARDED)
            pinCodes(templated(ref = """{ "id": "acme/old.sql", "version": 1 }""")).single().details["reason"] shouldBe "discarded"
            templates[TemplateRef("acme/page.html", 1)] = FakeTemplate(dialect = null, type = TemplateType.HTML)
            pinCodes(templated(ref = """{ "id": "acme/page.html", "version": 1 }""")).single().code shouldBe
                ParameterErrorCodes.TEMPLATE_TYPE_MISMATCH
            templates[TemplateRef("acme/oracle.sql", 1)] = FakeTemplate(dialect = Dialect.ORACLE, binds = listOf("country"))
            pinCodes(templated(ref = """{ "id": "acme/oracle.sql", "version": 1 }""")).single().code shouldBe
                ParameterErrorCodes.TEMPLATE_DIALECT_MISMATCH
        }

        @Test
        fun `a datasource not visible from the workspace`() {
            pinCodes(templated(datasource = "elsewhere")).single().code shouldBe ParameterErrorCodes.DATASOURCE_NOT_FOUND
        }

        @Test
        fun `a parent interpolated with dollar-brace is the existing template code`() {
            templates[states] = FakeTemplate(binds = listOf("country"), interpolates = listOf("country"))
            pinCodes(templated()).single().code shouldBe PipelineErrorCodes.Template.PARAMETER_INTERPOLATED
        }

        @Test
        fun `binds resolve by namespace (P30) - dependency, MULTI count, tiers - never a stranger, non-dependency, slice, execution_id`() {
            templates[states] =
                FakeTemplate(binds = listOf("country", "regions_count", "org_currency_symbol", "current_date", "current_timestamp"))
            val regions = ParameterSetFixtures.constantsSelect("regions", listOf("w", "e"), cardinality = "MULTI")
            validate(set(ParameterSetFixtures.countryJson(), regions, templated(dependsOn = """["country", "regions"]""")))
                .shouldBeInstanceOf<ParameterSetValidation.Valid>()

            templates[states] = FakeTemplate(binds = listOf("country", "city", "regions_count", "regions__1", "execution_id", "stranger"))
            failures(set(ParameterSetFixtures.countryJson(), regions, ParameterSetFixtures.textInput("city"), templated()))
                .filter { it.code == ParameterErrorCodes.BIND_UNDECLARED }
                .map { it.details["bind"] to it.details["reason"] } shouldContainExactlyInAnyOrder
                listOf(
                    "city" to "not_a_dependency",
                    "regions_count" to "not_a_dependency",
                    "regions__1" to "slice",
                    "execution_id" to "unknown",
                    "stranger" to "unknown",
                )
        }
    }

    @Nested
    inner class Steps5And6 {
        private val cascade = set(ParameterSetFixtures.countryJson(), ParameterSetFixtures.stateJson())

        private fun only(script: ProbeScript): ValidationFailure {
            probe[states.id] = script
            return failures(cascade).single()
        }

        @Test
        fun `the probe is CALLED - render with the parents' defaults and the tiers, then the statement with maxRows 2`() {
            probe[states.id] = ProbeScript.rows(listOf("NY", "New York", true))
            validate(cascade).shouldBeInstanceOf<ParameterSetValidation.Valid>()
            val render = probe.renders.single()
            render.template shouldBe states
            render.context["country"] shouldBe "USA" // the constants' marked default, top-down
            render.context.keys shouldContainAll listOf("org_currency_symbol", "current_date", "current_timestamp")
            ("execution_id" in render.context) shouldBe false
            val statement = probe.probes.single()
            statement.datasource shouldBe "warehouse"
            statement.maxRows shouldBe 2
            statement.binds["country"] shouldBe "USA"
        }

        @Test
        fun `render failed, unreachable, statement failed with the datasource's own code`() {
            only(ProbeScript(render = SelectorRender.Failed("undefined: x"))).code shouldBe ParameterErrorCodes.TEMPLATE_RENDER_FAILED
            only(ProbeScript(outcome = SelectorProbeOutcome.Unreachable("connection refused"))).code shouldBe
                ParameterErrorCodes.DATASOURCE_UNREACHABLE
            only(ProbeScript(outcome = SelectorProbeOutcome.StatementFailed("datasource.query_execution_failed", "no column c"))).let {
                it.code shouldBe ParameterErrorCodes.SELECTOR_QUERY_FAILED
                it.details["datasource_code"] shouldBe "datasource.query_execution_failed"
            }
        }

        @Test
        fun `a SELECT returns exactly its three columns, typed, ordered`() {
            only(
                ProbeScript(
                    outcome =
                        SelectorProbeOutcome.Probed(
                            ProbeScript.SELECT_COLUMNS + ColumnSchema("extra", LogicalType.STRING),
                            emptyList(),
                        ),
                ),
            ).code shouldBe ParameterErrorCodes.SELECTOR_COLUMNS_INVALID
            only(
                ProbeScript(outcome = SelectorProbeOutcome.Probed(ProbeScript.SELECT_COLUMNS.take(2), emptyList())),
            ).details["missing"] shouldBe
                listOf("is_default")
            only(
                ProbeScript(
                    outcome =
                        SelectorProbeOutcome.Probed(
                            listOf(
                                ColumnSchema("VALUE", LogicalType.STRING),
                                ColumnSchema("DISPLAY_VALUE", LogicalType.INTEGER),
                                ColumnSchema("IS_DEFAULT", LogicalType.BOOLEAN),
                            ),
                            emptyList(),
                        ),
                ),
            ).details["reason"] shouldBe "display_value_type" // upper-case labels (H2, Oracle) read as the same names
            only(
                ProbeScript(
                    outcome =
                        SelectorProbeOutcome.Probed(
                            listOf(ColumnSchema("value", LogicalType.NULL)) + ProbeScript.SELECT_COLUMNS.drop(1),
                            emptyList(),
                        ),
                ),
            ).let {
                it.code shouldBe ParameterErrorCodes.SELECTOR_VALUE_TYPE_MISMATCH
                it.details["hint"] shouldBe "CAST the column"
            }
            only(
                ProbeScript(
                    render = SelectorRender.Rendered("SELECT s AS value, n AS display_value, d AS is_default FROM t -- ORDER BY n"),
                ),
            ).code shouldBe ParameterErrorCodes.SELECTOR_ORDER_BY_MISSING
        }

        @Test
        fun `a database-fed INPUT - exactly the value column, at most one row, and the row passes the input's own rules`() {
            val sinceRef = TemplateRef("acme/sales/first_order.sql", 1)
            templates[sinceRef] = FakeTemplate(binds = listOf("country"))
            val since =
                """{ "name": "min_amount", "label": "Min", "type": "INTEGER", "kind": "INPUT", "constraints": { "min": 0 },
                    "source": { "template": { "id": "${sinceRef.id}", "version": 1 }, "datasource": "warehouse" },
                    "depends_on": ["country"] }"""
            val input = set(ParameterSetFixtures.countryJson(), since)
            val one = listOf(ColumnSchema("value", LogicalType.INTEGER))
            probe[sinceRef.id] =
                ProbeScript(
                    render = SelectorRender.Rendered("SELECT 5 AS value"),
                    outcome = SelectorProbeOutcome.Probed(one, listOf(listOf(5))),
                )
            validate(input).shouldBeInstanceOf<ParameterSetValidation.Valid>()
            probe[sinceRef.id] = ProbeScript(outcome = SelectorProbeOutcome.Probed(one, listOf(listOf(5), listOf(6))))
            failures(input).single().code shouldBe ParameterErrorCodes.INPUT_SOURCE_MULTIPLE_ROWS
            probe[sinceRef.id] = ProbeScript(outcome = SelectorProbeOutcome.Probed(one, listOf(listOf(-1))))
            failures(input).single().let {
                it.code shouldBe ParameterErrorCodes.DEFAULT_INVALID
                it.details["source"] shouldBe "row"
            }
            probe[sinceRef.id] = ProbeScript(outcome = SelectorProbeOutcome.Probed(ProbeScript.SELECT_COLUMNS, emptyList()))
            failures(input).single().code shouldBe ParameterErrorCodes.SELECTOR_COLUMNS_INVALID
        }

        @Test
        fun `top-down - a child renders against its template parent's probed default row`() {
            val cityRef = TemplateRef("acme/sales/cities.sql", 1)
            templates[cityRef] = FakeTemplate(binds = listOf("state"))
            val city =
                """{ "name": "city", "label": "City", "type": "STRING", "kind": "SELECT",
                    "source": { "template": { "id": "${cityRef.id}", "version": 1 }, "datasource": "warehouse" },
                    "depends_on": ["state"] }"""
            probe[states.id] = ProbeScript.rows(listOf("NJ", "New Jersey", false), listOf("NY", "New York", true))
            validate(
                set(ParameterSetFixtures.countryJson(), ParameterSetFixtures.stateJson(), city),
            ).shouldBeInstanceOf<ParameterSetValidation.Valid>()
            probe.renders.map { it.template.id } shouldBe listOf(states.id, cityRef.id)
            probe.renders.last().context["state"] shouldBe "NY"
        }

        @Test
        fun `a MULTI parent reaches the template as its count, and the statement as its list`() {
            templates[states] = FakeTemplate(binds = listOf("regions", "regions_count"))
            val regions = ParameterSetFixtures.constantsSelect("regions", listOf("w", "e"), cardinality = "MULTI", firstDefault = true)
            val state =
                ParameterSetFixtures.stateJson().replace("\"depends_on\": [\"country\"]", "\"depends_on\": [\"regions\"]").replace(
                    "\"disabled_expression\": { \"op\": \"is_null\", \"arg\": { \"ref\": \"country\" } }",
                    "\"disabled_expression\": { \"op\": \"is_empty\", \"arg\": { \"ref\": \"regions\" } }",
                )
            validate(set(regions, state)).shouldBeInstanceOf<ParameterSetValidation.Valid>()
            probe.renders.single().context.let {
                it["regions_count"] shouldBe 1
                ("regions" in it) shouldBe false
            }
            probe.probes.single().binds["regions"] shouldBe listOf("w")
        }

        @Test
        fun `a document refused at steps 1-4 never reaches the datasource`() {
            val bad = set(ParameterSetFixtures.countryJson(), ParameterSetFixtures.stateJson(), param("1bad"))
            failures(bad).map { it.code } shouldBe listOf(ParameterErrorCodes.NAME_INVALID)
            withClue("no render, no statement for a refused document") { (probe.renders.size + probe.probes.size) shouldBe 0 }
        }

        @Test
        fun `release and import re-run steps 4-6 on a stored body - a pin whose dialect moved is caught`() {
            probe[states.id] = ProbeScript.rows(listOf("NY", "New York", true))
            val stored = validate(cascade).shouldBeInstanceOf<ParameterSetValidation.Valid>().document.body
            validator().revalidateSources(workspace, stored).isValid shouldBe true
            templates[states] = FakeTemplate(dialect = Dialect.MSSQL, binds = listOf("country"))
            validator().revalidateSources(workspace, stored).codes shouldBe listOf(ParameterErrorCodes.TEMPLATE_DIALECT_MISMATCH)
        }
    }
}
