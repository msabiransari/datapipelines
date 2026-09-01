package co.datapipelines.mcp

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * 033/C2 — the production `@Bean` is bound to [McpToolCatalog]: the list the server actually
 * ships (the REAL bean method's output, not a fixture) must equal the catalog's names, in
 * `tools/list` order. Adding a tool to the bean without touching the catalog goes red here;
 * editing the catalog without shipping the tool goes red here too.
 */
class McpToolCatalogBindingTest {
    @Test
    fun `the production bean ships exactly the catalog, in tools-list order`() {
        val shipped = realShippedTools()

        assertAll(
            { shipped.map { it.name } shouldContainExactly McpToolCatalog.NAMES },
            { shipped.size shouldBe McpToolCatalog.NAMES.size },
        )
    }
}
