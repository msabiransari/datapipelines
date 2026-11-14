package co.datapipelines.lake.maintenance.compaction

import co.datapipelines.lake.definitions.LakeDefinitionFixtures.body
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.field
import co.datapipelines.lake.definitions.LakeDefinitionsHarness
import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.MaintenanceRunState
import co.datapipelines.lake.maintenance.compaction.CompactionHarness.Companion.MIB
import co.datapipelines.lake.maintenance.compaction.CompactionHarness.Companion.multiset
import co.datapipelines.lake.maintenance.compaction.CompactionHarness.Companion.record
import co.datapipelines.lake.maintenance.compaction.CompactionHarness.Fixture
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.iceberg.DataOperations
import org.apache.iceberg.FileFormat
import org.apache.iceberg.FileMetadata
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.Random

/**
 * #969 B, the rewrite over Postgres and MinIO (C1, C2, C5–C10): real managed tables, real Parquet written by Iceberg's
 * writer, a real maintenance run through 957's worker with 959's measurement before it. The oracle is Iceberg's reader
 * and `SortOrderComparators`, never DuckDB. C3 and C4 (earlier schemas and specs) are [CompactionEvolutionIntegrationTest].
 */
class CompactionRewriteIntegrationTest {
    private val h = CompactionHarness()

    @BeforeEach
    fun clean() = h.reset()

    @AfterEach
    fun close() = h.close()

    @Test
    fun `C1 - every admitted type, nulls, signed zeros, NaN, UTF-16-disagreeing strings and old timestamps survive bit for bit`() {
        val table = h.table("c1", ALL_TYPES)
        val schema = h.load(table).schema()
        val rows = allTypeRows(schema)
        h.append(table, Fixture(rows.take(4), sorted = false), Fixture(rows.drop(4), sorted = false))
        val before = multiset(h.rows(table))
        h.measure()

        val parts = h.run(table, listOf(h.part()))

        parts.getValue("compaction")["outcome"].asText() shouldBe MaintenanceOutcome.DONE.wire
        multiset(h.rows(table)) shouldBe before
        h.files(table).map { it.sortOrderId() }.toSet() shouldBe setOf(h.load(table).sortOrder().orderId())
    }

    @Test
    fun `C2 - an unsorted-stamped full-size file is rewritten in order, stamped, and the table converges`() {
        h.profile("maintenance.target_file_bytes" to "${16 * MIB}", "maintenance.max_bytes_rewritten_per_run" to "${64 * MIB}")
        val table = h.table("c2", KEYED)
        h.append(table, Fixture(keyedRows(h.load(table).schema(), Random(2), FULL_FILE_ROWS), sorted = false))
        val before = multiset(h.rows(table))
        h.measure()

        h.run(table, listOf(h.part())).getValue("compaction")["outcome"].asText() shouldBe MaintenanceOutcome.DONE.wire

        val t = h.load(table)
        val files = h.files(table)
        files.forEach { file ->
            file.sortOrderId() shouldBe t.sortOrder().orderId()
            h.outOfOrder(t, h.fileRows(t, file)) shouldBe 0
        }
        multiset(h.rows(table)) shouldBe before
        // Convergence (M12): measured again, nothing is due; a further run commits nothing.
        val snapshot = t.currentSnapshot().snapshotId()
        h.measure()
        h.run(table, listOf(h.part())).getValue("compaction")["outcome"].asText() shouldBe MaintenanceOutcome.NOTHING_TO_DO.wire
        h.load(table).currentSnapshot().snapshotId() shouldBe snapshot
    }

