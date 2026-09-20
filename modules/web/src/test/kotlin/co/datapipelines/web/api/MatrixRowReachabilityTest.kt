package co.datapipelines.web.api

import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.mcp.McpToolCatalog
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import org.junit.jupiter.api.Test

/**
 * Gate 3 of the roles design (§4, 177): **every `RestOperation` is claimed by at least one
 * handler, and every MCP tool the matrix names is a tool the catalog ships** — in both
 * directions. A row nothing implements is a lie in the doc: it reads as an enforced rule and
 * enforces nothing (the first run of this gate found `MUTATE_DATASOURCES`, a super-admin row
 * for instance datasources that no handler had ever declared because the same route serves
 * both halves and the super-admin rule lives in the service).
 *
 * `ScopeMatrixSpecDriftTest` proves the matrix equals the doc; this proves the matrix equals
 * the CODE that declares it. Together: doc ⇔ matrix ⇔ handlers, with no unreachable row.
 *
 * Falsified at birth: add an unused constant to `RestOperation` and the first test names it;
 * add a tool to `MCP_TOOL_MIN_SCOPE` that the catalog lacks and the second does.
 */
class MatrixRowReachabilityTest {
    @Test
    fun `every RestOperation is declared by at least one handler`() {
        val claimed = HandlerInventory.handlers().mapNotNull { it.operation }.toSet()
        val unclaimed = ScopeMatrix.RestOperation.entries.filter { it !in claimed }

        withClue("RestOperation constants no handler declares — a matrix row nothing enforces") {
            unclaimed.shouldBeEmpty()
        }
    }

    @Test
    fun `every MCP tool in the matrix is a catalogued tool, and every catalogued tool is in the matrix`() {
        ScopeMatrix.MCP_TOOL_MIN_SCOPE.keys shouldContainExactlyInAnyOrder McpToolCatalog.NAMES
        ScopeMatrix.MCP_TOOL_MIN_PERMISSION.keys shouldContainExactlyInAnyOrder McpToolCatalog.NAMES
    }

    /** Non-vacuity: a scan that found nothing would claim nothing and the first test would name every row. */
    @Test
    fun `the inventory sees the module's handlers`() {
        val handlers = HandlerInventory.handlers()
        handlers.size shouldBeGreaterThanOrEqual MINIMUM_HANDLERS
        handlers.mapNotNull { it.operation }.toSet().size shouldBeGreaterThanOrEqual MINIMUM_OPERATIONS
    }

    private companion object {
        /** The 2026-09-19 count was 220 mappings over 57 controllers; well under it, well over zero. */
        const val MINIMUM_HANDLERS = 120
        const val MINIMUM_OPERATIONS = 20
    }
}
