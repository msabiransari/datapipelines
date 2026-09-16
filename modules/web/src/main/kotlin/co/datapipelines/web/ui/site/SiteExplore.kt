package co.datapipelines.web.ui.site

import co.datapipelines.web.ui.DocsCatalog

/**
 * The `/explore` directory (145 §2) — every public page, GROUPED, generated from the two
 * registries that already decide what is public: [SitePages.ALL] and the packaged docs
 * catalog. Nothing here is typed: a page added to the registry lands in a group by its
 * route, a doc added to the jar lands in the documentation group, and `SiteExploreTest`
 * holds the directory EQUAL to the union of both sources — a page can neither be forgotten
 * nor invented.
 *
 * The grouping is a function of the route, deliberately coarse: the agent/engine cluster
 * (`/mcp-server…`, the client how-to, the tool list), the comparison cluster (`/compare/…`
 * and the Tableau hub), the docs, and everything else — the product story. A new route with
 * no better home is a product page, which is the honest default; the specialist audience
 * pages stay reachable from the hub page (/use-cases) as well as here.
 */
object SiteExplore {
    /** One directory entry: the route and the title the searcher would have seen. */
    data class Link(
        val path: String,
        val title: String,
    )

    /** One group: a stable id (the section anchor), its heading, one line of purpose, and its links. */
    data class Group(
        val id: String,
        val name: String,
        val blurb: String,
        val links: List<Link>,
    )

    /** The directory, in reading order, for the registry as it is now plus the packaged docs. */
    fun groups(docs: DocsCatalog): List<Group> {
        val pages = SitePages.ALL.filter { it.path != SitePages.EXPLORE.path }
        val agent = pages.filter(::isAgentPage)
        val compare = pages.filter(::isComparePage)
        val product = pages.filterNot { isAgentPage(it) || isComparePage(it) }
        val docLinks =
            listOf(Link("/docs", "Documentation")) +
                docs.index().flatMap { group -> group.docs.map { Link("/docs/${it.slug}", it.title) } }
        return listOf(
            Group(
                id = "product",
                name = "Understand the product",
                blurb = "Capabilities, worked examples, trust, pricing and the practical questions before you evaluate it.",
                links = product.map { Link(it.path, it.title) },
            ),
            Group(
                id = "connect",
                name = "Connect your agent and databases",
                blurb = "The SQL MCP server, each supported engine's driver and setup, client configuration and the tool list.",
                links = agent.map { Link(it.path, it.title) },
            ),
            Group(
                id = "compare",
                name = "Choose the right approach",
                blurb = "Honest comparisons with the tools you may already run, and how the product works beside Tableau.",
                links = compare.map { Link(it.path, it.title) },
            ),
            Group(
                id = "docs",
                name = "Read the technical documentation",
                blurb = "Deployment, configuration, security, the pipeline and template contracts, the REST and MCP surfaces.",
                links = docLinks,
            ),
        )
    }

    private fun isAgentPage(page: SitePage): Boolean =
        page.path == SitePages.PILLAR.path ||
            page.path.startsWith(SitePages.ENGINE_PREFIX) ||
            page.path == SitePages.ADD_TO_CLAUDE_CODE.path ||
            page.path == SitePages.MCP_TOOLS.path

    private fun isComparePage(page: SitePage): Boolean = page.path.startsWith("/compare/") || page.path.startsWith("/tableau")
}
