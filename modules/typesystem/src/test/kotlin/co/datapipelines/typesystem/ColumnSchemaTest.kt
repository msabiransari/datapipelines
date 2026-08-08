package co.datapipelines.typesystem

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.ResultSetMetaData

/**
 * The §7.1 column descriptor: what is emitted, what is omitted, and what must survive
 * being read back.
 *
 * The centre of gravity here is **absence**. Three fields carry meaning by being missing
 * (§7.3), so most of these tests assert on the serialized key SET rather than on values
 * — a regression that emits `"precision": null` would pass a value-only assertion and
 * still break the contract.
 */
class ColumnSchemaTest {
    private val mapper = ObjectMapper().registerKotlinModule()

    private fun keysOf(column: ColumnSchema): List<String> =
        mapper
            .readTree(mapper.writeValueAsString(column))
            .fieldNames()
            .asSequence()
            .toList()

    @Test
    fun `a minimal descriptor emits only name and type`() {
        keysOf(ColumnSchema("order_count", LogicalType.INTEGER)) shouldContainExactly listOf("name", "type")
    }

    @Test
    fun `an unbounded BIGDECIMAL emits NO precision key at all`() {
        // §4, normative: omitted precision means unbounded. `"precision": null` would be
        // a different statement, and a synthetic ceiling would be a lie about the source.
        val column = ColumnSchema("unbounded_total", LogicalType.BIGDECIMAL, precision = null, scale = 0)
        val keys = keysOf(column)

        keys shouldContainExactly listOf("name", "type", "scale")
        keys shouldNotContain "precision"
        mapper.writeValueAsString(column) shouldBe
            """{"name":"unbounded_total","type":"BIGDECIMAL","scale":0}"""
        column.isUnboundedPrecision shouldBe true
    }

    @Test
    fun `an approximate DECIMAL emits NO scale key`() {
        // §3.4 / §4.1: the absent scale is how a client learns the origin was REAL/DOUBLE.
        val column = ColumnSchema("measurement", LogicalType.DECIMAL, precision = 15)

        keysOf(column) shouldContainExactly listOf("name", "type", "precision")
        column.isApproximateNumeric shouldBe true
    }

    @Test
    fun `an exact DECIMAL emits both precision and scale`() {
        val column = ColumnSchema("lifetime_value", LogicalType.DECIMAL, precision = 12, scale = 2)

        keysOf(column) shouldContainExactly listOf("name", "type", "precision", "scale")
        column.isApproximateNumeric shouldBe false
    }

    @Test
    fun `nullable is emitted when known and omitted when unknown`() {
        keysOf(ColumnSchema("a", LogicalType.STRING, nullable = false)) shouldContainExactly
            listOf("name", "type", "nullable")
        keysOf(ColumnSchema("a", LogicalType.STRING, nullable = true)) shouldContainExactly
            listOf("name", "type", "nullable")
        keysOf(ColumnSchema("a", LogicalType.STRING, nullable = null)) shouldContainExactly
            listOf("name", "type")
    }

    @Test
    fun `columnNullableUnknown maps to an absent field, never to false`() {
        // §7.3: "absence means unknown — clients MUST NOT read an absent nullable as
        // false". Reporting false here would assert NOT NULL on a column nobody checked.
        ColumnSchema.nullableFromJdbc(ResultSetMetaData.columnNullable) shouldBe true
        ColumnSchema.nullableFromJdbc(ResultSetMetaData.columnNoNulls) shouldBe false
        ColumnSchema.nullableFromJdbc(ResultSetMetaData.columnNullableUnknown) shouldBe null
    }

    @Test
    fun `an unrecognized property does not break deserialization`() {
        // §7.1, normative: additionalProperties is true so §9.2 can add optional fields
        // without a version bump. A client that rejects them is non-conformant — and this
        // codebase is its own reference client.
        val fromTheFuture =
            """
            {"name":"total","type":"BIGDECIMAL","scale":2,"precision":18,
             "collation":"en_US","source_type_oid":1700,"nested":{"anything":[1,2,3]}}
            """.trimIndent()

        mapper.readValue<ColumnSchema>(fromTheFuture) shouldBe
            ColumnSchema("total", LogicalType.BIGDECIMAL, precision = 18, scale = 2)
    }

    @Test
    fun `the §7-2 example envelope round-trips`() {
        val envelope =
            SchemaEnvelope(
                schema =
                    listOf(
                        ColumnSchema("customer_id", LogicalType.INTEGER, nullable = false),
                        ColumnSchema("total_amount", LogicalType.BIGDECIMAL, 18, 2, nullable = true),
                        ColumnSchema("unbounded_total", LogicalType.BIGDECIMAL, scale = 0),
                        ColumnSchema("measurement", LogicalType.DECIMAL, precision = 15),
                        ColumnSchema("first_order_at", LogicalType.TIMESTAMP),
                    ),
            )

        val json = mapper.writeValueAsString(envelope)
        mapper.readValue<SchemaEnvelope>(json) shouldBe envelope
        mapper.readTree(json)["schema_version"].asInt() shouldBe 1
        // The snake_case key is exactly the shape a naming strategy would mangle.
        mapper
            .readTree(json)
            .fieldNames()
            .asSequence()
            .toList() shouldContainExactly
            listOf("schema", "schema_version")
    }

    @Test
    fun `the §7-1 conditional-required rules are enforced at construction`() {
        // DECIMAL requires precision; BIGDECIMAL requires scale (the JSON Schema allOf).
        shouldThrow<IllegalArgumentException> { ColumnSchema("c", LogicalType.DECIMAL) }
        shouldThrow<IllegalArgumentException> { ColumnSchema("c", LogicalType.BIGDECIMAL, precision = 20) }
        // …and the field bounds: minLength 1, precision >= 1, scale >= 0.
        shouldThrow<IllegalArgumentException> { ColumnSchema("", LogicalType.STRING) }
        shouldThrow<IllegalArgumentException> { ColumnSchema("c", LogicalType.DECIMAL, precision = 0) }
        shouldThrow<IllegalArgumentException> { ColumnSchema("c", LogicalType.BIGDECIMAL, 20, scale = -1) }
    }

    @Test
    fun `a descriptor deserialized from the wire rebuilds the same value`() {
        val json = """{"name":"logo","type":"BINARY","nullable":true}"""
        mapper.readValue<ColumnSchema>(json) shouldBe
            ColumnSchema("logo", LogicalType.BINARY, nullable = true)
    }
}
