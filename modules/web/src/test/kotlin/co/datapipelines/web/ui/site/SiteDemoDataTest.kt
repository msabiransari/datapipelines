package co.datapipelines.web.ui.site

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication

/**
 * The demo-data model (116 §C): the three vendored manifests parse into the typed families,
 * and the null-licence branch behaves — a family whose manifest carries no verified stamp
 * renders the visible `not yet verified` status and makes NO licence claim anywhere in its
 * sections. The doctored manifest is fed through the model's own parser IN MEMORY, not
 * written to disk: on disk it would be a lie in the repo, and the branch it exercises is
 * exactly "the next manifest ships before its licence verification".
 */
class SiteDemoDataTest {
    @Test
    fun `the three vendored manifests parse into the typed families`() {
        val model = SiteDemoData(javaClass.classLoader)

        val nyc = model.family("nyc")
        withClue("nyc version") { nyc.version shouldBe "v7" }
        withClue("nyc dataset") { nyc.dataset shouldBe "mobility" }
        withClue("nyc tables") {
            nyc.tables.map { it.table } shouldBe
                listOf(
                    "trips",
                    "trips_daily",
                    "trips_monthly",
                    "stations",
                    "observations",
                    "zones",
                    "rate_codes",
                    "payment_types",
                    "calendar",
                )
        }
        withClue("nyc licence stamp") { nyc.licenseVerified shouldBe "2026-09-02" }

        val trade = model.family("trade")
        withClue("trade version") { trade.version shouldBe "v4" }
        withClue("trade dataset (the trade manifest says family, not dataset)") { trade.dataset shouldBe "trade" }
        withClue("trade table count") { trade.tables.size shouldBe 11 }
        withClue("trade window (this manifest carries it as data)") { trade.window shouldBe "2023-01 → 2024-12" }
        withClue("trade licence stamp") { trade.licenseVerified shouldBe "2026-09-04" }
        // The trade shape: rows count lives under `rows`, and the census notice came through.
        withClue("trade census notice") {
            trade.provenance.firstNotNullOf { it.notice } shouldBe
                "This product uses the Census Bureau Data API but is not endorsed or certified by the Census Bureau."
        }

        val lake = model.family("lake")
        withClue("lake version") { lake.version shouldBe "v1" }
        withClue("lake tables") { lake.tables.size shouldBe 5 }
        withClue("lake licence stamp") { lake.licenseVerified shouldBe "2026-09-07" }
        // The lake shape: tables are named, not `table`d, and carry format and bytes.
        val trips = lake.tables.first()
        withClue("lake table name shape") { trips.table shouldBe "hvfhv_trips" }
        withClue("lake table format") { trips.format shouldBe "parquet" }
        withClue("lake formatted count") { trips.rowCountFormatted shouldBe "471,851,707" }
        // The FHV accuracy sentence is the publisher_notice the licence section quotes.
        withClue("lake FHV accuracy sentence") {
            lake.provenance.firstNotNullOf { it.publisherNotice } shouldBe
                "These records are generated from the FHV Trip Record submissions made by bases, " +
                "so we cannot guarantee or confirm their accuracy or completeness."
        }
    }

    @Test
    fun `a manifest without a licence stamp renders unverified, with no licence claim`() {
        val model = SiteDemoData(javaClass.classLoader)
        val nyc = model.family("nyc")
        // The doctored family: the same manifest semantics, the stamp removed — the state a
        // pre-verification repack would publish. `licenseVerified` derives from the
        // provenance rows, so stripping the stamps is the whole doctoring.
        val unverified = nyc.copy(provenance = nyc.provenance.map { it.copy(licenseVerified = null) })
        withClue("the doctored family must be unverified for this test to mean anything") {
            unverified.licenseVerified.shouldBeNull()
        }

        val html = renderWith(unverified)

        // Assertions are scoped to the family's own section: the other two families'
        // sections legitimately still carry their (real) licence statements.
        val nycSection = html.substringAfter("""aria-labelledby="dd-nyc"""").substringBefore("</section>")
        withClue("the unverified status renders") { nycSection shouldContain "not yet verified — no licence claim" }
        withClue("the family's licence strings render nowhere in its section") {
            nycSection shouldNotContain "freely usable"
            nycSection shouldNotContain "US Government work"
        }
        withClue("the attribution quotes make no claim") { nycSection shouldNotContain "There are no restrictions on the use of Open Data" }
        withClue("no verified stamp is claimed for it") { nycSection shouldNotContain "Licence verified 2026-09-02" }
        // The table itself still renders — the data facts are not the licence claim.
        withClue("the generated table still renders") { html shouldContain "4,897,311" }
    }

    /**
     * Processes the real template with [nyc] doctored — everything else exactly as
     * [SitePageRenderer]'s dispatch provides it, so the render differs from production in
     * the one field under test.
     */
    private fun renderWith(nyc: DemoFamily): String {
        val model = SiteDemoData(javaClass.classLoader)
        val families = listOf(nyc, model.family("trade"), model.family("lake"))
        val engine =
            SpringTemplateEngine().apply {
                setTemplateResolver(
                    ClassLoaderTemplateResolver().apply {
                        prefix = "templates/"
                        suffix = ".html"
                        characterEncoding = "UTF-8"
                    },
                )
            }
        val exchange =
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse())
        val context = WebContext(exchange)
        context.setVariable("pageTitle", SitePages.DEMO_DATA.title)
        context.setVariable("pageDescription", SitePages.DEMO_DATA.description)
        context.setVariable("canonicalUrl", SitePages.DEMO_DATA.canonical)
        context.setVariable("ogImage", SITE_ORIGIN + PublicPage.DEFAULT_OG_IMAGE)
        context.setVariable("currentSitePath", SitePages.DEMO_DATA.path)
        context.setVariable("toolCount", 34)
        context.setVariable("navPages", emptyList<SitePage>())
        context.setVariable("engines", emptyList<EngineFacts>())
        context.setVariable("demoFamilies", families)
        context.setVariable("demoNyc", nyc)
        context.setVariable("demoTrade", model.family("trade"))
        context.setVariable("demoLake", model.family("lake"))
        context.setVariable("faqEntries", emptyList<FaqEntry>())
        context.setVariable("faqJsonLd", "")
        return engine.process("site/demo-data", context)
    }
}
