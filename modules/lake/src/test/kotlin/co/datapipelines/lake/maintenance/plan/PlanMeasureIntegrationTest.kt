package co.datapipelines.lake.maintenance.plan

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames.COMPACTION
import co.datapipelines.lake.maintenance.plan.PlanHarness.FileSpec
import co.datapipelines.lake.profiles.LakeProfileFixtures
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #959 A, measuring over Postgres and MinIO: N1 (every stored figure), N2 (pages, M4), N2a (a failing table does not
 * hold the front), and the reader's FileIO refusing deletes (Security).
 */
class PlanMeasureIntegrationTest {
    private val h = PlanHarness()

    @BeforeEach
    fun clean() = h.reset()

    @AfterEach
    fun close() = h.close()

    /** target 16 MiB, ratio 0.75 (small < 12 MiB), at least 3 small files, cap 32 MiB. */
    private val n1Profile =
        LakeProfileFixtures.document(
            "maintenance.target_file_bytes" to "${16 * MIB}",
            "maintenance.max_bytes_rewritten_per_run" to "${32 * MIB}",
            "maintenance.compact_min_small_files" to "3",
        )

    @Test
    fun `N1 - every stored figure equals the expected value, candidate bytes count each candidate once`() {
        h.editProfile("production", n1Profile)
        val table = h.table("figures")
        h.append(
            table,
            // day 1: three small sorted files, one small UNSORTED file, one full-size sorted file → a candidate partition
            FileSpec(DAY_1, 1 * MIB),
            FileSpec(DAY_1, 2 * MIB),
            FileSpec(DAY_1, 3 * MIB),
            FileSpec(DAY_1, 4 * MIB, sorted = false),
            FileSpec(DAY_1, 16 * MIB),
            // day 2: one small sorted file (not a candidate partition), an unsorted file within the cap, one over it
            FileSpec(DAY_2, 1 * MIB),
            FileSpec(DAY_2, 20 * MIB, sorted = false),
            FileSpec(DAY_2, 40 * MIB, sorted = false),
        )
        h.delete(table, DAY_1, DELETE_BYTES)

        h.pass(h.planner())

        val row = h.measurement(table).shouldNotBeNull()
        row.errorCode.shouldBeNull()
        val figures = row.figures.shouldNotBeNull()
        figures.snapshotId shouldNotBe null
        figures.dataFiles shouldBe 8
        figures.deleteFileCount shouldBe 1
        figures.currentBytes shouldBe 87 * MIB + DELETE_BYTES
        figures.currentRows shouldBe 8 * PlanHarness.RECORDS
        figures.manifests shouldBe 2
        figures.unsortedFiles shouldBe 3
        figures.smallFiles shouldBe 4
        figures.unsortedOverCap shouldBe 1
        // 1 + 2 + 3 + 4 (small, in the candidate partition; the 4 MiB one is also unsorted: once) + 20 (unsorted, fits)
        figures.candidateBytes shouldBe 30 * MIB
        row.figuresProfileId shouldBe h.profileId("production")
        row.figuresProfileUpdatedAt shouldBe h.profileUpdatedAt("production")
        row.attemptProfileId shouldBe h.profileId("production")
    }

    @Test
    fun `N1 - a table with no snapshot is measured with every figure NULL, never zero`() {
        val table = h.table("empty")

        h.pass(h.planner())

        val row = h.measurement(table).shouldNotBeNull()
        row.measuredAt.shouldNotBeNull()
        row.figures shouldBe TableFigures.NO_SNAPSHOT
        val read = h.figures.figures(table.id).shouldNotBeNull()
        read.currentBytes.shouldBeNull()
        read.smallFiles.shouldBeNull()
        read.candidateBytes.shouldBeNull()
        read.growthPerWeek.shouldBeNull()
    }

