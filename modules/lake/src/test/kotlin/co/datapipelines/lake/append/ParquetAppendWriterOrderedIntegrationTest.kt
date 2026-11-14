package co.datapipelines.lake.append

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.profiles.LakeProfileFixtures
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.apache.iceberg.DataFile
import org.apache.iceberg.NullOrder
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Schema
import org.apache.iceberg.SortOrder
import org.apache.iceberg.SortOrderComparators
import org.apache.iceberg.Table
import org.apache.iceberg.data.GenericRecord
import org.apache.iceberg.data.InternalRecordWrapper
import org.apache.iceberg.data.Record
import org.apache.iceberg.types.Type
import org.apache.iceberg.types.Types
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.ByteBuffer
import java.util.UUID

/**
 * #968 (dp-lake design record §11a call 37, rows M13's append path and M14): an append to a table with sort columns is
 * written in the table's order and its files are stamped with it. The oracle is Iceberg's own reader
 * ([ParquetWriterHarness.read], `GenericParquetReaders`) and comparator (`SortOrderComparators.forSchema`), never
 * DuckDB. Every input is staged in an order that is NOT the table's (each test asserts that, so it can fail), and the
 * position list the writer passed to the scan is asserted beside the files' order.
 */
class ParquetAppendWriterOrderedIntegrationTest {
    private val h = ParquetWriterHarness()
    private val writer = ParquetAppendWriter()

    @BeforeEach
    fun reset() = LakeTestDb.reset()

    @Test
    fun `O1a - both sort columns supplied - the write scan orders by their staged positions and every file is in order and stamped`() {
        h.withTable(O1_SCHEMA, PartitionSpec.unpartitioned(), O1_ORDER) { table ->
            // Staged order note, score, id, region: region (field id 3, schema index 1) is staged position 4 and score
            // (field id 12, schema index 2) staged position 2. A field id (3, 12) or a schema index used as the
            // position is refused or orders by the wrong columns.
            h.withInput("SELECT note, score, id, region FROM ($O1_ROWS) r") { input ->
                withClue("non-vacuity: the staged arrival order is not the table's") { arrivalSorted(table, input) shouldBe false }
                val written = writer.write(table, input, UUID.randomUUID(), 1, PROFILE) {}
                println("(O1a) orders ${input.orders}; statements ${input.statements}")
                input.orders.takeLast(2) shouldBe listOf(emptyList(), listOf(4, 2))
                written.rows shouldBe O1_ROW_COUNT
                assertInTableOrder(table, written.files)
                val rows = h.read(table, written.files)
                withClue("nulls first: the first row's region is null") { rows.first().getField("region") shouldBe null }
                withClue("the second key decides inside the first key's ties") {
                    rows
                        .filter { it.getField("region") == "a" }
                        .map { it.getField("score") as Int? }
                        .let { scores -> scores shouldBe scores.sortedWith(nullsFirst()) }
                }
            }
        }
    }

    @Test
    fun `O1b - the optional second sort column left out - ordered by the first, stamped`() {
        h.withTable(O1_SCHEMA, PartitionSpec.unpartitioned(), O1_ORDER) { table ->
            h.withInput("SELECT note, id, region FROM ($O1_ROWS) r") { input ->
                withClue("non-vacuity") { arrivalSorted(table, input) shouldBe false }
                val written = writer.write(table, input, UUID.randomUUID(), 1, PROFILE) {}
                input.orders.takeLast(2) shouldBe listOf(emptyList(), listOf(3))
                assertInTableOrder(table, written.files)
                h.read(table, written.files).all { it.getField("score") == null } shouldBe true
            }
        }
    }

    @Test
    fun `O4 - the optional first sort column left out by the delivery - the append succeeds, ordered by the remaining one, stamped`() {
        h.withTable(O1_SCHEMA, PartitionSpec.unpartitioned(), O1_ORDER) { table ->
            h.withInput("SELECT score, note, id FROM ($O1_ROWS) r") { input ->
                withClue("non-vacuity") { arrivalSorted(table, input) shouldBe false }
                val written = writer.write(table, input, UUID.randomUUID(), 1, PROFILE) {}
                input.orders.takeLast(2) shouldBe listOf(emptyList(), listOf(1))
                written.rows shouldBe O1_ROW_COUNT
                assertInTableOrder(table, written.files)
                val rows = h.read(table, written.files)
                rows.all { it.getField("region") == null } shouldBe true
                rows.map { it.getField("score") as Int? }.let { it shouldBe it.sortedWith(nullsFirst()) }
            }
        }
    }

