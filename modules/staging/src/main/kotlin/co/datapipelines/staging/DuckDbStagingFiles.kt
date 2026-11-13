package co.datapipelines.staging

import com.sun.security.auth.module.UnixSystem
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.io.path.isDirectory

/**
 * The on-disk footprint of one DuckDB staging database (staging.md §8.5): the database file, its
 * write-ahead log beside it, the spill directory beside both, and the ownership marker naming the
 * factory that created them (#803). Every name is derived from the execution id alone — nothing from
 * request input reaches a path.
 */
internal class DuckDbStagingFiles(
    scratchDir: Path,
    /** The execution the database belongs to. */
    val executionId: UUID,
) {
    private val stem = "exec_$executionId"

    /** `<scratch dir>/exec_<id>.duckdb`. */
    val database: Path = scratchDir.resolve("$stem.duckdb")

    /** The engine's write-ahead log, named by the engine (measured: `<database>.wal`). */
    val wal: Path = scratchDir.resolve("$stem.duckdb.wal")

    /** The engine's `temp_directory`: where a sort, join or aggregate spills past `memory_limit`. */
    val tempDir: Path = scratchDir.resolve("$stem.tmp")

    /**
     * The ownership marker, written before [database] exists and removed after it: the owning
     * factory's id ([DuckDbScratchOwnership]). Its name does not start with the database's stem.
     */
    val marker: Path = scratchDir.resolve(DuckDbScratchNames.marker(executionId))

    /** The database's three top-level paths, the only ones [delete] removes. */
    val paths: List<Path> = listOf(database, wal, tempDir)

    /**
     * [paths] and [marker]. Built with `listOf`: a `Path` is an `Iterable<Path>`, so `paths + marker`
     * would append the marker's NAME ELEMENTS, not the marker.
     */
    val pathsWithMarker: List<Path> = listOf(database, wal, tempDir, marker)

    /**
     * Bytes on disk right now: file + WAL + every spill file. Read after each write and compared with
     * the reservation; a file that vanished between listing and sizing counts as zero.
     */
    fun diskBytes(): Long = sizeOf(database) + sizeOf(wal) + (if (tempDir.isDirectory()) treeBytes(tempDir) else 0L)

    /** Which of [paths] exist (a symbolic link counts as existing; it is never followed). */
    fun existing(): List<Path> = paths.filter { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }

    /**
     * Deletes the database's three paths — the spill directory recursively — and nothing else: a
     * lookalike name (`exec_<id>.duckdb.bak`) is never touched, nor is [marker] (its owner removes it
     * once this returned no failure). Never follows a symbolic link. Never throws for an I/O failure:
     * it returns what it could not delete, for the caller to log.
     */
    fun delete(): List<Pair<Path, IOException>> {
        val failures = mutableListOf<Pair<Path, IOException>>()
        paths.forEach { target ->
            try {
                deleteTree(target)
            } catch (e: IOException) {
                failures += target to e
            }
        }
        return failures
    }

    private fun treeBytes(root: Path): Long =
        Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.mapToLong { sizeOf(it) }.sum() }

    private fun sizeOf(path: Path): Long =
        try {
            Files.size(path)
        } catch (_: NoSuchFileException) {
            0L
        }

    companion object {
        /**
         * Creates the scratch directory owner-only (`rwx------`) when it does not exist, and refuses
         * one that is a symbolic link: staged tables are user data, and the default location is under
         * `java.io.tmpdir`, which a host may share with other local users.
         *
         * On a POSIX file system the directory, whether found or just created, must then belong to
         * the user the process runs as and grant nothing to group or other, or the open fails before
         * any file is written (#795 review F7). A directory that fails is refused, never tightened:
         * its mode belongs to whoever created it, and one that was open to others may already hold
         * entries nobody can vouch for. Another file system has no owner or mode to read, and is
         * not checked.
         */
        fun prepareScratchDir(dir: Path) {
            require(!Files.isSymbolicLink(dir)) { "the DuckDB staging scratch directory $dir is a symbolic link" }
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                if (posix(dir)) {
                    Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
                } else {
                    Files.createDirectories(dir)
                }
            }
            checkScratchDir(dir)
        }

        /**
         * [prepareScratchDir]'s checks of an EXISTING directory, creating nothing: not a symbolic
         * link, a directory, and on POSIX the process user's and owner-only. The leftover sweep runs
         * them before it reads anything in the directory (#803).
         */
        fun checkScratchDir(dir: Path) {
            require(!Files.isSymbolicLink(dir)) { "the DuckDB staging scratch directory $dir is a symbolic link" }
            require(Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) { "the DuckDB staging scratch path $dir is not a directory" }
            if (posix(dir)) requirePrivate(dir)
        }

        /**
         * Deletes [path] and, for a directory, everything inside it; never follows a symbolic link.
         * Every I/O failure is an [IOException], including one met while listing a directory.
         */
        fun deleteTree(path: Path) {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                listNames(path).forEach { deleteTree(path.resolve(it)) }
            }
            Files.deleteIfExists(path)
        }

        /**
         * The names directly inside [dir]. A failure while iterating, which the stream reports as an
         * [UncheckedIOException], is rethrown as its [IOException] cause, so callers that map I/O
         * failures to a fixed refusal see every one of them.
         */
        fun listNames(dir: Path): List<String> =
            try {
                Files.list(dir).use { entries -> entries.map { it.fileName.toString() }.toList() }
            } catch (e: UncheckedIOException) {
                throw e.cause ?: IOException("listing $dir failed")
            }

        private fun posix(dir: Path): Boolean = "posix" in dir.fileSystem.supportedFileAttributeViews()

        /**
         * The owner is compared by numeric uid, not by `user.name`: a process whose uid has no
         * passwd entry (an arbitrary container uid) reports `user.name` as `?`.
         */
        private fun requirePrivate(dir: Path) {
            val attributes = Files.readAttributes(dir, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val uid = UnixSystem().uid
            val processUser = dir.fileSystem.userPrincipalLookupService.lookupPrincipalByName(uid.toString())
            require(attributes.owner() == processUser) {
                "the DuckDB staging scratch directory $dir belongs to ${attributes.owner().name}, " +
                    "not to the user this process runs as (uid $uid)"
            }
            val granted = attributes.permissions()
            require(OWNER_ONLY.containsAll(granted)) {
                "the DuckDB staging scratch directory $dir is ${PosixFilePermissions.toString(granted)}; " +
                    "it must grant nothing to group or other"
            }
        }

        private val OWNER_ONLY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")
    }
}
