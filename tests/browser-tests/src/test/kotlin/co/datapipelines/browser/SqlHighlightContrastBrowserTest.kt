package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.ColorScheme
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #634 — the real SQL renderer in both workspaces, on every theme and auto's dark half.
 * Fixture borrowed from PipelineWorkspaceVersionBrowserTest's SQL node and
 * TemplateWorkspaceBrowserTest's render walk; extended to include all six token kinds.
 * The painted-background compositing is StatusInkContrastBrowserTest's technique.
 */
class SqlHighlightContrastBrowserTest : BrowserSuite() {
    @Test
    @Suppress("UNCHECKED_CAST")
    fun `SQL status hues read their inks and every token is measured on its painted background`() {
        startTrace()
        val user = seedLocalUser(uniqueEmail("sql634-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("sql634-" + generatedPassword("w").take(8).lowercase())
        val id = page.evaluate(SEED) as String
        val lines = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val observedMisses = mutableSetOf<String>()
        for (pane in listOf("pipeline", "template")) {
            if (pane == "pipeline") {
                page.navigate("$baseUrl/pipelines/$id")
                page.waitForSelector(".pe-card")
                page.locator(".pe-card-open").first().click()
                page.waitForSelector("#pe-node-sql .pe-sql-tok-string")
            } else {
                page.navigate("$baseUrl/templates/test/sql634?tab=render")
                page.locator("#tw-tab-render").click()
                page.click("#previewBtn")
                page.waitForSelector(".te-preview-pane .pe-sql-tok-string")
            }
            val themes =
                page.evaluate(
                    "() => [...document.querySelectorAll('#app-appearance [data-mode], .app-swatch[data-swatch]')]" +
                        ".map(e => e.dataset.mode || e.dataset.swatch)",
                ) as List<String>
            themes.size shouldBeGreaterThanOrEqual MIN_THEMES
            for ((theme, scheme) in themes.map { it to ColorScheme.LIGHT } + ("auto" to ColorScheme.DARK)) {
                page.emulateMedia(Page.EmulateMediaOptions().setColorScheme(scheme))
                page.evaluate(SWAP_THEME, theme) shouldBe true
                val pairs = page.evaluate(MEASURE, pane) as List<Map<String, Any>>
                pairs.map { it["role"] }.toSet() shouldBe TOKEN_KINDS.toSet()
                judge("$theme|${scheme.name.lowercase()}|$pane", pairs, lines, failures, observedMisses)
            }
        }
        val report = lines.joinToString("\n") + "\n"
        val path = Paths.get("build", "reports", "634-sql-highlight", "matrix.txt")
        path.parent.toFile().mkdirs()
        path.toFile().writeText(report)
        println(report)
        withClue(report) {
            failures shouldBe emptyList()
            observedMisses shouldBe KNOWN_MISSES.keys
        }
    }

    private fun judge(
        label: String,
        pairs: List<Map<String, Any>>,
        lines: MutableList<String>,
        failures: MutableList<String>,
        observedMisses: MutableSet<String>,
    ) {
        pairs.forEach { p ->
            val ratio = (p["ratio"] as Number).toDouble()
            val row = "634-measure|$label|${p["role"]}|${"%.2f".format(
                ratio,
            )}|text=${p["text"]} on ${p["background"]}"
            lines += row
            // Only measured, enumerated follow-ups may miss; a new or worse miss fails.
            val key = "$label|${p["role"]}"
            if (ratio < TEXT_FLOOR) {
                observedMisses += key
                val knownFloor = KNOWN_MISSES[key]
                if (knownFloor == null || ratio < knownFloor) failures += row
            }
            if (p["ink"] != null && p["text"] != p["ink"]) failures += "$row expected ink=${p["ink"]}"
        }
    }

