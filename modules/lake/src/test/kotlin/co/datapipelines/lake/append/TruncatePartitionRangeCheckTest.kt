package co.datapipelines.lake.append

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Schema
import org.apache.iceberg.types.Type
import org.apache.iceberg.types.Types
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.sql.ResultSet

/**
 * #967 V1: the edges of a numeric `truncate` partition. Every expected verdict is a literal read from the
 * orchestrator's probes on iceberg-api 1.12.0 (store `design-records/dp-lake-evidence/967-truncate-boundary/probes.out`),
 * never computed by the code under test.
 */
class TruncatePartitionRangeCheckTest {
    @ParameterizedTest(name = "{0} truncate[{1}] of {2} out of range = {3}")
    @MethodSource("edges")
    fun `the verdict at each edge is the probes' literal`(
        type: String,
        width: Int,
        value: String,
        outOfRange: Boolean,
    ) {
        val icebergType = type(type)
        guard(icebergType, width).outOfRange(parse(icebergType, value)) shouldBe outOfRange
    }

    @Test
    fun `a null value is never counted`() {
        val check = check(Types.IntegerType.get(), 10)
        val rows = mockk<ResultSet>()
        every { rows.getObject(1) } returns null
        check.observe(rows)
        check.counts() shouldBe emptyMap()
    }

    @Test
    fun `every offending row is counted under its transform and column, never a value`() {
        val check = check(Types.IntegerType.get(), 10)
        val rows = mockk<ResultSet>()
        every { rows.getObject(1) } returns Int.MIN_VALUE
        every { rows.getInt(1) } returns Int.MIN_VALUE
        repeat(3) { check.observe(rows) }
        check.counts() shouldBe mapOf("truncate[10](v)" to 3L)
    }

    @Test
    fun `a value that cannot be read keeps its own refusal for after the scan`() {
        val schema = Schema(Types.NestedField.optional(1, "v", Types.IntegerType.get()))
        val spec = PartitionSpec.builderFor(schema).truncate("v", 10).build()
        // No source type: a non-null value is APPEND_TYPE_MISMATCH in AppendValues.read.
        val check = TruncatePartitionRangeCheck.of(spec, listOf(AppendColumnBinding(schema.findField(1), 1, null)))
        val rows = mockk<ResultSet>()
        every { rows.getObject(1) } returns 7
        check.observe(rows)
        shouldThrow<DatapipelinesException> { check.counts() }.code shouldBe PipelineErrorCodes.Lake.APPEND_TYPE_MISMATCH
    }

    @Test
    fun `strings, identity, bucket, the time transforms, an unbound column and an unpartitioned table are not guarded`() {
        val schema =
            Schema(
                Types.NestedField.optional(1, "n", Types.IntegerType.get()),
                Types.NestedField.optional(2, "s", Types.StringType.get()),
                Types.NestedField.optional(3, "at", Types.TimestampType.withZone()),
                Types.NestedField.optional(4, "m", Types.LongType.get()),
                Types.NestedField.optional(5, "absent", Types.DecimalType.of(15, 3)),
            )
        val spec =
            PartitionSpec
                .builderFor(schema)
                .identity("n")
                .bucket("m", 16)
                .truncate("s", 4)
                .day("at")
                .truncate("absent", 2)
                .build()
        val bindings =
            schema.columns().mapIndexed { index, field ->
                val source = if (field.name() == "absent") null else index + 1
                AppendColumnBinding(field, source, source?.let { field.type().asPrimitiveType() })
            }
        TruncatePartitionRangeCheck.of(spec, bindings).guards shouldBe emptyList()
        TruncatePartitionRangeCheck.of(PartitionSpec.unpartitioned(), bindings).guards shouldBe emptyList()
    }

    @Test
    fun `int, long and decimal truncate fields are each guarded, keyed by transform and column`() {
        val schema =
            Schema(
                Types.NestedField.optional(1, "n", Types.IntegerType.get()),
                Types.NestedField.optional(2, "m", Types.LongType.get()),
                Types.NestedField.optional(3, "amount", Types.DecimalType.of(15, 3)),
            )
        val spec = PartitionSpec.builderFor(schema).truncate("n", 10).truncate("m", 1000).truncate("amount", 2).build()
        val bindings = schema.columns().mapIndexed { index, field -> AppendColumnBinding(field, index + 1, field.type().asPrimitiveType()) }
        TruncatePartitionRangeCheck.of(spec, bindings).guards.map { it.key } shouldBe
            listOf("truncate[10](n)", "truncate[1000](m)", "truncate[2](amount)")
    }