    @Test
    fun `C5 - nullable duplicate two-column keys, one partition over two groups - every file in order, ranges disjoint inside a group`() {
        h.profile("maintenance.target_file_bytes" to "${16 * MIB}", "maintenance.max_bytes_rewritten_per_run" to "${32 * MIB}")
        val table = h.table("c5", TWO_KEYS)
        val schema = h.load(table).schema()
        val random = Random(5)
        h.append(table, *Array(4) { Fixture(twoKeyRows(schema, random, GROUP_FILE_ROWS), sorted = false) })
        val before = multiset(h.rows(table))

        val groups =
            (1..2).map {
                h.measure()
                h.run(table, listOf(h.part())).getValue("compaction")["outcome"].asText() shouldBe MaintenanceOutcome.DONE.wire
                h.load(table).currentSnapshot()
            }

        val t = h.load(table)
        val first = SortOrderFirstColumn(t)
        groups.forEach { snapshot ->
            snapshot.operation() shouldBe DataOperations.REPLACE
            val earlier = h.files(table, snapshot.parentId()).map { it.location() }.toSet()
            val outputs = h.files(table, snapshot.snapshotId()).filter { it.location() !in earlier }.map { h.fileRows(t, it) }
            outputs.size shouldBeGreaterThanOrEqual 2
            outputs.forEach { h.outOfOrder(t, it) shouldBe 0 }
            first.overlapsInside(outputs) shouldBe 0
        }
        multiset(h.rows(table)) shouldBe before
    }

    @Test
    fun `C6 - a group's outputs are at the target but for at most one, and none of them is a candidate afterwards`() {
        h.profile("maintenance.target_file_bytes" to "${16 * MIB}", "maintenance.max_bytes_rewritten_per_run" to "${64 * MIB}")
        val table = h.table("c6", KEYED)
        val schema = h.load(table).schema()
        val random = Random(6)
        h.append(table, Fixture(keyedRows(schema, random, BIG_FILE_ROWS), sorted = false), Fixture(keyedRows(schema, random, BIG_FILE_ROWS), sorted = false))
        h.measure()

        h.run(table, listOf(h.part())).getValue("compaction")["outcome"].asText() shouldBe MaintenanceOutcome.DONE.wire

        val outputs = h.files(table)
        println("(C6) outputs ${outputs.map { it.fileSizeInBytes() }}")
        outputs.size shouldBeGreaterThanOrEqual 3
        outputs.count { it.fileSizeInBytes() < SMALL_BELOW } shouldBeLessThanOrEqual 1
        val snapshot = h.load(table).currentSnapshot().snapshotId()
        h.measure()
        h.run(table, listOf(h.part())).getValue("compaction")["outcome"].asText() shouldBe MaintenanceOutcome.NOTHING_TO_DO.wire
        h.load(table).currentSnapshot().snapshotId() shouldBe snapshot
    }

    @Test
    fun `C7 - one replace snapshot per group, time travel and tags unchanged, replaced files still readable`() {
        val table = h.table("c7", KEYED)
        val schema = h.load(table).schema()
        val random = Random(7)
        val fileA = h.append(table, Fixture(keyedRows(schema, random, SMALL_ROWS), sorted = false)).single()
        val s1 = h.load(table).currentSnapshot().snapshotId()
        h.change(table) { it.manageSnapshots().createTag("before", s1).commit() }
        val fileB = h.append(table, Fixture(keyedRows(schema, random, SMALL_ROWS), sorted = false)).single()
        val s2 = h.load(table).currentSnapshot().snapshotId()
        val at1 = multiset(h.rows(table, s1))
        val at2 = multiset(h.rows(table, s2))
        val snapshots = h.load(table).snapshots().count()
        h.measure()

        h.run(table, listOf(h.part())).getValue("compaction")["outcome"].asText() shouldBe MaintenanceOutcome.DONE.wire

        val t = h.load(table)
        t.snapshots().count() shouldBe snapshots + 1
        t.currentSnapshot().operation() shouldBe DataOperations.REPLACE
        t.currentSnapshot().parentId() shouldBe s2
        multiset(h.rows(table, s1)) shouldBe at1
        multiset(h.rows(table, s2)) shouldBe at2
        multiset(h.rows(table)) shouldBe at2
        t.refs().getValue("before").snapshotId() shouldBe s1
        listOf(fileA, fileB).forEach { t.io().newInputFile(it.location()).exists() shouldBe true }
    }

