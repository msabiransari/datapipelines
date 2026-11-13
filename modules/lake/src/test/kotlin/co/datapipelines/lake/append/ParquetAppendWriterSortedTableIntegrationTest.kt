package co.datapipelines.lake.append

import co.datapipelines.lake.LakeTestDb
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.apache.iceberg.NullOrder
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Schema
import org.apache.iceberg.SortOrder
import org.apache.iceberg.types.Types
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The append writer on a table that has sort columns (lake.md §7; dp-lake design record §11a call 36 item 4 and input
 * (d)): I8 every file is stamped unsorted, since the writer writes in arrival order and a stamp never claims an order the
 * file was not written in; I9 a sort column beyond Iceberg's first 100 columns still gets its bounds.
 */
class ParquetAppendWriterSortedTableIntegrationTest {
    private val h = ParquetWriterHarness()
    private val writer = ParquetAppendWriter()

    @BeforeEach
    fun reset() = LakeTestDb.reset()

    @Test
    fun `I8 - a sorted table's appended files are stamped unsorted`() {
        val schema =
            Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.optional(2, "label", Types.StringType.get()),
            )
        val order =
            SortOrder
                .builderFor(schema)
                .asc("label", NullOrder.NULLS_FIRST)
                .asc("id", NullOrder.NULLS_FIRST)
                .build()
        h.withTable(schema, PartitionSpec.unpartitioned(), order) { table ->
            h.withInput("SELECT * FROM (VALUES (2::BIGINT, 'b'), (1::BIGINT, 'a'), (3::BIGINT, NULL)) t(id, label)") { input ->
                val written = writer.write(table, input, UUID.randomUUID(), 1) {}
                println("(I8) table order ${table.sortOrder()}; file orders ${written.files.map { it.sortOrderId() }}")
                table.sortOrder().orderId() shouldNotBe 0
                written.files.size shouldBe 1
                written.files.map { it.sortOrderId() } shouldBe listOf(0)
            }
        }
    }

    @Test
    fun `I9 - on a 150-column table the 150th column, a sort column, has bounds and the 149th has none`() {
        val schema = Schema((1..WIDTH).map { Types.NestedField.optional(it, "c$it", Types.LongType.get()) })
        val order = SortOrder.builderFor(schema).asc("c$WIDTH", NullOrder.NULLS_FIRST).build()
        val columns = (1..WIDTH).joinToString(", ") { "(i + $it)::BIGINT AS c$it" }
        h.withTable(schema, PartitionSpec.unpartitioned(), order) { table ->
            h.withInput("SELECT $columns FROM range(3) t(i)") { input ->
                val file = writer.write(table, input, UUID.randomUUID(), 1) {}.files.single()
                val bounded = file.lowerBounds().keys.sorted()
                println("(I9) properties ${table.properties().filterKeys { it.contains("metrics") }}; lower-bound ids $bounded")
                file.lowerBounds().containsKey(WIDTH) shouldBe true
                file.upperBounds().containsKey(WIDTH) shouldBe true
                file.lowerBounds().containsKey(WIDTH - 1) shouldBe false
                file.upperBounds().containsKey(WIDTH - 1) shouldBe false
            }
        }
    }

    private companion object {
        const val WIDTH = 150
    }
}
