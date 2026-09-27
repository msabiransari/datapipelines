package co.datapipelines.parameters

import co.datapipelines.datasources.DatasourceErrorCodes
import co.datapipelines.parameters.EvaluatorFixtures.selections
import co.datapipelines.parameters.EvaluatorFixtures.templateSelect
import co.datapipelines.parameters.EvaluatorFixtures.version
import co.datapipelines.pipeline.ContextKeys
import co.datapipelines.pipeline.OrgContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The evaluator (record §5) over a SCRIPTED selector runtime — every P26 branch, the owner's three
 * scenarios over a twenty-parameter set with a parent that is also a child, P25's one "nothing
 * chosen" signal, step 1's refusals, hidden/disabled retention, per-parameter failures, the binds and
 * tiers a selector sees, and the §5.3 response. The real runner is `ParameterEvaluatorIntegrationTest`'s.
 */
class ParameterEvaluatorTest {
    private val selectors = EvaluatorFixtures.warehouse()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun stop() = scope.cancel()

    private fun evaluator(
        config: ParametersConfig = ParametersConfig(),
        pool: SelectorPool = SelectorPool(4, 64),
        org: OrgContext = OrgContext.DEFAULTS,
        clock: Clock = CLOCK,
    ) = ParameterEvaluator(selectors, pool, config, org, clock)

    private fun evaluate(
        set: ParameterSetVersion,
        vararg selections: Pair<String, Any?>,
        with: ParameterEvaluator = evaluator(),
    ): EvaluateResponse = with.evaluateBlocking(EvaluatorFixtures.WORKSPACE, set, selections(*selections))

    private fun EvaluateResponse.state(name: String): ParameterState = parameters.single { it.definition.name == name }.state

    private fun EvaluateResponse.codes(name: String): List<String> = state(name).errors.map { it.code }

    /** The response's own `values`, wire-encoded — what a client submits back unchanged. */
    private fun EvaluateResponse.wireValues(): Map<String, JsonNode?> =
        EvaluateResponseJson.write(this)["values"].properties().associate { it.key to it.value }

    private val twenty = version(*EvaluatorFixtures.twenty())

