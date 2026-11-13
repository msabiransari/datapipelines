package co.datapipelines.web.health

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.actuate.health.Status
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * #806 — the production [FileSystemScratchSpaceReader] and [ScratchSpace]'s nearest-existing walk over a real
 * directory tree: what each kind of path reads as, that nothing is read beneath a regular file, that a path that
 * can be confirmed neither present nor absent THROWS (never "absent"), and that reading creates nothing.
 */
class FileSystemScratchSpaceReaderTest {
    @TempDir
    lateinit var root: Path

    private val reader = FileSystemScratchSpaceReader()

    @Test
    fun `a directory, a file and an absent path read as what they are`() {
        val file = Files.writeString(root.resolve("file"), "x")
        reader.kind(root) shouldBe ScratchPathKind.DIRECTORY
        reader.kind(file) shouldBe ScratchPathKind.NOT_DIRECTORY
        reader.kind(root.resolve("absent")) shouldBe ScratchPathKind.ABSENT
        reader.canWrite(root) shouldBe true
        reader.usableBytes(root) shouldBeGreaterThan 0L
    }

    @Test
    fun `the nearest existing parent of an absent directory is found, and nothing is created`() {
        val dir = root.resolve("a/b/dp-staging")
        ScratchSpace(dir, reader).nearestExisting() shouldBe ScratchSpace.Nearest(root, ScratchPathKind.DIRECTORY)
        ScratchSpace(dir, reader).usableBytes() shouldBeGreaterThan 0L
        Files.exists(root.resolve("a")) shouldBe false
    }

    @Test
    fun `beneath a regular file the walk stops at the file - the indicator reads it as uncreatable, not as an error`() {
        val file = Files.writeString(root.resolve("file"), "x")
        val space = ScratchSpace(file.resolve("dp-staging"), reader)
        space.nearestExisting() shouldBe ScratchSpace.Nearest(file, ScratchPathKind.NOT_DIRECTORY)
        DuckDbScratchHealthIndicator(space, 1L).health().status shouldBe Status.OUT_OF_SERVICE
    }

    @Test
    fun `a path that can be confirmed neither present nor absent throws, and the indicator is DOWN`() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX permissions")
        val locked = Files.createDirectory(root.resolve("locked"))
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"))
        try {
            // Root ignores permissions; the case only means something for an ordinary user.
            assumeTrue(!Files.isReadable(locked), "running as a user the permission bits bind")
            val dir = locked.resolve("dp-staging")
            shouldThrow<IOException> { reader.kind(dir) }
            DuckDbScratchHealthIndicator(ScratchSpace(dir, reader), 1L).health().status shouldBe Status.DOWN
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `a directory without write permission reads as not writable`() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX permissions")
        val readOnly = Files.createDirectory(root.resolve("read-only"))
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-x------"))
        try {
            assumeTrue(!Files.isWritable(readOnly), "running as a user the permission bits bind")
            reader.canWrite(readOnly) shouldBe false
            DuckDbScratchHealthIndicator(ScratchSpace(readOnly, reader), 1L).health().status shouldBe Status.OUT_OF_SERVICE
        } finally {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwx------"))
        }
    }
}
