package co.datapipelines.web.ui.site

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * 119 §C — the open-source signals, as render guarantees:
 *
 *  1. **The nav label is a fact about the host.** On the public origin the last nav item
 *     invites a visitor to *try the live demo*; on any other host (a customer's own
 *     deployment) the same `/login` route reads *Sign in*. Both branches pinned here by
 *     rendering with each host — the export passes the public host, the app computes it
 *     per request (`SiteOriginAdvice`).
 *  2. **The GitHub badge's count is the build-time constant** — no runtime fetch, no
 *     stale counter; the rendered number equals [GITHUB_STARS] by construction, and this
 *     test fails the day the constant and the template drift apart.
 *  3. **One contact address, three placements, zero impostors.** The footer, /pricing and
 *     /security render [CONTACT_EMAIL] as a plain mailto; no page anywhere carries any
 *     OTHER mailto — a second address on a one-person product is an address nobody answers.
 */
class SiteOpenSourceSignalsTest {
    @Test
    fun `the nav label invites the demo on the public origin and says sign in on any other host`() {
        val deployment = SitePageRenderer.render(SitePages.HOME)
        withClue("on a deployment host (default render) the label must read Sign in") {
            deployment shouldContain "Sign in"
            deployment shouldNotContain "Try the live demo"
        }

        val public = SitePageRenderer.render(SitePages.HOME, SITE_ORIGIN_HOST)
        withClue("on the public origin the label must read Try the live demo") {
            public shouldContain "Try the live demo"
            public shouldNotContain ">Sign in<"
        }
    }

    @Test
    fun `the GitHub badge renders the build-time star count and the licence chip links pricing`() {
        val html = SitePageRenderer.render(SitePages.HOME)
        html shouldContain """<span class="nav-star-count">$GITHUB_STARS</span>"""
        html shouldContain """<a class="nav-chip" href="/pricing">AGPL-3.0</a>"""
    }

    @Test
    fun `every page carries the one contact address and no other mailto`() {
        val rendered =
            SitePages.ALL.associateWith { SitePageRenderer.render(it) } +
                mapOf(SitePage(path = "/docs", title = "", description = "", view = "") to SitePageRenderer.renderDocsIndex())

        rendered.forEach { (page, html) ->
            val mailtos = MAILTO.findAll(html).map { it.groupValues[1] }.toSet()
            withClue("${page.path}: mailto hrefs") {
                mailtos shouldBe setOf("mailto:$CONTACT_EMAIL")
            }
        }
        // The three placements the round names, beyond the footer every page carries.
        rendered.getValue(SitePages.PRICING) shouldContain "will help you connect"
        rendered.getValue(SitePages.SECURITY) shouldContain "Report a vulnerability"
        rendered.getValue(SitePages.HOME) shouldContain "Contact — <span>$CONTACT_EMAIL</span>"
    }

    @Test
    fun `the pricing page states the no-paid-tier promise under the dated markup`() {
        val html = SitePageRenderer.render(SitePages.PRICING)
        html shouldContain "Free. Open source. Yours to run."
        html shouldContain "There is no paid tier today; if a hosted version ever exists it will be announced on the roadmap first."
        html shouldContain """data-roadmap-updated="2026-09-11""""
    }

    private companion object {
        val MAILTO = Regex("""href="(mailto:[^"]*)"""")
    }
}
