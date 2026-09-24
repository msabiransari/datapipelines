package co.datapipelines.mcp

import co.datapipelines.calculators.CalculatorExample
import co.datapipelines.calculators.CalculatorInput
import co.datapipelines.calculators.CalculatorKind
import co.datapipelines.calculators.CalculatorOutput
import co.datapipelines.calculators.CalculatorRegistry
import co.datapipelines.pipeline.ContextKeys
import co.datapipelines.pipeline.OrgContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * `calculators_list` / `calculators_get` (mcp-server.md §6.2.23–24).
 *
 * The assertions are about the AGENT's problem, not the registry's: can a model that has only
 * called these two tools author a `CALCULATOR` node that validates? That needs the kind name, the
 * exact input names, which are required, which take arrays, and the Context keys it may reference
 * without declaring anything. Each of those is asserted below, on a real kind.
 */
class CalculatorToolsTest {
    private val list = CalculatorsListTool()
    private val get = CalculatorsGetTool()
    private val ctx = McpFixtures.ctx()

    @Test
    fun `list returns every shipped kind, in catalog order, with the count`() {
        val payload = list.call(McpArguments(emptyMap()), ctx).asMap()

        @Suppress("UNCHECKED_CAST")
        val kinds = payload["kinds"] as List<Map<String, Any?>>
        kinds.map { it["kind"] } shouldContainExactly CalculatorRegistry.NAMES
        payload["count"] shouldBe CalculatorRegistry.KINDS.size
        payload["docs"] shouldBe "docs/calculators.md"
        // 120/R2: every listed kind carries its phrases — the field the question's words are
        // matched against — identical to what calculators_get returns for the same kind.
        kinds.forEach { entry ->
            entry["phrases"] shouldBe CalculatorRegistry.require(entry["kind"] as String).phrases
        }
    }

    @Test
    fun `list names the Context keys a body may reference without declaring anything`() {
        // Without this an agent has no way to know `$org_fiscal_start_date` is legal, and would
        // either declare a parameter it does not need or guess a key that does not resolve.
        val keys = list.call(McpArguments(emptyMap()), ctx).asMap()["context_keys"].asMap()

        keys["org"] shouldBe OrgContext.KEYS

        @Suppress("UNCHECKED_CAST")
        val platform = keys["platform"] as List<Map<String, Any?>>
        platform.map { it["name"] } shouldContainExactly ContextKeys.PLATFORM
        platform.first { it["name"] == ContextKeys.CURRENT_DATE }["type"] shouldBe "DATE"
    }

    @Test
    fun `a kind's entry carries everything needed to author the node`() {
        val payload = get.call(McpArguments(mapOf("kind" to "fiscal_quarter")), ctx).asMap()

        payload["kind"] shouldBe "fiscal_quarter"
        payload["display_name"] shouldBe "Fiscal quarter"
        payload["output"] shouldBe "INTEGER"
        // 120/R2: the phrases the kind answers — the lookup path the skill's calculator rule
        // points at. Asserted from the registry, never a transcribed copy.
        payload["phrases"] shouldBe CalculatorRegistry.require("fiscal_quarter").phrases

        @Suppress("UNCHECKED_CAST")
        val inputs = payload["inputs"] as List<Map<String, Any?>>
        inputs.map { it["name"] } shouldContainExactly listOf("date", "fiscal_start")
        inputs.forEach {
            it["required"] shouldBe true
            it.containsKey("type") shouldBe true
            it.containsKey("description") shouldBe true
        }

        val example = payload["example"].asMap()
        example["output"] shouldBe "4"
    }

    @Test
    fun `an optional input carries its default and a list input says so - and neither is emitted otherwise`() {
        @Suppress("UNCHECKED_CAST")
        val inputs = get.call(McpArguments(mapOf("kind" to "add_business_days")), ctx).asMap()["inputs"] as List<Map<String, Any?>>

        val date = inputs.first { it["name"] == "date" }
        date["required"] shouldBe true
        // Absence carries the same information as `false`/`null` and costs an agent nothing to
        // read — eighty of those across the catalog is real context-window spend.
        date.containsKey("list") shouldBe false
        date.containsKey("default") shouldBe false

        val holidays = inputs.first { it["name"] == "holidays" }
        holidays["required"] shouldBe false
        holidays["list"] shouldBe true
        holidays["default"] shouldBe "[]"
        holidays["type"] shouldBe "DATE"
    }

    @Test
    fun `an ANY-typed input reads as ANY rather than as a canonical type it does not have`() {
        @Suppress("UNCHECKED_CAST")
        val inputs = get.call(McpArguments(mapOf("kind" to "coalesce")), ctx).asMap()["inputs"] as List<Map<String, Any?>>

        inputs.single()["type"] shouldBe "ANY"
        inputs.single()["list"] shouldBe true
        get.call(McpArguments(mapOf("kind" to "coalesce")), ctx).asMap()["output"] shouldBe "ANY"
    }

