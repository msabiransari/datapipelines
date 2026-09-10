package co.datapipelines.web.ui.site

import co.datapipelines.mcp.McpToolCatalog
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
 * CONSTANT — the one live number is [McpToolCatalog.NAMES]`.size`, a compile-time constant —
 * so no request on this controller touches a database, a principal or a workspace, and the
 * defence is the shared-cache header [PublicPage] sets, not a rate limiter (033/D1).
 *
 * Titles and descriptions are NOT here: they live in [SitePages], because the sitemap and
 * the SEO guards read the same rows. A handler is a lookup plus [PublicPage.render].
 */
@Controller
class SitePagesController {
    @GetMapping("/mcp-server-for-sql-databases")
    fun pillar(
        model: Model,
        response: HttpServletResponse,
    ): String {
        model.addAttribute("toolNames", McpToolCatalog.NAMES)
        // Derived, never transcribed: "N of them read, M can write" on the page is the
        // catalog's own split, so adding a tool cannot leave the sentence wrong.
        model.addAttribute("mutatingCount", McpToolCatalog.MUTATING.size)
        model.addAttribute("readCount", McpToolCatalog.NAMES.size - McpToolCatalog.MUTATING.size)
        return PublicPage.render(model, response, SitePages.PILLAR, toolCount())
    }

    /**
     * The six `/mcp-server/{engine}` pages: ONE template over the [SitePages.ENGINES] rows.
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
        val view = PublicPage.render(model, response, SitePages.enginePage(facts), toolCount())
        return ModelAndView(view, model.asMap())
    }

    @GetMapping("/add-mcp-server-to-claude-code")
    fun addToClaudeCode(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.ADD_TO_CLAUDE_CODE, toolCount())

    @GetMapping("/ai-data-pipeline")
    fun aiDataPipeline(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.AI_DATA_PIPELINE, toolCount())

    @GetMapping("/text-to-sql-agent")
    fun textToSqlAgent(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.TEXT_TO_SQL_AGENT, toolCount())

    @GetMapping("/compare/airflow")
    fun compareAirflow(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.COMPARE_AIRFLOW, toolCount())

    @GetMapping("/compare/dbt")
    fun compareDbt(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.COMPARE_DBT, toolCount())

    @GetMapping("/federated-query")
    fun federatedQuery(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.FEDERATED_QUERY, toolCount())

    @GetMapping("/dp-lake")
    fun dpLake(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.DP_LAKE, toolCount(), SiteFaqs.DP_LAKE)

    /**
     * The tool count, from the compile-time catalog rather than an injected `List<McpTool>`:
     * the tool bean is `@ConditionalOnBean(PipelineExecutor::class)` (033/C4), so an injected
     * list would render "0 tools" on any deployment without the engine.
     */
    private fun toolCount(): Int = McpToolCatalog.NAMES.size

    // ---- Site v2 (2026-09-09): the intent pages. Same shape: GET, anonymous, no DB read.
    @GetMapping("/faq")
    fun faq(
        model: Model,
        response: HttpServletResponse,
    ): String {
        model.addAttribute("faqGroups", SiteFaqs.ALL)
        return PublicPage.render(model, response, SitePages.FAQ, toolCount(), SiteFaqs.ALL.flatMap { it.second })
    }

    @GetMapping("/roadmap")
    fun roadmap(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.ROADMAP, toolCount())

    @GetMapping("/security")
    fun security(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.SECURITY, toolCount(), SiteFaqs.AGENTS_AND_SECURITY)

    @GetMapping("/published-api")
    fun publishedApi(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.PUBLISHED_API, toolCount(), SiteFaqs.APIS_AND_OPERATIONS.take(2))

    @GetMapping("/mcp-tools")
    fun mcpTools(
        model: Model,
        response: HttpServletResponse,
    ): String {
        model.addAttribute("toolGroups", McpToolGroups.groups())
        model.addAttribute("mutatingCount", McpToolCatalog.MUTATING.size)
        return PublicPage.render(model, response, SitePages.MCP_TOOLS, toolCount())
    }

    @GetMapping("/tableau")
    fun tableau(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.TABLEAU, toolCount(), SiteFaqs.TABLEAU)

    @GetMapping("/tableau/governed-dataset")
    fun tableauGovernedDataset(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.TABLEAU_GOVERNED_DATASET, toolCount())
}
