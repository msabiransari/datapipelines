package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import java.nio.file.Paths

/**
 * #599, #647 — what the Pipelines and sibling object homes' browser cases share on the v2 frame:
 * the summary strip's and its foot's numbers, the activity columns and the release-history bins as the hidden tables state them, the
 * definitions dialog's round trip with focus returned to its button, and the evidence screenshots at 1512
 * and 390 in each theme, with an optional intermediate viewport for the Pipelines home.
 */
internal object OverviewHomeBrowserSupport {
    const val WIDE = 1512
    const val WIDE_H = 900
    const val PHONE = 390
    const val PHONE_H = 844
    const val WEEKS = 12

    /** The viewport height that shows the whole overview: its root's top, its scroll height and a margin. */
    private const val FULL_HEIGHT =
        "() => { const root = document.querySelector('[data-overview]');" +
            " return Math.ceil(root.getBoundingClientRect().top + root.scrollHeight + 48); }"

    /** Where the Pipelines and three sibling object homes' evidence screenshots land, named by family. */
    val SHOTS: Path = Paths.get("build", "reports", "599-object-family-homes")

    /** Each strip or foot cell's number, by its key (`total`, `live`, `in-development`, `actively-developed`, …). */
    fun metrics(
        page: Page,
        vararg expected: Pair<String, String>,
    ) {
        expected.forEach { (key, value) ->
            withClue(key) { page.locator("[data-count='$key'] .app-overview-count-v").innerText().trim() shouldBe value }
        }
    }

    /**
     * The twelve columns, with this week's (the last) [created] and [released] events in the activity table,
     * and each named maturity cell (`row:part`, e.g. `r0:draft-open`) in the release-history table.
     */
    fun columnsAndBins(
        page: Page,
        created: Int,
        released: Int,
        vararg bins: Pair<String, Int>,
    ) {
        withClue("twelve weeks, this week's events last") {
            page.locator("[data-column]").count() shouldBe WEEKS
            val week = page.locator("[data-chart-table='activity'] tbody tr:last-child")
            week.locator("td[data-part='created']").textContent().trim() shouldBe "$created"
            week.locator("td[data-part='released']").textContent().trim() shouldBe "$released"
        }
        bins.forEach { (cell, count) ->
            val (row, part) = cell.split(":")
            withClue("release history $cell") {
                page.locator("[data-chart-table='maturity'] tr[data-row='$row'] td[data-part='$part']").textContent().trim() shouldBe
                    "$count"
            }
        }
    }

    /** Opens the dialog through [selector] and waits until it is modal. */
    fun openDialog(
        page: Page,
        selector: String,
    ) {
        namedWait(page, "the overview dialog's open control ($selector)") { page.click(selector) }
        namedWait(page, "the overview dialog's opening (dialog[data-overview-dialog] open, via $selector)", ::documentFacts) {
            page.waitForFunction("() => document.querySelector('dialog[data-overview-dialog]').open")
        }
    }

    /** The foot's info button opens the definitions section; Escape closes the dialog and focus returns to the button. */
    fun definitionsRoundTrip(
        page: Page,
        total: Int,
    ) {
        openDialog(page, "[data-overview-foot] [data-open='definitions']")
        page.locator("[data-overview-dialog-title]").innerText().trim() shouldBe "How the summary is counted"
        page.locator("[data-dialog-section='definitions']").isVisible shouldBe true
        page.locator("[data-dialog-section='prompt']").isVisible shouldBe false
        page.locator("[data-dialog-section='definitions']").innerText() shouldContain "These four groups total $total."
        page.keyboard().press("Escape")
        namedWait(page, "the overview dialog's close on Escape (dialog[data-overview-dialog] not open)", ::documentFacts) {
            page.waitForFunction("() => !document.querySelector('dialog[data-overview-dialog]').open")
        }
        page.evaluate("() => document.activeElement?.getAttribute('data-open')") shouldBe "definitions"
    }

    /**
     * The evidence set for [family]: 1512 and 390, plus [additionalViewports], in each theme.
     * Leaves the light theme on and the page at 1512. Siblings keep their original two viewports.
     */
    fun screenshots(
        page: Page,
        family: String,
        additionalViewports: List<Pair<Int, Int>> = emptyList(),
    ) {
        SHOTS.toFile().mkdirs()
        listOf("light", "dark").forEach { mode ->
            themeOn(page, mode)
            (listOf(WIDE to WIDE_H) + additionalViewports + (PHONE to PHONE_H)).forEach { (width, height) ->
                page.setViewportSize(width, height)
                reloadSummary(page, "$width x $height, $mode")
                page.evaluate("() => document.fonts.ready")
                page.screenshot(Page.ScreenshotOptions().setPath(SHOTS.resolve("$family-$width-$mode.png")))
                // The shell scrolls an inner container, so an element shot paints only the viewport's band: the whole
                // page is a second shot with the viewport grown to the overview root's full height.
                val full = (page.evaluate(FULL_HEIGHT) as Number).toInt()
                page.setViewportSize(width, maxOf(height, full))
                awaitSummary(page, "$width x ${maxOf(height, full)}, $mode, grown to the full height")
                page.screenshot(Page.ScreenshotOptions().setPath(SHOTS.resolve("$family-$width-$mode-content.png")))
            }
        }
        themeOn(page, "light")
        page.setViewportSize(WIDE, WIDE_H)
        reloadSummary(page, "$WIDE x $WIDE_H, light, the closing state")
    }

    /** #831: the evidence loop's reload, then the summary's render, each named with the shot it serves. */
    private fun reloadSummary(
        page: Page,
        shot: String,
    ) {
        namedWait(page, "the overview page reload (${page.url()}, $shot)") { page.reload() }
        awaitSummary(page, shot)
    }

    private fun awaitSummary(
        page: Page,
        shot: String,
    ) {
        namedWait(page, "the overview summary's render ([data-overview-summary], $shot)", ::documentFacts) {
            page.waitForSelector("[data-overview-summary]")
        }
    }

    /** The shared theme switch: the stored theme wins over an emulated colour scheme, so flip the top bar's toggle. */
    private fun themeOn(
        page: Page,
        mode: String,
    ) {
        val wanted = "/themes/$mode.css"
        // #831: BrowserSuite.ensureTheme's four named steps (#825), the same text, on this copy.
        var current = ""
        namedWait(page, "the #theme-link href read (wanted $wanted)", ::documentFacts) {
            current = page.locator("#theme-link").first().getAttribute("href") ?: ""
        }
        if (current.contains(wanted)) return
        val swap = "wanted $wanted, current href $current"
        namedWait(page, "the theme PATCH response ($swap)", ::documentFacts) {
            page.waitForResponse("**/partials/profile/theme") { page.locator("#mode-toggle").click() }
        }
        namedWait(page, "the #theme-link swap ($swap)", ::documentFacts) {
            page.waitForFunction("() => document.getElementById('theme-link').getAttribute('href').includes('$wanted')")
        }
        namedWait(page, "the html[data-theme='$mode'] update ($swap)", ::documentFacts) {
            page.locator("html[data-theme='$mode']").waitFor()
        }
    }
}
