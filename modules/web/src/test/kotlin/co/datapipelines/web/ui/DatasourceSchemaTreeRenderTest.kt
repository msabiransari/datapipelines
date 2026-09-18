package co.datapipelines.web.ui

import co.datapipelines.datasources.ColumnInfo
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.SchemaEntry
import co.datapipelines.datasources.TableInfo
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.withClue
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
 * 162 (#156) — the discovered-schema Tables view's own three levels, at the render: a schema
 * folder level, a schema's tables level (also the flat root's own content) and a table's
 * columns level, plus the inline failure branch every level shares.
 *
 * [DatasourceDetailRenderTest] pins the PAGE wrapper (which heading, which prose, which tree
 * id, LAKE vs every other dialect); this file pins what each LEVEL fragment itself renders,
 * with hand-built models exactly like [DatasourceDetailRenderTest] uses for the LAKE tree —
 * these are render tests, not a live `SchemaIntrospector`.
 */
class DatasourceSchemaTreeRenderTest {
    private val engine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }

    private fun render(
        view: String,
        fill: WebContext.() -> Unit,
    ): String {
        val context =
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
            )
        context.setVariable(
            "datasource",
            Datasource(
                name = "pg-demo",
                displayName = "pg-demo",
                description = null,
                dialect = Dialect.POSTGRES,
                jdbcUrl = "jdbc:postgresql://db/app",
            ),
        )
        context.setVariable("introspectionErrorCode", null)
        context.setVariable("introspectionErrorMessage", null)
        context.fill()
        return engine.process(view, context)
    }

    // ------------------------------------------------------------------ schemas (root) level

    @Test
    fun `schema folders render their label and the level id an expansion will reuse`() {
        val html =
            render(DatasourceSchemaTreeBrowseModel.SCHEMAS_VIEW) {
                setVariable("flat", false)
                setVariable("schemas", listOf(SchemaFolderView(SchemaEntry(listOf("public"), "public"))))
                setVariable("schemasTruncated", false)
            }

        html shouldContain "public"
        html shouldContain DatasourceSchemaTreeBrowseModel.tablesLevelId(listOf("public"))
    }

    @Test
    fun `no schemas renders the empty state, not the flat tables branch`() {
        val html =
            render(DatasourceSchemaTreeBrowseModel.SCHEMAS_VIEW) {
                setVariable("flat", false)
                setVariable("schemas", emptyList<Any>())
                setVariable("schemasTruncated", false)
            }

        html shouldContain "No schemas found"
    }

    @Test
    fun `a flat (schemaless) root delegates to the tables level inline`() {
        val html =
            render(DatasourceSchemaTreeBrowseModel.SCHEMAS_VIEW) {
                setVariable("flat", true)
                setVariable("levelId", "ds-tables-flat")
                setVariable("namespace", "")
                setVariable("tables", listOf(TableRowView(emptyList(), TableInfo(emptyList(), "orders", "TABLE"))))
                setVariable("tablesTruncated", false)
                setVariable("offset", 0)
                setVariable("hasMore", false)
                setVariable("total", 1)
            }

        html shouldContain "orders"
        html shouldContain "id=\"ds-tables-flat\""
    }

    // ------------------------------------------------------------------ tables level

    @Test
    fun `a table row carries its name, its raw type and the columns level id its own expansion targets`() {
        val html =
            render(DatasourceSchemaTreeBrowseModel.TABLES_VIEW) {
                setVariable("levelId", DatasourceSchemaTreeBrowseModel.tablesLevelId(listOf("public")))
                setVariable("namespace", "public")
                setVariable("tables", listOf(TableRowView(listOf("public"), TableInfo(listOf("public"), "orders", "TABLE"))))
                setVariable("tablesTruncated", false)
                setVariable("offset", 0)
                setVariable("hasMore", false)
                setVariable("total", 1)
            }

        html shouldContain ">orders<"
        html shouldContain ">TABLE<"
        html shouldContain DatasourceSchemaTreeBrowseModel.columnsLevelId(listOf("public"), "orders")
    }

    @Test
    fun `an empty schema renders its own empty state, unlike a namespace folder that cannot be empty`() {
        val html =
            render(DatasourceSchemaTreeBrowseModel.TABLES_VIEW) {
                setVariable("levelId", DatasourceSchemaTreeBrowseModel.tablesLevelId(listOf("empty_schema")))
                setVariable("namespace", "empty_schema")
                setVariable("tables", emptyList<Any>())
                setVariable("tablesTruncated", false)
                setVariable("offset", 0)
                setVariable("hasMore", false)
                setVariable("total", 0)
            }

        html shouldContain "No tables found"
    }

    // ------------------------------------------------------------------ columns level

    @Test
    fun `columns render their name, canonical type and a nullable badge only when nullable`() {
        val html =
            render(DatasourceSchemaTreeBrowseModel.COLUMNS_VIEW) {
                setVariable("levelId", DatasourceSchemaTreeBrowseModel.columnsLevelId(listOf("public"), "orders"))
                setVariable("table", "orders")
                setVariable(
                    "columns",
                    listOf(
                        ColumnInfo(
                            ColumnSchema(name = "id", type = LogicalType.INTEGER, nullable = false),
                            sourceTypeName = "int4",
                            warnings = emptyList(),
                        ),
                        ColumnInfo(
                            ColumnSchema(name = "note", type = LogicalType.STRING, nullable = true),
                            sourceTypeName = "text",
                            warnings = emptyList(),
                        ),
                    ),
                )
            }

        html shouldContain ">id<"
        html shouldContain ">INTEGER<"
        html shouldContain ">note<"
        html shouldContain ">STRING<"
        // Exactly one nullable badge — the non-nullable column carries none.
        Regex(">nullable<").findAll(html).count() shouldBe 1
    }

    @Test
    fun `no readable columns renders the empty state, never a blank pane`() {
        val html =
            render(DatasourceSchemaTreeBrowseModel.COLUMNS_VIEW) {
                setVariable("levelId", "ds-columns-empty")
                setVariable("table", "orders")
                setVariable("columns", emptyList<Any>())
            }

        html shouldContain "No readable columns"
    }

    // ------------------------------------------------------------------ the shared failure branch

    @Test
    fun `an introspection failure renders the catalogued code and message inline, on every level, never a blank pane`() {
        val views =
            listOf(
                DatasourceSchemaTreeBrowseModel.SCHEMAS_VIEW,
                DatasourceSchemaTreeBrowseModel.TABLES_VIEW,
                DatasourceSchemaTreeBrowseModel.COLUMNS_VIEW,
            )
        views.forEach { view ->
            val html =
                render(view) {
                    setVariable("introspectionErrorCode", PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE)
                    setVariable("introspectionErrorMessage", "Datasource 'pg-demo' could not be reached for schema introspection.")
                    // Content the failure must suppress, present to prove it is not what renders.
                    setVariable("flat", false)
                    setVariable("schemas", listOf(SchemaFolderView(SchemaEntry(listOf("public"), "public"))))
                    setVariable("schemasTruncated", false)
                    setVariable("levelId", "ds-tables-failure")
                    setVariable("namespace", "public")
                    setVariable("tables", listOf(TableRowView(listOf("public"), TableInfo(listOf("public"), "orders", "TABLE"))))
                    setVariable("tablesTruncated", false)
                    setVariable("offset", 0)
                    setVariable("hasMore", false)
                    setVariable("total", 1)
                    setVariable("table", "orders")
                    setVariable(
                        "columns",
                        listOf(ColumnInfo(ColumnSchema("id", LogicalType.INTEGER, nullable = false), "int4", emptyList())),
                    )
                }

            withClue(view) {
                html shouldContain PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE
                html shouldContain "could not be reached for schema introspection"
                html shouldNotContain "public"
                html shouldNotContain "orders"
            }
        }
    }
}
