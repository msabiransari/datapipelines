package co.datapipelines.lake.append

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Schema
import org.apache.iceberg.types.Types
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CancellationException

/** A4's files are real, readable, uncommitted and never deleted on refusal. */
class ParquetAppendWriterIntegrationTest {
    private val h = ParquetWriterHarness()
    private val writer = ParquetAppendWriter()

    @BeforeEach
    fun reset() = LakeTestDb.reset()

    @Test
    fun `native values bind by name into zstd files without loss or a catalog commit`() {
        h.withTable(VALUE_SCHEMA) { table ->
            h.withInput(VALUE_SQL) { input ->
                val operation = UUID.randomUUID()
                val written = writer.write(table, input, operation, 1) {}
                input.scans shouldBe 2
                written.rows shouldBe 1
                written.files.size shouldBe 1
                val file = written.files.single()
                file.recordCount() shouldBe 1
                file.sortOrderId() shouldBe 0
                file.location().startsWith("${table.location()}/data/") shouldBe true
                file.location().contains(operation.toString()) shouldBe true
                h.codecs(table, file) shouldBe setOf("ZSTD")
                table.currentSnapshot() shouldBe null
                val row = h.read(table, written.files).single()
                row.getField("id") shouldBe 4_294_967_295L
                row.getField("large") shouldBe BigDecimal("18446744073709551615")
                row.getField("amount") shouldBe BigDecimal("1234.56")
                row.getField("approx") shouldBe 0.1f.toDouble()
                row.getField("text") shouldBe "preserved"
                row.getField("payload") shouldBe ByteBuffer.wrap(byteArrayOf(1, 2))
                row.getField("day") shouldBe LocalDate.parse("2026-11-05")
                row.getField("clock") shouldBe LocalTime.parse("12:34:56.123456")
                row.getField("at") shouldBe OffsetDateTime.parse("2026-11-05T12:34:56.123456Z")
                row.getField("zoned") shouldBe OffsetDateTime.parse("2026-11-05T09:34:56.123456Z")
                row.getField("missing") shouldBe null
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["identity", "year", "month", "day", "hour", "bucket", "truncate"])
    fun `fanout uses every admitted partition function and writes readable schema ordered rows`(partitionFunction: String) {
        val spec = spec(partitionFunction)
        val sql =
            if (partitionFunction == "bucket") {
                "SELECT i::BIGINT AS id, TIMESTAMP '2026-11-05 00:30:00' AS at, 'abcd'::VARCHAR AS region FROM range(1024) t(i)"
            } else {
                "SELECT * FROM (VALUES (1::BIGINT, TIMESTAMP '2026-11-05 00:30:00', 'abcd'), " +
                    "(2::BIGINT, TIMESTAMP '2026-11-05 00:50:00', 'abef'), " +
                    "(3::BIGINT, TIMESTAMP '2027-01-01 00:30:00', 'cdef')) t(id, \"at\", region)"
            }
        h.withTable(PARTITION_SCHEMA, spec) { table ->
            h.withInput(sql) { input ->
                val written = writer.write(table, input, UUID.randomUUID(), 16) {}
                val expectedRows = if (partitionFunction == "bucket") 1024L else 3L
                written.rows shouldBe expectedRows
                written.files.sumOf { it.recordCount() } shouldBe expectedRows
                h.read(table, written.files).size.toLong() shouldBe expectedRows
                written.files.all { it.location().startsWith("${table.location()}/data/") } shouldBe true
                written.files.all { h.codecs(table, it) == setOf("ZSTD") } shouldBe true
                assertPartitions(partitionFunction, written.files.map { it.partition().get(0, Any::class.java) }.toSet())
                table.currentSnapshot() shouldBe null
            }
        }
    }

    @Test
    fun `a required null anywhere stops before the first data file is created`() {
        h.withTable(ID_SCHEMA) { table ->
            h.withInput("SELECT * FROM (VALUES (1::BIGINT), (NULL::BIGINT)) t(id)") { input ->
                val failure = shouldThrow<DatapipelinesException> { writer.write(table, input, UUID.randomUUID(), 1) {} }
                failure.code shouldBe PipelineErrorCodes.Lake.APPEND_NULL_IN_REQUIRED
                input.scans shouldBe 1
                h.dataKeys(table) shouldBe emptyList()
                table.currentSnapshot() shouldBe null
            }
        }
    }

    @Test
    fun `the excess partition is refused and already written files remain uncommitted`() {
        val spec = PartitionSpec.builderFor(PARTITION_SCHEMA).identity("region").build()
        h.withTable(PARTITION_SCHEMA, spec) { table ->
            h.withInput("SELECT i::BIGINT AS id, now() AS at, i::VARCHAR AS region FROM range(3) t(i)") { input ->
                val failure = shouldThrow<DatapipelinesException> { writer.write(table, input, UUID.randomUUID(), 2) {} }
                failure.code shouldBe PipelineErrorCodes.Lake.APPEND_TOO_MANY_PARTITIONS
                h.dataKeys(table).size shouldBe 2
                table.currentSnapshot() shouldBe null
            }
        }
    }

    @Test
    fun `caller cancellation during the writing scan closes but never removes its file`() {
        h.withTable(ID_SCHEMA) { table ->
            h.withInput("SELECT i::BIGINT AS id FROM range(3) t(i)") { input ->
                var writes = 0
                shouldThrow<CancellationException> {
                    writer.write(table, input, UUID.randomUUID(), 1) {
                        if (input.scans == 2 && ++writes == 2) throw CancellationException("Cancelled by caller.")
                    }
                }
                h.dataKeys(table).size shouldBe 1
                table.currentSnapshot() shouldBe null
            }
        }
    }

    @Test
    fun `an empty staged result creates no data file`() {
        h.withTable(ID_SCHEMA) { table ->
            h.withInput("SELECT 1::BIGINT AS id WHERE false") { input ->
                writer.write(table, input, UUID.randomUUID(), 1) {} shouldBe WrittenAppend(0, emptyList())
                h.dataKeys(table) shouldBe emptyList()
            }
        }
    }

    private fun spec(partitionFunction: String): PartitionSpec =
        PartitionSpec
            .builderFor(PARTITION_SCHEMA)
            .apply {
                when (partitionFunction) {
                    "identity" -> identity("region")
                    "year" -> year("at")
                    "month" -> month("at")
                    "day" -> day("at")
                    "hour" -> hour("at")
                    "bucket" -> bucket("id", 16)
                    "truncate" -> truncate("region", 2)
                    else -> error("unknown fixture partition function")
                }
            }.build()

    private fun assertPartitions(
        partitionFunction: String,
        actual: Set<Any>,
    ) {
        val first = OffsetDateTime.parse("2026-11-05T00:30:00Z")
        val second = OffsetDateTime.parse("2027-01-01T00:30:00Z")
        when (partitionFunction) {
            "identity" -> {
                actual shouldBe setOf("abcd", "abef", "cdef")
            }

            "truncate" -> {
                actual shouldBe setOf("ab", "cd")
            }

            "year" -> {
                actual shouldBe setOf(56, 57)
            }

            "month" -> {
                actual shouldBe setOf(682, 684)
            }

            "day" -> {
                actual shouldBe setOf(first.toLocalDate().toEpochDay().toInt(), second.toLocalDate().toEpochDay().toInt())
            }

            "hour" -> {
                actual shouldBe
                    setOf((first.toEpochSecond() / SECONDS_PER_HOUR).toInt(), (second.toEpochSecond() / SECONDS_PER_HOUR).toInt())
            }

            "bucket" -> {
                actual shouldBe (0 until 16).toSet()
            }

            else -> {
                error("unknown fixture partition function")
            }
        }
    }

    private companion object {
        const val SECONDS_PER_HOUR = 3600L
        val ID_SCHEMA = Schema(Types.NestedField.required(1, "id", Types.LongType.get()))
        val PARTITION_SCHEMA =
            Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.required(2, "at", Types.TimestampType.withZone()),
                Types.NestedField.required(3, "region", Types.StringType.get()),
            )
        val VALUE_SCHEMA =
            Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.required(2, "large", Types.DecimalType.of(20, 0)),
                Types.NestedField.optional(3, "amount", Types.DecimalType.of(18, 2)),
                Types.NestedField.optional(4, "approx", Types.DoubleType.get()),
                Types.NestedField.optional(5, "text", Types.StringType.get()),
                Types.NestedField.optional(6, "payload", Types.BinaryType.get()),
                Types.NestedField.optional(7, "day", Types.DateType.get()),
                Types.NestedField.optional(8, "clock", Types.TimeType.get()),
                Types.NestedField.optional(9, "at", Types.TimestampType.withZone()),
                Types.NestedField.optional(10, "zoned", Types.TimestampType.withZone()),
                Types.NestedField.optional(11, "missing", Types.StringType.get()),
            )
        val VALUE_SQL =
            """
            SELECT 'preserved' AS text, 4294967295::UINTEGER AS "ID", 18446744073709551615::UBIGINT AS large,
                   1234.56::DECIMAL(12,2) AS amount, 0.1::FLOAT AS approx, from_hex('0102') AS payload,
                   DATE '2026-11-05' AS day, TIME '12:34:56.123456' AS clock,
                   TIMESTAMP '2026-11-05 12:34:56.123456' AS at,
                   TIMESTAMPTZ '2026-11-05 12:34:56.123456+03:00' AS zoned
            """.trimIndent()
    }
}
