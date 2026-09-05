package co.datapipelines.executor

import co.datapipelines.pipeline.ContextKeys
import co.datapipelines.pipeline.OrgContext
import co.datapipelines.pipeline.Parameter
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The Context's tier precedence (calculators design §0.2, pipeline-contract §7.2), one test per
 * override direction.
 *
 * These are the tests that could fail for the reason we care about: the tiers are applied by
 * *map-merge order* in [RunContext.create], which is the kind of correctness that reads fine and
 * is wrong by one line. Every case here is a real authoring shape — a pipeline pinning its own
 * fiscal year, a caller overriding it per run — not a synthetic key collision.
 */
class RunContextTierTest {
    private val org =
        OrgContext.of(
            currencyName = "Pound",
            currencySymbol = "£",
            fiscalStartDate = "04-06",
            weekStart = "sunday",
            timezone = "Europe/London",
        )
    private val executionId = UUID.randomUUID()

    /** 2026-01-01T00:30Z is 2026-01-01 in London but 2025-12-31 in New York — the tz check. */
    private val startedAt: Instant = Instant.parse("2026-01-01T00:30:00Z")

    @Test
    fun `tier 1 - org config is present with no pipeline declaring anything`() {
        val context = create()

        context[OrgContext.CURRENCY_NAME] shouldBe "Pound"
        context[OrgContext.CURRENCY_SYMBOL] shouldBe "£"
        context[OrgContext.FISCAL_START_DATE] shouldBe "04-06"
        context[OrgContext.WEEK_START] shouldBe "sunday"
        context[OrgContext.TIMEZONE] shouldBe "Europe/London"
    }

    @Test
    fun `tier 2 - platform keys are present, and current_date is evaluated in org_timezone`() {
        val context = create()

        context[ContextKeys.CURRENT_TIMESTAMP] shouldBe startedAt
        context[ContextKeys.EXECUTION_ID] shouldBe executionId.toString()
        withClue("current_date must be the date in org_timezone, not the JVM's or UTC's") {
            context[ContextKeys.CURRENT_DATE] shouldBe LocalDate.of(2026, 1, 1)
        }

        // The same instant, one timezone west: a different calendar day. If `current_date` were
        // taken from the host clock's zone this assertion could not distinguish the two.
        val newYork = create(org = orgIn("America/New_York"))
        newYork[ContextKeys.CURRENT_DATE] shouldBe LocalDate.of(2025, 12, 31)
    }

    @Test
    fun `tier 3 beats tier 1 - a declared parameter overrides the org value`() {
        val context =
            create(
                parameters = mapOf(OrgContext.FISCAL_START_DATE to Parameter(LogicalType.STRING, default = text("09-15"))),
            )

        context[OrgContext.FISCAL_START_DATE] shouldBe "09-15"
        // Only the declared key is overridden; the rest of the org tier is untouched.
        context[OrgContext.TIMEZONE] shouldBe "Europe/London"
    }

    @Test
    fun `tier 3 beats tier 2 - a declared parameter overrides a platform key`() {
        val context =
            create(
                parameters = mapOf(ContextKeys.CURRENT_DATE to Parameter(LogicalType.DATE, default = text("2020-05-04"))),
            )

        context[ContextKeys.CURRENT_DATE] shouldBe LocalDate.of(2020, 5, 4)
        context[ContextKeys.EXECUTION_ID] shouldBe executionId.toString()
    }

    @Test
    fun `tier 4 beats tier 3 - an execute-time input overrides the declared default`() {
        val context =
            create(
                parameters = mapOf(OrgContext.TIMEZONE to Parameter(LogicalType.STRING, default = text("Europe/Paris"))),
                inputs = mapOf(OrgContext.TIMEZONE to text("Asia/Tokyo")),
            )

        context[OrgContext.TIMEZONE] shouldBe "Asia/Tokyo"
    }

    @Test
    fun `tier 4 beats tier 1 directly - an input for a parameter that shadows an org key`() {
        val context =
            create(
                parameters = mapOf(OrgContext.CURRENCY_SYMBOL to Parameter(LogicalType.STRING)),
                inputs = mapOf(OrgContext.CURRENCY_SYMBOL to text("€")),
            )

        context[OrgContext.CURRENCY_SYMBOL] shouldBe "€"
    }

    @Test
    fun `tier 5 - a calculator write is visible to every later reader of the same Context`() {
        val context = create()

        context.containsKey("run_fiscal_quarter") shouldBe false
        context.put("run_fiscal_quarter", 3)

        context["run_fiscal_quarter"] shouldBe 3
        context.snapshot()["run_fiscal_quarter"] shouldBe 3
        // A calculator MAY shadow an org or platform key (§0.2 tier 5); it may never shadow a
        // declared parameter, and that refusal is save-time (`calculator_output_collision`).
        context.put(OrgContext.CURRENCY_SYMBOL, "€")
        context[OrgContext.CURRENCY_SYMBOL] shouldBe "€"
    }

    @Test
    fun `an optional unsupplied parameter is present and null - even over an org key`() {
        // pipeline-contract §7.4: an optional parameter with no value is present-and-null so a
        // template referencing it is defined-but-null. Declaring it IS the override, so the org
        // value does NOT leak back in — the pipeline said this key is its own.
        val context = create(parameters = mapOf(OrgContext.WEEK_START to Parameter(LogicalType.STRING)))

        context.containsKey(OrgContext.WEEK_START) shouldBe true
        context[OrgContext.WEEK_START] shouldBe null
    }

    private fun create(
        org: OrgContext = this.org,
        parameters: Map<String, Parameter> = emptyMap(),
        inputs: Map<String, JsonNode> = emptyMap(),
    ): RunContext =
        RunContext.create(
            org = org,
            pipeline = Fixtures.pipeline(listOf(Fixtures.node("a")), parameters = parameters),
            inputs = inputs,
            executionId = executionId,
            startedAt = startedAt,
        )

    private fun orgIn(zone: String): OrgContext = OrgContext.of("Pound", "£", "04-06", "sunday", ZoneId.of(zone).id)

    private fun text(value: String): JsonNode = JsonNodeFactory.instance.textNode(value)
}
