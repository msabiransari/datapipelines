package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 097's two migrated screens, walked in a real browser and photographed.
 *
 * A screenshot proves a layout to a reader; an assertion proves it to the build. Both
 * screens changed how their markup is PRODUCED and must not have changed what it looks
 * like, so every stop here asserts the structural fact that the migration was supposed to
 * preserve — and the image is the reader's half of that claim.
 *
 * - **Admin users (§C)** — the rows were built as HTML strings in Kotlin, `<tr>` by `<tr>`,
 *   with an inline `style` on every action button. They are `partials/admin-user-row` now,
 *   from `AdminUsersBrowseModel`. The walk asserts the row shape, the semantic colour
 *   classes, the ABSENCE of any inline style in the table, and that the search box's swap
 *   renders the very same fragment the page painted.
 * - **The template editor (§D)** — the page's behaviour was a ~135-line inline script. The
 *   walk asserts the page carries no inline script of its own, that the preview goes through
 *   htmx to `/partials/templates/render`, and that Discard asks IN THE PAGE (it used to be a
 *   `window.confirm`, which Playwright would have auto-dismissed — a browser dialog is not a
 *   thing the product can style, test or dismiss).
 *
 * Files land in `build/reports/097-screenshots/` as `097-<state>.png`. They are a deliverable
 * of the round, not an input to it: nothing reads them back, and this class fails on the
 * assertions, never on a pixel.
 */
