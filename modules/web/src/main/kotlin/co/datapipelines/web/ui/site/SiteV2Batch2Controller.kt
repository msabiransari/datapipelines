package co.datapipelines.web.ui.site

import co.datapipelines.mcp.McpToolCatalog
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * Site v2 batch 2's intent pages (111 §A) — seven more routes in the exact shape of
 * [SitePagesController]: GET-only, anonymous, constant content, no datastore and no principal.
 * Its own controller because detekt's TooManyFunctions floor is part of the guard set, and
 * because the batch is a unit: everything the seven pages need lives beside each other.
 */
@Controller
class SiteV2Batch2Controller {
    // ---- Site v2 batch 2 (111): seven more intent pages. Same shape: GET, anonymous, no DB read.
    @GetMapping("/compare/fivetran-airbyte")
    fun compareFivetranAirbyte(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.COMPARE_FIVETRAN, toolCount(), SiteFaqsBatch2.COMPARE_FIVETRAN)

    @GetMapping("/compare/postgres-only")
    fun comparePostgresOnly(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.COMPARE_POSTGRES_ONLY, toolCount(), SiteFaqsBatch2.COMPARE_POSTGRES_ONLY)

    @GetMapping("/tableau/prep-vs-pipelines-as-code")
    fun tableauPrep(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.TABLEAU_PREP, toolCount(), SiteFaqsBatch2.TABLEAU_PREP)

    @GetMapping("/tableau/extracts-alerts-dashboards")
    fun tableauRoadmap(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.TABLEAU_ROADMAP, toolCount(), SiteFaqsBatch2.TABLEAU_ROADMAP)

    @GetMapping("/for/agencies")
    fun forAgencies(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.FOR_AGENCIES, toolCount(), SiteFaqsBatch2.FOR_AGENCIES)

    @GetMapping("/for/saas-teams")
    fun forSaasTeams(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.FOR_SAAS_TEAMS, toolCount(), SiteFaqsBatch2.FOR_SAAS_TEAMS)

    @GetMapping("/for/analysts")
    fun forAnalysts(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.FOR_ANALYSTS, toolCount(), SiteFaqsBatch2.FOR_ANALYSTS)

    /**
     * The tool count, from the compile-time catalog rather than an injected `List<McpTool>` —
     * the same rule [SitePagesController] follows: the tool bean is conditional on the engine,
     * so an injected list would render "0 tools" without one.
     */
    private fun toolCount(): Int = McpToolCatalog.NAMES.size

    // ---- 119: the pricing page (§C.4) and the learned-semantic-layer page (§B) joined this
    // controller for the same reason the batch-2 pages did: SitePagesController sits at the
    // detekt function ceiling, and these are the same shape — GET, anonymous, constant.
    @GetMapping("/pricing")
    fun pricing(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.PRICING, toolCount(), SiteFaqs.PRICING)

    @GetMapping("/semantic-layer")
    fun semanticLayer(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.SEMANTIC_LAYER, toolCount(), SiteFaqs.SEMANTIC_LAYER)
}
