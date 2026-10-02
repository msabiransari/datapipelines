package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.DriverManager
import java.util.UUID

/**
 * 287 (#287) — the Back path of the four hand-rolled style writers. htmx caches the page as
 * MARKUP before a boosted swap and re-parses it on Back; a style a script wrote through the
 * CSSOM serialises into that markup as a `style` attribute, and `style-src 'self'` (no
 * nonce, no 'unsafe-inline') refuses it — the console reports a CSP violation and the page
 * comes back without the style. The fix: the modals and the version menu leave NO inline
 * style behind (the `u-backdrop-hidden` class; the menu's placement is stripped by the
 * registered history cleanup, which also closes open menus — the snapshot, like the shell's
 * own skeletons, carries no transient chrome), and data-table's write set is stripped by the
 * same one listener (shell.js) through the `window.__dpHistoryStyleCleanups` registry.
 *
 * The walk per writer — open → close (the version menu is left OPEN; a placed list keeps its
 * measured placement) → boosted away → Back — with the suite's CSP collector armed throughout
 * (closePage fails the test on any violation; each test also drains and asserts the count
 * itself, so the red run reads "violations ≥ 1"). Measured on the base (dd7d807a) before the
 * fix, one Back carried 1 violation per walk; after the fix, 0 — and the modal still closed,
 * still opens again on the restored DOM.
 *
 * Screenshots (the three modals' pages after Back, light and dark) land in
 * build/reports/287-screenshots/ for the handback.
 */
