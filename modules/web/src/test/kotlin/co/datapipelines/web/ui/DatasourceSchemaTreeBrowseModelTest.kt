package co.datapipelines.web.ui

import co.datapipelines.datasources.ColumnInfo
import co.datapipelines.datasources.CurrentSchemaUnknownException
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceUnreachableException
import co.datapipelines.datasources.SchemaEntry
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.datasources.SchemasPage
import co.datapipelines.datasources.TableInfo
import co.datapipelines.datasources.TablesPage
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.ui.ExtendedModelMap

/**
 * 162 (#156) — the Tables view's schema-tree model, unit-tested against a mocked
 * [SchemaIntrospector] (the same double [co.datapipelines.web.datasources.DatasourceSchemaControllerTest]
 * uses for its own translation tests): the live-connection cases belong to
 * [co.datapipelines.datasources.SchemaIntrospectorH2Test], not here.
 */
class DatasourceSchemaTreeBrowseModelTest {
    private val introspector = mockk<SchemaIntrospector>()
    private val model = DatasourceSchemaTreeBrowseModel(introspector)
    private val datasource =
        Datasource(
            name = "pg-demo",
            displayName = "pg-demo",
            description = null,
            dialect = Dialect.POSTGRES,
            jdbcUrl = "jdbc:postgresql://db/app",
        )

    private fun table(name: String) = TableInfo(listOf("public"), name, "TABLE")

    // ------------------------------------------------------------------ fillRoot

    @Test
    fun `fillRoot with schemas renders the schemas view with one folder per entry`() {
        every { introspector.schemas(datasource) } returns
            SchemasPage(listOf(SchemaEntry(listOf("public"), "public"), SchemaEntry(listOf("app", "sales"), "sales")), truncated = false)

        val m = ExtendedModelMap()
        val view = model.fillRoot(m, datasource)

        view shouldBe DatasourceSchemaTreeBrowseModel.SCHEMAS_VIEW
        @Suppress("UNCHECKED_CAST")
        val folders = m["schemas"] as List<SchemaFolderView>
        folders.map { it.label } shouldBe listOf("public", "sales")
        folders.map { it.path } shouldBe listOf("public", "app.sales")
        m["schemasTruncated"] shouldBe false
        m["introspectionErrorCode"] shouldBe null
        // The template's root fragment branches on this attribute (`th:if="${flat}"`) — a
        // local Kotlin variable never reaching the model was the actual round-162 defect,
        // caught only by the browser test that renders the real template.
        m["flat"] shouldBe false
    }

    @Test
    fun `fillRoot on a schemaless dialect delegates to the flat tables level`() {
        every { introspector.schemas(datasource) } returns SchemasPage(emptyList(), truncated = false)
        every { introspector.tables(datasource, namespaceFilter = null) } returns TablesPage(listOf(table("orders")), truncated = false)

        val m = ExtendedModelMap()
        val view = model.fillRoot(m, datasource)

        view shouldBe DatasourceSchemaTreeBrowseModel.TABLES_VIEW
        m["flat"] shouldBe true
        m["levelId"] shouldBe "ds-tables-flat"
        @Suppress("UNCHECKED_CAST")
        (m["tables"] as List<TableRowView>).map { it.name } shouldBe listOf("orders")
        verify { introspector.tables(datasource, namespaceFilter = null) }
    }

    @Test
    fun `fillRoot renders the catalogued code and message inline when schemas() cannot reach the datasource`() {
        every { introspector.schemas(datasource) } throws
            DatasourceUnreachableException(datasource.name, RuntimeException("connect refused"))

        val m = ExtendedModelMap()
        val view = model.fillRoot(m, datasource)

        view shouldBe DatasourceSchemaTreeBrowseModel.SCHEMAS_VIEW
        m["introspectionErrorCode"] shouldBe PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE
        (m["introspectionErrorMessage"] as String).shouldStartWith("Datasource 'pg-demo' could not be reached")
        m["schemas"] shouldBe null
    }

    // ------------------------------------------------------------------ fillTables

    @Test
    fun `fillTables passes the dotted namespace through as a filter and sorts and pages the result`() {
        val tables = (1..30).map { table("t%02d".format(it)) }.shuffled(java.util.Random(1))
        every { introspector.tables(datasource, namespaceFilter = listOf("a", "sales")) } returns TablesPage(tables, truncated = false)

        val m = ExtendedModelMap()
        val view = model.fillTables(m, datasource, namespace = "a.sales", offset = 0)

        view shouldBe DatasourceSchemaTreeBrowseModel.TABLES_VIEW
        @Suppress("UNCHECKED_CAST")
        val page = m["tables"] as List<TableRowView>
        page.map { it.name } shouldBe (1..25).map { "t%02d".format(it) }
        m["hasMore"] shouldBe true
        m["total"] shouldBe 30
        m["namespace"] shouldBe "a.sales"

        val second = ExtendedModelMap()
        model.fillTables(second, datasource, namespace = "a.sales", offset = 25)
        @Suppress("UNCHECKED_CAST")
        (second["tables"] as List<TableRowView>).map { it.name } shouldBe (26..30).map { "t%02d".format(it) }
        second["hasMore"] shouldBe false
    }

