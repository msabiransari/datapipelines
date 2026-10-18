package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/** #606: measure the plain page, the page-coloured top bar and the three edge roles in the rendered shell. */
class ShellSurfacesBrowserTest : DashboardBrowserSuite() {
    @Test
    fun `the shell uses the page surface in every layout band`() {
        startTrace()
        ready("surfaces606")
        ensureTheme("dark")
        val readings = mutableListOf<Map<*, *>>()
        for (width in listOf(DESKTOP_WIDTH, PHONE_WIDTH, WIDE_WIDTH)) {
            page.setViewportSize(width, REVIEW_HEIGHT)
            for (path in listOf("/pipelines", "/overview/pipelines")) {
                page.navigate("$baseUrl$path")
                page.locator("#app-main").waitFor()
                page.evaluate("() => document.fonts.ready")
                val m = page.evaluate(SURFACES) as Map<*, *>
                println("606-surfaces|$width|$path|$m")
                readings.add(m)
                photograph("$width-${path.replace('/', '_')}")
            }
        }
        assertSoftly {
            for (m in readings) {
                withClue(m.toString()) {
                    m["backdrops"] shouldBe 0
                    m["body"] shouldBe m["pageToken"]
                    m["main"] shouldBe m["pageToken"]
                    m["topbar"] shouldBe m["pageToken"]
                    m["rail"] shouldBe m["panelToken"]
                    m["bodyImage"] shouldBe "none"
                    if (m["cardBorder"] != null) m["cardBorder"] shouldBe m["boundaryToken"]
                }
            }
        }
    }

    @Test
    fun `shell edges have their classified weight on every vendored theme`() {
        startTrace()
        ready("edges606")
        page.setViewportSize(DESKTOP_WIDTH, REVIEW_HEIGHT)
        page.navigate("$baseUrl/pipelines")
        val themes =
            page.evaluate(
                "() => [...document.querySelectorAll('#app-appearance [data-mode], .app-swatch[data-swatch]')]" +
                    ".map(e => e.dataset.mode || e.dataset.swatch)",
            ) as List<*>
        themes.isNotEmpty() shouldBe true
        val measurements =
            themes.associateWith { theme ->
                page.evaluate(SWAP_THEME, theme)
                // The input transitions its border when the stylesheet swaps; await that animation.
                page.evaluate(
                    """() => Promise.all(document.getAnimations()
                      .filter(a => a.effect.getTiming().iterations !== Infinity).map(a => a.finished))""",
                )
                val rows = page.evaluate(EDGES) as List<*>
                rows.isNotEmpty() shouldBe true
                println("606-edges|$theme|$rows")
                rows
            }
        assertSoftly {
            for ((theme, rows) in measurements) {
                for (row in rows) {
                    val m = row as Map<*, *>
                    withClue("$theme: $m") {
                        m["present"] shouldBe true
                        m["actual"] shouldBe m["expected"]
                        m["width"] shouldBe if (m["role"] == null) "0px" else "1px"
                    }
                }
            }
        }
    }

    @Test
    fun `shared tab strip specimens paint the decorative baseline`() {
        startTrace()
        ready("tabs606")
        page.navigate("$baseUrl/pipelines")
        // Isolated specimens measure the shared CSS without seeding unrelated resource workflows.
        val rows = page.evaluate(SHARED_TABS) as List<*>
        rows.size shouldBe SHARED_TAB_COUNT
        assertSoftly {
            for (row in rows) {
                val m = row as Map<*, *>
                withClue(m.toString()) {
                    m["actual"] shouldBe m["expected"]
                    m["width"] shouldBe "1px"
                }
            }
        }
    }

    @Test
    fun `the overview catalog and editor are photographed in expanded compact and phone modes`() {
        startTrace()
        val root = ready("shots606")
        ensureTheme("dark")
        val id =
            page.evaluate(
                """async name => {
                  const csrf = document.cookie.match(/(?:^|\s*)dp_csrf=([^;]*)/);
                  const response = await fetch('/api/v1/pipelines', { method: 'POST', credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json',
                      'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' },
                    body: JSON.stringify({name, display_name: 'Revenue by borough', nodes: [{id: 'quarter',
                      type: 'CALCULATOR', kind: 'fiscal_quarter', context_key: 'run_quarter',
                      inputs: {date: '${'$'}current_date', fiscal_start: '${'$'}org_fiscal_start_date'}}]}) });
                  if (response.status !== 201) throw new Error('fixture refused: ' + response.status);
                  return (await response.json()).data.id;
                }""",
                "$root/mobility/revenue_by_borough",
            ) as String
        page.setViewportSize(DESKTOP_WIDTH, REVIEW_HEIGHT)
        for ((name, path) in listOf("overview" to "/overview/pipelines", "catalog" to "/pipelines", "editor" to "/pipelines/$id")) {
            page.navigate("$baseUrl$path")
            page.locator(if (name == "editor") ".pe-root" else "#app-main").waitFor()
            photograph("$name-expanded")
        }
        page.navigate("$baseUrl/overview/pipelines")
        page.click("#rail-collapse")
        page.waitForFunction("() => document.documentElement.classList.contains('rail-collapsed')")
        photograph("overview-compact")
        page.setViewportSize(PHONE_WIDTH, REVIEW_HEIGHT)
        photograph("overview-phone")
    }

