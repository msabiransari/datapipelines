package co.datapipelines.lake.append

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Schema
import org.apache.iceberg.types.Type
import org.apache.iceberg.types.Types
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.UUID

/** #967 V2 and V6 at the writer: real DuckDB cursors and S3, the refusal before a data file exists. */
class TruncatePartitionWriterIntegrationTest {
    private val h = ParquetWriterHarness()
    private val writer = ParquetAppendWriter()

    @BeforeEach
    fun reset() = LakeTestDb.reset()

    @ParameterizedTest(name = "{0} truncate[{1}]")
    @MethodSource("edges")
    fun `one edge row among good rows is refused by the first scan and nothing is written`(
        type: String,
        width: Int,
        edge: String,
        good: String,
    ) {
        val schema = schema(columnType(type))
        h.withTable(schema, PartitionSpec.builderFor(schema).truncate("v", width).build()) { table ->
            val sql =
                "SELECT * FROM (VALUES (1::BIGINT, '$good'::$type), (2::BIGINT, '$edge'::$type), " +
                    "(3::BIGINT, '$good'::$type)) t(id, v)"
            h.withInput(sql) { input ->
                val failure = shouldThrow<DatapipelinesException> { writer.write(table, input, UUID.randomUUID(), 16) {} }
                failure.code shouldBe PipelineErrorCodes.Lake.APPEND_PARTITION_VALUE_OUT_OF_RANGE
                failure.details shouldBe mapOf("out_of_range_counts" to mapOf("truncate[$width](v)" to 1L))
                input.scans shouldBe 1
                h.dataKeys(table) shouldBe emptyList()
                table.currentSnapshot() shouldBe null
            }
        }
    }

    @Test
    fun `a required null is refused before an edge value in the same scan`() {
        val schema = schema(Types.IntegerType.get())
        h.withTable(schema, PartitionSpec.builderFor(schema).truncate("v", 10).build()) { table ->
            h.withInput("SELECT * FROM (VALUES (NULL::BIGINT, (-2147483648)::INTEGER), (2::BIGINT, 5::INTEGER)) t(id, v)") { input ->
                val failure = shouldThrow<DatapipelinesException> { writer.write(table, input, UUID.randomUUID(), 16) {} }
                failure.code shouldBe PipelineErrorCodes.Lake.APPEND_NULL_IN_REQUIRED
                input.scans shouldBe 1
                h.dataKeys(table) shouldBe emptyList()
            }
        }
    }

    private fun schema(type: Type): Schema =
        Schema(
            Types.NestedField.required(1, "id", Types.LongType.get()),
            Types.NestedField.optional(2, "v", type),
        )

    private fun columnType(sqlType: String): Type =
        when (sqlType) {
            "INTEGER" -> Types.IntegerType.get()
            "BIGINT" -> Types.LongType.get()
            "DECIMAL(15,3)" -> Types.DecimalType.of(15, 3)
            "DECIMAL(38,6)" -> Types.DecimalType.of(38, 6)
            else -> error("no fixture type for $sqlType")
        }

    companion object {
        /** SQL type, width, the edge value (probes.out), a good value. */
        @JvmStatic
        fun edges(): List<Array<Any>> =
            listOf(
                arrayOf("DECIMAL(15,3)", 2, "-999999999999.999", "1.500"),
                arrayOf("DECIMAL(38,6)", 2, "-99999999999999999999999999999999.999999", "1.500000"),
                arrayOf("INTEGER", 10, "-2147483648", "7"),
                arrayOf("BIGINT", 10, "-9223372036854775808", "7"),
            )
    }
}
