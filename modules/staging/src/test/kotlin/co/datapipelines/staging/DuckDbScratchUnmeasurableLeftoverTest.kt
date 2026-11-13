package co.datapipelines.staging

import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * A leftover that cannot be measured is never counted as zero (#803 review R2; staging.md §8.5): only
 * a path that vanished counts as 0; any other failure to read a size fails the admission as the
 * sanitized `creation_failed`, the sweep stays undone, and nothing is reserved.
 */
class DuckDbScratchUnmeasurableLeftoverTest {
    @TempDir
    lateinit var scratch: Path

    /** Plants an unvouched directory holding 60 bytes, then takes its read permission away. */
    private fun unreadableLeftover(): Path {
        val hidden = Files.createDirectory(scratch.resolve("unvouched"))
        Files.write(hidden.resolve("leftover"), ByteArray(LEFTOVER))
        Files.size(hidden.resolve("leftover")) shouldBe LEFTOVER.toLong()
        Files.setPosixFilePermissions(hidden, PosixFilePermissions.fromString("--x------"))
        return hidden
    }

    private fun restore(hidden: Path) = Files.setPosixFilePermissions(hidden, PosixFilePermissions.fromString("rwx------"))

    @Test
    fun `an unreadable leftover directory refuses the admission instead of counting zero`() {
        val hidden = unreadableLeftover()
        try {
            assumeFalse(Files.isReadable(hidden), "the process can read a --x directory (root?)")
            val factory = duckFactory(scratch, scratchCapacityBytes = 100)

            val refused = shouldThrow<DatapipelinesException> { factory.prepareDelivery(UUID.randomUUID(), 50).close() }

            // Rules out: an unmeasured leftover read as 0 bytes and its room offered again.
            refused.code shouldBe StagingErrorCodes.CREATION_FAILED
            refused.message.orEmpty().contains(hidden.toString()) shouldBe false
            factory.budget.leftoverBytes shouldBe null
            factory.budget.reservedBytes shouldBe 0L

            // Once the directory is readable again the next admission sweeps and counts all 60 bytes.
            restore(hidden)
            val blocked = shouldThrow<StagingScratchBlockedException> { factory.prepareDelivery(UUID.randomUUID(), 50).close() }
            blocked.details["leftover_bytes"] shouldBe LEFTOVER.toLong()
            factory.budget.leftoverBytes shouldBe LEFTOVER.toLong()
            Files.size(hidden.resolve("leftover")) shouldBe LEFTOVER.toLong()
        } finally {
            restore(hidden)
        }
    }

    @Test
    fun `the sweep throws the access failure of an unreadable leftover instead of returning a zero count`() {
        val hidden = unreadableLeftover()
        try {
            assumeFalse(Files.isReadable(hidden), "the process can read a --x directory (root?)")
            val housekeeper = DuckDbScratchHousekeeper(scratch, DuckDbScratchOwnership.newOwner(), RecordingLogger())

            shouldThrow<AccessDeniedException> { housekeeper.sweep() }

            restore(hidden)
            housekeeper.sweep().counted shouldBe mapOf(hidden to LEFTOVER.toLong())
        } finally {
            restore(hidden)
        }
    }

    private companion object {
        const val LEFTOVER = 60
    }
}
