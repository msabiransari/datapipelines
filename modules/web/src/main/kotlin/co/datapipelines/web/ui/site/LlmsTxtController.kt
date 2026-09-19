package co.datapipelines.web.ui.site

import co.datapipelines.web.ui.DocsCatalog
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody
import java.util.concurrent.TimeUnit

/**
 * `GET /llms.txt` and `GET /llms-full.txt` (173 §C.1) — the agent-facing index of the
 * public surface, in the exact shape of [SitemapController]: GET-only, anonymous,
 * read-only, generated from the page registry and the packaged docs catalog (both fixed at
 * startup), so the route touches no database and no principal. The bytes are
 * [LlmsText]'s, shared with the static export.
 *
 * Served as `text/markdown` (charset-qualified, like `/skill.md`): llmstxt.org specifies
 * Markdown, and the full file IS the docs' Markdown. The `.txt` suffix is the convention's,
 * not a content-type claim. Cache window: the short public one every page carries.
 */
@Controller
class LlmsTxtController(
    private val docs: DocsCatalog,
) {
    @GetMapping("/llms.txt", produces = [MARKDOWN])
    @ResponseBody
    fun index(response: HttpServletResponse): String {
        cache(response)
        return LlmsText.index(docs)
    }

    @GetMapping("/llms-full.txt", produces = [MARKDOWN])
    @ResponseBody
    fun full(response: HttpServletResponse): String {
        cache(response)
        return LlmsText.full(docs)
    }

    private fun cache(response: HttpServletResponse) {
        response.setHeader(
            HttpHeaders.CACHE_CONTROL,
            CacheControl.maxAge(PublicPage.PAGE_MAX_AGE_MINUTES, TimeUnit.MINUTES).cachePublic().headerValue,
        )
    }

    companion object {
        /** Markdown, charset-qualified: the summaries carry em dashes. */
        const val MARKDOWN = "text/markdown;charset=UTF-8"
    }
}
