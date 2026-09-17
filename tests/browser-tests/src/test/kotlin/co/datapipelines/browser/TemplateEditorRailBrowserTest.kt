package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #112 — an EMPTY side rail reclaims its width for the read-only source, in a real browser.
 *
 * The rail carries two things: the Render Context panel (author-only — 143/T315, its POST is
 * MUTATE) and the Imports table (a read, present only when the selected template imports a
 * library). A reader — viewer or pure promoter — on a template with no imports therefore got a
 * 320px column of NOTHING and a source pane narrowed by it. The fix is server-side: a rail with
 * nothing to show is not rendered (nor is its splitter handle), and `.te-body` collapses to one
 * column. What must NOT change: an author's rail, the Imports table for readers, the remembered
 * `--te-side-w` when the rail IS present, and the narrow-viewport contract ([TemplateEditorSideResizeBrowserTest]
 * owns the clamp floor).
 *
 * Geometry is asserted as RELATIONS (source spans the body; the rail takes its track), never
 * against a hard-coded page width. Falsification, recorded in the handback: every "no rail"
 * assertion here is red on the pre-fix markup (the rail rendered empty but present).
 */
class TemplateEditorRailBrowserTest : BrowserSuite() {
    // ------------------------------------------------------------------ the collapsed rail

    @Test
    fun `a viewer on an imports-less template gets NO rail - the source spans the body, in light and dark`() {
        startTrace()
        val maker = seedAndLogin("railmk", author = true, promoter = false, admin = false)
        val name = "test/rail_plain_" + suffix()
        seedTemplate(maker, name)
        maker.close()

        val viewer = seedAndLogin("railvw", author = false, promoter = false, admin = false)
        openEditor(viewer, name)

        assertCollapsed(probe(viewer.page), "on first paint")
        ensureThemeOn(viewer.page, "light")
        assertCollapsed(probe(viewer.page), "light")
        ensureThemeOn(viewer.page, "dark")
        assertCollapsed(probe(viewer.page), "dark")
        viewer.close()
    }

    @Test
    fun `a pure promoter on an imports-less template gets NO rail either - the panel is author-only`() {
        startTrace()
        val maker = seedAndLogin("railmk", author = true, promoter = false, admin = false)
        val name = "test/rail_promo_" + suffix()
        seedTemplate(maker, name)
        maker.close()

        val promoter = seedAndLogin("railpr", author = false, promoter = true, admin = false)
        openEditor(promoter, name)
        assertCollapsed(probe(promoter.page), "promoter")
        promoter.close()
    }

    @Test
    fun `the collapse holds narrow and wide - the phone band does not resurrect the rail`() {
        startTrace()
        val maker = seedAndLogin("railmk", author = true, promoter = false, admin = false)
        val name = "test/rail_np_" + suffix()
        seedTemplate(maker, name)
        maker.close()

        val viewer = seedAndLogin("railvw", author = false, promoter = false, admin = false)
        // 110 §C: at 390px the page shows the wide-screen note with the editor rendered
        // underneath — and the rail question has the same answer there.
        viewer.page.setViewportSize(390, 844)
        openEditor(viewer, name)
        assertCollapsed(probe(viewer.page), "narrow 390px")
        viewer.page.setViewportSize(1440, 900)
        assertCollapsed(probe(viewer.page), "wide 1440px")
        viewer.close()
    }

    // ------------------------------------------------------------------ the rail that stays

    @Test
    fun `a viewer on a template WITH imports keeps the rail - the Imports table is the read it carries`() {
        startTrace()
        // The maker holds BOTH editing and release: releasing the library v1 is a promoter's
        // verb (RELEASE_VERSION), an author-only fixture dies on it with a 403.
        val maker = seedAndLogin("railmk", author = true, promoter = true, admin = false)
        val lib = "test/rail_lib_" + suffix()
        val libHash = seedTemplate(maker, lib, isLibrary = true, body = "<#macro one>1</#macro>")
        libHash.isNotBlank() shouldBe true
        release(maker, lib, libHash) shouldBe 200
        val name = "test/rail_imp_" + suffix()
        seedTemplate(maker, name, imports = """[{"id":"$lib","version":1,"alias":"rl"}]""")
        maker.close()

        val viewer = seedAndLogin("railvw", author = false, promoter = false, admin = false)
        openEditor(viewer, name)

        val m = probe(viewer.page)
        withClue("the rail is gone even though the template imports a library: $m") {
            m.b("railPresent") shouldBe true
            m.b("handlePresent") shouldBe true
        }
        withClue("the rail does not carry its Imports table: $m") {
            m.b("importsHead") shouldBe true
        }
        withClue("a READER got the author-only Render Context panel: $m") {
            m.b("renderContext") shouldBe false
        }
        withClue("the rail is not at the stylesheet default (320px): $m") {
            m.d("railW") shouldBeGreaterThanOrEqual DEFAULT_W - 1
            m.d("railW") shouldBeLessThanOrEqual DEFAULT_W + 1
        }
        withClue("the source kept the remainder, not the width of a collapsed rail: $m") {
            m.d("sourceW") shouldBeLessThanOrEqual m.d("bodyW") - DEFAULT_W
        }

        // Narrow: the clamp floor owns the rail, as the resize suite pins for the author case.
        viewer.page.setViewportSize(390, 844)
        val narrow = probe(viewer.page)
        withClue("at 390px the rail is not at the 220px clamp floor: $narrow") {
            narrow.d("railW") shouldBeGreaterThanOrEqual SIDE_MIN - 1
            narrow.d("railW") shouldBeLessThanOrEqual SIDE_MIN + 1
        }
        viewer.close()
    }

