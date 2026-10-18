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
                val directory = Paths.get("build", "reports", "606-shell-surfaces").also { it.toFile().mkdirs() }
                page.screenshot(
                    Page.ScreenshotOptions().setPath(directory.resolve("$width-${path.replace('/', '_')}.png")),
                )
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
        for (theme in themes) {
            page.evaluate(SWAP_THEME, theme)
            val rows = page.evaluate(EDGES) as List<*>
            rows.isNotEmpty() shouldBe true
            println("606-edges|$theme|$rows")
            assertSoftly {
                for (row in rows) {
                    val m = row as Map<*, *>
                    withClue("$theme: $m") {
                        m["present"] shouldBe true
                        m["actual"] shouldBe m["expected"]
                        m["width"] shouldBe "1px"
                    }
                }
            }
        }
    }

    private companion object {
        const val DESKTOP_WIDTH = 1512
        const val PHONE_WIDTH = 390
        const val WIDE_WIDTH = 1920
        const val REVIEW_HEIGHT = 982

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
                ['.app-brand', 'Bottom', '--app-line-decorative'],
                ['.app-browser-head', 'Bottom', '--app-line-decorative'],
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
                probe.style.color = 'var(' + role + ')';
                return { selector, role, present: !!el, expected: getComputedStyle(probe).color,
                  actual: el && getComputedStyle(el)['border' + side + 'Color'],
                  width: el && getComputedStyle(el)['border' + side + 'Width'] };
              });
              probe.remove();
              return result;
            }
            """.trimIndent()
    }
}
