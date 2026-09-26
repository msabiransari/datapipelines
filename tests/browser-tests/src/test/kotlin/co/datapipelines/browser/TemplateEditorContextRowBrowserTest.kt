package co.datapipelines.browser

import com.microsoft.playwright.Mouse
import com.microsoft.playwright.Page
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #243 — the template editor's Render Context row fits its rail, in a real browser.
 *
 * On the base the row was 476px in a 320px rail at EVERY width (the issue's measurement:
 * key 256..469, value 473..686, remove 690..732 against a rail ending at 576): a flex
 * item's automatic minimum is its intrinsic size, an `<input>`'s intrinsic size is its
 * `size` attribute, so the `flex: 1` / `flex: 2` split never divided the row that existed
 * and the remove (×) button sat past the rail's right edge, reachable only by a sideways
 * scroll nothing suggests. The fix is `min-width: 0` on the inputs and `flex: none` on the
 * button (`template-editor.css`) — never a fixed width, so the row holds at any width the
 * splitter allows.
 *
 * The measurement is the `AppShellBrowserTest` right-edge shape: every control's right
 * edge against the rail's own box, plus the rail's scrollWidth against its clientWidth
 * (the issue's own numbers), for the static row AND a row cloned by the Add Row control,
 * at 1100 / 1440 / 1920 and at the splitter's 220px floor. Edges print per row, pass or
 * fail — the handback's before/after table is extracted from them.
 */
class TemplateEditorContextRowBrowserTest : BrowserSuite() {
    @Test
    fun `a render-context row fits its rail at every desktop width and at the narrowest rail`() {
        startTrace()
        val author = seedAndLogin("cxrow", role = "author")
        val name = "test/cxrow_" + suffix()
        seedTemplate(author, name)

        val offenders = mutableListOf<String>()
        for (width in DESKTOP_WIDTHS) {
            author.page.setViewportSize(width, 900)
            openEditor(author, name)
            offenders += measure(author.page, "static row at $width", expectedRows = 1)
            // A row cloned by the + control carries the same rules (lifecycle.js clones the
            // markup the template ships — one definition must hold for both).
            author.page.locator("[data-action='context-row-add']").click()
            offenders += measure(author.page, "added row at $width", expectedRows = 2)
        }

        // The splitter's narrowest rail: a drag far past the 220px floor lands ON the floor
        // (splitter.js clamps), which is the tightest box the product allows the row in.
        author.page.setViewportSize(1440, 900)
        openEditor(author, name)
        dragHandleBy(author.page, -400.0)
        offenders += measure(author.page, "narrowest rail at 1440", expectedRows = 1)

        offenders shouldBe emptyList()
        author.close()
    }

    // ------------------------------------------------------------------ the measurement

    /**
     * The row's key, value and × against the rail's box, and the rail's scroll against its
     * client width — read in ONE evaluate so they describe the same frame. Named edges are
     * printed for every row whether or not the run is green (the 240 precedent).
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
                  const rail = document.querySelector('.te-rail');
                  if (!rail) return { out: ['no .te-rail rendered'], edges: 'no rail', rows: -1 };
                  const rr = rail.getBoundingClientRect();
                  const out = [], edges = [];
                  const rows = [...document.querySelectorAll('#context-rows .context-row')];
                  rows.forEach((row, i) => {
                    const n = 'row' + (i + 1);
                    [...row.children].forEach(el => {
                      const r = el.getBoundingClientRect();
                      const tag = el.tagName === 'INPUT' ? (el.placeholder || 'input') : 'remove';
                      edges.push(n + '.' + tag + ' ' + Math.round(r.left) + '..' + Math.round(r.right));
                      if (r.right > rr.right + 0.5)
                        out.push(n + ' ' + tag + ' ends at ' + Math.round(r.right) + " past the rail's " + Math.round(rr.right));
                    });
                  });
                  if (rail.scrollWidth > rail.clientWidth + 1)
                    console.log('#243 note: the rail scrolls sideways: scrollWidth ' + rail.scrollWidth +
                      ' > clientWidth ' + rail.clientWidth + ' (diagnostic only - the widest rail child may be a panel head, not a context row)');
                  return {
                    out,
                    edges: 'rail ' + Math.round(rr.left) + '..' + Math.round(rr.right) + ' (w ' + Math.round(rr.width) + ') | ' + edges.join(', '),
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
        session.page.click("form button[type=submit]")
        session.page.waitForURL("**/dashboard")
        return session
    }

    private fun openEditor(
        session: Session,
        name: String,
    ) {
        session.page.navigate("$baseUrl/templates/editor?name=" + java.net.URLEncoder.encode(name, "UTF-8"))
        session.page.waitForSelector(".te-source")
        session.page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
    }

    /** The in-page REST create of the sibling suites; 201 is the fixture's contract. */
    private fun seedTemplate(
        session: Session,
        name: String,
    ) {
        val status =
            session.page.evaluate(
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

    /** A real pointer drag on the splitter handle (the 141 suite's shape): down, stepped move, up. */
    private fun dragHandleBy(
        page: Page,
        byX: Double,
    ) {
        val box = page.locator("[data-splitter='template-editor-side']").boundingBox() ?: error("no splitter handle")
        val x = box.x + box.width / 2
        val y = box.y + box.height / 2
        page.mouse().move(x, y)
        page.mouse().down()
        page.mouse().move(x + byX, y, Mouse.MoveOptions().setSteps(12))
        page.mouse().up()
    }

    private fun suffix(): String = generatedPassword("s").take(8).lowercase()

    private companion object {
        /** The three widths the issue names — the same walk AppShellBrowserTest's edge guard uses. */
        val DESKTOP_WIDTHS = listOf(1100, 1440, 1920)
    }
}