    @Nested
    inner class OwnerScenarios {
        @Test
        fun `(a) nothing passed - every parameter picks by priority and the form comes back fully chosen`() {
            val response = evaluate(twenty)

            response.valid shouldBe true
            response.values shouldBe
                linkedMapOf(
                    "country" to "USA",
                    "state" to "NY",
                    "city" to "NYC",
                    "regions" to listOf("EAST"),
                    "store" to "S-EAST",
                    "start_date" to LocalDate.parse("2026-01-01"),
                    "min_order_amount" to BigDecimal.ZERO,
                    "segment" to "retail",
                    "channel" to "web",
                    "tier" to "gold",
                    "currency" to "usd",
                    "metric" to "revenue",
                    "period" to "day",
                    "compare" to "none",
                    "note" to null,
                    "tag" to null,
                    "flags" to listOf("a"),
                    "owner" to null,
                    "sort" to "asc",
                    "label_text" to null,
                )
            response.parameters
                .map { it.state.origin }
                .distinct()
                .toSet() shouldBe
                setOf(ValueOrigin.DEFAULT, ValueOrigin.FIRST, ValueOrigin.SOURCE, ValueOrigin.NONE)
            response.state("country").origin shouldBe ValueOrigin.DEFAULT
            response.state("city").origin shouldBe ValueOrigin.FIRST
            response.state("start_date").origin shouldBe ValueOrigin.SOURCE
            response.parameters.none { it.state.reset } shouldBe true
        }

        @Test
        fun `(a) then the client submits the form back unchanged - every chosen value is kept as the client's`() {
            val first = evaluate(twenty)

            val second = evaluator().evaluateBlocking(EvaluatorFixtures.WORKSPACE, twenty, first.wireValues())

            second.values shouldBe first.values
            second.parameters
                .filter { it.state.value != null }
                .map { it.state.origin }
                .toSet() shouldBe setOf(ValueOrigin.CLIENT)
            second.parameters.none { it.state.reset } shouldBe true
        }

        @Test
        fun `(b) three of twenty passed - validated where they sit, children follow, a parent-that-is-a-child evaluated once in order`() {
            val response = evaluate(twenty, "country" to "CAN", "regions" to listOf("NORTH"), "segment" to "online")

            response.values.filterKeys {
                it in
                    setOf(
                        "country",
                        "state",
                        "city",
                        "regions",
                        "store",
                        "start_date",
                        "segment",
                        "channel",
                    )
            } shouldBe
                mapOf(
                    "country" to "CAN",
                    "state" to "ON",
                    "city" to "TOR",
                    "regions" to listOf("NORTH"),
                    "store" to "S-NORTH",
                    "start_date" to LocalDate.parse("2026-04-01"),
                    "segment" to "online",
                    "channel" to "web",
                )
            listOf("country", "regions", "segment").map { response.state(it).origin }.toSet() shouldBe setOf(ValueOrigin.CLIENT)
            response.state("state").origin shouldBe ValueOrigin.FIRST
            selectors.requestsFor("acme/sales/state.sql").single().binds["country"] shouldBe "CAN"
            withClue("state is a child of country and the parent of city: after one, before the other, once") {
                selectors.events.indexOf("end:acme/sales/state.sql") shouldBeLessThan selectors.events.indexOf("start:acme/sales/city.sql")
                selectors.requestsFor("acme/sales/city.sql").single().binds["state"] shouldBe "ON"
            }
        }

        @Test
        fun `(c) all passed and a child no longer fits (New Jersey under Canada) - no error, it walks with reset, its children follow`() {
            val all = evaluate(twenty).wireValues().toMutableMap()
            all["country"] = ParameterSetJson.mapper.valueToTree("CAN")
            all["state"] = ParameterSetJson.mapper.valueToTree("NJ")
            all["city"] = ParameterSetJson.mapper.valueToTree("NWK")

            val response = evaluator().evaluateBlocking(EvaluatorFixtures.WORKSPACE, twenty, all)

            response.valid shouldBe true
            (response.state("state").value to response.state("state").reset) shouldBe ("ON" to true)
            response.state("state").origin shouldBe ValueOrigin.FIRST
            (response.state("city").value to response.state("city").reset) shouldBe ("TOR" to true)
            withClue("a MULTI none of whose members survive walks to its default else first, reset") {
                (response.state("regions").value to response.state("regions").reset) shouldBe (listOf("NORTH") to true)
                (response.state("store").value to response.state("store").reset) shouldBe ("S-NORTH" to true)
            }
            withClue("a typed INPUT keeps winning while its computed_default follows the country") {
                response.state("start_date").value shouldBe LocalDate.parse("2026-01-01")
                response.state("start_date").origin shouldBe ValueOrigin.CLIENT
                response.state("start_date").computedDefault shouldBe LocalDate.parse("2026-04-01")
            }
        }
    }

    @Nested
    inner class NothingChosen {
        @Test
        fun `the three spellings of nothing produce byte-identical responses, required and optional, SINGLE and MULTI`() {
            val absent = EvaluateResponseJson.write(evaluate(twenty))
            val nulls = EvaluateResponseJson.write(evaluate(twenty, "country" to null, "regions" to null, "flags" to null, "note" to null))
            val empties =
                EvaluateResponseJson.write(
                    evaluate(
                        twenty,
                        "country" to null,
                        "regions" to emptyList<String>(),
                        "flags" to emptyList<String>(),
                    ),
                )

            nulls shouldBe absent
            empties shouldBe absent
        }
    }

