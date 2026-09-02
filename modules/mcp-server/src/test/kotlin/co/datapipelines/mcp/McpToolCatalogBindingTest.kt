package co.datapipelines.mcp

import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * 033/C2 — the production `@Bean` is bound to [McpToolCatalog]: the list the server actually
 * ships (the REAL bean method's output, not a fixture) must equal the catalog's names, in
 * `tools/list` order. Adding a tool to the bean without touching the catalog goes red here;
 * editing the catalog without shipping the tool goes red here too.
 *
 * 052 — the mutating declaration is bound the same way: every catalogued tool carries its
 * flag (structural — [McpToolCatalog.ENTRIES] is the single list both projections derive
 * from), no flag names an unshipped tool, and the KNOWN WRITERS are flagged. A read tool
 * declared mutating is a harmless over-audit; a mutating tool declared read is the hole —
 * the dispatcher's `mcp.tool.write` audit would silently skip it, so this test is what
 * makes that failure visible.
 */
class McpToolCatalogBindingTest {
    /** The tools the owner's ruling (R4) names as writers, intersected with the shipped surface. */
    private val knownWriters =
        setOf(
            "pipelines_create",
            "pipelines_update",
            "pipelines_execute",
            "pipelines_execute_node",
            "templates_create",
        )

    @Test
    fun `the production bean ships exactly the catalog, in tools-list order`() {
        val shipped = realShippedTools()

        assertAll(
            { shipped.map { it.name } shouldContainExactly McpToolCatalog.NAMES },
            { shipped.size shouldBe McpToolCatalog.NAMES.size },
        )
    }

    @Test
    fun `every catalogued tool declares its mutating flag exactly once`() {
        assertAll(
            { McpToolCatalog.ENTRIES.map { it.name } shouldContainExactly McpToolCatalog.NAMES },
            { McpToolCatalog.ENTRIES.size shouldBe McpToolCatalog.NAMES.size },
        )
    }

    @Test
    fun `the known writers are all flagged mutating`() {
        knownWriters.forEach { writer ->
            McpToolCatalog.ENTRIES shouldContain McpToolCatalog.Entry(name = writer, mutating = true)
        }
    }

    @Test
    fun `no mutating flag names a tool the surface does not ship`() {
        McpToolCatalog.MUTATING.forEach { flagged ->
            flagged shouldBeIn McpToolCatalog.NAMES
        }
    }
}
