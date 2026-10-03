package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #243 — the Render Context row fits the panel that holds it, in a real browser.
 *
 * On the base the row was 476px in a 320px rail at EVERY width (the issue's measurement:
 * key 256..469, value 473..686, remove 690..732 against a rail ending at 576): a flex
 * item's automatic minimum is its intrinsic size, an `<input>`'s intrinsic size is its
 * `size` attribute, so the `flex: 1` / `flex: 2` split never divided the row that existed
 * and the remove (×) button sat past the rail's right edge. The fix is `min-width: 0` on
 * the inputs and `flex: none` on the button (`template-editor.css`) — never a fixed width.
 *
 * #398 re-aims the guard: the rail and its splitter are gone (the Render Context is the
 * workspace's RENDER tab, a card in the page flow), and the property under test is the
 * same — every control's right edge against the CARD's own box, for the static row AND a
 * row cloned by the Add Row control (lifecycle.js clones the markup the template ships —
 * one definition must hold for both), at 1100 / 1440 / 1920, light and dark (the wrap is a
 * layout change). The 220px-floor arms retired with the splitter: there is no narrower box
 * the product allows, and the card is the row's whole world now.
 */
class TemplateEditorContextRowBrowserTest : BrowserSuite() {
    @Test
    fun `a render-context row fits its card at every desktop width, in both themes`() {
        startTrace()
        val author = seedAndLogin("cxrow", role = "author")
        val name = "test/cxrow_" + suffix()
        seedTemplate(author.page, name)

        val offenders = mutableListOf<String>()
        for (theme in listOf("light", "dark")) {
            ensureTheme(theme)
            for (width in DESKTOP_WIDTHS) {
                author.page.setViewportSize(width, 900)
                openWorkspace(author.page, name)
                offenders += measure(author.page, "static row at $width ($theme)", expectedRows = 1)
                // A row cloned by the + control carries the same rules (lifecycle.js clones
                // the markup the template ships — one definition must hold for both).
                author.page.locator("[data-action='context-row-add']").click()
                offenders += measure(author.page, "added row at $width ($theme)", expectedRows = 2)
            }
        }

        offenders shouldBe emptyList()
        author.close()
    }

    // ------------------------------------------------------------------ the measurement

    /**
     * The row's key, value and × against the CARD's box — read in ONE evaluate so they
     * describe the same frame. Named edges are printed for every row whether or not the run
     * is green (the 240 precedent).
     */
    @Suppress("UNCHECKED_CAST")
    private fun measure(
        page: Page,
        where: String,
        expectedRows: Int,
    ): List<String> =
        (
            page.evaluate(
                """
                () => {
                  const card = document.querySelector('#tw-pane-render .ds-card');
                  if (!card) return { out: ['no render card rendered'], edges: 'no card', rows: -1 };
                  const cb = card.getBoundingClientRect();
                  const out = [], edges = [];
                  const rows = [...document.querySelectorAll('#context-rows .context-row')];
                  rows.forEach((row, i) => {
                    const n = 'row' + (i + 1);
                    [...row.children].forEach(el => {
                      const r = el.getBoundingClientRect();
                      const tag = el.tagName === 'INPUT' ? (el.placeholder || 'input') : 'remove';
                      edges.push(n + '.' + tag + ' ' + Math.round(r.left) + '..' + Math.round(r.right));
                      if (r.right > cb.right + 0.5)
                        out.push(n + ' ' + tag + ' ends at ' + Math.round(r.right) + " past the card's " + Math.round(cb.right));
                    });
                  });
                  return {
                    out,
                    edges: 'card ' + Math.round(cb.left) + '..' + Math.round(cb.right) + ' (w ' + Math.round(cb.width) + ') | ' + edges.join(', '),
                    rows: rows.length,
                  };
                }
                """.trimIndent(),
            ) as Map<String, Any?>
        ).let { measured ->
            val rows = (measured["rows"] as Number).toInt()
            val clues = mutableListOf<String>()
            if (rows != expectedRows) clues += "$where: expected $expectedRows context row(s), found $rows"
            // The measured edges, pass or fail: the handback's before/after table.
            println("#243 $where: ${measured["edges"]}")
            clues + (measured["out"] as List<String>).map { "$where: $it" }
        }

    // ------------------------------------------------------------------ fixtures and drivers

    /** A per-test author, signed in on its own session (the 112 rail suite's shape). */
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
        // The name's segments are path segments (the workspace's capture variable) — a
        // percent-encoded slash is refused 400 below routing (§9.6).
        page.navigate("$baseUrl/templates/$name")
        page.waitForSelector(".tw-root")
        // The Render tab is where the context rows live (#398); a fresh draft-only template
        // is the seed here, so the tab is on the page.
        page.locator("#tw-tab-render").click()
        page.waitForSelector("#context-rows")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
    }

    /** The in-page REST create of the sibling suites; 201 is the fixture's contract. */
    private fun seedTemplate(
        page: Page,
        name: String,
    ) {
        val status =
            page.evaluate(
                """async (name) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const res = await fetch('/api/v1/templates', {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' },
                    body: JSON.stringify({ id: name, type: 'sql', dialect: 'POSTGRES',
                                           display_name: name, description: '#243 context row fixture',
                                           body: 'SELECT 1' }),
                  });
                  return res.status;
                }""",
                name,
            ) as Int
        status shouldBe 201
    }

    private fun suffix(): String = generatedPassword("s").take(8).lowercase()

    private companion object {
        /** The three widths the issue names — the same walk AppShellBrowserTest's edge guard uses. */
        val DESKTOP_WIDTHS = listOf(1100, 1440, 1920)
    }
}
