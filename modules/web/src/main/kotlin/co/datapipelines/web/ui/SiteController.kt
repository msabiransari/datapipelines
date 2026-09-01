package co.datapipelines.web.ui

import co.datapipelines.mcp.McpToolCatalog
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import java.util.concurrent.TimeUnit

/**
 * The public marketing site at `/` (033 — owner decision 2026-08-31: the app serves the
 * site; the signed-in dashboard moved to `/dashboard`, no auto-redirect).
 *
 * Everything on this page is CONSTANT content (Decision 4 — no DB-backed facts on public
 * surfaces): the one live number, the MCP tool count, comes from [McpToolCatalog.NAMES], a
 * compile-time constant. Injecting `List<McpTool>` instead would render "0 tools" on any
 * deployment where the conditional tool bean is absent (033/C4).
 *
 * Defence is cache headers, NOT a rate limiter (033/D1, OPEN-ITEMS T46): the login limiter
 * keys on `request.remoteAddr`, which behind the documented load balancer is the LB's
 * address — pointing it at `/` would give one client a DoS on the homepage. The content is
 * immutable between deploys, so a shared-cache TTL costs nothing per request.
 */
@Controller
class SiteController {
    @GetMapping("/")
    fun home(
        model: Model,
        response: HttpServletResponse,
    ): String {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.maxAge(PAGE_MAX_AGE_MINUTES, TimeUnit.MINUTES).cachePublic().headerValue)
        model.addAttribute("toolCount", McpToolCatalog.NAMES.size)
        return "site/index"
    }

    private companion object {
        /** Short: the page is fingerprint-free, so staleness after a deploy must bound in minutes. */
        const val PAGE_MAX_AGE_MINUTES = 5L
    }
}
