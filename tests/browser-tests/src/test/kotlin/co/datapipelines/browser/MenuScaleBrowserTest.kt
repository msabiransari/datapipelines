package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/** #605: computed menu metrics, token colours and compact/phone geometry from the real page. */
class MenuScaleBrowserTest : RestTreeBrowserFixture() {
    private val readings = linkedMapOf<String, Any?>()

    private fun collect(band: String) {
        val result =
            page.evaluate(
                """() => {
                  const row = document.querySelector('.app-nav-link[data-nav-section="/pipelines"]');
                  const rail = document.querySelector('#app-rail').getBoundingClientRect();
                  const box = row.getBoundingClientRect();
                  const icon = row.querySelector('svg').getBoundingClientRect();
                  const values = {};
                  const read = (name, selector) => {
                    const el = document.querySelector(selector), cs = getComputedStyle(el), r = el.getBoundingClientRect();
                    for (const key of ['fontSize','fontWeight','lineHeight','letterSpacing','paddingTop','paddingLeft',
                                       'paddingRight','paddingBottom','gap','borderTopLeftRadius']) {
                      values[name + '.' + key] = parseFloat(cs[key]);
                    }
                    values[name + '.height'] = r.height; values[name + '.width'] = r.width;
                  };
                  read('row', '.app-nav-link[data-nav-section="/pipelines"]');
                  read('caption', '#app-nav-group-build');
                  read('brand', '.app-brand'); read('switcher', '.app-ws'); read('avatar', '.app-ws-avatar');
                  read('workspaceName', '.app-ws-text b'); read('badge', '.app-ws-role');
                  read('nav', '.app-nav'); read('collapse', '.app-rail-collapse');
                  values.labelX = row.querySelector('.app-rail-label').getBoundingClientRect().left - rail.left;
                  values.railWidth = rail.width; values.iconCentre = icon.x + icon.width/2 - (rail.x + rail.width/2);
                  values.captionCount = document.querySelectorAll('.app-workspace-label').length;
                  values.selected = row.matches('[aria-current="page"]');
                  const probe = document.createElement('span'); document.body.append(probe);
                  const colour = token => { probe.style.color = 'var(' + token + ')'; return getComputedStyle(probe).color; };
                  values.labelColour = getComputedStyle(row).color; values.primary = colour('--text-primary');
                  values.iconColour = getComputedStyle(row.querySelector('svg')).color; values.link = colour('--text-link');
                  values.barColour = getComputedStyle(row).borderLeftColor; values.focus = colour('--border-focus');
                  values.barWidth = parseFloat(getComputedStyle(row).borderLeftWidth);
                  probe.remove(); return values;
                }""",
            ) as Map<*, *>
        result.forEach { (key, value) -> readings["$band.$key"] = value }
    }

    private fun near(
        key: String,
        expected: Double,
        tolerance: Double = 0.05,
    ) {
        withClue(key) { (readings[key] as Number).toDouble().shouldBeBetween(expected - tolerance, expected + tolerance, 0.0) }
    }

    private fun floor(
        key: String,
        minimum: Double,
    ) {
        withClue(key) { (readings[key] as Number).toDouble() shouldBeGreaterThanOrEqual minimum }
    }