    @Test
    fun `fillTables with a blank namespace is the flat, unfiltered read`() {
        every { introspector.tables(datasource, namespaceFilter = null) } returns TablesPage(emptyList(), truncated = false)

        val m = ExtendedModelMap()
        model.fillTables(m, datasource, namespace = "", offset = 0)

        m["namespace"] shouldBe ""
        m["levelId"] shouldBe "ds-tables-flat"
        verify { introspector.tables(datasource, namespaceFilter = null) }
    }

    @Test
    fun `fillTables honours the cap-dropped-some flag`() {
        every { introspector.tables(datasource, namespaceFilter = listOf("public")) } returns
            TablesPage(listOf(table("orders")), truncated = true)

        val m = ExtendedModelMap()
        model.fillTables(m, datasource, namespace = "public", offset = 0)

        m["tablesTruncated"] shouldBe true
    }

    // ------------------------------------------------------------------ fillColumns

    @Test
    fun `fillColumns reads through the namespace filter and exposes the raw columns`() {
        val columns =
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
            )
        every { introspector.columns(datasource, "orders", namespaceFilter = listOf("public")) } returns columns

        val m = ExtendedModelMap()
        val view = model.fillColumns(m, datasource, namespace = "public", table = "orders")

        view shouldBe DatasourceSchemaTreeBrowseModel.COLUMNS_VIEW
        m["columns"] shouldBe columns
        m["table"] shouldBe "orders"
        (m["levelId"] as String) shouldBe DatasourceSchemaTreeBrowseModel.columnsLevelId(listOf("public"), "orders")
    }

    @Test
    fun `fillColumns renders the catalogued code and message when the driver cannot report a current schema`() {
        every { introspector.columns(datasource, "orders", namespaceFilter = null) } throws CurrentSchemaUnknownException(datasource.name)

        val m = ExtendedModelMap()
        val view = model.fillColumns(m, datasource, namespace = null, table = "orders")

        view shouldBe DatasourceSchemaTreeBrowseModel.COLUMNS_VIEW
        m["introspectionErrorCode"] shouldBe PipelineErrorCodes.Execution.PARAMETER_REQUIRED
        (m["introspectionErrorMessage"] as String).shouldStartWith("Datasource 'pg-demo' reports no current schema")
        m["columns"] shouldBe null
    }

    @Test
    fun `fillColumns passes an already-catalogued exception's own code and message through unchanged`() {
        val notFound =
            DatapipelinesException(
                code = "datasource.table_not_found",
                message = "table 'ghost' does not exist in 'public'.",
            )
        every { introspector.columns(datasource, "ghost", namespaceFilter = listOf("public")) } throws notFound

        val m = ExtendedModelMap()
        model.fillColumns(m, datasource, namespace = "public", table = "ghost")

        m["introspectionErrorCode"] shouldBe "datasource.table_not_found"
        m["introspectionErrorMessage"] shouldBe "table 'ghost' does not exist in 'public'."
    }

    // ------------------------------------------------------------------ level ids

    @Test
    fun `level ids are stable and distinguish schema paths from table paths`() {
        val a = DatasourceSchemaTreeBrowseModel.tablesLevelId(listOf("public"))
        val b = DatasourceSchemaTreeBrowseModel.tablesLevelId(listOf("app", "sales"))
        val c = DatasourceSchemaTreeBrowseModel.columnsLevelId(listOf("public"), "orders")

        DatasourceSchemaTreeBrowseModel.tablesLevelId(listOf("public")) shouldBe a
        (a == b) shouldBe false
        a.shouldStartWith("ds-tables-")
        c.shouldStartWith("ds-columns-")
        DatasourceSchemaTreeBrowseModel.tablesLevelId(emptyList()) shouldBe "ds-tables-flat"
    }

    /** Not just any distinct-arguments assertion: [slot] proves the EXACT filter reaches the introspector. */
    @Test
    fun `a two-segment schema path splits into a two-element namespace filter`() {
        val captured = slot<List<String>>()
        every { introspector.tables(datasource, namespaceFilter = capture(captured)) } returns TablesPage(emptyList(), truncated = false)

        model.fillTables(ExtendedModelMap(), datasource, namespace = "a1.sales", offset = 0)

        captured.captured shouldBe listOf("a1", "sales")
    }
}