    @Nested
    inner class SelectionPriority {
        private val countries =
            version(
                ParameterSetFixtures.constantsSelect("country", listOf("USA", "CAN", "AAA")),
                templateSelect("state", dependsOn = listOf("country"), required = true),
                templateSelect("city", dependsOn = listOf("state")),
            )

        @Test
        fun `SINGLE step 1 - the submitted value when it is among the options`() {
            evaluate(countries, "country" to "USA", "state" to "NJ").state("state").let {
                (it.value to it.origin to it.reset) shouldBe (("NJ" to ValueOrigin.CLIENT) to false)
                it.computedDefault shouldBe "NY"
            }
        }

        @Test
        fun `SINGLE step 2 - default_value when among the options, else the is_default row`() {
            val declared =
                version(ParameterSetFixtures.countryJson(), templateSelect("state", dependsOn = listOf("country"), defaultValue = "\"CA\""))
            evaluate(declared).state("state").let { (it.value to it.origin) shouldBe ("CA" to ValueOrigin.DEFAULT) }
            evaluate(countries, "country" to "USA").state("state").let { (it.value to it.origin) shouldBe ("NY" to ValueOrigin.DEFAULT) }
        }

        @Test
        fun `SINGLE step 3 - the first option when no configured default is among them`() {
            val declared =
                version(ParameterSetFixtures.countryJson(), templateSelect("state", dependsOn = listOf("country"), defaultValue = "\"ZZ\""))
            evaluate(declared, "country" to "CAN").state("state").let { (it.value to it.origin) shouldBe ("ON" to ValueOrigin.FIRST) }
        }

        @Test
        fun `SINGLE - a stale value walks to 2 then 3 with reset true, never an error`() {
            evaluate(countries, "country" to "USA", "state" to "ON").state("state").let {
                (it.value to it.origin to it.reset) shouldBe (("NY" to ValueOrigin.DEFAULT) to true)
                it.errors shouldBe emptyList()
            }
            evaluate(countries, "country" to "CAN", "state" to "NY").state("state").let {
                (it.value to it.origin to it.reset) shouldBe (("ON" to ValueOrigin.FIRST) to true)
            }
        }

        @Test
        fun `the Country A to B walk - a required state with no options is required_missing, switching resolves it to B's first`() {
            val stranded = evaluate(countries, "country" to "AAA")
            stranded.valid shouldBe false
            stranded.state("state").let {
                (it.value to it.origin) shouldBe (null to ValueOrigin.NONE)
                it.errors.single().code shouldBe ParameterErrorCodes.EVALUATE_REQUIRED_MISSING
                it.errors.single().details["reason"] shouldBe "no_options"
                it.options shouldBe emptyList()
            }
            withClue("an optional child of the unresolved parent is empty and not in error; it bound NULL") {
                stranded.state("city").let { (it.value to it.errors) shouldBe (null to emptyList()) }
                ("state" in selectors.requestsFor("acme/sales/city.sql").single().binds) shouldBe true
                selectors.requestsFor("acme/sales/city.sql").single().binds["state"] shouldBe null
            }

            val resolved = evaluate(countries, "country" to "CAN", "state" to null)

            resolved.valid shouldBe true
            resolved.state("state").let { (it.value to it.origin) shouldBe ("ON" to ValueOrigin.FIRST) }
        }

        @Test
        fun `a sentinel ALL row is one more option - chosen by default, bound to the children like any value`() {
            selectors["acme/sales/state.sql"] = { ScriptedSelectors.options("ALL", "NY", "NJ", default = "ALL") }

            val response = evaluate(countries, "country" to "USA")

            response.state("state").let { (it.value to it.origin) shouldBe ("ALL" to ValueOrigin.DEFAULT) }
            selectors.requestsFor("acme/sales/city.sql").single().binds["state"] shouldBe "ALL"
        }

        @Test
        fun `MULTI - the survivors, then the default members, then the first option alone`() {
            evaluate(twenty, "regions" to listOf("WEST", "SOUTH")).state("regions").let {
                (it.value to it.origin to it.reset) shouldBe ((listOf("WEST", "SOUTH") to ValueOrigin.CLIENT) to false)
            }
            evaluate(twenty, "regions" to listOf("WEST", "NORTH")).state("regions").let {
                (it.value to it.origin to it.reset) shouldBe ((listOf("WEST") to ValueOrigin.CLIENT) to true)
            }
            evaluate(twenty, "regions" to listOf("NORTH")).state("regions").let {
                (it.value to it.origin to it.reset) shouldBe ((listOf("EAST") to ValueOrigin.DEFAULT) to true)
            }
            evaluate(twenty, "country" to "CAN", "regions" to listOf("ZZZ")).state("regions").let {
                (it.value to it.origin to it.reset) shouldBe ((listOf("NORTH") to ValueOrigin.FIRST) to true)
            }
            val declared =
                version(
                    ParameterSetFixtures.countryJson(),
                    templateSelect(
                        "regions",
                        dependsOn = listOf("country"),
                        cardinality = "MULTI",
                        defaultValue = "[\"WEST\", \"NOPE\", \"SOUTH\"]",
                    ),
                )
            evaluate(declared).state("regions").let { (it.value to it.origin) shouldBe (listOf("WEST", "SOUTH") to ValueOrigin.DEFAULT) }
        }

        @Test
        fun `INPUT - the client's value, else the sourced row, else default_value, else required_missing`() {
            val sourced =
                version(ParameterSetFixtures.countryJson(), EvaluatorFixtures.startDate(required = true, defaultValue = "2025-12-31"))

            evaluate(sourced).state("start_date").let {
                (it.value to it.origin) shouldBe (LocalDate.parse("2026-01-01") to ValueOrigin.SOURCE)
                it.computedDefault shouldBe LocalDate.parse("2026-01-01")
                it.options shouldBe null
            }
            evaluate(sourced, "start_date" to "2026-02-02").state("start_date").let {
                (it.value to it.origin) shouldBe (LocalDate.parse("2026-02-02") to ValueOrigin.CLIENT)
                it.computedDefault shouldBe LocalDate.parse("2026-01-01")
            }
            selectors["acme/sales/start_date.sql"] = { ScriptedSelectors.inputRows(LogicalType.DATE) }
            evaluate(sourced).state("start_date").let {
                (it.value to it.origin) shouldBe
                    (LocalDate.parse("2025-12-31") to ValueOrigin.DEFAULT)
            }
            withClue("one row whose value is NULL is no row") {
                selectors["acme/sales/start_date.sql"] = { ScriptedSelectors.inputRows(LogicalType.DATE, null) }
                evaluate(sourced).state("start_date").origin shouldBe ValueOrigin.DEFAULT
            }
            val bare = version(ParameterSetFixtures.countryJson(), EvaluatorFixtures.startDate(required = true))
            evaluate(bare).state("start_date").let {
                (it.value to it.origin) shouldBe (null to ValueOrigin.NONE)
                it.errors.single().details["reason"] shouldBe "no_row"
            }
        }

        @Test
        fun `INPUT - two sourced rows are input_source_multiple_rows and default_value is used`() {
            selectors["acme/sales/start_date.sql"] =
                { ScriptedSelectors.inputRows(LogicalType.DATE, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-02")) }
            val set = version(ParameterSetFixtures.countryJson(), EvaluatorFixtures.startDate(defaultValue = "2025-12-31"))

            evaluate(set).state("start_date").let {
                it.errors.single().code shouldBe ParameterErrorCodes.EVALUATE_INPUT_SOURCE_MULTIPLE_ROWS
                (it.value to it.origin) shouldBe (LocalDate.parse("2025-12-31") to ValueOrigin.DEFAULT)
            }
        }

        @Test
        fun `INPUT without a source - required, no default, the client's null is required_missing no_default`() {
            val set =
                version(
                    """{ "name": "code", "label": "Code", "type": "STRING", "kind": "INPUT", "required": true }""",
                    ParameterSetFixtures.amountJson(),
                )

            val response = evaluate(set, "code" to null)

            response
                .state("code")
                .errors
                .single()
                .details["reason"] shouldBe "no_default"
            response.state("min_order_amount").let { (it.value to it.origin) shouldBe (BigDecimal.ZERO to ValueOrigin.DEFAULT) }
        }
    }

    @Nested
    inner class StepOne {
        @Test
        fun `a scale or min breach is constraint_violation with its reason, and the parameter walks as absent`() {
            evaluate(twenty, "min_order_amount" to BigDecimal("12.345")).state("min_order_amount").let {
                it.errors.single().code shouldBe ParameterErrorCodes.EVALUATE_CONSTRAINT_VIOLATION
                it.errors.single().details["reason"] shouldBe "scale"
                (it.value to it.origin) shouldBe (BigDecimal.ZERO to ValueOrigin.DEFAULT)
            }
            evaluate(twenty, "min_order_amount" to -1)
                .state("min_order_amount")
                .errors
                .single()
                .details["reason"] shouldBe "min"
            evaluate(twenty, "min_order_amount" to BigDecimal("250.00")).state("min_order_amount").let {
                (it.value.canonical() to it.errors) shouldBe (BigDecimal("250.00").canonical() to emptyList())
            }
        }

        @Test
        fun `a wrong wire form or a duplicate MULTI member is invalid_value_type - and the rest of the form still answers`() {
            val response = evaluate(twenty, "min_order_amount" to "12", "regions" to listOf("EAST", "EAST"))

            response.codes("min_order_amount") shouldContainExactly listOf(ParameterErrorCodes.EVALUATE_INVALID_VALUE_TYPE)
            response.codes("regions") shouldContainExactly listOf(ParameterErrorCodes.EVALUATE_INVALID_VALUE_TYPE)
            response.state("regions").value shouldBe listOf("EAST")
            response.state("store").value shouldBe "S-EAST"
            response.valid shouldBe false
        }

        @Test
        fun `a MULTI longer than max-multi-bind-values is too_many_values - the bound itself is accepted`() {
            val flags = version(ParameterSetFixtures.constantsSelect("flags", List(10) { "f$it" }, cardinality = "MULTI"))
            val tight = evaluator(config = ParametersConfig(maxMultiBindValues = 3))

            evaluate(flags, "flags" to listOf("f1", "f2", "f3", "f4"), with = tight).codes("flags") shouldContainExactly
                listOf(ParameterErrorCodes.EVALUATE_TOO_MANY_VALUES)
            evaluate(flags, "flags" to listOf("f1", "f2", "f3"), with = tight).codes("flags") shouldBe emptyList()
        }

        @Test
        fun `a refused value is not ALSO required_missing - the user supplied something, it was wrong`() {
            val set =
                version(
                    """
                    { "name": "qty", "label": "Qty", "type": "INTEGER", "kind": "INPUT", "required": true, "constraints": { "min": 1 } }
                    """.trimIndent(),
                )

            evaluate(set, "qty" to 0).codes("qty") shouldContainExactly listOf(ParameterErrorCodes.EVALUATE_CONSTRAINT_VIOLATION)
            evaluate(set).codes("qty") shouldContainExactly listOf(ParameterErrorCodes.EVALUATE_REQUIRED_MISSING)
        }

        @Test
        fun `a selections key that names no parameter refuses the whole request - unknown_parameter`() {
            val refused = shouldThrow<DatapipelinesException> { evaluate(twenty, "country" to "USA", "nope" to 1) }

            refused.code shouldBe ParameterErrorCodes.EVALUATE_UNKNOWN_PARAMETER
            refused.details["unknown"] shouldBe listOf("nope")
        }
    }

    @Nested
    inner class HiddenAndDisabled {
        private val flagged =
            version(
                ParameterSetFixtures.countryJson(),
                ParameterSetFixtures.constantsSelect("hide", listOf("no", "yes"), firstDefault = true),
                """
                { "name": "min_order_amount", "label": "Minimum", "type": "DECIMAL", "precision": 12, "scale": 2, "kind": "INPUT",
                  "default_value": 0, "constraints": { "min": 0 }, "depends_on": ["hide"],
                  "hidden_expression": { "op": "eq", "left": { "ref": "hide" }, "right": { "literal": "yes" } } }
                """.trimIndent(),
                templateSelect(
                    "state",
                    dependsOn = listOf("country", "hide"),
                    extra = """, "disabled_expression": { "op": "eq", "left": { "ref": "hide" }, "right": { "literal": "yes" } }""",
                ),
                templateSelect("city", dependsOn = listOf("state")),
                templateSelect(
                    "regions",
                    dependsOn = listOf("country", "hide"),
                    cardinality = "MULTI",
                    extra = """, "hidden_expression": { "op": "eq", "left": { "ref": "hide" }, "right": { "literal": "yes" } }""",
                ),
                templateSelect("store", dependsOn = listOf("regions")),
                templateSelect("bucket", dependsOn = listOf("min_order_amount")),
            )
        private val chosen = arrayOf("min_order_amount" to BigDecimal("250.00"), "state" to "NJ", "regions" to listOf("WEST", "SOUTH"))

        @Test
        fun `toggling only the flags leaves every non-default valid selection - INPUT, SELECT and MULTI - and what the children bind`() {
            val shown = evaluate(flagged, "hide" to "no", *chosen)
            val hidden = evaluate(flagged, "hide" to "yes", *chosen)

            listOf("min_order_amount", "regions").map { hidden.state(it).hidden } shouldBe listOf(true, true)
            hidden.state("state").disabled shouldBe true
            listOf("min_order_amount", "state", "regions").map { shown.state(it).hidden || shown.state(it).disabled } shouldBe
                listOf(false, false, false)
            listOf("min_order_amount", "state", "regions").forEach { name ->
                withClue(name) {
                    hidden.state(name).value shouldBe shown.state(name).value
                    hidden.state(name).origin shouldBe ValueOrigin.CLIENT
                }
            }
            hidden.values.filterKeys { it != "hide" } shouldBe shown.values.filterKeys { it != "hide" }
            selectors.requestsFor("acme/sales/bucket.sql").map { it.binds["min_order_amount"].canonical() } shouldBe
                List(2) { BigDecimal("250").canonical() }
            selectors.requestsFor("acme/sales/store.sql").map { it.binds["regions"] } shouldBe
                listOf(listOf("WEST", "SOUTH"), listOf("WEST", "SOUTH"))
            selectors.requestsFor("acme/sales/city.sql").map { it.binds["state"] } shouldBe listOf("NJ", "NJ")
        }

        @Test
        fun `a hidden parameter changed by client code is evaluated as submitted - and its errors are not suppressed`() {
            evaluate(flagged, "hide" to "yes", "min_order_amount" to BigDecimal("300.00")).state("min_order_amount").let {
                (it.hidden to it.value.canonical()) shouldBe (true to BigDecimal("300").canonical())
            }
            selectors
                .requestsFor("acme/sales/bucket.sql")
                .last()
                .binds["min_order_amount"]
                .canonical() shouldBe
                BigDecimal("300").canonical()
            evaluate(flagged, "hide" to "yes", "min_order_amount" to -5).state("min_order_amount").let {
                it.hidden shouldBe true
                it.errors.single().code shouldBe ParameterErrorCodes.EVALUATE_CONSTRAINT_VIOLATION
            }
        }
    }

    @Nested
    inner class FailingSelector {
        private val chain =
            version(
                ParameterSetFixtures.countryJson(),
                templateSelect("state", dependsOn = listOf("country"), required = true),
                templateSelect("city", dependsOn = listOf("state")),
            )

        @Test
        fun `an unreachable datasource - datasource_unreachable, no options, the children bind NULL`() {
            selectors["acme/sales/state.sql"] = { SelectorRun.Unreachable("connection refused") }

            val response = evaluate(chain)

            response.codes("state") shouldContainExactly
                listOf(PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE, ParameterErrorCodes.EVALUATE_REQUIRED_MISSING)
            response.state("state").options shouldBe emptyList()
            selectors.requestsFor("acme/sales/city.sql").single().binds["state"] shouldBe null
            response.state("city").errors shouldBe emptyList()
        }

        @Test
        fun `a statement or resolution failure carries the runner's code - datasource_not_found for a revoked grant`() {
            selectors["acme/sales/state.sql"] =
                { SelectorRun.Failed(DatasourceErrorCodes.NOT_FOUND, "Datasource 'warehouse' is not visible from this workspace.") }

            evaluate(chain).state("state").errors.first().let {
                it.code shouldBe DatasourceErrorCodes.NOT_FOUND
                it.details["datasource"] shouldBe "warehouse"
                it.details["parameter"] shouldBe "state"
            }
        }

        @Test
        fun `a full queue is selectors_saturated inline - decided at once, the response whole`() {
            val pool = SelectorPool(size = 1, waiting = 0)
            val runaway = StubJdbc()
            scope.launch { pool.run(SelectorLabel("x", "y", "z"), runaway.task()) }
            waitUntil { runaway.started() == 1 }
            try {
                val response = evaluate(chain, with = evaluator(pool = pool))

                response
                    .state("state")
                    .errors
                    .first()
                    .code shouldBe ParameterErrorCodes.EVALUATE_SELECTORS_SATURATED
                response.state("country").value shouldBe "USA"
                response.valid shouldBe false
            } finally {
                runaway.releaseAll()
            }
        }
    }

    @Nested
    inner class WhatASelectorSees {
        @Test
        fun `a MULTI parent binds its list and its count, the template sees only the count, and the tiers are the real ones`() {
            val org = OrgContext.of("Dollar", "$", "01-01", "monday", "Pacific/Auckland")
            evaluate(twenty, "regions" to listOf("EAST", "WEST"), with = evaluator(org = org))

            val store = selectors.requestsFor("acme/sales/store.sql").single()
            store.binds["regions"] shouldBe listOf("EAST", "WEST")
            store.binds["regions_count"] shouldBe 2
            store.context["regions_count"] shouldBe 2
            ("regions" in store.context) shouldBe false
            store.binds[ContextKeys.CURRENT_DATE] shouldBe LocalDate.parse("2026-09-28")
            store.binds[ContextKeys.CURRENT_TIMESTAMP] shouldBe CLOCK.instant()
            store.binds[OrgContext.TIMEZONE] shouldBe "Pacific/Auckland"
            (ContextKeys.EXECUTION_ID in store.binds) shouldBe false
            selectors.resolvers.get() shouldBe 1
        }

        @Test
        fun `an unresolved MULTI parent binds an empty list and a zero count`() {
            selectors["acme/sales/regions.sql"] = { ScriptedSelectors.options() }

            evaluate(twenty)

            selectors.requestsFor("acme/sales/store.sql").single().let {
                (it.binds["regions"] to it.binds["regions_count"]) shouldBe (emptyList<String>() to 0)
            }
        }
    }

    @Nested
    inner class TheResponse {
        @Test
        fun `dependents are transitive in display order, controls derived, constants echoed once, values wire-encoded`() {
            val json = EvaluateResponseJson.write(evaluate(twenty))
            val country = json["parameters"][0]
            val city = json["parameters"][2]

            country["dependents"].map { it.asText() } shouldContainExactly listOf("state", "city", "regions", "store", "start_date")
            country.has("source") shouldBe false
            country["state"]["options"].map { it["value"].asText() } shouldContainExactly listOf("USA", "CAN")
            city["presentation"]["control"].asText() shouldBe "dropdown"
            city["state"]["origin"].asText() shouldBe "first"
            json["values"]["start_date"].asText() shouldBe "2026-01-01"
            json["values"]["regions"].map { it.asText() } shouldContainExactly listOf("EAST")
            json["values"]["note"].isNull shouldBe true
            json["valid"].asBoolean() shouldBe true
            json["org"]["currency_symbol"].asText() shouldBe "$"
            json["version"].asInt() shouldBe 4
            json["parameters"][6]["state"]["options"].isNull shouldBe true
        }
    }

    /** A decimal compared by the §7.3 equality (`250.00` = `2.5E+2`); anything else as itself. */
    private fun Any?.canonical(): Any? = (this as? BigDecimal)?.stripTrailingZeros() ?: this

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-27T20:00:00Z"), ZoneOffset.UTC)
        const val WAIT_NANOS = 10_000_000_000L
        const val POLL_MS = 10L
    }
}