    @Test
    fun `the reader's FileIO refuses deletes - the planner can delete nothing`() {
        val table = h.table("nodelete")
        val location =
            LakeTestDb.jdbc.queryForObject(
                "SELECT metadata_location FROM iceberg_tables WHERE table_namespace = ? AND table_name = ?",
                String::class.java,
                table.namespace,
                table.name,
            )
        val io =
            h.m.defs.catalogs
                .metadataFileIo()

        shouldThrowAny { io.deleteFile(location) }

        io.newInputFile(location).exists() shouldBe true
    }

    @Test
    fun `N2 - every table is attempted within the ceiling of tables over page passes, oldest attempt first`() {
        val tables = (1..5).map { h.table("t$it") }
        // Postgres orders a uuid by its bytes: the hex text's order, not java.util.UUID's signed compareTo
        val ids = tables.map { it.id }.sortedBy { it.toString() }
        val planner = h.planner(pageSize = 2)

        repeat(3) { h.pass(planner) }

        // ⌈5 ÷ 2⌉ = 3 passes attempt every table: never attempted first (by id), then the oldest attempt
        h.control.metadataReads shouldContainExactly listOf(ids[0], ids[1], ids[2], ids[3], ids[4], ids[0])
        tables.forEach { h.measurement(it).shouldNotBeNull() }

        h.pass(planner)
        h.control.metadataReads.drop(6) shouldContainExactly listOf(ids[1], ids[2])

        // a reassignment moves the table to the front ONCE
        val moved = tables.single { it.id == ids[4] }
        h.assign(moved, "development")
        h.pass(planner)
        h.control.metadataReads.drop(8) shouldContainExactly listOf(ids[4], ids[3])
        h.pass(planner)
        h.control.metadataReads.drop(10) shouldContainExactly listOf(ids[0], ids[1])
    }

    @Test
    fun `N2a - a failing table is attempted once under its new profile, then only in its ordinary turn`() {
        val a = h.table("a")
        val b = h.table("b")
        val c = h.table("c")
        // A on its own profile (development), five small files in one partition: compaction work under it
        h.assign(a, "development")
        h.append(a, *Array(5) { FileSpec(DAY_1, 1 * MIB) })
        val planner = h.planner(pageSize = 1)
        repeat(3) { h.pass(planner) }
        val seeded = h.control.metadataReads.toList()
        seeded.toSet() shouldBe setOf(a.id, b.id, c.id)
        h
            .decide()
            .getValue(a.id)
            .let { COMPACTION in it.due || COMPACTION in it.held } shouldBe true
        val oldestFirst = seeded // the ordinary order from here: the oldest attempt first

        h.editProfile(
            "development",
            LakeProfileFixtures.document(
                "maintenance.window" to "\"00:00-00:00\"",
                "maintenance.cleanup_interval_hours" to "48",
            ),
        )
        h.control.fail(a.id)
        val sequence = mutableListOf<UUID>()
        repeat(4) {
            val before = h.control.metadataReads.size
            h.pass(planner)
            sequence += h.control.metadataReads.drop(before)
            val answer = h.decide().getValue(a.id)
            (COMPACTION in answer.due || COMPACTION in answer.held) shouldBe false
        }

        // A once (under the new profile), then the other two in their turn, and A again only in its ordinary turn
        val others = oldestFirst.filter { it != a.id }
        sequence shouldContainExactly listOf(a.id) + others + listOf(a.id)
        val row = h.measurement(a).shouldNotBeNull()
        row.errorCode shouldBe "unclassified" // the scripted IllegalStateException is not a catalogued failure
        row.attemptProfileUpdatedAt shouldBe h.profileUpdatedAt("development")
        row.figuresProfileUpdatedAt shouldNotBe h.profileUpdatedAt("development")
    }

    companion object {
        const val MIB = 1_048_576L
        const val DAY_1 = "2026-11-01"
        const val DAY_2 = "2026-11-02"
        const val DELETE_BYTES = 50L
    }
}
