package co.datapipelines.web.ui

import co.datapipelines.mcp.McpToolCatalog
import co.datapipelines.web.ui.site.PublicPage
import co.datapipelines.web.ui.site.SitePages
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * The public marketing site at `/` (033 — owner decision 2026-08-31: the app serves the
 * site; the signed-in dashboard moved to `/dashboard`, no auto-redirect).
 *
 * GET-only, anonymous, read-only. Everything on this page is CONSTANT content (Decision 4 —
 * no DB-backed facts on public surfaces): the one live number, the MCP tool count, comes
 * from [McpToolCatalog.NAMES], a compile-time constant. Injecting `List<McpTool>` instead
 * would render "0 tools" on any deployment where the conditional tool bean is absent
 * (033/C4).
 *
 * Defence is cache headers, NOT a rate limiter (033/D1, OPEN-ITEMS T46): the login limiter
 * keys on `request.remoteAddr`, which behind the documented load balancer is the LB's
 * address — pointing it at `/` would give one client a DoS on the homepage. The content is
 * immutable between deploys, so a shared-cache TTL costs nothing per request.
 *
 * 073: the `<head>` (title, description, canonical, `og:`) is no longer written into the
 * template — it comes from [SitePages.HOME] through [PublicPage], the same row
 * `/sitemap.xml` and the SEO guards read, so the homepage cannot carry a title the sitemap
 * disagrees with. The `<h1>` is untouched: the poster's line is the pitch, and only the
 * TITLE has to speak the searcher's words.
 */
@Controller
class SiteController {
    @GetMapping("/")
    fun home(
        model: Model,
        response: HttpServletResponse,
    ): String = PublicPage.render(model, response, SitePages.HOME, McpToolCatalog.NAMES.size)
}
