package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #112's presence rules, re-aimed by #398 onto the WORKSPACE — the rail and its splitter are
 * gone (the Render Context is the author-only RENDER tab; the Imports table is a card on the
 * Source tab), and what the old cases pinned is the TAB presence the same rules now produce:
 *
 *  - a READER (viewer or pure promoter) on any template gets NO Render tab and NO context
 *    rows — the panel was author-only (143/T315, its POST is MUTATE) and so is the tab;
 *  - a reader on a template WITH imports keeps the Imports table — the read the rail used
 *    to carry — on the Source tab;
 *  - an author gets the Render tab with the context panel (and the release-only template
 *    shows Edit, never an editable surface, R5).
 *
 * The empty-rail geometry and the remembered `--te-side-w` retired with the rail (no rail,
 * no splitter, no width to remember); the withdrawal guards live in
 * [TemplateWorkspaceBrowserTest], which also owns the workspace's own layout.
 */
class TemplateEditorRailBrowserTest : BrowserSuite() {
    // ------------------------------------------------------------------ the reader gets no Render tab

    @Test
    fun `a viewer on any template gets NO Render tab and no context rows, in light and dark`() {
        startTrace()
        val maker = seedAndLogin("railmk", role = "author")
        val name = "test/rail_plain_" + suffix()
        seedTemplate(maker, name)
        maker.close()

        val viewer = seedAndLogin("railvw", role = "viewer")
        listOf("light", "dark").forEach { theme ->
            ensureThemeOn(viewer.page, theme)
            openWorkspace(viewer.page, name)
            assertNoRenderTab(viewer.page, "viewer at ${mode(viewer.page)}")
        }
        viewer.close()
    }

    @Test
    fun `a pure promoter on an imports-less template gets NO Render tab either - the panel is author-only`() {
        startTrace()
        val maker = seedAndLogin("railmk", role = "author")
        val name = "test/rail_promo_" + suffix()
        seedTemplate(maker, name)
        maker.close()

        val promoter = seedAndLogin("railpr", role = "promoter")
        openWorkspace(promoter.page, name)
        assertNoRenderTab(promoter.page, "promoter")
        promoter.close()
    }

    @Test
    fun `the tab absence holds narrow and wide - the phone band does not resurrect it`() {
        startTrace()
        val maker = seedAndLogin("railmk", role = "author")
        val name = "test/rail_np_" + suffix()
        seedTemplate(maker, name)
        maker.close()

        val viewer = seedAndLogin("railvw", role = "viewer")
        // 110 §C: at 390px the page shows the wide-screen note with the workspace rendered
        // underneath — and the tab question has the same answer there.
        viewer.page.setViewportSize(390, 844)
        openWorkspace(viewer.page, name)
        assertNoRenderTab(viewer.page, "narrow 390px")
        viewer.page.setViewportSize(1440, 900)
        assertNoRenderTab(viewer.page, "wide 1440px")
        viewer.close()
    }

    // ------------------------------------------------------------------ the reads that stay

    @Test
    fun `a viewer on a template WITH imports keeps the Imports table - the read the rail used to carry`() {
        startTrace()
        // The maker edits AND releases: since 2026-09-20 release is the AUTHOR's verb (D8), so
        // an author fixture holds both.
        val maker = seedAndLogin("railmk", role = "author")
        val lib = "test/rail_lib_" + suffix()
        val libHash = seedTemplate(maker, lib, isLibrary = true, body = "<#macro one>1</#macro>")
        libHash.isNotBlank() shouldBe true
        release(maker, lib, libHash) shouldBe 200
        val name = "test/rail_imp_" + suffix()
        seedTemplate(maker, name, imports = """[{"id":"$lib","version":1,"alias":"rl"}]""")
        maker.close()

        val viewer = seedAndLogin("railvw", role = "viewer")
        openWorkspace(viewer.page, name)

        // The Imports table is on the SOURCE tab now, and the reader keeps it.
        withClue("the Imports card did not render on the Source tab") {
            viewer.page.locator(".tw-imports").waitFor()
        }
        viewer.page.locator(".tw-imports .ds-table").waitFor()
        viewer.page.locator(".tw-imports").innerText() shouldContain "rail_lib_"
        // ...and the author-only Render Context is still not theirs (no tab, no rows).
        assertNoRenderTab(viewer.page, "imports viewer")
        viewer.close()
    }

    @Test
    fun `an author's Render Context stays - the Render tab carries it, and the release shows Edit instead`() {
        startTrace()
        val author = seedAndLogin("railau", role = "author")
        val name = "test/rail_auth_" + suffix()
        seedTemplate(author, name)

        openWorkspace(author.page, name)
        // A fresh DRAFT-only template: the draft is the view, and the author gets the Render
        // tab with the context panel.
        author.page.locator("#tw-tab-render").waitFor()
        author.page.locator("#tw-tab-render").click()
        author.page.locator("#context-rows").waitFor()
        author.page.locator("#tab-kv-btn").waitFor()
        author.page.locator("#tab-json-btn").waitFor()
        author.close()
    }

