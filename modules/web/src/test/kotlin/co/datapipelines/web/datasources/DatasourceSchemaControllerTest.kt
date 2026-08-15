package co.datapipelines.web.datasources

import co.datapipelines.datasources.ColumnInfo
import co.datapipelines.datasources.DatasourceUnreachableException
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.datasources.TableInfo
import co.datapipelines.datasources.TablesPage
import co.datapipelines.datasources.toWireMap
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * §7A endpoint DELEGATION over a mocked introspector: the controller binds paths, threads the
 * query/table/schema arguments through, and serves the shared wire projections (`toWireMap`)
 * unchanged — the field-by-field shape of those projections is owned ONCE by `SchemaWireTest`
 * in `modules/datasources`, not re-asserted here per surface. Error paths (not-found,
 * unreachable) are this layer's own behavior and stay fully asserted.
 */
class DatasourceSchemaControllerTest {
    private val introspector = mockk<SchemaIntrospector>()
    private val controller = DatasourceSchemaController(introspector)

    @Test
    fun `tables delegates to the introspector and serves the shared wire projection`() {
        val page = TablesPage(listOf(TableInfo("public", "orders", "TABLE")), truncated = true)
        every { introspector.tables("pg-prod", "sales") } returns page

        val data = controller.tables("pg-prod", schema = "sales").data

        data shouldBe page.toWireMap()
        verify(exactly = 1) { introspector.tables("pg-prod", "sales") }
    }

    @Test
    fun `columns delegates to the introspector and serves the shared wire projection`() {
        val columns =
            listOf(
                ColumnInfo(ColumnSchema("id", LogicalType.INTEGER, nullable = false), "int4", emptyList()),
                ColumnInfo(ColumnSchema("amount", LogicalType.DECIMAL, precision = 10, scale = 2), "numeric", emptyList()),
            )
        every { introspector.columns("pg-prod", "orders", null) } returns columns

        val data = controller.columns("pg-prod", "orders", schema = null).data

        data shouldBe columns.map { it.toWireMap() }
        verify(exactly = 1) { introspector.columns("pg-prod", "orders", null) }
    }

    @Test
    fun `an unknown datasource surfaces the catalogued not-found`() {
        every { introspector.tables("nope", null) } throws
            DatapipelinesException(
                code = PipelineErrorCodes.Datasource.NOT_FOUND,
                message = "Datasource 'nope' is not registered in this environment.",
                details = mapOf("datasource" to "nope"),
            )

        shouldThrow<DatapipelinesException> { controller.tables("nope", schema = null) }
            .code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND
    }

    @Test
    fun `a connection failure during introspection is the catalogued datasource_unreachable`() {
        // A customer DB being down is not a server error: the introspector's
        // DatasourceUnreachableException (its lease boundary wraps BOTH the SQLException lease
        // family and the RuntimeException pool-build family — PoolInitializationException on a
        // down database, which round 1 missed; that path is pinned by the introspector tests)
        // must surface as the §13.8 code (HTTP 502 via the catalog), never as the 500 backstop.
        every { introspector.tables("pg-prod", null) } throws
            DatasourceUnreachableException("pg-prod", RuntimeException("Connection refused"))

        val thrown = shouldThrow<DatapipelinesException> { controller.tables("pg-prod", schema = null) }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE },
            { thrown.details["datasource"] shouldBe "pg-prod" },
            { thrown.message shouldNotContain "Connection refused" },
        )
    }
}
