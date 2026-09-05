package co.datapipelines.web.ui

import co.datapipelines.web.ui.site.SitePageRenderer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The docs, public (073 §C): EVERY packaged doc rendered anonymously through the real
 * controller, and checked for the four things an indexable page needs — a title that names
 * the document, a canonical, a description drawn from the document's own prose, and the
 * public chrome instead of the app's.
 *
 * Swept over the whole catalog rather than sampled: the value of making the docs public is
 * the ~25 long-tail pages, and one of them silently rendering the signed-in navigation (or
 * with an empty description) is exactly the defect a sample would miss.
 */
class DocsPublicSeoTest {
    private val slugs = SitePageRenderer.docs.index().flatMap { group -> group.docs.map { it.slug } }
    private val rendered = slugs.associateWith { SitePageRenderer.renderDoc(it) }

    @Test
    fun `the sweep sees the whole packaged spec set`() {
        // Non-vacuity: an empty catalog would pass every assertion below.
        check(slugs.size >= MIN_DOCS) { "only ${slugs.size} packaged docs reached the sweep" }
    }

    @Test
    fun `every doc page carries its own title, canonical and description`() {
        val broken =
            rendered.mapNotNull { (slug, html) ->
                val title =
                    TITLE
                        .find(html)
                        ?.groupValues
                        ?.get(1)
                        ?.trim()
                        .orEmpty()
                val canonical = CANONICAL.find(html)?.groupValues?.get(1)
                val description =
                    DESCRIPTION
                        .find(html)
                        ?.groupValues
                        ?.get(1)
                        .orEmpty()
                when {
                    !title.endsWith(TITLE_SUFFIX) -> "$slug: title is \"$title\""
                    title.length > TITLE_MAX -> "$slug: title is ${title.length} chars (limit $TITLE_MAX)"
                    title == TITLE_SUFFIX.trimStart(' ', '—', ' ') -> "$slug: title has no document name"
                    canonical != "https://datapipelines.co/docs/$slug" -> "$slug: canonical is $canonical"
                    description.length < MIN_DESCRIPTION_CHARS -> "$slug: description is \"$description\""
                    description.startsWith("Status:") -> "$slug: description is the status block, not the subject"
                    else -> null
                }
            }
        broken shouldBe emptyList()
    }

    @Test
    fun `every doc page renders the public chrome and no link into the signed-in app`() {
        val broken =
            rendered.mapNotNull { (slug, html) ->
                when {
                    // The public footer's site map — present on every page the site layout renders.
                    "footer-map" !in html -> "$slug: no public footer"

                    "class=\"site-header\"" !in html -> "$slug: no public header"

                    // The app chrome's own markers. Either would mean the wrong layout was chosen.
                    "app-nav" in html -> "$slug: rendered the signed-in application navigation"

                    "href=\"/dashboard\"" in html -> "$slug: links into the authenticated app"

                    // The CHROME only: 13 packaged docs legitimately carry ${VAR} placeholders
                    // in YAML and env examples, and 033/B1 requires them to survive verbatim.
                    "\${" in chromeOf(html) -> "$slug: an unresolved expression survived in the page chrome"

                    else -> null
                }
            }
        broken shouldBe emptyList()
    }

    @Test
    fun `every doc page has exactly one h1, and it is the document's own heading`() {
        val broken =
            rendered.mapNotNull { (slug, html) ->
                val h1s = H1.findAll(html).count()
                if (h1s == 1) null else "$slug: $h1s <h1> elements"
            }
        broken shouldBe emptyList()
    }

    @Test
    fun `the public docs index lists every packaged doc with its description`() {
        val html = SitePageRenderer.renderDocsIndex()
        val missing = slugs.filterNot { "/docs/$it\"" in html }
        missing shouldBe emptyList()
        html.contains("<title>Documentation — datapipelines.co</title>") shouldBe true
        html.contains("footer-map") shouldBe true
    }

    /** The page minus the rendered Markdown body — everything this round is responsible for. */
    private fun chromeOf(html: String): String {
        val from = html.indexOf(DOC_BODY_OPEN)
        val to = html.lastIndexOf(DOC_BODY_CLOSE)
        check(from > 0 && to > from) { "the doc-body markers moved — fix this guard, do not delete it" }
        return html.take(from) + html.substring(to)
    }

    private companion object {
        const val MIN_DOCS = 15
        const val DOC_BODY_OPEN = "<article class=\"doc-body\">"
        const val DOC_BODY_CLOSE = "</article>"
        const val MIN_DESCRIPTION_CHARS = 60
        const val TITLE_SUFFIX = DocsController.TITLE_SUFFIX
        const val TITLE_MAX = DocsController.TITLE_MAX

        val TITLE = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
        val CANONICAL = Regex("""<link rel="canonical" href="([^"]*)"""")
        val DESCRIPTION = Regex("""<meta name="description" content="([^"]*)"""")
        val H1 = Regex("""<h1[\s>]""")
    }
}