    @Test
    fun `C8 - an append committed after the plan and before the commit - the rewrite commits, every row present once`() {
        val table = h.table("c8", KEYED)
        val schema = h.load(table).schema()
        val random = Random(8)
        h.append(table, Fixture(keyedRows(schema, random, SMALL_ROWS), sorted = false), Fixture(keyedRows(schema, random, SMALL_ROWS), sorted = false))
        val between = keyedRows(schema, random, SMALL_ROWS)
        val expected = multiset(h.rows(table) + between)
        h.measure()
        var appended = false
        val port =
            DuckDbTestScratch(LakeDefinitionsHarness.BUCKET, before = {
                if (!appended) {
                    appended = true
                    h.append(table, Fixture(between, sorted = true))
                }
            })

        h.run(table, listOf(h.part(port))).getValue("compaction")["outcome"].asText() shouldBe MaintenanceOutcome.DONE.wire

        appended shouldBe true
        h.load(table).currentSnapshot().operation() shouldBe DataOperations.REPLACE
        multiset(h.rows(table)) shouldBe expected
    }

    @Test
    fun `C9 - a reader that drops one row - the group is abandoned, nothing committed, the run failed`() {
        val table = h.table("c9", KEYED)
        val schema = h.load(table).schema()
        val random = Random(9)
        h.append(table, Fixture(keyedRows(schema, random, SMALL_ROWS), sorted = false), Fixture(keyedRows(schema, random, SMALL_ROWS), sorted = false))
        val snapshot = h.load(table).currentSnapshot().snapshotId()
        h.measure()

        val parts = h.run(table, listOf(h.part(DuckDbTestScratch(LakeDefinitionsHarness.BUCKET, rows = CompactionHarness::dropFirstRow))))

        parts.getValue("compaction")["outcome"].asText() shouldBe MaintenanceOutcome.FAILED.wire
        h.lastRun(table).state shouldBe MaintenanceRunState.FAILED.wire
        h.failures.single().message.orEmpty() shouldContain "rows where its inputs hold"
        h.load(table).currentSnapshot().snapshotId() shouldBe snapshot
    }

    @Test
    fun `C10 - a table with a delete file - the part fails naming the table, nothing rewritten`() {
        val table = h.table("c10", KEYED)
        h.append(table, Fixture(keyedRows(h.load(table).schema(), Random(10), SMALL_ROWS), sorted = false))
        h.change(table) { t ->
            val deletes =
                FileMetadata
                    .deleteFileBuilder(t.spec())
                    .ofPositionDeletes()
                    .withPath("${t.location()}/data/c10-test-deletes.parquet")
                    .withFormat(FileFormat.PARQUET)
                    .withFileSizeInBytes(DELETE_BYTES)
                    .withRecordCount(1)
                    .build()
            t.newRowDelta().addDeletes(deletes).commit()
        }
        val snapshot = h.load(table).currentSnapshot().snapshotId()
        h.measure()

        val parts = h.run(table, listOf(h.part()))

        parts.getValue("compaction")["outcome"].asText() shouldBe MaintenanceOutcome.FAILED.wire
        h.lastRun(table).state shouldBe MaintenanceRunState.FAILED.wire
        h.failures.single().message.orEmpty() shouldContain table.id.toString()
        h.load(table).currentSnapshot().snapshotId() shouldBe snapshot
        h.files(table) shouldHaveSize 1
    }

