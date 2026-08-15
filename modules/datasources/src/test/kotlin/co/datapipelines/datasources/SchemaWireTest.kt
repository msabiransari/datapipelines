package co.datapipelines.datasources

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.TypeMappingWarning
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * The shared §7A wire projections (used verbatim by BOTH the REST endpoints and the MCP tools):
 * snake_case maps built field-by-field, never a serialized entity — credentials are not part
 * of schema metadata, and building the maps explicitly keeps it that way by construction.
 */
class SchemaWireTest {
    @Test
    fun `table descriptor is snake_case`() {
        val wire = TableInfo("public", "orders", "TABLE").toWireMap()

        wire shouldBe mapOf("schema" to "public", "name" to "orders", "type" to "TABLE")
    }

    @Test
    fun `column descriptor carries source type and the mapper's warning messages`() {
        val warned =
            ColumnInfo(
                column = ColumnSchema("weird", LogicalType.STRING),
                sourceTypeName = "sql_variant",
                warnings = listOf(TypeMappingWarning.sqlVariant("weird"), TypeMappingWarning.unknownSourceType("weird2", "money")),
            )

        val wire = warned.toWireMap()

        assertAll(
            { wire["name"] shouldBe "weird" },
            { wire["type"] shouldBe "STRING" },
            { wire["source_type"] shouldBe "sql_variant" },
            {
                (wire["warnings"] as List<*>) shouldContainExactly
                    listOf(
                        TypeMappingWarning.sqlVariant("weird").message,
                        TypeMappingWarning.unknownSourceType("weird2", "money").message,
                    )
            },
        )
    }

    @Test
    fun `column descriptor omits precision scale and nullable when the metadata did not report them`() {
        val wire = ColumnInfo(ColumnSchema("id", LogicalType.INTEGER), "int4", emptyList()).toWireMap()

        assertAll(
            { wire.containsKey("precision") shouldBe false },
            { wire.containsKey("scale") shouldBe false },
            { wire.containsKey("nullable") shouldBe false },
            { (wire["warnings"] as List<*>) shouldBe emptyList<String>() },
        )
    }

    @Test
    fun `snapshot descriptor nests table and column descriptors`() {
        val snapshot =
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

        val wire = snapshot.toWireMap()
        val firstTable = (wire["tables"] as List<*>).single() as Map<*, *>

        assertAll(
            { wire["datasource"] shouldBe "pg-prod" },
            { wire["dialect"] shouldBe "POSTGRES" },
            { wire["truncated"] shouldBe false },
            { (firstTable["table"] as Map<*, *>)["name"] shouldBe "orders" },
            { ((firstTable["columns"] as List<*>).single() as Map<*, *>)["source_type"] shouldBe "int4" },
        )
    }
}
