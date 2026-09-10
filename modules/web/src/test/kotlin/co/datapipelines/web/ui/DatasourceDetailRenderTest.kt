package co.datapipelines.web.ui

import co.datapipelines.application.datasources.LakeTable
import co.datapipelines.application.datasources.LakeTableFormat
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.datasources.visibleDialectProperties
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.ui.ExtendedModelMap
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import java.time.Instant
import java.util.UUID

/**
 * 098 §F — the datasource detail's page header, at the render.
 *
 * The defect, visible in the shipped `datasource-lake.png` (093 §8): the dialect badge, the
 * machine name and the description all lived inside ONE `<p class="app-page-sub">`, so the
 * screen read `LAKE sample-lake NYC TLC high-volume for-hire trips…` as a single wrapped
 * paragraph. Every other detail screen in the app puts the identity line and the prose in
 * separate elements (`executions/detail.html`: headline, then a `u-row u-wrap` of badge and
 * identifiers, then the facts).
 *
 * The assertion is STRUCTURAL, not cosmetic: the description must be the whole text of its own
 * element, which is exactly what a shared paragraph makes false. Falsification: put the
 * description `<span>` back inside the identity paragraph and every case below goes red.
 *
 * "For every dialect" is not decoration either. The route only serves LAKE today
 * (`DatasourceDetailUiController.visibleLake`), but the template renders
 * `${datasource.dialect.wire}` with no branch, so the header is dialect-agnostic by
 * construction — and this test is what keeps it that way if a later round gives another
 * dialect a detail page.
 */
class DatasourceDetailRenderTest {
    private val description = "NYC TLC high-volume for-hire trips, 2024, as Parquet on S3."

    private fun datasource(dialect: Dialect) =
        Datasource(
            name = "sample-lake",
            displayName = "Sample lake",
            description = description,
            dialect = dialect,
            jdbcUrl = "jdbc:duckdb:",
        )

    /**
     * 109 §B — the dialect a detail row RUNS with, through the REAL §5.6 projection the
     * controller puts in the model (`visibleDialectProperties`): what this render asserts is
     * what the operator sees, not a hand-built map.
     */
    private fun dialectPropertiesFor(datasource: Datasource): Map<String, Any?> =
        visibleDialectProperties(datasource.dialect, datasource.properties.dialect)

    private val tables =
        listOf(
            LakeTable(
                id = UUID.randomUUID(),
                datasourceId = "sample-lake",
                namespace = listOf("nyc", "mobility"),
                name = "hvfhv_zone_day",
                format = LakeTableFormat.PARQUET,
                location = "s3://datapipelines-co/sample-data/lake/v1/hvfhv_zone_day",
                partitionColumn = null,
                registeredBy = UUID.randomUUID(),
                registeredAt = Instant.parse("2026-09-07T12:00:00Z"),
            ),
        )