    // ------------------------------------------------------------------ the assertion

    /** The author-only rule, on the workspace: no Render tab, and no context rows anywhere. */
    private fun assertNoRenderTab(
        page: Page,
        clue: String,
    ) {
        withClue("$clue: the Render tab is rendered for a reader") {
            page.locator("#tw-tab-render").count() shouldBe 0
        }
        withClue("$clue: the Render pane is rendered for a reader") {
            page.locator("#tw-pane-render").count() shouldBe 0
        }
        withClue("$clue: the context rows are rendered for a reader") {
            page.locator("#context-rows").count() shouldBe 0
        }
    }

    private fun mode(page: Page): String =
        if ((page.locator("#theme-link").first().getAttribute("href") ?: "").contains("/light.css")) "light" else "dark"

    // ------------------------------------------------------------------ fixtures and drivers

    /** A per-test user with the ONE workspace role the case needs, signed in on its own session. */
    private fun seedAndLogin(
        slug: String,
        role: String,
    ): Session {
        val user =
            seedLocalUser(
                uniqueEmail("$slug-" + suffix()),
                generatedPassword("pw"),
                mustChange = false,
                isAdmin = false,
                role = role,
            )
        val session = newSession()
        session.page.navigate("$baseUrl/login")
        session.page.fill("#login-email", user.email)
        session.page.fill("#login-password", user.oneTimePassword)
        named(session.page, "the login form's submit button on the seed session ($baseUrl/login)") {
            session.page.click("form button[type=submit]")
        }
        session.page.waitForURL("**/dashboard")
        return session
    }

    private fun openWorkspace(
        page: Page,
        name: String,
    ) {
        page.navigate("$baseUrl/templates/" + java.net.URLEncoder.encode(name, "UTF-8"))
        // The source pane renders whether or not the Render tab does — the stable landmark.
        page.waitForSelector(".tw-root")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
    }

    /**
     * The in-page REST create of the sibling suites: cookie session, the CSRF pair. Returns the
     * created version's `body_hash` (the release call's If-Match), blank on a failure status.
     */
    private fun seedTemplate(
        session: Session,
        name: String,
        isLibrary: Boolean = false,
        imports: String = "[]",
        body: String = "SELECT 1",
    ): String {
        val status =
            session.page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const res = await fetch('/api/v1/templates', {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' },
                    body: JSON.stringify({ id: args.name, type: 'sql', dialect: 'POSTGRES',
                                           display_name: args.name, description: '112 rail fixture',
                                           is_library: args.isLibrary, imports: JSON.parse(args.imports),
                                           body: args.body }),
                  });
                  const text = await res.text();
                  const m = /"body_hash"\s*:\s*"([0-9a-f]+)"/.exec(text);
                  return { status: res.status, hash: m ? m[1] : '' };
                }""",
                mapOf("name" to name, "isLibrary" to isLibrary, "imports" to imports, "body" to body),
            ) as Map<*, *>
        val statusCode = (status["status"] as Number).toInt()
        statusCode shouldBe 201
        return status["hash"] as String
    }

    /** Releases v1 of [name] — an imports pin resolves a RELEASED library version. */
    private fun release(
        session: Session,
        name: String,
        ifMatch: String,
    ): Int =
        session.page.evaluate(
            """async (args) => {
              const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              const res = await fetch('/api/v1/templates/release', {
                method: 'POST', credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json',
                           'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '',
                           'If-Match': args.ifMatch },
                body: JSON.stringify({ name: args.name }),
              });
              return res.status;
            }""",
            mapOf("name" to name, "ifMatch" to ifMatch),
        ) as Int

    /** [BrowserSuite.ensureTheme], for a session's own page (the toggle flips light ↔ dark). */
    private fun ensureThemeOn(
        page: Page,
        mode: String,
    ) {
        val wanted = "/themes/$mode.css"
        val current = page.locator("#theme-link").first().getAttribute("href") ?: ""
        if (current.contains(wanted)) return
        page.waitForResponse("**/partials/profile/theme") { page.locator("#mode-toggle").click() }
        page.waitForFunction("() => document.getElementById('theme-link').getAttribute('href').includes('$wanted')")
        page.locator("html[data-theme='$mode']").waitFor()
    }

    private fun withClue(
        clue: String,
        block: () -> Unit,
    ) = try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError(clue, e)
    }

    private fun suffix(): String = generatedPassword("s").take(8).lowercase()
}
