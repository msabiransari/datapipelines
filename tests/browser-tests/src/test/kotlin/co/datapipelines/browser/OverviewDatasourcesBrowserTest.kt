package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #583 — the Datasources overview in real Chromium: the rail's Datasources item lands on
 * `/overview/datasources` with the workspace's numbers — the count tiles, the connection-health
 * and by-dialect bars (the dialect bar links the filtered catalog), and Recently added to this
 * workspace in grant order — and the resource browser STAYS closed: the page is an ordinary
 * section page, not a tree family. Copy prompt is the shared overview.mjs path over the
 * datasources-specific starter text.
 *
 * Seeding walks the register modal (the same shared-rules + save boundary as REST, ui-screens
 * §4.5) with H2 in-memory URLs — the bundled driver, no external database — and probes two of
 * the rows through the real connection-test endpoint so the health buckets split.
 */
class OverviewDatasourcesBrowserTest : RestTreeBrowserFixture() {
    private fun registerDatasource(
        name: String,
        jdbcUrl: String,
    ) {
        page.navigate("$baseUrl/datasources")
        page.click("text=Register Datasource")
        page.fill("#register-modal input[name=name]", name)
        page.selectOption("#register-modal select[name=dialect]", "H2")
        page.fill("#register-modal input[name=jdbcUrl]", jdbcUrl)
        page.fill("#register-modal input[name=username]", "sa")
        page.fill("#register-modal input[name=password]", "sa")
        // The register is an ASYNC htmx post — the click runs inside the wait; the 200's OOB
        // list swap is the release signal, never a sleep.
        page.waitForResponse("**/partials/datasources") {
            page.click("#register-modal button[type=submit]")
        }
        page
            .locator(
                "td",
                com.microsoft.playwright.Page
                    .LocatorOptions()
                    .setHasText(name),
            ).first()
            .waitFor()
    }

    /** The row's first button is the connection probe (ui-screens §5.1); the outcome lands in the buckets. */
    private fun probe(name: String) {
        val row =
            page
                .locator(
                    "tr",
                    com.microsoft.playwright.Page
                        .LocatorOptions()
                        .setHasText(name),
                ).first()
        page.waitForResponse("**/partials/datasources/*/test") {
            row.locator("button").first().click()
        }
        // The response's handler commits the outcome synchronously; the overview read below
        // (a fresh navigation) is the assertion, so no toast class is assumed here.
    }

    private fun openOverview() {
        page.navigate("$baseUrl/dashboard")
        page.click(".app-nav-link[data-nav-section='/datasources']")
        page.waitForURL("**/overview/datasources")
        page.waitForSelector(".app-overview[data-overview='datasources']")
    }

    private fun count(key: String): String = page.locator("[data-count='$key'] .app-overview-count-v").innerText().trim()

    @Test
    fun `the Datasources item lands on the overview with the workspace's numbers and no browser`() {
        startTrace()
        readyTree()
        val ok = "ovl-ok-" + generatedPassword("d").take(8).lowercase()
        val failed = "ovl-failed-" + generatedPassword("d").take(8).lowercase()
        val untested1 = "ovl-untested-" + generatedPassword("d").take(8).lowercase()
        val untested2 = "ovl-newest-" + generatedPassword("d").take(8).lowercase()

        registerDatasource(ok, "jdbc:h2:mem:${ok.replace("-", "_")}")
        registerDatasource(failed, "jdbc:h2:mem:never_created;IFEXISTS=TRUE")
        registerDatasource(untested1, "jdbc:h2:mem:${untested1.replace("-", "_")}")
        registerDatasource(untested2, "jdbc:h2:mem:${untested2.replace("-", "_")}")
        probe(ok)
        probe(failed)

        openOverview()
        page.locator(".app-nav-link.active").getAttribute("data-nav-section") shouldBe "/datasources"
        page.locator("#app-crumbs .app-crumb-page").innerText().trim() shouldBe "Datasources"
        page.locator("h1").innerText().trim() shouldBe "Datasources"
        // Not a browserFamily: the browser column and its opener stay hidden on the overview.
        page.locator("#app-browser").isHidden shouldBe true
        page.locator("#browser-open").isHidden shouldBe true

        count("datasources") shouldBe "4"
        // The health numbers are the CHART's bars (the page has one count tile), read as their values.
        page.locator("[data-bar='ok'] .app-overview-bar-v").innerText() shouldBe "1"
        page.locator("[data-bar='failed'] .app-overview-bar-v").innerText() shouldBe "1"
        page.locator("[data-bar='never'] .app-overview-bar-v").innerText() shouldBe "2"
        // Bars are shares of the tallest value (2): both health buckets at 1 sit at 50.0.
        page.locator("[data-bar='ok'] rect").getAttribute("width") shouldBe "50.0"
        page.locator("[data-bar='failed'] rect").getAttribute("width") shouldBe "50.0"
        page.locator("[data-bar='never'] rect").getAttribute("width") shouldBe "100.0"
        page.locator("[data-chart='dialect'] [data-bar='H2'] rect").getAttribute("width") shouldBe "100.0"

        // Recently added is grant order, newest first, each entry linking the catalog row —
        // and no lifecycle badge: datasources carry no draft/release lifecycle.
        page.locator("[data-overview-recent] li a").allInnerTexts() shouldBe
            listOf(untested2, untested1, failed, ok)
        page.locator("[data-overview-recent] .ds-badge").count() shouldBe 0
        page
            .locator("[data-overview-recent] li a")
            .first()
            .getAttribute("href") shouldBe "/datasources/$untested2"

        // The dialect bar is a link: it filters the catalog to the workspace's H2 rows.
        page.locator("[data-chart='dialect'] a[href='/datasources?dialect=H2']").click()
        page.waitForURL("**/datasources?dialect=H2")
        page.waitForSelector("#ds-filter-dialect")
        page.locator("#ds-filter-dialect").inputValue() shouldBe "H2"
        listOf(ok, failed, untested1, untested2).forEach { name ->
            page.locator("main").innerText() shouldContain name
        }
    }

    @Test
    fun `Copy prompt copies the datasources prompt, says Copied and makes no request`() {
        startTrace()
        readyTree()
        openOverview()
        val prompt = page.locator("#overview-prompt")
        prompt.fill(prompt.inputValue().replace("[name]", "example"))
        page.evaluate(
            """() => {
              window.__copied = null;
              Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {
                writeText: (text) => Promise.resolve().then(() => { window.__copied = text; }),
              }});
            }""",
        )
        val requests = mutableListOf<String>()
        page.onRequest { requests += "${it.method()} ${it.url()}" }

        page.click("[data-overview-copy]")
        page.waitForFunction("() => window.__copied !== null")
        page.locator("[data-overview-copy]").innerText().trim() shouldBe "Copied"
        (page.evaluate("() => window.__copied") as String) shouldContain "example"
        requests shouldBe emptyList()
    }
}