    private companion object {
        const val TEXT_FLOOR = 4.5
        const val MIN_THEMES = 9
        val TOKEN_KINDS = listOf("keyword", "string", "comment", "number", "parameter", "punct")

        /**
         * Measured at the base, outside the four-alias fence: vendored text-muted on inset
         * (both panes), and #080's constant type-calc hue on dark (pipeline only).
         * Follow-up filing is held with GitHub writes; the handback carries the measured table.
         * Floors allow only the report's rounding margin; new/worse misses and stale rows fail.
         */
        val KNOWN_MISSES =
            mapOf(
                "light|light|pipeline|comment" to 4.27,
                "forest|light|pipeline|comment" to 3.98,
                "healthcare|light|pipeline|comment" to 3.97,
                "saas|light|pipeline|comment" to 4.39,
                "light|light|template|comment" to 4.27,
                "forest|light|template|comment" to 3.98,
                "healthcare|light|template|comment" to 3.97,
                "saas|light|template|comment" to 4.39,
                "dark|light|pipeline|parameter" to 3.28,
                "auto|dark|pipeline|parameter" to 3.28,
            )

        val SEED =
            """
            async () => {
              const csrf = decodeURIComponent(document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/)[1]);
              const call = async (path, body, hash) => {
                const headers = { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf };
                if (hash) headers['If-Match'] = hash;
                const res = await fetch(path, { method: 'POST', headers, body: JSON.stringify(body) });
                const text = await res.text();
                if (!res.ok) throw new Error(path + ': ' + res.status + ' ' + text);
                return JSON.parse(text).data;
              };
              const tpl = await call('/api/v1/templates', { id: 'test/sql634', type: 'sql', dialect: 'H2',
                display_name: 'SQL contrast', description: '634 contrast fixture', imports: [], body: "SELECT 'literal', 42, :parameter /* comment */;" });
              await call('/api/v1/templates/release', { name: 'test/sql634' }, tpl.body_hash);
              const pipe = await call('/api/v1/pipelines', { name: 'test/sql634', display_name: 'SQL contrast',
                parameters: { parameter: { type: 'INTEGER', required: false, default: 42 } },
                nodes: [{ id: 'sql_node', type: 'DQL', source: 'tempdb', template: { id: 'test/sql634', version: 1 }, depends_on: [] }] });
              return pipe.id;
            }
            """.trimIndent()

        val SWAP_THEME =
            """
            (t) => new Promise((resolve, reject) => {
              const old = document.getElementById('theme-link');
              const link = document.createElement('link');
              link.rel = 'stylesheet';
              link.onload = () => { old.remove(); link.id = 'theme-link'; resolve(true); };
              link.onerror = () => reject(new Error('theme ' + t + ' did not load'));
              link.href = '/vendor/design-system/themes/' + t + '.css';
              old.insertAdjacentElement('afterend', link);
            })
            """.trimIndent()

        val HELPERS =
            """
            const settle = async el => {
              const running = () => el.getAnimations().filter(a => a instanceof CSSTransition && a.playState === 'running');
              const deadline = performance.now() + 3000;
              while (running().length && performance.now() < deadline) await new Promise(r => setTimeout(r, 25));
              if (running().length) throw new Error('transition still running on ' + el.className);
            };
            const canvas = document.createElement('canvas');
            canvas.width = canvas.height = 1;
            const ctx = canvas.getContext('2d', { willReadFrequently: true });
            const rgba = c => { ctx.clearRect(0, 0, 1, 1); ctx.fillStyle = '#000'; ctx.fillStyle = c;
              ctx.fillRect(0, 0, 1, 1); const d = ctx.getImageData(0, 0, 1, 1).data; return [d[0], d[1], d[2], d[3] / 255]; };
            const css = a => 'rgb(' + a[0] + ', ' + a[1] + ', ' + a[2] + ')';
            const paint = el => {
              const layers = [];
              for (let node = el; node; node = node.parentElement) {
                const bg = getComputedStyle(node).backgroundColor;
                const c = rgba(bg);
                if (c[3] > 0) layers.push(bg);
                if (c[3] === 1) break;
              }
              if (!layers.length || rgba(layers[layers.length - 1])[3] !== 1) throw new Error('no opaque ancestor behind ' + el.className);
              ctx.clearRect(0, 0, 1, 1);
              for (let i = layers.length - 1; i >= 0; i--) { ctx.fillStyle = layers[i]; ctx.fillRect(0, 0, 1, 1); }
              const d = ctx.getImageData(0, 0, 1, 1).data;
              return [d[0], d[1], d[2], 1];
            };
            const lum = c => { const f = v => { const s = v / 255;
                return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); };
              return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2]); };
            const ratio = (a, b) => { const l1 = lum(a), l2 = lum(b);
              return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05); };
            const pair = (role, surface, el) => { const t = rgba(getComputedStyle(el).color), b = paint(el);
              if (t[3] !== 1) throw new Error(surface + ' text is translucent');
              return { role, surface, ratio: ratio(t, b), text: css(t), background: css(b) }; };
            """.trimIndent()

        val MEASURE =
            """
            async pane => {
              $HELPERS
              const root = document.querySelector(pane === 'pipeline' ? '#pe-node-sql' : '#previewPane');
              if (!root) throw new Error('missing pane ' + pane);
              if (pane === 'pipeline') {
                const reference = document.createElement('div');
                reference.className = 'u-backdrop u-backdrop-hidden';
                document.body.appendChild(reference);
                const modal = document.querySelector('.pe-modal-backdrop');
                if (!modal) throw new Error('missing error-modal scrim');
                const actual = rgba(getComputedStyle(modal).backgroundColor);
                const expected = rgba(getComputedStyle(reference).backgroundColor);
                reference.remove();
                if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new Error('error-modal scrim differs from house backdrop');
              }
              await settle(root);
              const pairs = [];
              for (const kind of ['keyword', 'string', 'comment', 'number', 'parameter', 'punct']) {
                const token = root.querySelector('.pe-sql-tok-' + kind);
                if (!token) throw new Error('missing ' + pane + ' token ' + kind + ': ' + root.outerHTML);
                const p = pair(kind, pane, token);
                if (kind === 'string' || kind === 'number') {
                  const role = pane === 'template' && kind === 'string' ? 'success' : 'warning';
                  const probe = document.createElement('span');
                  probe.style.color = 'var(--app-' + role + '-ink)';
                  root.appendChild(probe);
                  p.ink = css(rgba(getComputedStyle(probe).color));
                  probe.remove();
                }
                pairs.push(p);
              }
              return pairs;
            }
            """.trimIndent()
    }
}
