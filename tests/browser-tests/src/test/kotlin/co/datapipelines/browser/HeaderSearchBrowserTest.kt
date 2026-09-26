package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories

/**
 * 161 — the header search, end to end in a real browser (#155): ⌘K / Ctrl+K opens the
 * palette, a typed pipeline name fetches /partials/search over htmx, ↑ selects the hit, Enter
 * follows its link through the rail's boost, and the editor the row named is on screen — the
 * palette closed behind it. The screenshot is the review artifact.
 *
 * Everything this arm touches was a `<div aria-hidden>` before 161: every locator below is
 * also the falsification — on the placeholder markup the run is red at the first waitFor.
 */
class HeaderSearchBrowserTest : BrowserSuite() {
    @Test
    fun `ctrl-k opens the palette, typing finds the pipeline, and enter lands on its editor`() {
        startTrace()
        signedIn("srch")
        // The demo pipeline, in this session's own workspace — seeded through the page's
        // own session (the 106 fixture pattern).
        val fixture = "srch" + generatedPassword("f").take(8).lowercase()
        postJson(
            "/api/v1/pipelines",
            """{"name":"test/$fixture","display_name":"$fixture","description":"header search fixture",""" +
                """"nodes":[{"id":"fq","type":"CALCULATOR","kind":"fiscal_quarter","context_key":"run_fiscal_quarter",""" +
                """"inputs":{"date":"${'$'}current_date","fiscal_start":"${'$'}org_fiscal_start_date"}}]}""",
        )

        page.navigate("$baseUrl/dashboard")
        val input = page.locator("#app-search-input")
        input.waitFor()

        // The chord the placeholder promised since 079. preventDefault in shell.js keeps
        // the browser's own Ctrl+K out of the way; the palette opens, aria-expanded says so.
        page.keyboard().press("Control+k")
        page.waitForFunction("() => document.getElementById('app-search-input').getAttribute('aria-expanded') === 'true'")

        input.fill(fixture)
        val firstOption = page.locator("#app-search-results [role='option']").first()
        firstOption.waitFor()
        firstOption.getAttribute("href") shouldContain "/editor"

        // ↑ moves the ACTIVE row (aria-selected on the first hit); the screenshot pins the
        // open palette with the selection on it.
        page.keyboard().press("ArrowDown")
        page.waitForFunction(
            "() => document.querySelector('#app-search-results [role=option][aria-selected=true]') !== null",
        )
        val shots = Paths.get("build", "reports", "screenshots")
        shots.createDirectories()
        page.screenshot(Page.ScreenshotOptions().setPath(shots.resolve("header-search-palette.png")))

        // Enter clicks the row's anchor — the app's own boosted navigation — and the
        // boosted settle closes the palette behind it.
        page.keyboard().press("Enter")
        page.waitForURL("**/editor")
        page.url() shouldContain "/editor"
        page.waitForFunction("() => document.getElementById('app-search-palette').hidden === true")
    }

    /**
     * #159 — the desktop contract the band entry point must not disturb: at ≥1100px the
     * band button is display:none (it exists ONLY in the 768–1099 band), the topbar copy
     * is the displayed one, and the chord still drives it directly.
     */
    @Test
    fun `at desktop width the band entry point does not exist and the topbar copy keeps the chord`() {
        startTrace()
        signedIn("hdrchk")
        page.setViewportSize(1440, 900)
        page.navigate("$baseUrl/dashboard")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)

        page.locator("#app-search-band").isVisible().shouldBeFalse()
        page.locator("#app-search-input").isVisible().shouldBeTrue()

        page.keyboard().press("Control+k")
        page.waitForFunction("() => document.getElementById('app-search-input').getAttribute('aria-expanded') === 'true'")
    }

    // ------------------------------------------------------------------ fixtures

    /** A signed-in session in ITS OWN workspace (the AppShellBrowserTest fixture, verbatim). */
    private fun signedIn(slug: String): String {
        val user = seedLocalUser(uniqueEmail("$slug-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("$slug" + generatedPassword("w").take(8).lowercase())
        return user.email
    }

    /** POSTs JSON through the page's own session (the 106 fixture pattern), asserting 201. */
    private fun postJson(
        url: String,
        body: String,
    ) {
        val status =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const res = await fetch(args.url, {
                    method: 'POST', credentials: 'same-origin',
                    headers: {'Content-Type': 'application/json',
                              'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : ''},
                    body: args.body,
                  });
                  return res.status;
                }""",
                mapOf("url" to url, "body" to body),
            )
        (status as Number).toInt() shouldBe 201
    }
}
