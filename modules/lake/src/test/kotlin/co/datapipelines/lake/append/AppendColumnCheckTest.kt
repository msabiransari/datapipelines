package co.datapipelines.lake.append

import co.datapipelines.pipeline.LakeAppendColumn
import co.datapipelines.pipeline.LakeAppendInput
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.apache.iceberg.Schema
import org.apache.iceberg.types.Type
import org.apache.iceberg.types.Types
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.sql.ResultSet
import java.sql.ResultSetMetaData

/** Binding is a native type check, not the canonical envelope's fallback-to-text policy. */
class AppendColumnCheckTest {
    private val checker = AppendColumnCheck()

    @Test
    fun `materialization cannot hide duplicate original column labels by renaming them`() {
        val stored = input(listOf(Column("id", "INTEGER"), Column("id_1", "INTEGER")))
        val original =
            object : LakeAppendInput by stored {
                override val originalColumns =
                    listOf(LakeAppendColumn("id", "INTEGER", 0, 0), LakeAppendColumn("ID", "INTEGER", 0, 0))
            }
        shouldThrow<DatapipelinesException> { checker.check(original, schema(Types.IntegerType.get())) }
            .code shouldBe PipelineErrorCodes.Lake.APPEND_AMBIGUOUS_COLUMN
    }

    @Test
    fun `a materialized null retains its original SQL NULL type for an optional string field`() {
        val stored = input(listOf(Column("id", "INTEGER")), listOf(listOf(null)))
        val original =
            object : LakeAppendInput by stored {
                override val originalColumns = listOf(LakeAppendColumn("id", "SQLNULL", 0, 0))
            }
        val plan = checker.check(original, schema(Types.StringType.get()))
        plan.bindings.single().sourceType shouldBe null
        plan.rowCount shouldBe 1
    }

    @Test
    fun `column validation stops at the callers cancellation or deadline during its scan`() {
        var checks = 0
        val failure =
            shouldThrow<java.util.concurrent.CancellationException> {
                checker.check(
                    input(listOf(Column("id", "INTEGER")), listOf(listOf(1), listOf(2), listOf(3))),
                    schema(Types.IntegerType.get(), required = true),
                ) {
                    if (++checks == 3) throw java.util.concurrent.CancellationException("Stopped by caller.")
                }
            }
        checks shouldBe 3
        failure.message shouldBe "Stopped by caller."
    }

    @Test
    fun `columns bind case insensitively in target field order and missing optional columns become null`() {
        val schema =
            Schema(
                Types.NestedField.required(11, "id", Types.LongType.get()),
                Types.NestedField.optional(24, "label", Types.StringType.get()),
                Types.NestedField.optional(39, "omitted", Types.StringType.get()),
            )
        val plan = checker.check(input(listOf(Column("LABEL", "VARCHAR"), Column("Id", "INTEGER"))), schema)
        plan.schema shouldBe schema
        plan.bindings.map { it.sourceIndex } shouldBe listOf(2, 1, null)
        plan.bindings.map { it.field.fieldId() } shouldBe listOf(11, 24, 39)
        plan.bindings.last().sourceType shouldBe null
    }

    @Test
    fun `two staged names differing only in case are ambiguous before types are inspected`() {
        val failure =
            shouldThrow<DatapipelinesException> {
                checker.check(input(listOf(Column("id", "INTEGER"), Column("ID", "INTEGER"))), schema(Types.IntegerType.get()))
            }
        failure.code shouldBe PipelineErrorCodes.Lake.APPEND_AMBIGUOUS_COLUMN
        failure.details["columns"] shouldBe listOf("id")
    }

    @Test
    fun `extra columns are refused instead of dropped`() {
        val failure =
            shouldThrow<DatapipelinesException> {
                checker.check(input(listOf(Column("id", "INTEGER"), Column("extra", "VARCHAR"))), schema(Types.IntegerType.get()))
            }
        failure.code shouldBe PipelineErrorCodes.Lake.APPEND_UNKNOWN_COLUMN
        failure.details["columns"] shouldBe listOf("extra")
    }