    private companion object {
        const val FULL_FILE_ROWS = 13_000
        const val BIG_FILE_ROWS = 20_000
        const val GROUP_FILE_ROWS = 11_000
        const val SMALL_ROWS = 300
        const val PAD_BYTES = 1024
        const val DELETE_BYTES = 100L
        const val BIG_SCALE = 6
        const val KEY_SPACE = 40
        const val NULL_ONE_IN = 20
        val SMALL_BELOW = 12 * MIB

        /** Strings whose UTF-8 (code point) and UTF-16 orders disagree: U+FF61 sorts before U+1F600 by code point only. */
        val DISAGREEING = listOf("｡", "😀", "a", "Z", "é", "")

        /** Every type a definition admits; sorted by the string `k` and the timestamp `ts`. */
        val ALL_TYPES: String =
            body(
                listOf(
                    field(1, "k", "STRING"),
                    field(2, "flag", "BOOLEAN"),
                    field(3, "i", "INTEGER"),
                    field(4, "l", "BIGINTEGER"),
                    field(5, "d", "DECIMAL"),
                    field(6, "n", "DECIMAL", "precision" to 12, "scale" to 2),
                    field(7, "big", "BIGDECIMAL", "precision" to 30, "scale" to 6),
                    field(8, "bin", "BINARY"),
                    field(9, "day", "DATE"),
                    field(10, "at", "TIME"),
                    field(11, "ts", "TIMESTAMP"),
                ),
                sort = listOf(1, 11),
            )

        /** A string key and incompressible padding (sizes are the padding's). */
        val KEYED: String = body(listOf(field(1, "k", "STRING"), field(2, "pad", "BINARY")))

        /** Two nullable sort columns with duplicates, one identity partition (one value: one partition). */
        val TWO_KEYS: String =
            body(
                listOf(field(1, "k1", "INTEGER"), field(2, "k2", "STRING"), field(3, "region", "STRING"), field(4, "pad", "BINARY")),
                partition = listOf(mapOf("field_id" to 3, "transform" to "identity")),
                sort = listOf(1, 2),
            )

        fun allTypeRows(schema: org.apache.iceberg.Schema) =
            listOf(
                listOf(DISAGREEING[0], true, Int.MIN_VALUE, Long.MAX_VALUE, -0.0, "-9999999999.99", "-123456789012345678901234.123456"),
                listOf(DISAGREEING[1], false, Int.MAX_VALUE, Long.MIN_VALUE, 0.0, "0.01", "0.000001"),
                listOf(DISAGREEING[2], true, 0, 0L, Double.NaN, "12.30", "999999999999999999999999.999999"),
                listOf(null, null, null, null, null, null, null),
                listOf(DISAGREEING[3], false, -1, 1L, Double.NEGATIVE_INFINITY, "1.00", "-0.000001"),
                listOf(DISAGREEING[4], true, 7, -7L, Double.MIN_VALUE, "-0.01", "1.5"),
                listOf(DISAGREEING[5], true, 1, 2L, 1.5, "3.14", "2.718281"),
            ).mapIndexed { index, v ->
                val nulls = v[0] == null
                record(
                    schema,
                    v[0],
                    v[1],
                    v[2],
                    v[3],
                    v[4],
                    (v[5] as String?)?.let(::BigDecimal),
                    (v[6] as String?)?.let { BigDecimal(it).setScale(BIG_SCALE) },
                    if (nulls) null else ByteBuffer.wrap(byteArrayOf(0, -1, index.toByte(), Byte.MAX_VALUE)),
                    if (nulls) null else LocalDate.of(1969, 12, 31).plusDays(index * 400L - 1200L),
                    if (nulls) null else LocalTime.of(23, 59, 59, 999_999_000).minusSeconds(index * 3_600L),
                    if (nulls) null else OffsetDateTime.parse("1969-12-31T23:59:59.999999Z").plusSeconds(index * 86_400L * 700 - 86_400L * 2000),
                )
            }

        fun keyedRows(
            schema: org.apache.iceberg.Schema,
            random: Random,
            count: Int,
        ) = List(count) {
            record(schema, DISAGREEING[random.nextInt(DISAGREEING.size)] + random.nextInt(), CompactionHarness.noise(random, PAD_BYTES))
        }

        fun twoKeyRows(
            schema: org.apache.iceberg.Schema,
            random: Random,
            count: Int,
        ) = List(count) {
            record(
                schema,
                if (random.nextInt(NULL_ONE_IN) == 0) null else random.nextInt(KEY_SPACE),
                if (random.nextInt(NULL_ONE_IN) == 0) null else DISAGREEING[random.nextInt(DISAGREEING.size)],
                "eu",
                CompactionHarness.noise(random, PAD_BYTES),
            )
        }
    }
}
