package co.datapipelines.web.ui.site

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.servlet.ModelAndView

/**
 * The public site's intent-cluster pages (073 §B) — one route per thing a searcher actually
 * types, each server-rendered from a static template.
 *
 * **Every handler here is GET-only, anonymous and read-only, by construction.** There is no
 * mutating handler and there never should be: these routes are `permitAll` in
 * `SecurityConfig`, so a POST added here would be an unauthenticated write. The content is
 * CONSTANT — the live numbers come from [SiteFacts], compile-time constants derived from the
 * catalogs themselves — so no request on this controller touches a database, a principal or a
 * workspace, and the defence is the shared-cache header [PublicPage] sets, not a rate limiter
 * (033/D1).
 *
 * Titles and descriptions are NOT here: they live in [SitePages], because the sitemap and
 * the SEO guards read the same rows. A handler is a lookup plus [PublicPage.render].
 */
@Controller
class SitePagesController(
    private val demoData: SiteDemoData,
) {
    @GetMapping("/mcp-server-for-sql-databases")
    fun pillar(
        model: Model,
        response: HttpServletResponse,
    ): String {
        // 160: families instead of all 41 names — the full tool list is /mcp-tools' job, and
        // two pages listing every catalogue name read to a crawler as duplicate content.
        model.addAttribute("toolGroups", McpToolGroups.groups())
        return PublicPage.render(model, response, SitePages.PILLAR, faq = SiteFaqsCluster.PILLAR)
    }

    /**
     * The `/mcp-server/{engine}` pages: ONE template over the [SitePages.ENGINES] rows.
     * An unknown engine is an ordinary miss (404), never an error page — same rule the docs
     * viewer follows for an unknown slug.
     */
    @GetMapping("/mcp-server/{engine}")
    fun engine(
        @PathVariable engine: String,
        model: Model,
        response: HttpServletResponse,
    ): ModelAndView {
        val facts = SitePages.engine(engine.lowercase()) ?: return ModelAndView("error/404", HttpStatus.NOT_FOUND)
        model.addAttribute("engine", facts)
        val view = PublicPage.render(model, response, SitePages.enginePage(facts), faq = SiteFaqsCluster.ENGINES)
        return ModelAndView(view, model.asMap())
    }

    @GetMapping("/add-mcp-server-to-claude-code")
    fun addToClaudeCode(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.ADD_TO_CLAUDE_CODE, faq = SiteFaqsCluster.ADD_TO_CLIENT)

    @GetMapping("/ai-data-pipeline")
    fun aiDataPipeline(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.AI_DATA_PIPELINE, faq = SiteFaqsCluster.AI_DATA_PIPELINE)

    @GetMapping("/text-to-sql-agent")
    fun textToSqlAgent(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.TEXT_TO_SQL_AGENT, faq = SiteFaqsCluster.TEXT_TO_SQL)

    @GetMapping("/compare/airflow")
    fun compareAirflow(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.COMPARE_AIRFLOW, faq = SiteFaqsCluster.COMPARE_AIRFLOW)

    @GetMapping("/compare/dbt")
    fun compareDbt(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.COMPARE_DBT, faq = SiteFaqsCluster.COMPARE_DBT)

    @GetMapping("/federated-query")
    fun federatedQuery(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.FEDERATED_QUERY, faq = SiteFaqsCluster.FEDERATED_QUERY)

    @GetMapping("/dp-lake")
    fun dpLake(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.DP_LAKE, faq = SiteFaqs.DP_LAKE)

    // ---- Site v2 (2026-09-09): the intent pages. Same shape: GET, anonymous, no DB read.
    @GetMapping("/faq")
    fun faq(
        model: Model,
        response: HttpServletResponse,
    ): String {
        model.addAttribute("faqGroups", SiteFaqs.ALL)
        return PublicPage.render(model, response, SitePages.FAQ, faq = SiteFaqs.ALL.flatMap { it.second })
    }

    @GetMapping("/roadmap")
    fun roadmap(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.ROADMAP)

    @GetMapping("/security")
    fun security(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.SECURITY, faq = SiteFaqs.AGENTS_AND_SECURITY)

    @GetMapping("/published-api")
    fun publishedApi(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.PUBLISHED_API, faq = SiteFaqs.APIS_AND_OPERATIONS.take(2))

    @GetMapping("/mcp-tools")
    fun mcpTools(
        model: Model,
        response: HttpServletResponse,
    ): String {
        model.addAttribute("toolGroups", McpToolGroups.groups())
        return PublicPage.render(model, response, SitePages.MCP_TOOLS)
    }

    @GetMapping("/tableau")
    fun tableau(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.TABLEAU, faq = SiteFaqs.TABLEAU)

    @GetMapping("/tableau/governed-dataset")
    fun tableauGovernedDataset(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.TABLEAU_GOVERNED_DATASET)

    /**
     * 115 §A.3 — the engineering page the buyer-facing home page handed its vocabulary to.
     * The moved sections render the engine strip from the registry (the `engines` model
     * attribute [PublicPage] fills) and the agent loop from the compile-time tool count, so
     * nothing here is page-specific beyond the row and its FAQ. 145 opens the page with an
     * accessible walkthrough and three practical questions ([SiteFaqsHub.HOW_IT_WORKS]);
     * the engineering depth follows, unchanged.
     */
    @GetMapping("/how-it-works")
    fun howItWorks(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.HOW_IT_WORKS, faq = SiteFaqsHub.HOW_IT_WORKS)

    /**
     * 116 — the demo-data page. Its one live input is [SiteDemoData], parsed from the
     * vendored manifests at startup and constant from then on — the same "constant content"
     * shape as every handler above: GET-only, anonymous, no datastore, no principal.
     */
    @GetMapping("/demo-data")
    fun demoData(
        model: Model,
        response: HttpServletResponse,
    ): String {
        model.addAttribute("demoFamilies", demoData.families)
        model.addAttribute("demoNyc", demoData.family("nyc"))
        model.addAttribute("demoTrade", demoData.family("trade"))
        model.addAttribute("demoLake", demoData.family("lake"))
        return PublicPage.render(
            model,
            response,
            SitePages.DEMO_DATA,
            facts = SiteFacts.current(demoData.families.associate { it.key to it.engineCount }),
            faq = SiteFaqs.DEMO_DATA,
        )
    }
}