    private fun shot(name: String) {
        val dir = Paths.get("build", "browser-evidence")
        Files.createDirectories(dir)
        page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("605-$name.png")))
    }

    @Test
    @Suppress("LongMethod") // Collect all three viewports before assertions, preserving the whole baseline on red.
    fun `menu scale follows the record in expanded compact and phone layouts`() {
        startTrace()
        readyTree()
        page.setViewportSize(DESKTOP_W, DESKTOP_H)
        page.navigate("$baseUrl/pipelines")
        page.waitForSelector(".app-nav-link[aria-current='page']")
        page.evaluate("() => document.fonts.ready")
        ensureTheme("dark")
        collect("expanded")
        shot("expanded-1512")
        page.click("#rail-collapse")
        page.waitForFunction("() => document.getElementById('app-rail').getBoundingClientRect().width === 64")
        collect("compact")
        shot("compact-1512")
        page.setViewportSize(PHONE_W, PHONE_H)
        page.click("#rail-open")
        page.waitForFunction("() => document.getElementById('app-rail').getBoundingClientRect().left === 0")
        collect("phone")
        shot("drawer-390")
        readings.forEach { (key, value) -> println("605-measure|$key|$value") }
        assertSoftly {
            near("expanded.row.fontSize", UI)
            near("expanded.row.fontWeight", NORMAL)
            near("expanded.row.lineHeight", UI_LINE)
            floor("expanded.row.height", CONTROL)
            near("expanded.row.paddingTop", PAD_Y)
            near("expanded.row.paddingLeft", PAD_X)
            near("expanded.row.gap", PAD_X)
            near("expanded.row.borderTopLeftRadius", RADIUS)
            near("expanded.nav.paddingLeft", PAD_X)
            near("expanded.labelX", LABEL_X)
            near("expanded.caption.fontSize", META)
            near("expanded.caption.letterSpacing", TRACKING)
            near("expanded.caption.fontWeight", EMPHASIS)
            near("expanded.caption.paddingTop", GROUP_SPACE)
            near("expanded.brand.fontSize", WORDMARK)
            near("expanded.brand.fontWeight", EMPHASIS)
            near("expanded.brand.letterSpacing", WORDMARK_TRACKING)
            near("expanded.switcher.paddingTop", PAD_X)
            near("expanded.switcher.paddingLeft", PAD_X)
            near("expanded.switcher.gap", PAD_X)
            near("expanded.switcher.borderTopLeftRadius", RADIUS)
            near("expanded.avatar.width", AVATAR)
            near("expanded.avatar.height", AVATAR)
            near("expanded.workspaceName.fontSize", UI)
            near("expanded.badge.fontSize", BADGE)
            near("expanded.captionCount", 0.0)
            near("expanded.barWidth", BAR)
            readings["expanded.selected"] shouldBe true
            readings["expanded.labelColour"] shouldBe readings["expanded.primary"]
            readings["expanded.iconColour"] shouldBe readings["expanded.link"]
            readings["expanded.barColour"] shouldBe readings["expanded.focus"]
            near("compact.railWidth", COMPACT)
            floor("compact.row.height", CONTROL)
            near("compact.iconCentre", 0.0, CENTRE_TOLERANCE)
            floor("phone.row.height", CONTROL)
            near("phone.row.fontSize", UI)
            near("phone.labelX", LABEL_X)
        }
    }

    @Test
    fun `selected label and icon meet contrast floors across every bundled theme`() {
        readyTree()
        page.navigate("$baseUrl/pipelines")
        page.waitForSelector(".app-nav-link[aria-current='page']")
        val results =
            page.evaluate(
                """async () => {
                  const themes = ['auto','dark','forest','healthcare','light','minimal','ocean','professional','saas'];
                  const row = document.querySelector('.app-nav-link[aria-current="page"]');
                  const link = document.getElementById('theme-link');
                  const luminance = colour => {
                    const c = colour.match(/[\d.]+/g).slice(0,3).map(Number).map(v => v/255)
                      .map(v => v <= 0.03928 ? v/12.92 : Math.pow((v+0.055)/1.055,2.4));
                    return c[0]*0.2126 + c[1]*0.7152 + c[2]*0.0722;
                  };
                  const contrast = (a,b) => (Math.max(luminance(a),luminance(b))+0.05) /
                    (Math.min(luminance(a),luminance(b))+0.05);
                  const results = [];
                  for (const theme of themes) {
                    await new Promise((resolve,reject) => {
                      link.onload = resolve; link.onerror = reject;
                      link.href = '/vendor/design-system/themes/' + theme + '.css';
                    });
                    document.documentElement.dataset.theme = theme;
                    // A transition's interpolated colour is not the theme's final ink.
                    await Promise.all(row.getAnimations().map(animation => animation.finished));
                    const cs = getComputedStyle(row), icon = getComputedStyle(row.querySelector('svg'));
                    results.push({theme, label: contrast(cs.color, cs.backgroundColor),
                      icon: contrast(icon.color, cs.backgroundColor)});
                  }
                  link.onload = null; link.onerror = null;
                  return results;
                }""",
            ) as List<*>
        results.size shouldBe THEME_COUNT
        assertSoftly {
            results.forEach { item ->
                val result = item as Map<*, *>
                println("605-contrast|$result")
                withClue("${result["theme"]} selected label") {
                    (result["label"] as Number).toDouble() shouldBeGreaterThanOrEqual TEXT_CONTRAST
                }
                withClue("${result["theme"]} selected icon") {
                    (result["icon"] as Number).toDouble() shouldBeGreaterThanOrEqual ICON_CONTRAST
                }
            }
        }
    }

    private companion object {
        const val THEME_COUNT = 9
        const val TEXT_CONTRAST = 4.5
        const val ICON_CONTRAST = 3.0
        const val DESKTOP_W = 1512
        const val DESKTOP_H = 982
        const val PHONE_W = 390
        const val PHONE_H = 844
        const val UI = 13.0
        const val UI_LINE = 19.5
        const val NORMAL = 400.0
        const val CONTROL = 36.0
        const val PAD_Y = 8.0
        const val PAD_X = 12.0
        const val RADIUS = 8.0
        const val LABEL_X = 55.0 // 12 gutter + 3 indicator + 12 padding + 16 icon + 12 gap.
        const val META = 10.0
        const val TRACKING = 1.2
        const val EMPHASIS = 650.0
        const val GROUP_SPACE = 24.0
        const val WORDMARK = 14.0
        const val WORDMARK_TRACKING = -0.35 // Existing --tracking-tight at 14px; record rounds to -0.4px.
        const val AVATAR = 32.0
        const val BADGE = 11.0
        const val BAR = 3.0
        const val COMPACT = 64.0
        const val CENTRE_TOLERANCE = 1.0
    }
}