    @Test
    fun `O2 - one partition, three or more files at a small target - first-column ranges are disjoint but for one shared boundary`() {
        val schema =
            Schema(
                Types.NestedField.required(4, "id", Types.LongType.get()),
                Types.NestedField.optional(9, "k", Types.IntegerType.get()),
            )
        val order =
            SortOrder
                .builderFor(schema)
                .asc("k", NullOrder.NULLS_FIRST)
                .asc("id", NullOrder.NULLS_FIRST)
                .build()
        // A target of one byte rolls a file at every check, which the pinned writer makes every 1,000 rows.
        val small = PROFILE.copy(targetFileBytes = 1, rowGroupBytes = SMALL_ROW_GROUP)
        h.withTable(schema, PartitionSpec.unpartitioned(), order) { table ->
            h.withInput("SELECT i::BIGINT AS id, ((i * 37) % 500)::INTEGER AS k FROM range($O2_ROWS) t(i)") { input ->
                withClue("non-vacuity") { arrivalSorted(table, input) shouldBe false }
                val written = writer.write(table, input, UUID.randomUUID(), 1, small) {}
                written.files.size shouldBeGreaterThanOrEqual 3
                assertInTableOrder(table, written.files)
                val ranges =
                    written.files
                        .map { file -> h.read(table, listOf(file)).map { it.getField("k") as Int } }
                        .map { ks -> ks.min() to ks.max() }
                        .sortedWith(compareBy({ it.first }, { it.second }))
                println("(O2) ${written.files.size} files, k ranges $ranges")
                ranges.zipWithNext().forEach { (left, right) ->
                    withClue("$left then $right overlap in more than one boundary value") { (left.second <= right.first) shouldBe true }
                }
            }
        }
    }

