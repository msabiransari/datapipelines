package co.datapipelines.web.ui.site

import co.datapipelines.web.ui.DocsCatalog
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * The two 145 hub pages — `/use-cases` and `/explore` — in the exact shape of
 * [SitePagesController] and [SiteV2Batch2Controller]: GET-only, anonymous, constant content,
 * no datastore and no principal on the request. Their own controller for the same reason
 * batch 2 got one (detekt's TooManyFunctions ceiling on the first controller), and because
 * the pair is a unit: both exist to route a reader from the redesigned front door to the
 * specialist pages the site already had.
 *
 * `/explore` is the only page here with an input beyond the registry: the packaged docs
 * catalog, the same bean `/sitemap.xml` reads — fixed at startup, so the page is as constant
 * as the tool count.
 */
@Controller
class SiteHubController(
    private val docs: DocsCatalog,
) {
    @GetMapping("/use-cases")
    fun useCases(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.USE_CASES, faq = SiteFaqsHub.USE_CASES)

    @GetMapping("/explore")
    fun explore(
        model: Model,
        response: HttpServletResponse,
    ): String {
        model.addAttribute("exploreGroups", SiteExplore.groups(docs))
        return PublicPage.render(model, response, SitePages.EXPLORE)
    }
}
