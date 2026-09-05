package co.datapipelines.web.ui

import co.datapipelines.web.ui.site.PublicPage
import co.datapipelines.web.ui.site.SITE_ORIGIN
import co.datapipelines.web.ui.site.SitePages
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.servlet.ModelAndView
import java.util.concurrent.TimeUnit

/**
 * The in-product spec set (033): `GET /docs` (grouped index) and `GET /docs/{slug}`.
 *
 * **Public since 073.** Both routes are `permitAll`, GET-only and read-only. What they serve
 * is the Markdown packaged into the jar, rendered once at startup by [DocsCatalog] — whose
 * only collaborator is a `ClassLoader`. No principal is read, no workspace is resolved, no
 * datastore is touched on either route, and the same content is already public in the AGPL
 * repository on GitHub. The change is which chrome it renders in, not what it can reach.
 *
 * Anonymous requests get the PUBLIC site chrome (`docs/index-public`, `docs/doc-public`):
 * the marketing header and footer, the SEO head, and no link into the authenticated app.
 * Signed-in requests keep the app chrome exactly as before — the docs are still a nav entry
 * inside the product, and a logged-in reader should not be thrown out to the marketing site.
 * The two views differ only in their layout; the body is the same rendered Markdown.
 *
 * The authentication answer comes from the `authenticated` model attribute that
 * [UiWorkspaceAdvice] fills for every `@Controller` — Spring runs its `@ModelAttribute`
 * methods before this handler, and reading it here keeps ONE definition of "is there a
 * principal" rather than a second `SecurityContextHolder` lookup that could disagree.
 */
@Controller
class DocsController(
    private val docs: DocsCatalog,
) {
    @GetMapping("/docs")
    fun index(
        model: Model,
        response: HttpServletResponse,
    ): String {
        model.addAttribute("groups", docs.index())
        if (isAuthenticated(model)) return "docs/index"
        publicHead(
            model,
            response,
            title = "Documentation — datapipelines.co",
            description = INDEX_DESCRIPTION,
            path = "/docs",
        )
        return "docs/index-public"
    }

    @GetMapping("/docs/{slug}")
    fun doc(
        @PathVariable slug: String,
        model: Model,
        response: HttpServletResponse,
    ): ModelAndView {
        val rendered = docs.render(slug.lowercase()) ?: return ModelAndView("error/404", HttpStatus.NOT_FOUND)
        model.addAttribute("docTitle", rendered.entry.title)
        model.addAttribute("docGroup", rendered.entry.group)
        model.addAttribute(
            "docHtml",
            // 033/B1 — this reaches the page as a model attribute inserted with th:utext,
            // so a "${" inside it is DATA and Thymeleaf never evaluates it (13 of the
            // packaged docs legitimately contain ${VAR} placeholders in YAML/env examples).
            // Do NOT "fix" this into an inline expression ([[...]] / [(...)]): inlining
            // would parse the doc body as a template and evaluate those placeholders.
            rendered.html,
        )
        if (isAuthenticated(model)) return ModelAndView("docs/doc", model.asMap())
        publicHead(
            model,
            response,
            title = docPageTitle(rendered.entry.title),
            description = rendered.entry.description,
            path = "/docs/${rendered.entry.slug}",
        )
        return ModelAndView("docs/doc-public", model.asMap())
    }

    /**
     * The SEO head for an anonymous doc page, plus the short shared-cache window every
     * public route carries. Deliberately NOT [PublicPage.render]: the registry rows are the
     * hand-written cluster pages, and a doc's title and description come from the packaged
     * Markdown itself — but the og:/canonical/cache contract is the same one, from the same
     * constants, so the two surfaces cannot drift apart.
     */
    private fun publicHead(
        model: Model,
        response: HttpServletResponse,
        title: String,
        description: String,
        path: String,
    ) {
        response.setHeader(
            HttpHeaders.CACHE_CONTROL,
            CacheControl.maxAge(PublicPage.PAGE_MAX_AGE_MINUTES, TimeUnit.MINUTES).cachePublic().headerValue,
        )
        model.addAttribute("pageTitle", title)
        model.addAttribute("pageDescription", description)
        model.addAttribute("canonicalUrl", SITE_ORIGIN + path)
        model.addAttribute("ogImage", SITE_ORIGIN + PublicPage.DEFAULT_OG_IMAGE)
        model.addAttribute("extraCss", "/css/docs.css")
        // The public footer builds its engine column from the registry, on every public page.
        model.addAttribute("engines", SitePages.ENGINES)
    }

    private fun isAuthenticated(model: Model): Boolean = model.getAttribute("authenticated") == true

    companion object {
        /** What a search result shows before it truncates the title mid-word. */
        const val TITLE_MAX = 70

        /** The brand suffix every doc page's title carries, so a result set reads as one site. */
        const val TITLE_SUFFIX = " — datapipelines.co docs"

        /**
         * The doc page's `<title>`: its H1 plus the brand suffix — unless that overruns the
         * display limit, in which case the H1's LEAD SEGMENT is used instead.
         *
         * Truncating a title mid-word is a broken-looking search result, and the specs here
         * write their H1s as "Short name — the long explanation" (`key-providers.md` is 63
         * characters before the suffix is added). Cutting at that natural break gives the
         * searcher the document's actual name; a blind `take(70)` gives them half a clause.
         * The word-boundary cut below is the last resort for an H1 with no break at all.
         */
        internal fun docPageTitle(h1: String): String {
            val full = h1 + TITLE_SUFFIX
            if (full.length <= TITLE_MAX) return full
            val lead = h1.split(" — ", ": ").first().trim()
            if (lead.isNotEmpty() && (lead + TITLE_SUFFIX).length <= TITLE_MAX) return lead + TITLE_SUFFIX
            val budget = TITLE_MAX - TITLE_SUFFIX.length
            return lead.take(budget).substringBeforeLast(' ').trimEnd() + TITLE_SUFFIX
        }

        private const val INDEX_DESCRIPTION =
            "The operations manual and contracts for datapipelines.co: deployment, auth, datasources, " +
                "the pipeline contract, templates and the MCP server."
    }
}