    @Test
    fun `an unknown kind is the SAME code a rejected pipelines_create returns, with the catalog attached`() {
        val failure =
            shouldThrow<DatapipelinesException> { get.call(McpArguments(mapOf("kind" to "fiscal_fortnight")), ctx) }

        failure.code shouldBe PipelineErrorCodes.Validation.CALCULATOR_UNKNOWN
        failure.details["known_kinds"] shouldBe CalculatorRegistry.NAMES
    }

    @Test
    fun `both tools are read-only in the catalog and require only the read scope`() {
        // The catalog's `mutating` flag is what the dispatcher's `mcp.tool.write` audit keys on,
        // and a read tool wrongly declared mutating is only noise — the reverse is the hole.
        McpToolCatalog.isMutating("calculators_list") shouldBe false
        McpToolCatalog.isMutating("calculators_get") shouldBe false
        McpToolCatalog.permissionOf("calculators_list") shouldBe co.datapipelines.auth.Permission.CALCULATOR_READ
        McpToolCatalog.permissionOf("calculators_get") shouldBe co.datapipelines.auth.Permission.CALCULATOR_READ
    }

    // ---- 121/D6: the output half of the wire shape ----

    @Test
    fun `a single-output kind carries output and NO outputs key - the wire it has always had`() {
        val fromGet = get.call(McpArguments(mapOf("kind" to "fiscal_quarter")), ctx).asMap()
        fromGet["output"] shouldBe "INTEGER"
        fromGet.containsKey("outputs") shouldBe false

        @Suppress("UNCHECKED_CAST")
        val kinds = list.call(McpArguments(emptyMap()), ctx).asMap()["kinds"] as List<Map<String, Any?>>
        // Registry-driven, so the day a kind ships multi it cannot slip into the wrong shape:
        // single kinds keep the wire they have always had, multi kinds say so — and nothing else.
        CalculatorRegistry.KINDS.forEach { kind ->
            val entry = kinds.first { it["kind"] == kind.kind }
            if (kind.outputs.isEmpty()) {
                entry.containsKey("outputs") shouldBe false
                (entry["output"] != null) shouldBe true
            } else {
                entry["output"] shouldBe null
                @Suppress("UNCHECKED_CAST")
                (entry["outputs"] as List<Map<String, Any?>>).map { it["name"] } shouldBe kind.outputs.map { it.name }
            }
        }
    }

    @Test
    fun `a multi-output kind carries output null and the named outputs set - one projection for both tools`() {
        // No multi-output kind is registered yet (the two period kinds land in 121 commit C), so
        // the multi arm is driven through a fixture kind at the PROJECTION — the one function
        // both tools return, which is what keeps the two wire shapes from drifting apart.
        val payload = CalculatorPayload.of(MULTI_FIXTURE)

        payload["output"] shouldBe null

        @Suppress("UNCHECKED_CAST")
        val outputs = payload["outputs"] as List<Map<String, Any?>>
        outputs shouldBe
            listOf(
                mapOf("name" to "start", "type" to "DATE", "description" to "The first day."),
                mapOf("name" to "end", "type" to "DATE", "description" to "The last day."),
            )

        // The single ANY shape is untouched — "ANY", never null — so the two nulls stay distinct.
        CalculatorPayload.of(SINGLE_ANY_FIXTURE)["output"] shouldBe "ANY"
        CalculatorPayload.of(SINGLE_ANY_FIXTURE).containsKey("outputs") shouldBe false

        // 121 commit C made the shape real: `trailing_periods` is in the registry, so the TOOL
        // — not just the projection — serves its outputs set.
        val real = get.call(McpArguments(mapOf("kind" to "trailing_periods")), ctx).asMap()
        real["output"] shouldBe null
        @Suppress("UNCHECKED_CAST")
        (real["outputs"] as List<Map<String, Any?>>).map { it["name"] } shouldBe listOf("start", "end")
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(): Map<String, Any?> = this as Map<String, Any?>

    private companion object {
        /** The throwaway multi-output fixture kind (121) — the D6 wire shape's test double. */
        val MULTI_FIXTURE: CalculatorKind =
            object : CalculatorKind {
                override val kind = "period_window"
                override val displayName = "Period window"
                override val description = "The first and last day of a window."
                override val phrases = listOf("this window")
                override val inputs = listOf(CalculatorInput("date", LogicalType.DATE, "The date."))
                override val output: LogicalType? = null
                override val outputs =
                    listOf(
                        CalculatorOutput("start", LogicalType.DATE, "The first day."),
                        CalculatorOutput("end", LogicalType.DATE, "The last day."),
                    )
                override val example = CalculatorExample(mapOf("date" to "2026-08-14"), "start=2026-08-01, end=2026-08-31")

                override fun evaluate(values: Map<String, Any?>): Any = emptyMap<String, Any>()
            }

        /** An ANY-output single kind — the shape a null `output` must never be confused with. */
        val SINGLE_ANY_FIXTURE: CalculatorKind = CalculatorRegistry.require("if_null")
    }
}