class ModalBackBrowserTest : BrowserSuite() {
    @Test
    fun `the register datasource modal - open, close, boosted away, back - no refused inline style`() {
        val admin = seedLocalUser(uniqueEmail("mbr-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(admin.email, admin.oneTimePassword)
        page.waitForURL("**/dashboard")
        page.navigate("$baseUrl/datasources")
        page.locator("[data-action='datasource-register-open']").waitFor()

        walkModal("#register-modal", "[data-action='datasource-register-open']", "[data-action='datasource-register-close']")
    }

    @Test
    fun `the api key modal - open, close, boosted away, back - no refused inline style`() {
        val admin = seedLocalUser(uniqueEmail("mbk-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(admin.email, admin.oneTimePassword)
        page.waitForURL("**/dashboard")
        page.navigate("$baseUrl/api-keys")
        page.locator("[data-action='key-modal-open']").waitFor()

        walkModal("#key-modal", "[data-action='key-modal-open']", "[data-action='key-modal-close']")
    }

    @Test
    fun `the create template modal - open, close, boosted away, back - no refused inline style`() {
        val admin = seedLocalUser(uniqueEmail("mbt-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(admin.email, admin.oneTimePassword)
        page.waitForURL("**/dashboard")
        page.navigate("$baseUrl/templates")
        page.locator("[data-action='template-create-open']").waitFor()

        walkModal("#create-template-modal", "[data-action='template-create-open']", "[data-action='template-create-close']")
    }

    /**
     * The version menu: its PLACED list is the writer (`--vmenu-top`/`--vmenu-left`, written
     * the moment the menu opens), so the walk leaves the menu OPEN across the boosted
     * navigation. After Back the menu is closed (the cleanup closes open menus before the
     * snapshot) and no list in the restored page carries a `style` attribute.
     */
    @Test
    fun `the version menu - opened, boosted away, back - no refused inline style, menu closed`() {
        val admin = seedLocalUser(uniqueEmail("mbv-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(admin.email, admin.oneTimePassword)
        page.waitForURL("**/dashboard")
        val pipelineId = seedPipeline("test/mbv-" + generatedPassword("p").take(8).lowercase(), admin.email)
        // #350: the version rows (and their ⋯ menus) are the WORKSPACE's Versions tab — the
        // explorer's detail pane is gone. The same fragment, the same placement script
        // (lifecycle-dialog.js), the same restore discipline under test.
        page.navigate("$baseUrl/pipelines/$pipelineId?tab=versions")
        page.waitForSelector(".pe-root")
        page.locator("#pe-pane-versions tr[data-version-row]").first().waitFor()

        // The ⋯ usually sits below the fold: scroll it into view OURSELVES, then wait for the
        // scroll to have SETTLED — the summary's rect inside the viewport and unchanged across
        // two animation frames (301 #301; was a fixed 300 ms sleep) — so Playwright's own
        // pre-click auto-scroll cannot race the menu's scroll-out-of-sight close.
        page.locator("#pe-pane-versions details.tplx-vmenu > summary").first().evaluate("el => el.scrollIntoView({ block: 'center' })")
        page.waitForFunction(
            """(sel) => {
              const el = document.querySelector(sel);
              if (!el) return false;
              const rect = el.getBoundingClientRect();
              if (rect.top < 0 || rect.bottom > innerHeight) return false;
              return new Promise(done => requestAnimationFrame(() => requestAnimationFrame(() => {
                const after = el.getBoundingClientRect();
                done(after.top === rect.top && after.bottom === rect.bottom);
              })));
            }""",
            "#pe-pane-versions details.tplx-vmenu > summary",
        )
        page.locator("#pe-pane-versions details.tplx-vmenu > summary").first().click()
        page.waitForFunction(
            """() => {
              const list = document.querySelector('details.tplx-vmenu[open] .tplx-vmenu-list');
              return !!list && list.style.getPropertyValue('--vmenu-top').endsWith('px');
            }""",
        )
        withClue("the menu opened and its list was placed through the CSSOM") {
            placedMenuState() shouldBe true
        }

        boostedAwayAndBack()
        withClue("CSP violations after Back (the placed list's custom properties came back refused)") {
            drainCspViolations().shouldBeEmpty()
        }
        withClue("the restored page: the menu is closed and no list carries a style") { menuAfterBack() }
    }

    /**
     * The handback's screens: each modal's page after the Back (the state a person sees —
     * the modal closed, the page whole), light and dark at 1440.
     */
    @Test
    fun `screens - the three modal pages after Back, light and dark`() {
        val admin = seedLocalUser(uniqueEmail("mbs-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(admin.email, admin.oneTimePassword)
        page.waitForURL("**/dashboard")
        for (mode in listOf("light", "dark")) {
            ensureTheme(mode)
            page.setViewportSize(1440, 900)
            page.navigate("$baseUrl/datasources")
            walkAndShoot(
                "#register-modal",
                "[data-action='datasource-register-open']",
                "[data-action='datasource-register-close']",
                "datasources-after-back-$mode",
            )
            page.navigate("$baseUrl/api-keys")
            walkAndShoot(
                "#key-modal",
                "[data-action='key-modal-open']",
                "[data-action='key-modal-close']",
                "api-keys-after-back-$mode",
            )
            page.navigate("$baseUrl/templates")
            walkAndShoot(
                "#create-template-modal",
                "[data-action='template-create-open']",
                "[data-action='template-create-close']",
                "templates-after-back-$mode",
            )
        }
        withClue("CSP violations across the screens walk") { drainCspViolations().shouldBeEmpty() }
    }

    /** Open → close → boosted away → Back → the page's screenshot, exactly the walk's end state. */
    private fun walkAndShoot(
        modal: String,
        opener: String,
        closer: String,
        shot: String,
    ) {
        page.locator(opener).waitFor()
        page.locator(opener).click()
        page.locator(closer).click()
        boostedAwayAndBack()
        page.locator(opener).waitFor()
        withClue("$modal closed on the restored page (the screenshot's state)") {
            modalState(modal, "display") shouldBe "none"
        }
        page.screenshot(Page.ScreenshotOptions().setPath(shotDir().resolve("$shot.png")))
    }

    private fun shotDir(): Path = Paths.get("build", "reports", "287-screenshots").also { it.toFile().mkdirs() }

    // ------------------------------------------------------------------ the walk

    /** Open → (visible) → close → (no inline style left) → boosted away → Back → assert. */
    private fun walkModal(
        modal: String,
        opener: String,
        closer: String,
    ) {
        page.locator(opener).click()
        withClue("$modal opened") { modalState(modal, "display") shouldBe "flex" }

        page.locator(closer).click()
        withClue("$modal closed") { modalState(modal, "display") shouldBe "none" }

        boostedAwayAndBack()
        withClue("CSP violations after Back (the closed modal's inline display came back refused)") {
            drainCspViolations().shouldBeEmpty()
        }
        withClue("$modal still closed on the restored page, and no inline style on it") {
            modalState(modal, "display") shouldBe "none"
            modalState(modal, "hasStyleAttr") shouldBe false
        }
        // The class toggle still works on the restored DOM: the modal opens and closes again.
        page.locator(opener).click()
        withClue("$modal opens again after Back") { modalState(modal, "display") shouldBe "flex" }
        page.locator(closer).click()
        withClue("$modal closes again after Back") { modalState(modal, "display") shouldBe "none" }
    }

    /** A boosted navigation away and a Back to the page — the htmx history-restore path. */
    private fun boostedAwayAndBack() {
        val here = page.url()
        page.locator("nav a[href='/dashboard']").click()
        page.waitForURL("**/dashboard")
        page.goBack()
        page.waitForURL(here.substringBefore("?") + "**")
    }

    /** `display` = the modal's computed display; `hasStyleAttr` = whether a `style` attribute rides on it. */
    private fun modalState(
        modal: String,
        what: String,
    ): Any =
        page.evaluate(
            """([sel, what]) => {
              const m = document.querySelector(sel);
              if (!m) return 'absent';
              if (what === 'display') return getComputedStyle(m).display;
              return m.hasAttribute('style');
            }""",
            arrayOf(modal, what),
        ) ?: "null"

    /** True when an open menu's list is placed (the measured custom properties are set). */
    private fun placedMenuState(): Boolean =
        page.evaluate(
            """() => {
              const list = document.querySelector('details.tplx-vmenu[open] .tplx-vmenu-list');
              return !!list && list.style.getPropertyValue('--vmenu-top').endsWith('px');
            }""",
        ) as Boolean

    /** On the restored page: no open menu, and no `.tplx-vmenu-list` carries a `style` attribute. */
    private fun menuAfterBack() {
        val state =
            page.evaluate(
                """() => ({
                  open: document.querySelectorAll('details.tplx-vmenu[open]').length,
                  styled: document.querySelectorAll('.tplx-vmenu-list[style]').length,
                })""",
            ) as Map<*, *>
        state["open"] shouldBe 0
        state["styled"] shouldBe 0
    }

    // ------------------------------------------------------------------ fixtures

    /** One pipeline (one version) in the `default` workspace — the versions panel's ⋯ menu needs nothing more. */
    private fun seedPipeline(
        name: String,
        ownerEmail: String,
    ): UUID {
        val pipelineId = UUID.randomUUID()
        DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO pipelines (id, workspace_id, name, display_name, owner_id, current_version)
                    SELECT '$pipelineId', 'defa0000-0000-0000-0000-000000000001', '$name', '$name', id, 1
                      FROM users WHERE email = '$ownerEmail'
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO pipeline_versions (pipeline_id, version, body_json, created_by, body_hash)
                    SELECT '$pipelineId', 1, '{}'::jsonb, id, 'seeded-fixture-hash' FROM users WHERE email = '$ownerEmail'
                    """.trimIndent(),
                )
            }
        }
        return pipelineId
    }
}
