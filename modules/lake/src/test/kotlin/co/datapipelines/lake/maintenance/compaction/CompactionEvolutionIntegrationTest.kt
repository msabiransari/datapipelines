package co.datapipelines.lake.maintenance.compaction

import co.datapipelines.lake.definitions.LakeDefinitionFixtures.body
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.field
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.partition
import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.compaction.CompactionHarness.Companion.multiset
import co.datapipelines.lake.maintenance.compaction.CompactionHarness.Companion.record
import co.datapipelines.lake.maintenance.compaction.CompactionHarness.Fixture
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.apache.iceberg.DataOperations
import org.apache.iceberg.types.Types
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * #969 C3 and C4: one table holds files of earlier schemas and an earlier partition spec (the definition policy applies
 * an added optional column, a widening and an added partition field automatically). The rewrite reads every file by
 * field id under the CURRENT schema and writes a group under its inputs' spec and partition value. The oracle is
 * Iceberg's reader. C1's table passes every type a definition admits through the `schema` map; this class adds `FLOAT`
 * (a float column exists only by a test-only schema change: no definition type maps to it).
 */
class CompactionEvolutionIntegrationTest {
    private val h = CompactionHarness()

    @BeforeEach
    fun clean() = h.reset()

    @AfterEach
    fun close() = h.close()

    @Test
    fun `C3 - files before an added column, int to long, float to double and a decimal widening are rewritten with later files`() {
        val table = h.table("c3", V1)
        // File A: the first schema (i INTEGER, n DECIMAL(10,2)), no `note`, no `f`.
        val a = listOf(listOf<Any?>("a1", Int.MAX_VALUE, BigDecimal("12345678.91")), listOf<Any?>("a0", Int.MIN_VALUE, BigDecimal("-0.01")))
        h.append(table, Fixture(a.map { record(h.load(table).schema(), *it.toTypedArray()) }, sorted = false))
        // File B: the released v2 (i BIGINTEGER, n DECIMAL(14,2), an optional `note`).
        h.evolve(table, V2)
        val b = listOf(listOf<Any?>("b1", Long.MAX_VALUE, BigDecimal("999999999999.99"), "note b1"))
        h.append(table, Fixture(b.map { record(h.load(table).schema(), *it.toTypedArray()) }, sorted = false))
        // Files C and D: a float column added and later widened to double (test-only commits; see the class KDoc).
        h.change(table) { it.updateSchema().addColumn("f", Types.FloatType.get()).commit() }
        val c = listOf(listOf<Any?>("c1", 3L, BigDecimal("0.10"), null, -0.0f), listOf<Any?>("c0", 4L, BigDecimal("0.20"), "x", 1.1f))
        h.append(table, Fixture(c.map { record(h.load(table).schema(), *it.toTypedArray()) }, sorted = false))
        h.change(table) { it.updateSchema().updateColumn("f", Types.DoubleType.get()).commit() }
        val d = listOf(listOf<Any?>("d1", 5L, BigDecimal("1.00"), "d", 0.1))
        h.append(table, Fixture(d.map { record(h.load(table).schema(), *it.toTypedArray()) }, sorted = false))
        val before = h.rows(table)
        h.files(table) shouldHaveSize 4
        h.measure()

        h.run(table, listOf(h.part())).getValue("compaction")["outcome"].asText() shouldBe MaintenanceOutcome.DONE.wire

        val after = h.rows(table)
        h.load(table).currentSnapshot().operation() shouldBe DataOperations.REPLACE
        h.files(table) shouldHaveSize 1
        multiset(after) shouldBe multiset(before)
        val byKey = after.associateBy { it.getField("k") }
        // The added columns are null for the rows written before them; the widened values are equal.
        byKey.getValue("a1").getField("note") shouldBe null
        byKey.getValue("a1").getField("f") shouldBe null
        byKey.getValue("a1").getField("i") shouldBe Int.MAX_VALUE.toLong()
        byKey.getValue("a1").getField("n") shouldBe BigDecimal("12345678.91")
        java.lang.Double.doubleToRawLongBits(byKey.getValue("c1").getField("f") as Double) shouldBe
            java.lang.Double.doubleToRawLongBits((-0.0f).toDouble())
        byKey.getValue("c0").getField("f") shouldBe 1.1f.toDouble()
    }

    @Test
    fun `C4 - after an added partition field, the old spec's files are rewritten among themselves and keep spec and partition`() {
        val table = h.table("c4", P1)
        val schema1 = h.load(table).schema()
        h.append(
            table,
            Fixture(List(ROWS) { record(schema1, "old-$it", "eu", "x") }, sorted = false),
            Fixture(List(ROWS) { record(schema1, "old2-$it", "eu", "y") }, sorted = false),
        )
        val oldSpec = h.load(table).spec().specId()
        h.evolve(table, P2)
        val t2 = h.load(table)
        val newSpec = t2.spec().specId()
        h.append(
            table,
            Fixture(List(ROWS) { record(t2.schema(), "new-$it", "eu", "x") }, sorted = false),
            Fixture(List(ROWS) { record(t2.schema(), "new2-$it", "eu", "x") }, sorted = false),
        )
        val before = h.files(table)
        val rows = multiset(h.rows(table))
        h.measure()

        h.run(table, listOf(h.part())).getValue("compaction")["outcome"].asText() shouldBe MaintenanceOutcome.DONE.wire

        val after = h.files(table)
        after.none { file -> before.any { it.location() == file.location() } } shouldBe true
        val t = h.load(table)
        val partitions = after.map { it.specId() to t.specs().getValue(it.specId()).partitionToPath(it.partition()) }
        partitions shouldContainExactlyInAnyOrder listOf(oldSpec to "region=eu", newSpec to "region=eu/cat=x")
        multiset(h.rows(table)) shouldBe rows
    }

    private companion object {
        const val ROWS = 50

        val V1: String = body(listOf(field(1, "k", "STRING"), field(2, "i", "INTEGER"), field(3, "n", "DECIMAL", "precision" to 10, "scale" to 2)))

        val V2: String =
            body(
                listOf(
                    field(1, "k", "STRING"),
                    field(2, "i", "BIGINTEGER"),
                    field(3, "n", "DECIMAL", "precision" to 14, "scale" to 2),
                    field(4, "note", "STRING"),
                ),
            )

        val P1: String =
            body(listOf(field(1, "k", "STRING"), field(2, "region", "STRING"), field(3, "cat", "STRING")), partition = listOf(partition(2, "identity")))

        val P2: String =
            body(
                listOf(field(1, "k", "STRING"), field(2, "region", "STRING"), field(3, "cat", "STRING")),
                partition = listOf(partition(2, "identity"), partition(3, "identity")),
            )
    }
}
