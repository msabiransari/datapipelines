package co.datapipelines.lake.maintenance.compaction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import org.apache.iceberg.Schema
import org.apache.iceberg.SortOrder
import org.apache.iceberg.types.Types
import org.junit.jupiter.api.Test

/**
 * #969 D6, the statement as text (Security: integers and a closed type vocabulary only; the file list bound). The
 * engine side is the integration classes'.
 */
class CompactionStatementTest {
    private val schema =
        Schema(
            Types.NestedField.optional(1, "k'); DROP TABLE x; --", Types.StringType.get()),
            Types.NestedField.optional(2, "flag", Types.BooleanType.get()),
            Types.NestedField.optional(3, "i", Types.IntegerType.get()),
            Types.NestedField.optional(4, "l", Types.LongType.get()),
            Types.NestedField.optional(5, "f", Types.FloatType.get()),
            Types.NestedField.optional(6, "d", Types.DoubleType.get()),
            Types.NestedField.optional(7, "n", Types.DecimalType.of(30, 6)),
            Types.NestedField.optional(8, "bin", Types.BinaryType.get()),
            Types.NestedField.optional(9, "day", Types.DateType.get()),
            Types.NestedField.optional(10, "at", Types.TimeType.get()),
            Types.NestedField.optional(11, "ts", Types.TimestampType.withZone()),
        )

    @Test
    fun `the map names every column by field id and a closed type, no column name reaches the text, one bound parameter`() {
        val order =
            SortOrder
                .builderFor(schema)
                .asc("k'); DROP TABLE x; --")
                .asc("ts")
                .build()
        val statement = CompactionStatement.of(schema, order)

        statement.sql shouldBe
            "SELECT c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11 FROM read_parquet(?, schema = MAP {" +
            "1: {name: 'c1', type: 'VARCHAR', default_value: NULL}, 2: {name: 'c2', type: 'BOOLEAN', default_value: NULL}, " +
            "3: {name: 'c3', type: 'INTEGER', default_value: NULL}, 4: {name: 'c4', type: 'BIGINT', default_value: NULL}, " +
            "5: {name: 'c5', type: 'FLOAT', default_value: NULL}, 6: {name: 'c6', type: 'DOUBLE', default_value: NULL}, " +
            "7: {name: 'c7', type: 'DECIMAL(30,6)', default_value: NULL}, 8: {name: 'c8', type: 'BLOB', default_value: NULL}, " +
            "9: {name: 'c9', type: 'DATE', default_value: NULL}, 10: {name: 'c10', type: 'TIME', default_value: NULL}, " +
            "11: {name: 'c11', type: 'TIMESTAMPTZ', default_value: NULL}}, hive_partitioning = false) " +
            "ORDER BY #1 COLLATE \"binary\" ASC NULLS FIRST, 11 ASC NULLS FIRST"
        statement.sql shouldNotContain "DROP"
        statement.sql.count { it == '?' } shouldBe 1
        statement.sql.count { it == '\'' } shouldBe 2 * (11 + 11)
        statement.stamp shouldBe order
        statement.bindings.map { it.sourceIndex } shouldBe (1..11).toList()
    }

    @Test
    fun `a legacy table with no sort order is merged only - no ORDER BY, stamped unsorted`() {
        val statement = CompactionStatement.of(schema, SortOrder.unsorted())
        statement.sql shouldEndWith "hive_partitioning = false)"
        statement.sql shouldNotContain "ORDER BY"
        statement.stamp.isUnsorted shouldBe true
    }

    @Test
    fun `a floating sort column is refused by 968's order, and a type outside the vocabulary is a defect`() {
        val floating = shouldThrow<IllegalStateException> { CompactionStatement.of(schema, SortOrder.builderFor(schema).asc("d").build()) }
        floating.message.orEmpty() shouldContain "floating-point"
        val error = shouldThrow<IllegalStateException> { CompactionStatement.duckDbType(Types.UUIDType.get()) }
        error.message.orEmpty() shouldContain "cannot be compacted"
        shouldThrow<IllegalStateException> {
            CompactionStatement.of(Schema(Types.NestedField.optional(1, "s", Types.StructType.of())), SortOrder.unsorted())
        }
    }
}
