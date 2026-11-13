package co.datapipelines.web.ui.site

import co.datapipelines.web.ui.ShellPage
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * The three #699 capability pages — scheduling, dashboards and parameter sets — in the exact
 * shape of [SiteHubController]: GET-only, anonymous, constant content, no datastore and no
 * principal on the request. Each route is an exact entry in the auth module's PublicPaths
 * allowlist with its docs/auth.md §8.3 row; the application's own `/schedules`, `/dashboards`
 * and `/parameter-sets` pages are different routes and stay behind a session.
 */
@Controller
class SiteCapabilitiesController : ShellPage {
    @GetMapping("/pipeline-scheduling")
    fun pipelineScheduling(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.PIPELINE_SCHEDULING, faq = SiteCapabilitiesFaqs.SCHEDULING)

    @GetMapping("/sql-dashboards")
    fun sqlDashboards(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.SQL_DASHBOARDS, faq = SiteCapabilitiesFaqs.DASHBOARDS)

    @GetMapping("/reusable-parameter-sets")
    fun reusableParameterSets(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.REUSABLE_PARAMETER_SETS, faq = SiteCapabilitiesFaqs.PARAMETER_SETS)
}
