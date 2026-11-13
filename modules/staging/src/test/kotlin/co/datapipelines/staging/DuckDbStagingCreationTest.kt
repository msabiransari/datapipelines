package co.datapipelines.staging

import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.StagingEngine
import com.sun.security.auth.module.UnixSystem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * What a DuckDB staging database's creation refuses, and what its refusal may say (#795 round two,
 * review F6–F8; staging.md §8.5).
 */
class DuckDbStagingCreationTest {
    @TempDir
    lateinit var scratch: Path

    @Test
    fun `a failed open says nothing of the scratch path to the caller, even when the driver's message does`() {
        val props = duckProps(scratch)
        val thrownByDriver = AtomicReference<String>()
        val leaky =
            DuckDbStagingFactory(props, ScratchBudget(props.scratchCapacityBytes)) { url, _ ->
                val message = "IO Error: Cannot open database \"${url.removePrefix("jdbc:duckdb:")}\": Permission denied"
                thrownByDriver.set(message)
                throw SQLException(message)
            }
        val executionId = UUID.randomUUID()

        val thrown = shouldThrow<DatapipelinesException> { leaky.create(executionId, StagingEngine.DUCKDB) }

        // Non-vacuous: the driver's own message did carry the path.
        thrownByDriver.get() shouldContain scratch.toString()
        thrown.code shouldBe StagingErrorCodes.CREATION_FAILED
        thrown.message shouldContain executionId.toString()
        // Everything the executor transports to the caller: the message, the details and every
        // message along the cause chain (057, error-detail: full).
        callerVisibleText(thrown) shouldNotContain scratch.toString()
        leaky.budget.reservedBytes shouldBe 0L
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["rwxrwxrwx", "rwxr-x---", "rwx-----x"])
    fun `a pre-existing scratch directory open to group or other is refused before any file is written, and left as it was`(mode: String) {
        assumeTrue(posix(), "POSIX file system: owner and mode are only read there")
        val dir = Files.createDirectory(scratch.resolve("shared"))
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(mode))
        val executionId = UUID.randomUUID()
        val sentinel = Files.writeString(dir.resolve("exec_$executionId.duckdb"), "untouched")
        val factory = duckFactory(dir)

        val thrown = shouldThrow<DatapipelinesException> { factory.create(executionId, StagingEngine.DUCKDB) }

        thrown.code shouldBe StagingErrorCodes.CREATION_FAILED
        Files.list(dir).use { it.toList() } shouldBe listOf(sentinel)
        Files.readString(sentinel) shouldBe "untouched"
        PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)) shouldBe mode
        factory.budget.reservedBytes shouldBe 0L
        shouldThrow<IllegalArgumentException> { DuckDbStagingFiles.prepareScratchDir(dir) }.message shouldContain "grant nothing"
    }

    @Test
    fun `refusing a scratch directory symlink leaves its target untouched`() {
        val target = Files.createDirectory(scratch.resolve("target"))
        val executionId = UUID.randomUUID()
        val sentinel = Files.writeString(target.resolve("exec_$executionId.duckdb"), "untouched")
        val link = Files.createSymbolicLink(scratch.resolve("link"), target)
        val props = duckProps(link)
        val factory =
            DuckDbStagingFactory(props, ScratchBudget(props.scratchCapacityBytes)) { _, _ ->
                error("The driver must not be called for a refused scratch directory")
            }

        val thrown = shouldThrow<DatapipelinesException> { factory.create(executionId, StagingEngine.DUCKDB) }

        thrown.code shouldBe StagingErrorCodes.CREATION_FAILED
        Files.readString(sentinel) shouldBe "untouched"
        Files.isSymbolicLink(link) shouldBe true
        factory.budget.reservedBytes shouldBe 0L
    }

    @Test
    fun `a pre-existing owner-only scratch directory of the process user is used`() {
        assumeTrue(posix(), "POSIX file system: owner and mode are only read there")
        val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        val dir = Files.createDirectory(scratch.resolve("private"), ownerOnly)

        duckFactory(dir).open().use { it.scalarText("SELECT 42") shouldBe "42" }
    }

    @Test
    fun `a scratch directory that belongs to another user is refused, even owner-only`() {
        // A foreign owner needs root to make. /root is one on the usual Linux host: root's, rwx------,
        // and stat-able through the root directory, which is all the check reads. Skipped where that
        // does not hold — a run as root, or a host without it.
        val foreign = Path.of("/root")
        assumeTrue(posix() && Files.isDirectory(foreign, LinkOption.NOFOLLOW_LINKS), "a POSIX host with /root")
        val attributes = Files.readAttributes(foreign, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val me = foreign.fileSystem.userPrincipalLookupService.lookupPrincipalByName(UnixSystem().uid.toString())
        assumeTrue(attributes.owner() != me, "/root belongs to another user")
        // Owner-only, so the owner is the only thing wrong with it.
        assumeTrue(PosixFilePermissions.toString(attributes.permissions()) == "rwx------", "/root is owner-only")

        shouldThrow<IllegalArgumentException> { DuckDbStagingFiles.prepareScratchDir(foreign) }.message shouldContain "belongs to"
    }

    private fun posix(): Boolean = "posix" in scratch.fileSystem.supportedFileAttributeViews()

    private fun callerVisibleText(e: DatapipelinesException): String =
        generateSequence<Throwable>(e) { it.cause }.joinToString(" | ") { "${it.javaClass.name}: ${it.message}" } + " | " + e.details
}