    @Test
    fun `a missing required field is refused even for an empty input`() {
        shouldThrow<DatapipelinesException> { checker.check(input(emptyList()), schema(Types.IntegerType.get(), required = true)) }
            .code shouldBe PipelineErrorCodes.Lake.APPEND_MISSING_REQUIRED_COLUMN
    }

    @Test
    fun `every null in every required column is counted before returning a write plan`() {
        val schema =
            Schema(
                Types.NestedField.required(1, "id", Types.IntegerType.get()),
                Types.NestedField.required(2, "name", Types.StringType.get()),
                Types.NestedField.optional(3, "optional", Types.StringType.get()),
            )
        val failure =
            shouldThrow<DatapipelinesException> {
                checker.check(
                    input(
                        listOf(Column("id", "INTEGER"), Column("name", "VARCHAR"), Column("optional", "VARCHAR")),
                        listOf(listOf(1, null, null), listOf(null, "a", null), listOf(null, null, "kept")),
                    ),
                    schema,
                )
            }
        failure.code shouldBe PipelineErrorCodes.Lake.APPEND_NULL_IN_REQUIRED
        failure.details["null_counts"] shouldBe mapOf("id" to 2L, "name" to 2L)
    }

    @Test
    fun `a typed NULL column can fill an optional field but fails a required field by counted nulls`() {
        val optional = checker.check(input(listOf(Column("id", "SQLNULL")), listOf(listOf(null))), schema(Types.StringType.get()))
        optional.bindings.single().sourceIndex shouldBe 1
        optional.bindings.single().sourceType shouldBe null
        val failure =
            shouldThrow<DatapipelinesException> {
                checker.check(input(listOf(Column("id", "SQLNULL")), listOf(listOf(null))), schema(Types.StringType.get(), required = true))
            }
        failure.code shouldBe PipelineErrorCodes.Lake.APPEND_NULL_IN_REQUIRED
        failure.details["null_counts"] shouldBe mapOf("id" to 1L)
    }

    @ParameterizedTest
    @MethodSource("compatible")
    fun `native types admit only equal representations and Iceberg promotions`(pair: Pair<Column, Type.PrimitiveType>) {
        val plan = checker.check(input(listOf(pair.first)), schema(pair.second))
        plan.bindings.single().sourceIndex shouldBe 1
    }

