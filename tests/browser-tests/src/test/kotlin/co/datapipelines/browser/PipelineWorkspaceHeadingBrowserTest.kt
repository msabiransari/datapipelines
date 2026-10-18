package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * #607 (#558 slice 1) — the pipeline workspace's shared resource heading, MEASURED in the browser
 * (application-shell record §9: "folder/context line, wrapping title, version/status/action area,
 * tabs, active workspace"; §4/§5 for the 24px title, 12px support copy and the phone rules).
 *
 * Desktop: `.pe-topbar` is the heading BLOCK — `.app-page-h` holding the title column (the context
 * line, `h1.ds-headline.pe-crumb-name`, `.pe-version-line`) beside `.app-page-actions` — then the tab
 * strip, then the panes. The heading's top edge and the title's left edge are read against the
 * Pipelines overview's `.app-page-h` at the same viewport: the record's "align the main content's
 * top edge and inset consistently between families".
 *
 * Phone (760px and below): the band IS the page (owner ruling 2026-10-18, option (a)) — `.pe-root`
 * is not laid out, the band's controls are reachable and unclipped at 390 and 320, and a graph first
 * built in the hidden root is fitted once the viewport widens without a reload.
 *
 * Each case collects its readings first and prints them (`607-measure|…` in the XML's system-out,
 * the handback's base/tip table), takes its screenshot, then asserts softly — a red names every
 * number that moved.
 */
class PipelineWorkspaceHeadingBrowserTest : BrowserSuite() {
    private fun loginReadyUser() {
        val user =
            seedLocalUser(
                uniqueEmail("wsh-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("wshws-" + generatedPassword("w").take(8).lowercase())
    }

    /** Four independent CALCULATOR nodes (PipelineWorkspaceLayoutBrowserTest's fixture); it lands as a draft. */
    private fun seedPipeline(name: String): Int =
        page.evaluate(
            """async (name) => {
              const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              const calc = (id) => ({
                id,
                type: 'CALCULATOR',
                kind: 'fiscal_quarter',
                context_key: 'q_' + id,
                inputs: { date: '${'$'}current_date', fiscal_start: '${'$'}org_fiscal_start_date' },
              });
              const res = await fetch('/api/v1/pipelines', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' },
                body: JSON.stringify({ name, display_name: name, nodes: ['a', 'b', 'c', 'd'].map(calc) }),
              });
              return res.status;
            }""",
            name,
        ) as Int

    private fun openWorkspaceFor(name: String) {
        page.navigate("$baseUrl/pipelines?q=$name")
        page.locator("#pipeline-list-wrapper a.tpl-result").first().click()
        page.waitForURL(PipelineWorkspaceUrl.PATTERN)
        page.locator(".pe-card").first().waitFor()
    }

    /** A ready author on a seeded draft whose leaf is [LONG_LEAF]; returns the workspace URL. */
    private fun seededWorkspace(): String {
        loginReadyUser()
        val name = "wsh/" + generatedPassword("f").take(8).lowercase() + "/" + LONG_LEAF
        seedPipeline(name) shouldBe 201
        page.setViewportSize(DESK_W, DESK_H)
        openWorkspaceFor(name)
        return page.url()
    }

    private val readings = linkedMapOf<String, Any?>()

    /** One element's computed type and box, or null when [selector] matches nothing. */
    @Suppress("UNCHECKED_CAST")
    private fun measure(selector: String): Map<String, Any?>? =
        page.evaluate(
            """(sel) => {
              const e = document.querySelector(sel);
              if (!e) return null;
              const cs = getComputedStyle(e);
              const r = e.getBoundingClientRect();
              return { tag: e.tagName, fontSize: parseFloat(cs.fontSize), lineHeight: parseFloat(cs.lineHeight) || null,
                       fontWeight: parseFloat(cs.fontWeight), whiteSpace: cs.whiteSpace, color: cs.color,
                       top: r.top, bottom: r.bottom, left: r.left, right: r.right, width: r.width, height: r.height,
                       overflowX: e.scrollWidth - e.clientWidth };
            }""",
            selector,
        ) as Map<String, Any?>?

    private fun read(
        label: String,
        selector: String,
        key: String,
    ): Any? = measure(selector)?.get(key).also { readings[label] = it }

    private fun num(label: String): Double = withClue(label) { (readings[label] as Number?).shouldNotBeNull().toDouble() }

    private fun near(
        label: String,
        expected: Double,
        tolerance: Double,
    ) = withClue(label) { num(label).shouldBeBetween(expected - tolerance, expected + tolerance, 0.0) }

    private fun printReadings(case: String) {
        readings.forEach { (label, value) -> println("607-measure|$case|$label|" + "$value".replace('\n', ' ')) }
    }

    private fun shot(name: String) {
        val dir = Paths.get("build", "browser-evidence")
        Files.createDirectories(dir)
        page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("607-$name.png")).setFullPage(false))
    }

    /**
     * The leaf elements inside the heading whose RENDERED text says "draft": the draft state is said
     * once (the chip), so the selector's viewed row carries no second "· draft".
     */
    @Suppress("UNCHECKED_CAST")
    private fun draftTexts(): List<String> =
        page.evaluate(
            """() => [...document.querySelectorAll('.pe-topbar *')]
                 .filter((e) => e.getClientRects().length > 0 && /draft/i.test(e.innerText || ''))
                 .filter((e) => ![...e.children].some((c) => /draft/i.test(c.innerText || '')))
                 .map((e) => e.className + ': ' + e.innerText.trim())""",
        ) as List<String>

    /** The author's verbs inside the heading, by name and count. */
    @Suppress("UNCHECKED_CAST")
    private fun verbs(): Map<String, Int> =
        (
            page.evaluate(
                """() => [...document.querySelectorAll('.pe-topbar [data-verb]')]
                     .reduce((m, e) => { m[e.dataset.verb] = (m[e.dataset.verb] || 0) + 1; return m; }, {})""",
            ) as Map<String, Any?>
        ).mapValues { (it.value as Number).toInt() }

    /** Reads the overview's heading anchors at the current viewport (the alignment's reference side). */
    private fun readOverviewAnchors(prefix: String) {
        page.navigate("$baseUrl/overview/pipelines")
        page.locator(".app-page-h h1").waitFor()
        read("$prefix.overview.headingTop", ".app-page-h", "top")
        read("$prefix.overview.titleLeft", ".app-page-h h1", "left")
        read("$prefix.overview.titleTop", ".app-page-h h1", "top")
    }

    private fun readEditorAnchors(prefix: String) {
        read("$prefix.editor.headingTop", ".pe-topbar", "top")
        read("$prefix.editor.titleLeft", ".pe-crumb-name", "left")
        read("$prefix.editor.titleTop", ".pe-crumb-name", "top")
    }

    private fun assertAligned(prefix: String) {
        near("$prefix.editor.headingTop", num("$prefix.overview.headingTop"), 1.0)
        near("$prefix.editor.titleLeft", num("$prefix.overview.titleLeft"), 1.0)
    }

    @Test
    fun `desktop 1512 - context line, wrapping 24px title, the draft said once, actions, then tabs, aligned with the overview`() {
        startTrace()
        seededWorkspace()

        read("title.tag", ".pe-crumb-name", "tag")
        read("title.fontSize", ".pe-crumb-name", "fontSize")
        read("title.lineHeight", ".pe-crumb-name", "lineHeight")
        read("title.fontWeight", ".pe-crumb-name", "fontWeight")
        read("title.height", ".pe-crumb-name", "height")
        read("title.overflowX", ".pe-crumb-name", "overflowX")
        read("heading.overflowX", ".pe-topbar", "overflowX")
        read("heading.bottom", ".pe-topbar", "bottom")
        read("crumbPath.fontSize", ".pe-crumb-path", "fontSize")
        read("crumbPath.color", ".pe-crumb-path", "color")
        read("back.fontSize", ".pe-back", "fontSize")
        read("tabs.top", ".pe-tabs", "top")
        read("tabs.left", ".pe-tab", "left")
        read("canvas.height", "#cy-canvas", "height")
        readings["draftTexts"] = draftTexts()
        readings["verbs"] = verbs()
        readEditorAnchors("1512")
        shot("desktop-1512")
        readOverviewAnchors("1512")
        shot("overview-1512")
        printReadings("desktop-1512")

        assertSoftly {
            withClue("title.tag") { readings["title.tag"] shouldBe "H1" }
            near("title.fontSize", 24.0, 0.05)
            near("title.lineHeight", 32.4, 0.5)
            near("title.fontWeight", 600.0, 0.5)
            // A 60-character leaf wraps beside the actions instead of being clipped.
            withClue("title.height wraps") { num("title.height") shouldBeGreaterThanOrEqual 2 * num("title.lineHeight") - 0.5 }
            withClue("title.overflowX") { num("title.overflowX") shouldBeLessThanOrEqual 0.0 }
            withClue("heading.overflowX") { num("heading.overflowX") shouldBeLessThanOrEqual 0.0 }
            near("crumbPath.fontSize", 12.0, 0.05)
            withClue("draftTexts") { (readings["draftTexts"] as List<*>).size shouldBe 1 }
            withClue("tabs below the heading") { num("tabs.top") shouldBeGreaterThanOrEqual num("heading.bottom") - 0.5 }
            withClue("verbs") { readings["verbs"] shouldBe AUTHOR_DRAFT_VERBS }
            assertAligned("1512")
        }
    }

    @Test
    fun `desktop 1512 with the menu compact - the heading still aligns with the overview`() {
        startTrace()
        val url = seededWorkspace()
        page.locator("#rail-collapse").click()
        page.locator("html.rail-collapsed").waitFor()
        page.navigate(url)
        page.locator(".pe-card").first().waitFor()

        readEditorAnchors("compact")
        read("compact.title.height", ".pe-crumb-name", "height")
        read("compact.heading.overflowX", ".pe-topbar", "overflowX")
        shot("desktop-1512-compact")
        readOverviewAnchors("compact")
        printReadings("compact-1512")

        assertSoftly {
            withClue("compact.heading.overflowX") { num("compact.heading.overflowX") shouldBeLessThanOrEqual 0.0 }
            assertAligned("compact")
        }
    }

    /**
     * Between the phone edge and the wide desktop the content column is narrower than the title
     * and the ~480px action group side by side: the actions wrap under the title instead of
     * overflowing (record §4: no page-level horizontal scrollbar), at the record's checked widths.
     */
    @Test
    fun `from 1280 down to 761 the actions wrap under the title and nothing overflows`() {
        startTrace()
        val url = seededWorkspace()
        val failures = mutableListOf<String>()
        listOf(1280, 1101, 1100, 1024, 768, 761).forEach { w ->
            page.setViewportSize(w, DESK_H)
            page.navigate(url)
            page.locator(".pe-card").first().waitFor()

            @Suppress("UNCHECKED_CAST")
            val r =
                page.evaluate(
                    """() => {
                      const main = document.querySelector('.pe-topbar').getBoundingClientRect();
                      const verbs = [...document.querySelectorAll('.pe-topbar [data-verb], .pe-topbar .pe-status')]
                        .filter((e) => e.getClientRects().length > 0)
                        .filter((e) => { const b = e.getBoundingClientRect(); return b.left < main.left - 0.5 || b.right > main.right + 0.5; })
                        .map((e) => e.dataset.verb || e.className);
                      const h1 = document.querySelector('.pe-crumb-name');
                      return { doc: document.documentElement.scrollWidth - document.documentElement.clientWidth,
                               heading: document.querySelector('.pe-topbar').scrollWidth - document.querySelector('.pe-topbar').clientWidth,
                               title: h1.scrollWidth - h1.clientWidth, outside: verbs };
                    }""",
                ) as Map<String, Any?>
            readings["$w"] = r
            val overflows = listOf("doc", "heading", "title").any { (r[it] as Number).toDouble() > 0 }
            if (overflows || (r["outside"] as List<*>).isNotEmpty()) failures += "$w: $r"
            if (w == 1024) shot("desktop-1024")
        }
        printReadings("widths")
        failures.shouldBeEmpty()
    }

    /** At a phone viewport the band is the page: nothing of the workspace is laid out beneath it. */
    private fun bandIsThePage(
        width: Int,
        height: Int,
    ) {
        startTrace()
        val url = seededWorkspace()
        page.setViewportSize(width, height)
        page.navigate(url)
        val band = page.locator(".app-wide-screen-note").first()
        band.waitFor()
        val link = band.locator("a:has-text('Open in Pipelines')")
        link.scrollIntoViewIfNeeded()

        @Suppress("UNCHECKED_CAST")
        val phone =
            page.evaluate(
                """() => {
                  const box = (e) => { const r = e.getBoundingClientRect(); return r.width > 0 || r.height > 0; };
                  const root = document.querySelector('.pe-root');
                  const rr = root.getBoundingClientRect();
                  const band = document.querySelector('.app-wide-screen-note');
                  const a = [...band.querySelectorAll('a')].find((e) => /Open in Pipelines/.test(e.innerText));
                  const ar = a.getBoundingClientRect();
                  const hit = document.elementFromPoint(ar.left + ar.width / 2, ar.top + ar.height / 2);
                  return {
                    rootW: rr.width, rootH: rr.height, rootOffsetParentNull: root.offsetParent === null,
                    laidOut: ['.pe-topbar', '.pe-tab', '.pe-dock'].filter((s) => [...document.querySelectorAll(s)].some(box)),
                    linkLeft: ar.left, linkRight: ar.right, linkHit: hit === a || a.contains(hit),
                    bandOverflowX: band.scrollWidth - band.clientWidth,
                    docOverflowX: document.documentElement.scrollWidth - document.documentElement.clientWidth,
                    bandText: band.innerText,
                  };
                }""",
            ) as Map<String, Any?>
        readings.putAll(phone.mapKeys { "$width.${it.key}" })
        shot("phone-$width")
        printReadings("phone-$width")

        assertSoftly {
            withClue("band text") {
                val text = phone["bandText"] as String
                text shouldContain LONG_LEAF
                text shouldContain "v1"
                text shouldContain "Open in Pipelines"
            }
            near("$width.rootW", 0.0, 0.0)
            near("$width.rootH", 0.0, 0.0)
            withClue("rootOffsetParentNull") { phone["rootOffsetParentNull"] shouldBe true }
            withClue("laidOut") { (phone["laidOut"] as List<*>).shouldBeEmpty() }
            withClue("linkLeft") { num("$width.linkLeft") shouldBeGreaterThanOrEqual 0.0 }
            withClue("linkRight") { num("$width.linkRight") shouldBeLessThanOrEqual width.toDouble() }
            withClue("linkHit") { phone["linkHit"] shouldBe true }
            withClue("bandOverflowX") { num("$width.bandOverflowX") shouldBeLessThanOrEqual 0.0 }
            near("$width.docOverflowX", 0.0, 0.0)
        }
    }

    @Test
    fun `phone 390 - the band is the page and its controls are reachable`() {
        bandIsThePage(390, 844)
    }

    @Test
    fun `phone 320 - the band is the page and its controls are reachable`() {
        bandIsThePage(320, 568)
    }

    @Test
    fun `a phone-first load rotated to desktop without a reload shows a fitted graph`() {
        startTrace()
        val url = seededWorkspace()
        page.setViewportSize(390, 844)
        page.navigate(url)
        page.locator(".app-wide-screen-note").first().waitFor()
        page.waitForSelector(".pe-card", Page.WaitForSelectorOptions().setState(WaitForSelectorState.ATTACHED))

        page.setViewportSize(ROTATED_W, ROTATED_H)
        page.waitForFunction("() => document.querySelector('.pe-stage').getBoundingClientRect().height > 0")
        // The stage ResizeObserver's re-fit is a canvas tween (PipelineEditorFitBrowserTest's 600ms).
        page.waitForTimeout(600.0)

        @Suppress("UNCHECKED_CAST")
        val graph =
            page.evaluate(
                """() => {
                  const c = document.getElementById('cy-canvas').getBoundingClientRect();
                  const cards = [...document.querySelectorAll('.pe-card')];
                  const outside = cards.map((el) => ({ id: el.getAttribute('data-node-id'), r: el.getBoundingClientRect() }))
                    .filter(({ r }) => r.width === 0 || r.top < c.top - 1 || r.bottom > c.bottom + 1
                                    || r.left < c.left - 1 || r.right > c.right + 1)
                    .map(({ id }) => id);
                  return { cards: cards.length, firstCardW: cards.length ? cards[0].getBoundingClientRect().width : 0,
                           canvasW: c.width, canvasH: c.height, outside };
                }""",
            ) as Map<String, Any?>
        readings.putAll(graph.mapKeys { "rotate.${it.key}" })
        shot("rotate-$ROTATED_W")
        printReadings("rotate")

        assertSoftly {
            withClue("cards") { num("rotate.cards") shouldBeGreaterThan 0.0 }
            withClue("firstCardW") { num("rotate.firstCardW") shouldBeGreaterThan 0.0 }
            withClue("outside") { (graph["outside"] as List<*>).shouldBeEmpty() }
        }
    }

    private companion object {
        const val DESK_W = 1512
        const val DESK_H = 982
        const val ROTATED_W = 1440
        const val ROTATED_H = 900

        /** 60 characters with no space: it can only wrap if the title breaks anywhere. */
        val LONG_LEAF = "monthly_revenue_reconciliation_across_regions_and_channels_x".also { require(it.length == 60) }

        /** What the heading offers the author of a draft-only pipeline, at the base and at the tip. */
        val AUTHOR_DRAFT_VERBS =
            mapOf(
                "pipeline-release" to 1,
                "pipeline-purge-entity" to 1,
                "pipeline-execute" to 1,
                "execution-cancel" to 1,
            )
    }
}