    @Test
    fun `an author's rail is untouched - Render Context stays and the remembered width still paints first`() {
        startTrace()
        val author = seedAndLogin("railau", author = true, promoter = false, admin = false)
        val name = "test/rail_auth_" + suffix()
        seedTemplate(author, name)

        // A remembered 400px: the parser-blocking restore writes --te-side-w before the first
        // paint, and the collapse change must not have touched that path.
        author.page.addInitScript("window.localStorage.setItem('dp.pane.template-editor-side', '400');")
        openEditor(author, name)

        val m = probe(author.page)
        withClue("the author's rail vanished with the reader's empty one: $m") {
            m.b("railPresent") shouldBe true
            m.b("renderContext") shouldBe true
        }
        withClue("the remembered 400px did not survive the change: $m") {
            m.d("railW") shouldBeGreaterThanOrEqual 400.0 - 1
            m.d("railW") shouldBeLessThanOrEqual 400.0 + 1
        }
        author.close()
    }

    // ------------------------------------------------------------------ the assertion

    /** The empty-rail contract: no rail, no handle, and the source column IS the body. */
    private fun assertCollapsed(
        m: Map<String, Any?>,
        clue: String,
    ) {
        withClue("$clue: the empty rail is still rendered: $m") {
            m.b("railPresent") shouldBe false
        }
        withClue("$clue: the splitter handle outlived its rail: $m") {
            m.b("handlePresent") shouldBe false
        }
        withClue("$clue: the source does not start at the body's left edge: $m") {
            (m.d("sourceX") - m.d("bodyX")) shouldBeLessThanOrEqual 1.0
            (m.d("bodyX") - m.d("sourceX")) shouldBeLessThanOrEqual 1.0
        }
        withClue("$clue: the source does not span the body: $m") {
            (m.d("bodyW") - m.d("sourceW")) shouldBeLessThanOrEqual 1.0
            (m.d("sourceW") - m.d("bodyW")) shouldBeLessThanOrEqual 1.0
        }
    }

    // ------------------------------------------------------------------ fixtures and drivers

    /** A per-test user with the membership flags the case needs, signed in on its own session. */
    private fun seedAndLogin(
        slug: String,
        author: Boolean,
        promoter: Boolean,
        admin: Boolean,
    ): Session {
        val user =
            seedLocalUser(
                uniqueEmail("$slug-" + suffix()),
                generatedPassword("pw"),
                mustChange = false,
                isAdmin = false,
                author = author,
                promoter = promoter,
                admin = admin,
            )
        val session = newSession()
        session.page.navigate("$baseUrl/login")
        session.page.fill("#login-email", user.email)
        session.page.fill("#login-password", user.oneTimePassword)
        session.page.click("form button[type=submit]")
        session.page.waitForURL("**/dashboard")
        return session
    }

    private fun openEditor(
        session: Session,
        name: String,
    ) {
        session.page.navigate("$baseUrl/templates/editor?name=" + java.net.URLEncoder.encode(name, "UTF-8"))
        // The source pane renders whether or not the rail does — the stable landmark to wait on.
        session.page.waitForSelector(".te-source")
        session.page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
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

    /** The facts, read in ONE evaluate so they describe the same frame. */
    private val probeJs =
        """
        () => {
          const body = document.querySelector('.te-body');
          if (!body) return null;
          const rail = document.querySelector('.te-rail');
          const handle = document.querySelector('[data-splitter="template-editor-side"]');
          const source = document.querySelector('.te-source');
          const b = body.getBoundingClientRect();
          const s = source ? source.getBoundingClientRect() : null;
          const r = rail ? rail.getBoundingClientRect() : null;
          return {
            railPresent: !!rail,
            handlePresent: !!handle,
            bodyX: b.x, bodyW: b.width,
            sourceX: s ? s.x : -1, sourceW: s ? s.width : -1,
            railW: r ? r.width : -1,
            renderContext: !!document.querySelector('#context-rows'),
            importsHead: !!Array.from(document.querySelectorAll('.te-rail h2')).find(h => h.textContent === 'Imports'),
          };
        }
        """.trimIndent()

    @Suppress("UNCHECKED_CAST")
    private fun probe(page: Page): Map<String, Any?> = page.evaluate(probeJs) as Map<String, Any?>

    private fun Map<String, Any?>.d(k: String) = (this[k] as Number).toDouble()

    private fun Map<String, Any?>.b(k: String) = this[k] as Boolean

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

    private companion object {
        /** `template-editor.css`'s default (the `--app-detail-width` token) and its clamp floor. */
        const val DEFAULT_W = 320.0
        const val SIDE_MIN = 220.0
    }
}