    private fun photograph(name: String) {
        page.evaluate("() => document.fonts.ready")
        page.evaluate(
            """() => Promise.all(document.getAnimations()
              .filter(a => a.effect.getTiming().iterations !== Infinity).map(a => a.finished))""",
        )
        val directory = Paths.get("build", "reports", "606-shell-surfaces").also { it.toFile().mkdirs() }
        page.screenshot(Page.ScreenshotOptions().setPath(directory.resolve("$name.png")))
    }

    private companion object {
        const val DESKTOP_WIDTH = 1512
        const val PHONE_WIDTH = 390
        const val WIDE_WIDTH = 1920
        const val REVIEW_HEIGHT = 982
        const val SHARED_TAB_COUNT = 3

        val SHARED_TABS =
            """
            async () => {
              for (const file of ['dashboards.css', 'template-tree.css', 'pipeline-editor.css']) {
                await new Promise((resolve, reject) => {
                  const link = document.createElement('link'); link.rel = 'stylesheet';
                  link.href = '/css/' + file; link.onload = resolve; link.onerror = reject;
                  document.head.appendChild(link);
                });
              }
              const probe = document.createElement('span'); probe.style.color = 'var(--app-line-decorative)';
              document.body.appendChild(probe);
              const expected = getComputedStyle(probe).color;
              const result = ['dp-ws-tabs', 'tplx-tabs', 'pe-dock-tabs'].map(name => {
                const el = document.createElement('div'); el.className = name;
                document.querySelector('#app-main').appendChild(el);
                const cs = getComputedStyle(el);
                const row = { name, actual: cs.borderBottomColor, width: cs.borderBottomWidth, expected };
                el.remove(); return row;
              });
              probe.remove(); return result;
            }
            """.trimIndent()

        val SWAP_THEME =
            """
            (theme) => new Promise((resolve, reject) => {
              const old = document.getElementById('theme-link');
              const link = document.createElement('link');
              link.rel = 'stylesheet';
              link.onload = () => { old.remove(); link.id = 'theme-link'; resolve(true); };
              link.onerror = () => reject(new Error('theme ' + theme + ' did not load'));
              link.href = '/vendor/design-system/themes/' + theme + '.css';
              old.insertAdjacentElement('afterend', link);
            })
            """.trimIndent()

        val SURFACES =
            """
            () => {
              const background = el => {
                for (let node = el; node; node = node.parentElement) {
                  const color = getComputedStyle(node).backgroundColor;
                  if (color !== 'rgba(0, 0, 0, 0)' && color !== 'transparent') return color;
                }
                throw new Error('no painted ancestor');
              };
              const probe = document.createElement('span');
              document.body.appendChild(probe);
              const token = name => { probe.style.color = 'var(' + name + ')'; return getComputedStyle(probe).color; };
              const result = { backdrops: document.querySelectorAll('.app-backdrop').length,
                pageToken: token('--surface-page'), panelToken: token('--surface-default'),
                boundaryToken: token('--border-default'),
                cardBorder: document.querySelector('#app-main .ds-card') &&
                  getComputedStyle(document.querySelector('#app-main .ds-card')).borderTopColor,
                body: background(document.body), main: background(document.querySelector('#app-main')),
                topbar: background(document.querySelector('.app-topbar')),
                rail: background(document.querySelector('.app-rail')), bodyImage: getComputedStyle(document.body).backgroundImage };
              probe.remove();
              return result;
            }
            """.trimIndent()

        val EDGES =
            """
            () => {
              const rows = [
                ['.app-rail', 'Right', '--app-line-decorative'],
                ['.app-brand', 'Bottom', null],
                ['.app-avatar', 'Top', null],
                ['.app-kbd', 'Top', '--app-line-decorative'],
                ['.app-browser-head', 'Bottom', '--app-line-decorative'],
                ['.app-browser-foot', 'Top', '--app-line-decorative'],
                ['.app-rail-foot', 'Top', '--app-line-decorative'],
                ['.app-topbar', 'Bottom', '--app-line-decorative'],
                ['.app-ws', 'Top', '--border-default'],
                ['.app-browser-tree > .dp-tree-search', 'Bottom', '--border-subtle'],
                ['.app-search', 'Top', '--border-default'],
                ['#pipeline-filter-q', 'Top', '--border-default'],
              ];
              const probe = document.createElement('span');
              document.body.appendChild(probe);
              const result = rows.map(([selector, side, role]) => {
                const el = document.querySelector(selector);
                probe.style.color = role ? 'var(' + role + ')' : '';
                return { selector, role, present: !!el, expected: role && getComputedStyle(probe).color,
                  actual: role && el && getComputedStyle(el)['border' + side + 'Color'],
                  width: el && getComputedStyle(el)['border' + side + 'Width'] };
              });
              probe.remove();
              return result;
            }
            """.trimIndent()
    }
}
