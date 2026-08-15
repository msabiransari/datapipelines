package co.datapipelines.web.datasources

import co.datapipelines.datasources.ColumnInfo
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.datasources.SchemaSnapshot
import co.datapipelines.datasources.TableInfo
import co.datapipelines.datasources.TableWithColumns
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.TypeMappingWarning
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.sql.SQLException

/**
 * §7A over a mocked introspector — the endpoints are a pure snake_case projection of the
 * introspector's payloads; every field name is asserted on the **serialized** JSON, not the
 * Kotlin map, so a renamed key cannot slip through.
 */
class DatasourceSchemaControllerTest {
    private val introspector = mockk<SchemaIntrospector>()
    private val controller = DatasourceSchemaController(introspector)
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Test
    fun `tables returns snake_case table descriptors`() {
        every { introspector.tables("pg-prod", null) } returns listOf(TableInfo("public", "orders", "TABLE"))

        val data = controller.tables("pg-prod", schema = null).data

        val node = mapper.readTree(mapper.writeValueAsString(data))
        assertAll(
            { node[0]["schema"].asText() shouldBe "public" },
            { node[0]["name"].asText() shouldBe "orders" },
            { node[0]["type"].asText() shouldBe "TABLE" },
        )
    }

    @Test
    fun `tables passes the schema filter through`() {
        every { introspector.tables("pg-prod", "sales") } returns emptyList()

        controller.tables("pg-prod", schema = "sales").data.size shouldBe 0
    }

    @Test
    fun `columns returns canonical and source types snake_case`() {
        every { introspector.columns("pg-prod", "orders", null) } returns
            listOf(
                ColumnInfo(ColumnSchema("id", LogicalType.INTEGER, nullable = false), "int4", emptyList()),
                ColumnInfo(ColumnSchema("amount", LogicalType.DECIMAL, precision = 10, scale = 2), "numeric", emptyList()),
            )

        val node = mapper.readTree(mapper.writeValueAsString(controller.columns("pg-prod", "orders", schema = null).data))

        assertAll(
            { node.size() shouldBe 2 },
            { node[0]["name"].asText() shouldBe "id" },
            { node[0]["type"].asText() shouldBe "INTEGER" },
            { node[0]["nullable"].asBoolean() shouldBe false },
            { node[0]["source_type"].asText() shouldBe "int4" },
            { node[0]["warnings"].size() shouldBe 0 },
            { node[1]["precision"].asInt() shouldBe 10 },
            { node[1]["scale"].asInt() shouldBe 2 },
        )
    }

    @Test
    fun `columns carries the mapper's warning messages`() {
        every { introspector.columns("pg-prod", "wide", null) } returns
            listOf(
                ColumnInfo(
                    ColumnSchema("mystery", LogicalType.STRING),
                    "sql_variant",
                    listOf(TypeMappingWarning.sqlVariant("mystery")),
                ),
            )

        val node = mapper.readTree(mapper.writeValueAsString(controller.columns("pg-prod", "wide", schema = null).data))

        node[0]["warnings"][0].asText() shouldBe TypeMappingWarning.sqlVariant("mystery").message
    }

    @Test
    fun `schema returns the snapshot payload snake_case`() {
        every { introspector.snapshot("pg-prod") } returns
            SchemaSnapshot(
                datasource = "pg-prod",
                dialect = "POSTGRES",
                truncated = false,
                tables =
                    listOf(
                        TableWithColumns(
                            TableInfo("public", "orders", "TABLE"),
                            listOf(ColumnInfo(ColumnSchema("id", LogicalType.INTEGER, nullable = false), "int4", emptyList())),
                        ),
                    ),
            )

        val node = mapper.readTree(mapper.writeValueAsString(controller.schema("pg-prod").data))

        assertAll(
            { node["datasource"].asText() shouldBe "pg-prod" },
            { node["dialect"].asText() shouldBe "POSTGRES" },
            { node["truncated"].asBoolean() shouldBe false },
            { node["tables"][0]["table"]["name"].asText() shouldBe "orders" },
            { node["tables"][0]["table"]["schema"].asText() shouldBe "public" },
            { node["tables"][0]["columns"][0]["name"].asText() shouldBe "id" },
            { node["tables"][0]["columns"][0]["source_type"].asText() shouldBe "int4" },
        )
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
        // A customer DB being down is not a server error: the raw SQLException must surface as
        // the §13.8 code (HTTP 502 via the catalog), never as the 500 backstop.
        every { introspector.tables("pg-prod", null) } throws SQLException("Connection refused")

        val thrown = shouldThrow<DatapipelinesException> { controller.tables("pg-prod", schema = null) }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE },
            { thrown.details["datasource"] shouldBe "pg-prod" },
            { thrown.message shouldNotContain "Connection refused" },
        )
    }
}