    private fun check(
        type: Type.PrimitiveType,
        width: Int,
    ): TruncatePartitionRangeCheck {
        val schema = Schema(Types.NestedField.optional(1, "v", type))
        val spec = PartitionSpec.builderFor(schema).truncate("v", width).build()
        return TruncatePartitionRangeCheck.of(spec, listOf(AppendColumnBinding(schema.findField(1), 1, type)))
    }

    private fun guard(
        type: Type.PrimitiveType,
        width: Int,
    ): TruncatePartitionRangeCheck.Guard = withClue("one guard for $type truncate[$width]") { check(type, width).guards.single() }

    private fun parse(
        type: Type.PrimitiveType,
        value: String,
    ): Any =
        when (type) {
            is Types.IntegerType -> value.toInt()
            is Types.LongType -> value.toLong()
            is Types.DecimalType -> BigDecimal(value).setScale(type.scale()) // the value as AppendValues writes it
            else -> error("no fixture parser for $type")
        }

    companion object {
        private fun type(name: String): Type.PrimitiveType =
            when (name) {
                "int" -> Types.IntegerType.get()
                "long" -> Types.LongType.get()
                else -> Types.fromPrimitiveString(name) as Type.PrimitiveType
            }

        private const val D15_LOW = "-999999999999.999"
        private const val D15_NEXT = "-999999999999.998"
        private const val D15_HIGH = "999999999999.999"
        private const val D38_LOW = "-99999999999999999999999999999999.999999"
        private const val D38_NEXT = "-99999999999999999999999999999999.999998"
        private const val D38_HIGH = "99999999999999999999999999999999.999999"

        /** Type, width, value, refused — each row is a line of probes.out; -1 and zero cannot leave a type. */
        @JvmStatic
        fun edges(): List<Array<Any>> =
            buildList {
                fun row(
                    type: String,
                    width: Int,
                    value: String,
                    out: Boolean,
                ) = add(arrayOf<Any>(type, width, value, out))

                // decimal: the lowest value leaves the type at [2], [10], [100]; its neighbour at [10] and [100];
                // [3] never; the highest value, -1 and zero never.
                listOf(
                    Triple("decimal(15, 3)", D15_LOW, D15_NEXT) to D15_HIGH,
                    Triple("decimal(38, 6)", D38_LOW, D38_NEXT) to D38_HIGH,
                    Triple("decimal(4, 2)", "-99.99", "-99.98") to "99.99",
                ).forEach { (edge, high) ->
                    val (type, low, next) = edge
                    mapOf(2 to (true to false), 10 to (true to true), 100 to (true to true), 3 to (false to false))
                        .forEach { (width, verdicts) ->
                            row(type, width, low, verdicts.first)
                            row(type, width, next, verdicts.second)
                            row(type, width, high, false)
                            row(type, width, "-1", false)
                            row(type, width, "0", false)
                        }
                }
                // int and long: [2] never wraps (the minimum is even); [10] and [1000] wrap the lowest value and
                // its neighbour; the nearest value that fits, the highest, -1 and zero never.
                row("int", 2, "-2147483648", false)
                row("int", 2, "-2147483647", false)
                row("int", 2, "-2147483646", false)
                row("int", 10, "-2147483648", true)
                row("int", 10, "-2147483647", true)
                row("int", 10, "-2147483638", false)
                row("int", 1000, "-2147483648", true)
                row("int", 1000, "-2147483647", true)
                row("int", 1000, "-2147482648", false)
                row("long", 2, "-9223372036854775808", false)
                row("long", 2, "-9223372036854775807", false)
                row("long", 2, "-9223372036854775806", false)
                row("long", 10, "-9223372036854775808", true)
                row("long", 10, "-9223372036854775807", true)
                row("long", 10, "-9223372036854775798", false)
                row("long", 1000, "-9223372036854775808", true)
                row("long", 1000, "-9223372036854775807", true)
                row("long", 1000, "-9223372036854774808", false)
                listOf("int" to "2147483647", "long" to "9223372036854775807").forEach { (type, high) ->
                    listOf(2, 10, 1000).forEach { width ->
                        row(type, width, high, false)
                        row(type, width, "-1", false)
                        row(type, width, "0", false)
                    }
                }
            }
    }
}
