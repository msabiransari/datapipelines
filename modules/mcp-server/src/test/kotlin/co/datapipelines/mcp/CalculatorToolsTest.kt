package co.datapipelines.mcp

import co.datapipelines.calculators.CalculatorRegistry
import co.datapipelines.pipeline.ContextKeys
import co.datapipelines.pipeline.OrgContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
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
        co.datapipelines.auth.ScopeMatrix
            .requiredScopeForTool("calculators_list") shouldBe co.datapipelines.auth.Scope.READ
        co.datapipelines.auth.ScopeMatrix
            .requiredScopeForTool("calculators_get") shouldBe co.datapipelines.auth.Scope.READ
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(): Map<String, Any?> = this as Map<String, Any?>
}
