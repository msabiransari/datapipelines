package co.datapipelines.mcp

import co.datapipelines.mcp.docs.DocContext
import co.datapipelines.mcp.docs.DocErrorCatalog
import co.datapipelines.mcp.docs.DocRenderer
import co.datapipelines.mcp.docs.DocSet

/**
 * The rendered set for the guards, outside Spring. The tool instances are the REAL bean
 * method's output ([realShippedTools]) and the context is the shipped default — the same
 * construction `docsExport` runs; only the [DocErrorCatalog] is a fixture, because the real
 * implementation lives in `web`. The fixture's rows are shapes: the structure and budget
 * guards hold the SET to the catalogs, and the §13 statuses and messages the real catalog
 * supplies are `web`'s own tested projection, not this module's claim.
 */
object DocSetTestSupport {
    /** Answers every catalogued code with a row whose data is shape, not content. */
    class FixtureDocErrorCatalog : DocErrorCatalog {
        override fun describe(code: String): DocErrorCatalog.ErrorDocRow =
            DocErrorCatalog.ErrorDocRow(
                status = 400,
                // The code's own family, so the fixture groups the reference the way the real
                // §13 anchors do — one section per family, never one 30k-section document.
                familyAnchor = code.split(".").take(2).joinToString("-"),
                userMessage = "The server refused the call ($code).",
            )
    }

    /** The rendered set under the shipped defaults and the fixture catalog. */
    fun renderedDocSet(): DocSet = DocRenderer(DocContext.DEFAULTS, FixtureDocErrorCatalog(), realShippedTools()).render()

    /**
     * A two-document set for wiring tests that only need A served manual to exist — the docs
     * surfaces route through it and the suites assert routing, not content.
     */
    fun minimalDocSet(): DocSet {
        val core =
            co.datapipelines.mcp.docs.Doc(
                name = DocSet.CORE_NAME,
                area = co.datapipelines.mcp.docs.DocArea.CORE,
                layer = co.datapipelines.mcp.docs.DocLayer.CORE,
                title = "datapipelines",
                purpose = "The operating core.",
                markdown = "# datapipelines\n\n## What this product is\n\nA server.\n",
                sections = emptyList(),
            )
        val reference =
            core.copy(
                name = "core-tools",
                layer = co.datapipelines.mcp.docs.DocLayer.REFERENCE,
                title = "Tools — core",
                purpose = "The manual's own tools.",
                markdown = "# Tools — core\n",
            )
        return DocSet(listOf(core, reference))
    }
}