    @Test
    fun `O3 - a legacy table - no ORDER BY in either scan, stamp 0`() {
        h.withTable(O1_SCHEMA) { table ->
            h.withInput("SELECT note, score, id, region FROM ($O1_ROWS) r") { input ->
                val written = writer.write(table, input, UUID.randomUUID(), 1, PROFILE) {}
                input.orders shouldBe listOf(emptyList(), emptyList())
                input.statements.forEach { it shouldNotContain "ORDER BY" }
                input.statements shouldBe listOf("SELECT * FROM append_input799", "SELECT * FROM append_input799")
                written.files.map { it.sortOrderId() } shouldBe listOf(0)
                written.files.all { h.codecs(table, it) == setOf("ZSTD") } shouldBe true
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["identity", "day"])
    fun `O5 - partitioned - each partition's files are in order and the open-partitions refusal fires at the same count`(function: String) {
        val spec =
            PartitionSpec
                .builderFor(O5_SCHEMA)
                .apply { if (function == "identity") identity("region") else day("at") }
                .build()
        h.withTable(O5_SCHEMA, spec, O5_ORDER) { table ->
            h.withInput(O5_ROWS) { input ->
                withClue("non-vacuity") { arrivalSorted(table, input) shouldBe false }
                val written = writer.write(table, input, UUID.randomUUID(), 16, PROFILE) {}
                written.files
                    .map { it.partition() }
                    .toSet()
                    .size shouldBe 3
                written.files.forEach { assertInTableOrder(table, listOf(it)) }
            }
        }
        LakeTestDb.reset()
        h.withTable(O5_SCHEMA, spec, O5_ORDER) { table ->
            h.withInput(O5_ROWS) { input ->
                val failure = shouldThrow<DatapipelinesException> { writer.write(table, input, UUID.randomUUID(), 2, PROFILE) {} }
                failure.code shouldBe PipelineErrorCodes.Lake.APPEND_TOO_MANY_PARTITIONS
                failure.details["limit"] shouldBe 2
                h.dataKeys(table).size shouldBe 2
                table.currentSnapshot() shouldBe null
            }
        }
    }

    @Test
    fun `O6 - nothing changes on the way - the sorted write's rows equal the arrival-order write's, bit for bit`() {
        val sorted = mutableListOf<List<Any?>>()
        val legacy = mutableListOf<List<Any?>>()
        val byText = SortOrder.builderFor(O6_SCHEMA).asc("text", NullOrder.NULLS_FIRST).build()
        h.withTable(O6_SCHEMA, PartitionSpec.unpartitioned(), byText) { table ->
            h.withInput(O6_ROWS) { input ->
                withClue("non-vacuity") { arrivalSorted(table, input) shouldBe false }
                val written = writer.write(table, input, UUID.randomUUID(), 1, PROFILE) {}
                assertInTableOrder(table, written.files)
                h.read(table, written.files).mapTo(sorted) { bits(it) }
            }
        }
        LakeTestDb.reset()
        h.withTable(O6_SCHEMA) { table ->
            h.withInput(O6_ROWS) { input ->
                h.read(table, writer.write(table, input, UUID.randomUUID(), 1, PROFILE) {}.files).mapTo(legacy) { bits(it) }
            }
        }
        val approx = O6_SCHEMA.columns().indexOfFirst { it.name() == "approx" }
        withClue("the fixture holds 0.0, -0.0, NaN and both infinities in the non-key approx column") {
            sorted.map { it[approx] }.toSet() shouldBe
                setOf(0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, null).map { raw(it) }.toSet()
        }
        sorted.size shouldBe O6_ROW_COUNT
        sorted shouldContainExactlyInAnyOrder legacy
        withClue("the sort reordered the rows") { sorted shouldNotBe legacy }
    }

    @ParameterizedTest
    @ValueSource(strings = ["boolean", "int", "long", "decimal", "string", "date", "time", "timestamptz"])
    fun `O7 - every sort type 935 admits - file order equals the comparator's`(type: String) {
        val (icebergType, values) = O7_TYPES.getValue(type)
        val schema =
            Schema(
                Types.NestedField.required(2, "id", Types.LongType.get()),
                Types.NestedField.optional(5, "v", icebergType),
            )
        val order = SortOrder.builderFor(schema).asc("v", NullOrder.NULLS_FIRST).build()
        val rows = values.mapIndexed { i, v -> "(${i + 1}::BIGINT, $v)" }.joinToString(", ")
        h.withTable(schema, PartitionSpec.unpartitioned(), order) { table ->
            h.withInput("SELECT * FROM (VALUES $rows) t(id, v)") { input ->
                withClue("non-vacuity") { arrivalSorted(table, input) shouldBe false }
                val written = writer.write(table, input, UUID.randomUUID(), 1, PROFILE) {}
                written.rows shouldBe values.size.toLong()
                assertInTableOrder(table, written.files)
                if (type == "string") {
                    val read = h.read(table, written.files).map { it.getField("v") }
                    withClue("code-point (UTF-8) order puts U+FF5E before U+1F600; UTF-16 order would not") {
                        read.indexOf("～") shouldBeLessThan read.indexOf("😀")
                    }
                }
            }
        }
    }

    /** Every file is stamped with the table's order and its rows, read by Iceberg, are in the comparator's order. */
    private fun assertInTableOrder(
        table: Table,
        files: List<DataFile>,
    ) {
        table.sortOrder().orderId() shouldBeGreaterThan 0
        files.size shouldBeGreaterThan 0
        files.forEach { file ->
            assertSoftly {
                withClue("${file.location()} stamp") { file.sortOrderId() shouldBe table.sortOrder().orderId() }
                val rows = h.read(table, listOf(file))
                withClue("${file.location()} order") { inversions(table, rows) shouldBe 0 }
            }
        }
    }

    /**
     * Whether the staged rows, in arrival order (a plain scan), already are in the table's order. Its two scans are
     * recorded by the input, so the writer's scans are the last two.
     */
    private fun arrivalSorted(
        table: Table,
        input: ParquetWriterHarness.NativeInput,
    ): Boolean {
        val plan = AppendColumnCheck().check(input, table.schema(), table.spec())
        val rows = mutableListOf<Record>()
        input.scan { cursor ->
            while (cursor.next()) {
                val record = GenericRecord.create(table.schema())
                plan.bindings.forEachIndexed { index, binding -> record.set(index, AppendValues.read(cursor, binding)) }
                rows += record
            }
        }
        return inversions(table, rows) == 0
    }

    private fun inversions(
        table: Table,
        rows: List<Record>,
    ): Int {
        val comparator = SortOrderComparators.forSchema(table.schema(), table.sortOrder())
        val left = InternalRecordWrapper(table.schema().asStruct())
        val right = InternalRecordWrapper(table.schema().asStruct())
        return rows.zipWithNext().count { (a, b) -> comparator.compare(left.wrap(a), right.wrap(b)) > 0 }
    }

    private fun bits(record: Record): List<Any?> = (0 until record.size()).map { raw(record.get(it)) }

    /** A comparable form: a double by its raw bits (so -0.0 and NaN payloads count), a buffer by its bytes. */
    private fun raw(value: Any?): Any? =
        when (value) {
            is Double -> "double:" + java.lang.Double.doubleToRawLongBits(value)
            is Float -> "float:" + java.lang.Float.floatToRawIntBits(value)
            is ByteBuffer -> "bytes:" + value.duplicate().let { b -> ByteArray(b.remaining()).also { b.get(it) } }.toList()
            else -> value
        }

    private companion object {
        val PROFILE = LakeProfileFixtures.builtIn()
        const val SMALL_ROW_GROUP = 4096L
        const val O2_ROWS = 3500

        /** Non-consecutive field ids; schema order id, region, score, note. */
        val O1_SCHEMA =
            Schema(
                Types.NestedField.required(7, "id", Types.LongType.get()),
                Types.NestedField.optional(3, "region", Types.StringType.get()),
                Types.NestedField.optional(12, "score", Types.IntegerType.get()),
                Types.NestedField.optional(5, "note", Types.StringType.get()),
            )
        val O1_ORDER: SortOrder =
            SortOrder
                .builderFor(O1_SCHEMA)
                .asc("region", NullOrder.NULLS_FIRST)
                .asc("score", NullOrder.NULLS_FIRST)
                .build()
        const val O1_ROW_COUNT = 60L

        /** Region repeats (a, b, c, null); score is scrambled inside each region tie and null every fifth row. */
        const val O1_ROWS =
            "SELECT i::BIGINT AS id, " +
                "CASE i % 4 WHEN 0 THEN NULL WHEN 1 THEN 'b' WHEN 2 THEN 'a' ELSE 'c' END AS region, " +
                "CASE WHEN i % 5 = 0 THEN NULL ELSE ((i * 7919) % 13)::INTEGER END AS score, " +
                "'n' || i AS note FROM range(60) t(i)"

        val O5_SCHEMA =
            Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.required(2, "region", Types.StringType.get()),
                Types.NestedField.required(3, "at", Types.TimestampType.withZone()),
                Types.NestedField.optional(4, "score", Types.IntegerType.get()),
            )
        val O5_ORDER: SortOrder =
            SortOrder
                .builderFor(O5_SCHEMA)
                .asc("score", NullOrder.NULLS_FIRST)
                .asc("id", NullOrder.NULLS_FIRST)
                .build()

        /** Three regions on three days, one per row in turn; score scrambled with nulls. */
        const val O5_ROWS =
            "SELECT i::BIGINT AS id, ['x', 'y', 'z'][(i % 3) + 1] AS region, " +
                "TIMESTAMP '2026-11-01 06:00:00' + to_days((i % 3)::INTEGER) AS at, " +
                "CASE WHEN i % 7 = 0 THEN NULL ELSE ((i * 31) % 17)::INTEGER END AS score FROM range(90) t(i)"

        val O6_SCHEMA =
            Schema(
                Types.NestedField.required(21, "id", Types.LongType.get()),
                Types.NestedField.optional(22, "large", Types.DecimalType.of(20, 0)),
                Types.NestedField.optional(23, "amount", Types.DecimalType.of(18, 2)),
                Types.NestedField.optional(24, "approx", Types.DoubleType.get()),
                Types.NestedField.optional(25, "text", Types.StringType.get()),
                Types.NestedField.optional(26, "payload", Types.BinaryType.get()),
                Types.NestedField.optional(27, "day", Types.DateType.get()),
                Types.NestedField.optional(28, "clock", Types.TimeType.get()),
                Types.NestedField.optional(29, "at", Types.TimestampType.withZone()),
                Types.NestedField.optional(30, "flag", Types.BooleanType.get()),
                Types.NestedField.optional(31, "small", Types.IntegerType.get()),
            )
        const val O6_ROW_COUNT = 6

        /** The key is `text`; `approx` (not a key) holds 0.0, -0.0, NaN, +inf, -inf and null. */
        val O6_ROWS =
            """
            SELECT * FROM (VALUES
              (1::BIGINT, 18446744073709551615::UBIGINT, 1234.56::DECIMAL(18,2), '0.0'::DOUBLE, 'f', from_hex('01'),
               DATE '1969-12-31', TIME '00:00:01', TIMESTAMP '1969-12-31 23:59:59.999999', true, -1),
              (2::BIGINT, 0::UBIGINT, -0.01::DECIMAL(18,2), '-0.0'::DOUBLE, 'b', from_hex('0203'),
               DATE '2026-11-14', TIME '23:59:59.999999', TIMESTAMP '2026-11-14 01:02:03.000004', false, 2147483647),
              (3::BIGINT, NULL::UBIGINT, NULL::DECIMAL(18,2), 'NaN'::DOUBLE, NULL, NULL,
               NULL::DATE, NULL::TIME, NULL::TIMESTAMP, NULL::BOOLEAN, NULL::INTEGER),
              (4::BIGINT, 7::UBIGINT, 0.00::DECIMAL(18,2), 'Infinity'::DOUBLE, 'e', from_hex(''),
               DATE '1900-01-01', TIME '12:00:00', TIMESTAMP '1900-01-01 00:00:00', true, -2147483648),
              (5::BIGINT, 1::UBIGINT, 99.99::DECIMAL(18,2), '-Infinity'::DOUBLE, 'a', from_hex('ff'),
               DATE '2000-02-29', TIME '06:30:00', TIMESTAMP '2000-02-29 06:30:00', false, 0),
              (6::BIGINT, 2::UBIGINT, -99.99::DECIMAL(18,2), NULL::DOUBLE, 'c', from_hex('00'),
               DATE '1970-01-01', TIME '00:00:00', TIMESTAMP '1970-01-01 00:00:00', NULL::BOOLEAN, 5)
            ) t(id, large, amount, approx, "text", payload, "day", clock, "at", flag, small)
            """.trimIndent()

        /** One column per type 935 admits as a sort column (not BINARY, not floating): staged values, unsorted, with null. */
        val O7_TYPES: Map<String, Pair<Type.PrimitiveType, List<String>>> =
            mapOf(
                "boolean" to (Types.BooleanType.get() to listOf("true", "false", "NULL::BOOLEAN", "true", "false")),
                "int" to
                    (
                        Types.IntegerType.get() to
                            listOf(
                                "5::INTEGER",
                                "(-3)::INTEGER",
                                "NULL::INTEGER",
                                "2147483647::INTEGER",
                                "(-2147483648)::INTEGER",
                                "0::INTEGER",
                            )
                    ),
                "long" to
                    (
                        Types.LongType.get() to
                            listOf(
                                "9223372036854775807::BIGINT",
                                "(-9223372036854775808)::BIGINT",
                                "NULL::BIGINT",
                                "0::BIGINT",
                                "(-1)::BIGINT",
                            )
                    ),
                "decimal" to
                    (
                        Types.DecimalType.of(12, 3) to
                            listOf(
                                "(-1.500)::DECIMAL(12,3)",
                                "2.250::DECIMAL(12,3)",
                                "(-100.000)::DECIMAL(12,3)",
                                "NULL::DECIMAL(12,3)",
                                "0.000::DECIMAL(12,3)",
                                "(-0.001)::DECIMAL(12,3)",
                            )
                    ),
                "string" to
                    (
                        Types.StringType.get() to
                            listOf("'😀'", "'a'", "'～'", "NULL::VARCHAR", "'Z'", "''", "'é'", "'a'")
                    ),
                "date" to
                    (
                        Types.DateType.get() to
                            listOf("DATE '2026-11-14'", "DATE '1969-12-31'", "NULL::DATE", "DATE '1900-01-01'", "DATE '1970-01-01'")
                    ),
                "time" to
                    (
                        Types.TimeType.get() to
                            listOf("TIME '23:59:59.999999'", "TIME '00:00:00'", "NULL::TIME", "TIME '12:00:00'", "TIME '00:00:00.000001'")
                    ),
                "timestamptz" to
                    (
                        Types.TimestampType.withZone() to
                            listOf(
                                "TIMESTAMP '2026-11-14 01:02:03'",
                                "TIMESTAMP '1969-12-31 23:59:59.999999'",
                                "NULL::TIMESTAMP",
                                "TIMESTAMP '1950-06-01 00:00:00'",
                                "TIMESTAMP '1970-01-01 00:00:00'",
                            )
                    ),
            )
    }
}
