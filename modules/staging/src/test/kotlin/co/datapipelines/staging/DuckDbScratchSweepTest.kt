package co.datapipelines.staging

import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.event.Level
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * The startup sweep of the DuckDB scratch directory (#803; staging.md §8.5; owner ruling 2026-11-12):
 * a provably dead owner's databases are removed, a live one's are never touched, and what nobody can
 * vouch for is counted against the aggregate, never deleted. Every test first asserts its planted files
 * exist (non-vacuity). The cross-process cases are [DuckDbScratchCrossProcessTest].
 */
class DuckDbScratchSweepTest {
    @TempDir
    lateinit var scratch: Path

    private fun housekeeper(log: RecordingLogger = RecordingLogger()) =
        DuckDbScratchHousekeeper(scratch, DuckDbScratchOwnership.newOwner(), log)

    @Test
    fun `a dead owner's databases are removed by a new factory's sweep and the reserved figure is unchanged`() {
        val dead = plantDeadOwner(scratch)
        val first = plantDatabase(scratch, ownerId = dead)
        val second = plantDatabase(scratch, ownerId = dead, spillBytes = 4096)
        namesIn(scratch) shouldHaveSize 9
        val factory = duckFactory(scratch)

        factory.prepareDelivery(UUID.randomUUID(), MIB).close()

        // Rules out: leftovers of a crashed process surviving the next start (the issue's symptom).
        (first.pathsWithMarker + second.pathsWithMarker).filter { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }.shouldBeEmpty()
        Files.exists(scratch.resolve(DuckDbScratchNames.owner(dead))) shouldBe false
        factory.budget.reservedBytes shouldBe 0L
        factory.budget.leftoverBytes shouldBe 0L
    }

    @Test
    fun `leftovers nobody can vouch for are counted, never deleted, and one WARN names every path and the total`() {
        val unvouched = plantDatabase(scratch, ownerId = null, databaseBytes = 100, walBytes = 20, spillBytes = 30)
        val damaged = plantDatabase(scratch, ownerId = null, databaseBytes = 1, walBytes = 1, spillBytes = 1)
        Files.writeString(damaged.marker, "not-a-uuid")
        val stray = Files.write(scratch.resolve("exec_${UUID.randomUUID()}.duckdb.bak"), ByteArray(9))
        val before = namesIn(scratch)
        before shouldHaveSize 8
        val log = RecordingLogger()

        val result = housekeeper(log).sweep()

        // Rules out: an unconditional or age-based delete (the ruled-out behaviour).
        namesIn(scratch) shouldBe before
        result.removed.shouldBeEmpty()
        result.counted.keys shouldContainAll (unvouched.paths + damaged.pathsWithMarker + listOf(stray))
        result.countedBytes shouldBe (150L + 3 + "not-a-uuid".length + 9)
        val warns = log.at(Level.WARN)
        warns shouldHaveSize 1
        warns.single() shouldContain "${result.countedBytes} bytes"
        (unvouched.paths + damaged.paths + listOf(stray)).forEach { warns.single() shouldContain it.toString() }
    }

    @Test
    fun `the factory debits the counted bytes and its read tells not-swept from swept-zero without sweeping`() {
        val fresh = duckFactory(scratch)
        plantDatabase(scratch, ownerId = null, databaseBytes = 40, walBytes = 0, spillBytes = 0)
        fresh.budget.leftoverBytes.shouldBeNull()
        fresh.budget.leftoverBytes.shouldBeNull()

        fresh.prepareDelivery(UUID.randomUUID(), MIB).close()

        fresh.budget.leftoverBytes shouldBe 40L
        fresh.budget.reservedBytes shouldBe 0L
        val empty = duckFactory(Files.createDirectory(scratch.resolve("empty"), ownerOnly()))
        empty.prepareDelivery(UUID.randomUUID(), MIB).close()
        empty.budget.leftoverBytes shouldBe 0L
    }

    @Test
    fun `the sweep runs once per factory`() {
        plantDatabase(scratch, ownerId = null, databaseBytes = 40, walBytes = 0, spillBytes = 0)
        val factory = duckFactory(scratch)
        factory.prepareDelivery(UUID.randomUUID(), MIB).close()
        factory.budget.leftoverBytes shouldBe 40L

        plantDatabase(scratch, ownerId = null, databaseBytes = 1000, walBytes = 0, spillBytes = 0)
        factory.prepareDelivery(UUID.randomUUID(), MIB).close()
        factory.open().close()

        factory.budget.leftoverBytes shouldBe 40L
    }

    @Test
    fun `an absent directory costs nothing and is not created before the first open`() {
        val absent = scratch.resolve("not-yet")
        val factory = duckFactory(absent)

        factory.prepareDelivery(UUID.randomUUID(), MIB).close()

        Files.exists(absent, LinkOption.NOFOLLOW_LINKS) shouldBe false
        factory.budget.leftoverBytes shouldBe 0L
        factory.open().close()
        // The first open created it, with this factory's owner file and nothing of the closed database.
        namesIn(absent).single() shouldMatch DuckDbScratchNames.OWNER
    }

    @Test
    fun `a directory that fails the checks is not read and the admission is refused as creation_failed`() {
        val shared = Files.createDirectory(scratch.resolve("shared"))
        val sentinel = plantDatabase(shared, ownerId = plantDeadOwner(shared))
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"))
        val factory = duckFactory(shared)

        val refused = shouldThrow<DatapipelinesException> { factory.prepareDelivery(UUID.randomUUID(), MIB) }

        refused.code shouldBe StagingErrorCodes.CREATION_FAILED
        Files.exists(sentinel.database) shouldBe true
        factory.budget.leftoverBytes.shouldBeNull()
        factory.budget.reservedBytes shouldBe 0L
    }

    @Test
    fun `a symlink planted as a spill directory is never followed - unvouched or dead-owned`() {
        val target = Files.createDirectory(scratch.resolve("elsewhere"))
        val precious = Files.write(target.resolve("precious"), ByteArray(4096))
        val unvouchedId = UUID.randomUUID()
        val unvouchedLink = Files.createSymbolicLink(scratch.resolve("exec_$unvouchedId.tmp"), target)
        val dead = plantDeadOwner(scratch)
        val deadId = UUID.randomUUID()
        val deadFiles = DuckDbStagingFiles(scratch, deadId)
        Files.writeString(deadFiles.marker, dead.toString())
        Files.write(deadFiles.database, ByteArray(10))
        val deadLink = Files.createSymbolicLink(deadFiles.tempDir, target)

        val result = housekeeper().sweep()

        // Rules out: a delete or a size read through a link reaching a file outside the directory.
        Files.readAllBytes(precious).size shouldBe 4096
        Files.isSymbolicLink(unvouchedLink) shouldBe true
        Files.isSymbolicLink(deadLink) shouldBe true
        Files.exists(deadFiles.database) shouldBe true
        result.counted.keys shouldContainAll listOf(unvouchedLink, deadLink, deadFiles.database, deadFiles.marker)
        (result.counted.getValue(unvouchedLink) < 4096) shouldBe true
    }

    @Test
    fun `a name that only resembles a database is left alone and counted`() {
        val id = UUID.randomUUID()
        val lookalikes =
            listOf(
                "exec_$id.duckdb.bak",
                "exec_$id",
                "exec_${id.toString().uppercase()}.duckdb",
                "dp-marker_$id.old",
                "dp-owner_$id.lock.1",
            ).map { Files.write(scratch.resolve(it), ByteArray(3)) }

        val result = housekeeper().sweep()

        lookalikes.forEach { Files.exists(it) shouldBe true }
        result.counted.keys shouldContainExactly lookalikes.toSet()
    }

    @Test
    fun `a failed delete of a dead owner's file is logged and its bytes are counted`() {
        val dead = plantDeadOwner(scratch)
        // A zero-byte spill file: whichever child the delete reaches first, 11 bytes are left.
        val files = plantDatabase(scratch, ownerId = dead, spillBytes = 0)
        val pinned = Files.createDirectory(files.tempDir.resolve("pinned"))
        Files.write(pinned.resolve("file"), ByteArray(11))
        Files.setPosixFilePermissions(pinned, PosixFilePermissions.fromString("r-x------"))
        try {
            assumeFalse(Files.isWritable(pinned), "the process can write a r-x directory (root?)")
            val log = RecordingLogger()

            val result = housekeeper(log).sweep()

            Files.exists(files.database) shouldBe false
            result.counted.keys shouldContainAll listOf(files.tempDir, files.marker)
            result.counted.getValue(files.tempDir) shouldBe 11L
            log.at(Level.WARN).any { it.contains(StagingErrorCodes.CLEANUP_FAILED) } shouldBe true
            // The marker stays with what is left, and so does the owner file: the proof is kept.
            Files.exists(scratch.resolve(DuckDbScratchNames.owner(dead))) shouldBe true
        } finally {
            Files.setPosixFilePermissions(pinned, PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `a marker naming an owner whose owner file is missing is not provably dead - counted`() {
        val files = plantDatabase(scratch, ownerId = UUID.randomUUID())

        val result = housekeeper().sweep()

        files.pathsWithMarker.forEach { Files.exists(it, LinkOption.NOFOLLOW_LINKS) shouldBe true }
        result.counted.keys shouldContainAll files.paths
    }

    @Test
    fun `a second factory in the same JVM sees the first as live and leaves its open database alone`() {
        val first = duckFactory(scratch)
        val live = first.open()
        live.authorExec("CREATE TABLE t AS SELECT 1 AS id")
        val liveFiles = DuckDbStagingFiles(scratch, live.executionId)
        Files.exists(liveFiles.database) shouldBe true
        Files.exists(liveFiles.marker) shouldBe true
        val second = duckFactory(scratch)

        second.prepareDelivery(UUID.randomUUID(), MIB).close()

        // Rules out: one factory's sweep deleting a sibling factory's live database.
        Files.exists(liveFiles.database) shouldBe true
        Files.exists(liveFiles.marker) shouldBe true
        second.budget.leftoverBytes shouldBe 0L
        live.authorExec("INSERT INTO t VALUES (2)")
        live.scalarText("SELECT count(*) FROM t") shouldBe "2"
        live.close()
        pathsOf(scratch, live.executionId).shouldBeEmpty()
        Files.exists(liveFiles.marker) shouldBe false
    }

    private fun ownerOnly() = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
}