    @ParameterizedTest
    @MethodSource("incompatible")
    fun `narrowing scale changes and numeric or binary coercion are refused`(pair: Pair<Column, Type.PrimitiveType>) {
        shouldThrow<DatapipelinesException> { checker.check(input(listOf(pair.first)), schema(pair.second)) }
            .code shouldBe PipelineErrorCodes.Lake.APPEND_TYPE_MISMATCH
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "HUGEINT", "UHUGEINT", "UUID", "JSON", "INTEGER[]", "STRUCT(a INTEGER)", "MAP", "UNION",
            "TIME WITH TIME ZONE", "TIMESTAMP_NS",
        ],
    )
    fun `native types cannot hide loss through a fallback string mapping`(native: String) {
        shouldThrow<DatapipelinesException> {
            checker.check(input(listOf(Column("id", native))), schema(Types.StringType.get()))
        }.code shouldBe PipelineErrorCodes.Lake.APPEND_TYPE_MISMATCH
    }

    @ParameterizedTest
    @MethodSource("nonAsciiIdentifiers")
    fun `non ASCII names cannot alias an ASCII managed field through Unicode case folding`(names: Pair<String, String>) {
        val target = Schema(Types.NestedField.optional(1, names.second, Types.IntegerType.get()))
        val failure =
            shouldThrow<DatapipelinesException> {
                checker.check(input(listOf(Column(names.first, "INTEGER"))), target)
            }
        failure.code shouldBe PipelineErrorCodes.Lake.APPEND_UNKNOWN_COLUMN
        failure.details["columns"] shouldBe listOf(names.first)
    }

    @Test
    fun `a declared precision beyond Iceberg or an impossible scale is refused`() {
        listOf(Column("id", "DECIMAL", 39, 0), Column("id", "DECIMAL", 0, 0), Column("id", "DECIMAL", 12, 13)).forEach { column ->
            shouldThrow<DatapipelinesException> { checker.check(input(listOf(column)), schema(Types.DecimalType.of(38, 0))) }
                .code shouldBe PipelineErrorCodes.Lake.APPEND_TYPE_MISMATCH
        }
    }

    private fun schema(
        type: Type.PrimitiveType,
        required: Boolean = false,
    ) = Schema(if (required) Types.NestedField.required(1, "id", type) else Types.NestedField.optional(1, "id", type))

    private fun input(
        columns: List<Column>,
        values: List<List<Any?>> = emptyList(),
    ): LakeAppendInput =
        object : LakeAppendInput {
            override fun scan(consume: (ResultSet) -> Unit) {
                val metadata = mockk<ResultSetMetaData>()
                every { metadata.columnCount } returns columns.size
                every { metadata.isNullable(any()) } returns ResultSetMetaData.columnNoNulls
                columns.forEachIndexed { index, column ->
                    every { metadata.getColumnLabel(index + 1) } returns column.name
                    every { metadata.getColumnTypeName(index + 1) } returns column.native
                    every { metadata.getPrecision(index + 1) } returns column.precision
                    every { metadata.getScale(index + 1) } returns column.scale
                }
                val rows = mockk<ResultSet>()
                var cursor = -1
                every { rows.metaData } returns metadata
                every { rows.next() } answers { ++cursor < values.size }
                every { rows.getObject(any<Int>()) } answers { values[cursor][firstArg<Int>() - 1] }
                consume(rows)
            }
        }

    data class Column(
        val name: String,
        val native: String,
        val precision: Int = 0,
        val scale: Int = 0,
    )

    companion object {
        @JvmStatic
        fun compatible(): List<Pair<Column, Type.PrimitiveType>> =
            buildList {
                listOf(
                    "TINYINT",
                    "SMALLINT",
                    "INTEGER",
                    "UTINYINT",
                    "USMALLINT",
                ).forEach { add(Column("id", it) to Types.IntegerType.get()) }
                listOf("BIGINT", "UINTEGER", "INTEGER").forEach { add(Column("id", it) to Types.LongType.get()) }
                add(Column("id", "UBIGINT") to Types.DecimalType.of(20, 0))
                listOf("float", "REAL", "DOUBLE").forEach { add(Column("id", it) to Types.DoubleType.get()) }
                add(Column("id", "BOOLEAN") to Types.BooleanType.get())
                add(Column("id", "VARCHAR") to Types.StringType.get())
                add(Column("id", "BLOB") to Types.BinaryType.get())
                add(Column("id", "DATE") to Types.DateType.get())
                add(Column("id", "TIME") to Types.TimeType.get())
                listOf("TIMESTAMP", "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE", "TIMESTAMP_S", "TIMESTAMP_MS")
                    .forEach { add(Column("id", it) to Types.TimestampType.withZone()) }
                add(Column("id", "DECIMAL(12,2)", 12, 2) to Types.DecimalType.of(12, 2))
                add(Column("id", "DECIMAL", 12, 2) to Types.DecimalType.of(18, 2))
            }

        @JvmStatic
        fun nonAsciiIdentifiers(): List<Pair<String, String>> = listOf("K" to "k", "İ" to "i", "ı" to "i", "ſ" to "s")

        @JvmStatic
        fun incompatible(): List<Pair<Column, Type.PrimitiveType>> =
            listOf(
                Column("id", "BIGINT") to Types.IntegerType.get(),
                Column("id", "DOUBLE") to Types.FloatType.get(),
                Column("id", "INTEGER") to Types.DoubleType.get(),
                Column("id", "DECIMAL", 18, 2) to Types.DecimalType.of(12, 2),
                Column("id", "DECIMAL", 12, 2) to Types.DecimalType.of(18, 3),
                Column("id", "DECIMAL", 12, 2) to Types.DoubleType.get(),
                Column("id", "BLOB") to Types.StringType.get(),
            )
    }
}