    private fun render(dialect: Dialect): String {
        val model = ExtendedModelMap()
        model.addAttribute("datasource", datasource(dialect))
        model.addAttribute("activeTheme", "saas")
        model.addAttribute("authenticated", true)
        model.addAttribute("currentPath", "/datasources/sample-lake")
        model.addAttribute("_csrf", mapOf("token" to "t"))
        model.addAttribute("workspaceHeaderFragment", "")
        model.addAttribute("workspaceOptions", emptyList<Any>())
        model.addAttribute("activeWorkspace", "acme")
        LakeTableBrowseModel().fillLevel(model, tables, prefix = null, offset = 0)

        val context =
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
            )
        model.asMap().forEach { (k, v) -> context.setVariable(k, v) }
        val engine =
            SpringTemplateEngine().apply {
                setTemplateResolver(
                    ClassLoaderTemplateResolver().apply {
                        this.prefix = "templates/"
                        suffix = ".html"
                        characterEncoding = "UTF-8"
                    },
                )
            }
        return engine.process("datasources/detail", context)
    }

    /**
     * The description is the ENTIRE text of one element. `<p class="app-page-sub">…</p>` with
     * nothing else inside it is the mechanical statement of "it is not sharing a paragraph with
     * the badge and the name".
     */
    @Test
    fun `the description is in its own element, for every dialect`() {
        Dialect.entries.forEach { dialect ->
            val html = render(dialect)
            val own = Regex("<p class=\"app-page-sub\"[^>]*>\\s*" + Regex.escape(description) + "\\s*</p>")

            withClue(dialect) { own.containsMatchIn(html) shouldBe true }
        }
    }

    /** The identity line is its own row: the dialect badge and the machine name, and no prose. */
    @Test
    fun `the identity line carries the badge and the name and stops there, for every dialect`() {
        Dialect.entries.forEach { dialect ->
            val html = render(dialect)
            val identity =
                Regex("<div class=\"u-row u-wrap u-mb-xs\">([\\s\\S]*?)</div>")
                    .find(html)
                    ?.groupValues
                    ?.get(1)
                    .orEmpty()

            withClue(dialect) {
                identity shouldContain dialect.wire
                identity shouldContain "sample-lake"
                identity.contains(description) shouldBe false
            }
        }
    }

    /** The headline is the display name, above both of them — unchanged by this round. */
    @Test
    fun `the headline is the display name`() {
        render(Dialect.LAKE) shouldContain ">Sample lake</h1>"
    }

    // ------------------------------- dialect properties section (109 §B)

    private fun lakeWithDialectProperties(entries: Map<String, Any?>): Datasource =
        Datasource(
            name = "sample-lake",
            displayName = "Sample lake",
            description = description,
            dialect = Dialect.LAKE,
            jdbcUrl = "jdbc:duckdb:",
            properties = DatasourceProperties(dialect = entries),
        )

    private fun render(datasource: Datasource): String {
        val model = ExtendedModelMap()
        model.addAttribute("datasource", datasource)
        model.addAttribute("dialectProperties", dialectPropertiesFor(datasource))
        model.addAttribute("activeTheme", "saas")
        model.addAttribute("authenticated", true)
        model.addAttribute("currentPath", "/datasources/sample-lake")
        model.addAttribute("_csrf", mapOf("token" to "t"))
        model.addAttribute("workspaceHeaderFragment", "")
        model.addAttribute("workspaceOptions", emptyList<Any>())
        model.addAttribute("activeWorkspace", "acme")
        LakeTableBrowseModel().fillLevel(model, tables, prefix = null, offset = 0)

        val context =
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
            )
        model.asMap().forEach { (k, v) -> context.setVariable(k, v) }
        val engine =
            SpringTemplateEngine().apply {
                setTemplateResolver(
                    ClassLoaderTemplateResolver().apply {
                        this.prefix = "templates/"
                        suffix = ".html"
                        characterEncoding = "UTF-8"
                    },
                )
            }
        return engine.process("datasources/detail", context)
    }

    @Test
    fun `the dialect section shows region and unsigned as key-value badges`() {
        val html = render(lakeWithDialectProperties(mapOf("region" to "us-east-1", "unsigned" to "true")))

        html shouldContain ">region</dt>"
        html shouldContain ">us-east-1</dd>"
        html shouldContain ">unsigned</dt>"
        html shouldContain ">true</dd>"
    }

    @Test
    fun `a secret-valued key's VALUE is absent from the HTML - the projection drops it before the render`() {
        // The §F assertion that matters: not "the key is absent" but THE VALUE — a leaked
        // key's value is the incident. `auth_clientKey` rides the §5.6 secret suffix, so the
        // real projection (dialectPropertiesFor) drops it; had it survived, the badge would
        // echo the secret into the page.
        val html =
            render(
                lakeWithDialectProperties(
                    mapOf(
                        "region" to "us-east-1",
                        "auth_clientKey" to "sk-prod-do-not-echo",
                    ),
                ),
            )

        html shouldNotContain "sk-prod-do-not-echo"
        html shouldNotContain "auth_clientKey"
    }

    @Test
    fun `a datasource with no dialect properties renders no empty section`() {
        val html = render(datasource(Dialect.LAKE))

        html shouldNotContain ">region</dt>"
    }
}
