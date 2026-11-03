package co.datapipelines.staging

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * The on-disk footprint of one DuckDB staging database (staging.md §8.5): the database file, its
 * write-ahead log beside it, and the spill directory beside both. Every name is derived from the
 * execution id alone — nothing from request input reaches a path.
 */
internal class DuckDbStagingFiles(
    scratchDir: Path,
    executionId: UUID,
) {
    private val stem = "exec_$executionId"

    /** `<scratch dir>/exec_<id>.duckdb`. */
    val database: Path = scratchDir.resolve("$stem.duckdb")

    /** The engine's write-ahead log, named by the engine (measured: `<database>.wal`). */
    val wal: Path = scratchDir.resolve("$stem.duckdb.wal")

    /** The engine's `temp_directory`: where a sort, join or aggregate spills past `memory_limit`. */
    val tempDir: Path = scratchDir.resolve("$stem.tmp")

    private val dir: Path = scratchDir

    /**
     * Bytes on disk right now: file + WAL + every spill file. Read after each write and compared with
     * the reservation; a file that vanished between listing and sizing counts as zero.
     */
    fun diskBytes(): Long = sizeOf(database) + sizeOf(wal) + (if (tempDir.isDirectory()) treeBytes(tempDir) else 0L)

    /** Paths of this database that still exist: what [delete] must leave empty. */
    fun leftovers(): List<Path> = Files.list(dir).use { entries -> entries.filter { it.name.startsWith(stem) }.toList() }

    /**
     * Deletes every path of this database (any `exec_<id>` sibling, the spill directory recursively).
     * Never throws for an I/O failure: it returns what it could not delete, for the caller to log.
     */
    fun delete(): List<Pair<Path, IOException>> {
        val failures = mutableListOf<Pair<Path, IOException>>()
        val targets =
            try {
                leftovers()
            } catch (e: IOException) {
                return listOf(dir to e)
            }
        targets.forEach { target ->
            try {
                deleteTree(target)
            } catch (e: IOException) {
                failures += target to e
            }
        }
        return failures
    }

    private fun deleteTree(path: Path) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.list(path).use { children -> children.toList() }.forEach { deleteTree(it) }
        }
        Files.deleteIfExists(path)
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
         */
        fun prepareScratchDir(dir: Path) {
            require(!Files.isSymbolicLink(dir)) { "the DuckDB staging scratch directory $dir is a symbolic link" }
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                val posix = "posix" in dir.fileSystem.supportedFileAttributeViews()
                if (posix) {
                    Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                } else {
                    Files.createDirectories(dir)
                }
            }
            require(Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) { "the DuckDB staging scratch path $dir is not a directory" }
        }
    }
}