class UiBoundaryShotsBrowserTest : BrowserSuite() {
    /**
     * A user whose ROW is byte-for-byte the same on every run: a FIXED id, email and display
     * name. The migrated table renders the id's first 8 characters, so a generated user makes
     * the screenshot different every time and un-comparable with the one taken before the
     * migration — which is the whole point of photographing this screen.
     */
    private fun seedFixtureRow() {
        java.sql.DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject,
                                           is_active, is_admin, password_hash, must_change_password)
                        VALUES ('$FIXTURE_ID', '$FIXTURE_EMAIL', 'Fixture Row', 'local', '$FIXTURE_EMAIL',
                                TRUE, FALSE, 'x', FALSE)
                        ON CONFLICT (email) DO NOTHING
                        """.trimIndent(),
                    )
                }
            }
    }

    private fun loginReadyAdmin(): LocalUser {
        val user =
            seedLocalUser(
                uniqueEmail("uib-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("uibws-" + generatedPassword("w").take(8).lowercase())
        return user
    }

    @Test
    fun `the admin users table is a fragment now, and looks like the table it replaced`() {
        startTrace()
        val user = loginReadyAdmin()

        page.navigate("$baseUrl/admin/users")
        page.waitForSelector("#user-table-body tr[id^=user-row-]")

        // §B: the PAGE painted these rows. Before 097 the tbody arrived with three skeleton
        // rows and an inline script fetched the real ones, so this markup was never in the
        // page's own response.
        page.locator("#user-table-body tr[id^=user-row-]").count() shouldBeGreaterThanOrEqual 1
        page.locator("#user-table-body").innerText() shouldContain user.email

        // §C: the action buttons carry the semantic classes, and NOTHING in the table carries
        // an inline style attribute — the thing the widened InlineWidthAuditTest bans in the
        // Kotlin that used to write it.
        page.locator("#user-table-body button.u-danger").count() shouldBeGreaterThanOrEqual 1
        page.locator("#user-table-body button.u-warning").count() shouldBeGreaterThanOrEqual 1
        val tableHtml = page.locator("#user-table-body").innerHTML()
        (tableHtml.contains("style=")) shouldBe false
        // The badges are the design system's, as they were.
        page.locator("#user-table-body span.ds-badge").count() shouldBeGreaterThanOrEqual 2

        shot("admin-users")

        // The comparable shot: ONE deterministic row, photographed as the TABLE element. The
        // page around it carries a generated admin's email and id; this does not.
        seedFixtureRow()
        page.waitForResponse({ it.url().contains("/partials/admin/users") }, {
            page.fill("#userSearch", FIXTURE_EMAIL)
            page.locator("#userSearch").press("End")
        })
        page.locator("#user-table-body tr:has-text('$FIXTURE_EMAIL')").waitFor()
        // Fonts first: a screenshot taken while a webfont is still loading is drawn in the
        // fallback face, which makes a run-to-run byte comparison meaningless.
        page.waitForFunction("() => document.fonts.ready.then(() => document.fonts.status === 'loaded')")
        page.locator("table.ds-table").screenshot(
            com.microsoft.playwright.Locator
                .ScreenshotOptions()
                .setPath(shotDir().resolve("097-admin-users-table.png")),
        )
        page.fill("#userSearch", "")

        // The search box swaps the SAME fragment the page rendered: this user's row survives
        // the round trip byte-identically (the parity §A asserts for datasources, here by
        // construction — one fragment, two entry points).
        val row = page.locator("#user-table-body tr:has-text('${user.email}')")
        val before = row.innerHTML()
        page.waitForResponse({ it.url().contains("/partials/admin/users") }, {
            page.fill("#userSearch", user.email)
            // The box is hx-triggered on `keyup changed`, which `fill` does not fire.
            page.locator("#userSearch").press("End")
        })
        page.waitForSelector("#user-table-body tr[id^=user-row-]")
        page.locator("#user-table-body tr:has-text('${user.email}')").innerHTML() shouldBe before
    }

    @Test
    fun `the template editor works with no script of its own — preview through htmx, discard asked in the page`() {
        startTrace()
        loginReadyAdmin()
        val name = FIXTURE_TEMPLATE

        page.navigate("$baseUrl/templates")
        page.click("text=Create Template")
        page.fill("#create-template-modal input[name=name]", name)
        page.selectOption("#create-template-type", "sql")
        page.selectOption("#create-template-dialect", "POSTGRES")
        page.fill("#create-template-modal textarea[name=body]", "SELECT 1 AS one")
        page.fill("#create-template-modal input[name=description]", "browser test template")
        page
            .waitForResponse("**/partials/templates") {
                page.click("#create-template-modal button[type=submit]")
            }.status() shouldBe 200

        page.navigate("$baseUrl/templates/editor?name=" + java.net.URLEncoder.encode(name, "UTF-8"))
        page.waitForSelector(".te-page")

        // §D: the page's own scripts are FILES. The layout keeps exactly one inline snippet
        // (the rail-collapse flash preventer, which must run before first paint).
        val inlineScripts = page.evaluate("() => [...document.querySelectorAll('script')].filter(s => !s.src).length")
        inlineScripts shouldBe 1
        val srcs = page.evaluate("() => [...document.querySelectorAll('script[src]')].map(s => s.getAttribute('src')).join(' ')")
        srcs.toString() shouldContain "/js/template-editor/lifecycle.js"
        srcs.toString() shouldContain "/js/csrf.js"

        // The preview: htmx, not a raw fetch — and the CSRF header rides on the layout for it.
        val render =
            page.waitForResponse({ it.url().contains("/partials/templates/render") }, {
                page.click("#previewBtn")
            })
        render.status() shouldBe 200
        page.waitForSelector("#previewPane pre")
        page.locator("#previewPane").innerText() shouldContain "SELECT 1"

        shot("template-editor")
        // The comparable shot: the editor's own region, from a FIXED template name and body,
        // so the image can be diffed against the one taken before the script moved out.
        page.waitForFunction("() => document.fonts.ready.then(() => document.fonts.status === 'loaded')")
        page.locator(".te-page").screenshot(
            com.microsoft.playwright.Locator
                .ScreenshotOptions()
                .setPath(shotDir().resolve("097-template-editor-page.png")),
        )

        // Discard asks IN THE PAGE. A window.confirm would be auto-dismissed by Playwright
        // and the draft would be gone by the next line; this dialog is markup.
        page.click("#tpl-discard-draft")
        page.waitForSelector("#tpl-discard-confirm .app-modal")
        shot("template-editor-discard-confirm")
        page.click("#tpl-discard-confirm button:has-text('Keep the draft')")
        page.locator("#tpl-discard-confirm").waitFor(
            com.microsoft.playwright.Locator
                .WaitForOptions()
                .setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN),
        )
        // Nothing was discarded: the draft's own affordances are still there.
        page.locator("#tpl-release-draft").isVisible shouldBe true
    }

    private fun shot(state: String) {
        page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("097-$state.png")))
    }

    private fun shotDir(): Path = Paths.get("build", "reports", "097-screenshots").also { it.toFile().mkdirs() }

    private companion object {
        /** Fixed so the row's rendered id prefix is the same on both sides of the migration. */
        const val FIXTURE_ID = "00000000-0000-4000-8000-000000000097"
        const val FIXTURE_EMAIL = "fixture-097@browser.datapipelines.test"

        /** Fixed for the same reason the user is: the editor renders the name and the body. */
        const val FIXTURE_TEMPLATE = "test/uib_shot_tpl"
    }
}
