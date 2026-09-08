package co.datapipelines.web.ui

import co.datapipelines.application.datasources.LakeTable
import co.datapipelines.application.datasources.LakeTableFormat
import co.datapipelines.datasources.Datasource
import co.datapipelines.typesystem.Dialect
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
 * Render-level guard for the LAKE datasource detail's registered-tables tree (089 §A), the
 * [TemplateTreeRenderTest] pattern applied to the read-only twin: controller tests pin the
 * model; this pins what the browser actually receives — and, since the tree's whole point is
 * being READ-ONLY (R10: registration stays REST/MCP), what it must NEVER receive. An absence
 * has no natural test: nothing fails when a well-meaning round adds a Register button to this
 * partial. These assertions are what stands between that round and the rule.
 *
 * The model is filled by the REAL [LakeTableBrowseModel], so derivation (folders vs leaves,
 * counts, the one-level-per-request rule) and markup are covered together.
 */
class LakeTableTreeRenderTest {
    private val datasource = Datasource(name = "sample-lake", displayName = "Sample lake", dialect = Dialect.LAKE, jdbcUrl = "jdbc:duckdb:")

    private val tables =
        listOf(
            table(listOf("nyc", "mobility"), "hvfhv_zone_day", LakeTableFormat.PARQUET),
            table(listOf("nyc", "mobility"), "hvfhv_trips", LakeTableFormat.PARQUET, partitionColumn = "pickup_date"),
            table(listOf("nyc", "reference"), "zones", LakeTableFormat.PARQUET),
            table(listOf("trade"), "orders", LakeTableFormat.ICEBERG),
        )

    private fun table(
        namespace: List<String>,
        name: String,
        format: LakeTableFormat,
        partitionColumn: String? = null,
    ) = LakeTable(
        id = UUID.randomUUID(),
        datasourceId = "sample-lake",
        namespace = namespace,
        name = name,
        format = format,
        location = "s3://datapipelines-co/sample-data/lake/v1/$name",
        partitionColumn = partitionColumn,
        registeredBy = UUID.randomUUID(),
        registeredAt = Instant.parse("2026-09-07T12:00:00Z"),
    )

    private fun renderLevel(
        prefix: String?,
        levelTables: List<LakeTable> = tables,
    ): String {
        val model = ExtendedModelMap()
        model.addAttribute("datasource", datasource)
        LakeTableBrowseModel().fillLevel(model, levelTables, prefix, 0)
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
        return COMMENT.replace(engine.process("partials/lake-table-tree-level", context), "")
    }

    @Test
    fun `the root level renders namespace folders with counts, each expanding with ONE level request`() {
        val html = renderLevel(prefix = null)

        html shouldContain "id=\"lake-table-tree\""
        // The root holds the two top-level namespaces, counted over their subtrees.
        html shouldContain ">nyc</span>"
        html shouldContain ">trade</span>"
        // One level per request: the folder fetches its OWN prefix, on the summary's first
        // click, into the pending placeholder that follows it (the template tree's rule).
        html shouldContain "hx-get=\"/partials/datasources/sample-lake/lake-tables?prefix=nyc\""
        html shouldContain "hx-trigger=\"click once\""
        html shouldContain "hx-target=\"next .tpl-level\""
        html shouldContain "tpl-level tpl-level-pending"
    }

    @Test
    fun `a namespace level renders its tables with format and partition badges`() {
        val html = renderLevel(prefix = "nyc/mobility")

        // The derived id placeholder and fragment agree (the one-derivation rule).
        html shouldContain "id=\"${LakeTableBrowseModel.levelId("nyc/mobility")}\""
        html shouldContain "hvfhv_zone_day"
        html shouldContain "hvfhv_trips"
        html shouldContain ">parquet</span>"
        html shouldContain ">pickup_date</span>"
        // The qualified name and the location ride on `title`.
        html shouldContain "title=\"nyc.mobility.hvfhv_trips"
        // Tables of SIBLING namespaces are not in this level (one level per request).
        html shouldNotContain "zones"
        html shouldNotContain "orders"
    }

    @Test
    fun `the tree is READ-ONLY - no form, no button, no mutating request, and it says so`() {
        val html = renderLevel(prefix = "nyc/mobility")

        listOf("hx-post", "hx-delete", "hx-put", "hx-patch", "<form", "<button", "Register</button>", "Unregister")
            .forEach { html shouldNotContain it }
        // The registration hint is the ONLY mention of registration: a pointer, not a control.
        renderLevel(prefix = null, levelTables = emptyList()) shouldContain "lake_tables_register"
    }

    @Test
    fun `an empty registry renders the empty state with the REST or MCP pointer`() {
        val html = renderLevel(prefix = null, levelTables = emptyList())

        html shouldContain "No tables registered"
        html shouldContain "lake_tables_import"
        // No tree chrome at all, so no folder shape can imply content exists.
        html shouldNotContain "<ul class=\"tpl-tree\">"
    }

    @Test
    fun `an illegal prefix renders an ordinary empty level, never an error and never a wildcard`() {
        val html = renderLevel(prefix = "nyc/../../etc")

        html shouldContain "No tables registered"
        html shouldNotContain "hvfhv_zone_day"
    }

    private companion object {
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    }
}
