package co.datapipelines.staging

import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.StagingEngine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.SQLException
import java.util.UUID

/**
 * Every delete path obeys the sweep's rules (#803; staging.md §8.5): the same-id cleanup at open
 * removes only a provably dead leftover or this factory's own earlier attempt, and a failed open
 * removes only what that attempt created.
 */
class DuckDbSameIdLeftoverTest {
    @TempDir
    lateinit var scratch: Path

    /** A factory whose sweep already ran, so a leftover planted afterwards meets the OPEN, not the sweep. */
    private fun sweptFactory(): DuckDbStagingFactory =
        duckFactory(scratch).also {
            it.prepareDelivery(UUID.randomUUID(), MIB).close()
            it.budget.leftoverBytes shouldBe 0L
        }

    private fun snapshot(files: DuckDbStagingFiles): Map<Path, ByteArray> =
        (files.pathsWithMarker + listOf(files.tempDir.resolve("duckdb_temp_storage_DEFAULT-0.tmp")))
            .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .associateWith { Files.readAllBytes(it) }

    @Test
    fun `same id, provably dead leftover - removed and the open succeeds`() {
        val factory = sweptFactory()
        val id = UUID.randomUUID()
        val planted = plantDatabase(scratch, id, ownerId = plantDeadOwner(scratch))
        planted.existing().size shouldBe 3

        factory.create(id, StagingEngine.DUCKDB).use { it.scalarText("SELECT 42") shouldBe "42" }

        pathsOf(scratch, id).shouldBeEmpty()
        Files.exists(planted.marker) shouldBe false
    }

    @Test
    fun `same id, unvouched leftover - the open fails and every file is still there`() {
        val factory = sweptFactory()
        val id = UUID.randomUUID()
        val planted = plantDatabase(scratch, id, ownerId = null)
        val before = snapshot(planted)
        before.size shouldBe 3

        val refused = shouldThrow<DatapipelinesException> { factory.create(id, StagingEngine.DUCKDB) }

        // Rules out: the unconditional same-id delete at open (today's behaviour before #803).
        refused.code shouldBe StagingErrorCodes.CREATION_FAILED
        snapshot(planted).mapValues { it.value.toList() } shouldBe before.mapValues { it.value.toList() }
        Files.exists(planted.marker) shouldBe false
        factory.budget.reservedBytes shouldBe 0L
    }

    @Test
    fun `same id, damaged marker - the open fails and every file is still there`() {
        val factory = sweptFactory()
        val id = UUID.randomUUID()
        val planted = plantDatabase(scratch, id, ownerId = null)
        Files.writeString(planted.marker, "../../etc/passwd")
        val before = snapshot(planted)
        before.size shouldBe 4

        shouldThrow<DatapipelinesException> { factory.create(id, StagingEngine.DUCKDB) }.code shouldBe StagingErrorCodes.CREATION_FAILED

        snapshot(planted).mapValues { it.value.toList() } shouldBe before.mapValues { it.value.toList() }
    }

    @Test
    fun `same id, live in another factory - the open fails and the live database is intact`() {
        val holder = duckFactory(scratch)
        val live = holder.open()
        live.authorExec("CREATE TABLE t AS SELECT 1 AS id")
        val other = sweptFactory()

        val refused = shouldThrow<DatapipelinesException> { other.create(live.executionId, StagingEngine.DUCKDB) }

        refused.code shouldBe StagingErrorCodes.CREATION_FAILED
        other.budget.reservedBytes shouldBe 0L
        live.authorExec("INSERT INTO t VALUES (2)")
        live.scalarText("SELECT count(*) FROM t") shouldBe "2"
        Files.exists(DuckDbStagingFiles(scratch, live.executionId).marker) shouldBe true
        live.close()
        pathsOf(scratch, live.executionId).shouldBeEmpty()
    }

    @Test
    fun `same id, open in this factory - the second open fails and the first is intact`() {
        val factory = sweptFactory()
        val live = factory.open()

        val refused = shouldThrow<DatapipelinesException> { factory.create(live.executionId, StagingEngine.DUCKDB) }

        refused.code shouldBe StagingErrorCodes.CREATION_FAILED

        live.scalarText("SELECT 1") shouldBe "1"
        live.close()
        factory.budget.reservedBytes shouldBe 0L
    }

    @Test
    fun `a failed open leaves every file that existed before it, a lookalike included`() {
        val props = duckProps(scratch)
        val failing =
            DuckDbStagingFactory(props, ScratchBudget(props.scratchCapacityBytes)) { _, _ ->
                throw SQLException("simulated open failure")
            }
        val id = UUID.randomUUID()
        val lookalike = Files.writeString(scratch.resolve("exec_$id.duckdb.bak"), "operator's copy")
        val unrelated = Files.writeString(scratch.resolve("notes.txt"), "keep")

        shouldThrow<DatapipelinesException> { failing.create(id, StagingEngine.DUCKDB) }.code shouldBe StagingErrorCodes.CREATION_FAILED

        // Rules out: the failed-open cleanup deleting every name that starts with the stem.
        Files.readString(lookalike) shouldBe "operator's copy"
        Files.readString(unrelated) shouldBe "keep"
        Files.exists(DuckDbStagingFiles(scratch, id).marker) shouldBe false
        pathsOf(scratch, id) shouldBe listOf(lookalike.fileName.toString())
        failing.budget.reservedBytes shouldBe 0L
    }

    @Test
    fun `this factory's own earlier attempt that close could not delete is removed by a later open of the same id`() {
        val factory = sweptFactory()
        val first = factory.open()
        val files = DuckDbStagingFiles(scratch, first.executionId)
        val pinned = Files.createDirectories(files.tempDir.resolve("pinned"))
        Files.writeString(pinned.resolve("file"), "x")
        Files.setPosixFilePermissions(pinned, PosixFilePermissions.fromString("r-x------"))
        try {
            assumeFalse(Files.isWritable(pinned), "the process can write a r-x directory (root?)")
            first.close()
            // The marker outlived the failed delete: what is left is still this factory's.
            pathsOf(scratch, first.executionId) shouldBe listOf(files.tempDir.fileName.toString())
            Files.exists(files.marker) shouldBe true
        } finally {
            Files.setPosixFilePermissions(pinned, PosixFilePermissions.fromString("rwx------"))
        }

        factory.create(first.executionId, StagingEngine.DUCKDB).close()

        pathsOf(scratch, first.executionId).shouldBeEmpty()
        Files.exists(files.marker) shouldBe false
    }
}
