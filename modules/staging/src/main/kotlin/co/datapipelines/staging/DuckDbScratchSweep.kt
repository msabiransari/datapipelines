package co.datapipelines.staging

import co.datapipelines.staging.DuckDbScratchOwnership.Marker
import co.datapipelines.staging.DuckDbScratchOwnership.OwnerState
import org.slf4j.Logger
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * Removes what a crashed process left in the DuckDB scratch directory, and counts what it cannot
 * prove dead (staging.md §8.5, #803; owner ruling 2026-11-12: a leftover nobody can vouch for is
 * never deleted, at any age — its bytes are counted against the aggregate and one WARN names it).
 *
 * Exactly three kinds of path are ever deleted, each only for a database whose owner is proven
 * dead ([DuckDbScratchOwnership.probe]) or is [ownerId] itself: a database's three top-level
 * paths, everything inside its spill directory, and the ownership files this code writes (a marker
 * with its database, an owner file once none of its databases remains). No symbolic link is
 * followed at any depth, and nothing outside [dir] is touched. Every other name is left alone and
 * counted.
 *
 * @param ownerId the id of the factory this housekeeper works for; its own databases are never swept.
 * @param log where the WARN and the cleanup failures go (server log; paths and sizes only).
 */
internal class DuckDbScratchHousekeeper(
    private val dir: Path,
    private val ownerId: UUID,
    private val log: Logger,
) {
    /** What one sweep did: the paths it removed, and the paths it counted with their bytes. */
    data class SweepResult(
        val removed: List<Path>,
        val counted: Map<Path, Long>,
    ) {
        /** The counted bytes, saturating at `Long.MAX_VALUE`. */
        val countedBytes: Long get() = counted.values.fold(0L, ::saturatingAdd)
    }

    /**
     * The startup sweep. An absent directory costs nothing and creates nothing; an existing one
     * must pass [DuckDbStagingFiles.checkScratchDir] before anything in it is read.
     *
     * @throws IllegalArgumentException the directory fails the checks (nothing was read).
     * @throws IOException the directory cannot be listed.
     */
    fun sweep(): SweepResult {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(dir)) return SweepResult(emptyList(), emptyMap())
        DuckDbStagingFiles.checkScratchDir(dir)
        val names = DuckDbStagingFiles.listNames(dir)
        val databaseIds = linkedSetOf<UUID>()
        val ownerFiles = linkedSetOf<UUID>()
        val counted = linkedMapOf<Path, Long>()
        names.forEach { name ->
            val databaseId = DuckDbScratchNames.idIn(DuckDbScratchNames.DATABASE_PATH, name) ?: DuckDbScratchNames.idIn(DuckDbScratchNames.MARKER, name)
            val owner = DuckDbScratchNames.idIn(DuckDbScratchNames.OWNER, name)
            when {
                databaseId != null -> databaseIds += databaseId
                owner != null -> ownerFiles += owner
                else -> dir.resolve(name).let { counted[it] = bytesOf(it) }
            }
        }
        val byOwner = linkedMapOf<UUID, MutableList<DuckDbStagingFiles>>()
        databaseIds.map { DuckDbStagingFiles(dir, it) }.forEach { files ->
            when (val marker = DuckDbScratchOwnership.readMarker(files.marker)) {
                is Marker.Owned -> byOwner.getOrPut(marker.ownerId) { mutableListOf() } += files
                Marker.Absent, Marker.Damaged -> counted += remainder(files)
            }
        }
        val removed = mutableListOf<Path>()
        byOwner.forEach { (owner, databases) ->
            when (val state = DuckDbScratchOwnership.probe(dir, owner)) {
                // Live in this JVM (never opened) or in another process (its lock is held).
                OwnerState.Live -> Unit
                OwnerState.Unprovable -> databases.forEach { counted += remainder(it) }
                is OwnerState.Dead ->
                    state.proof.use { dead ->
                        var allGone = true
                        databases.forEach { files ->
                            val present = files.pathsWithMarker.filter(::exists)
                            val remaining = removeDead(files)
                            removed += present - remaining.keys
                            counted += remaining
                            if (remaining.isNotEmpty()) allGone = false
                        }
                        if (allGone) deleteOwnerFile(dead)
                    }
            }
        }
        (ownerFiles - byOwner.keys).forEach { owner ->
            (DuckDbScratchOwnership.probe(dir, owner) as? OwnerState.Dead)?.proof?.use(::deleteOwnerFile)
        }
        val result = SweepResult(removed, counted)
        report(result)
        return result
    }

    /**
     * Prepares [files]' execution id for a new database of [ownerId]: writes the marker (exclusive)
     * after removing a same-id leftover that is provably dead or is [ownerId]'s own earlier attempt.
     * The caller guarantees the id is not open in [ownerId]'s factory.
     *
     * @throws IOException a same-id leftover is live, unvouched or could not be removed; every
     *   existing file is left as it was.
     */
    fun claim(files: DuckDbStagingFiles) {
        if (!tryWriteMarker(files)) {
            val removable =
                when (val marker = DuckDbScratchOwnership.readMarker(files.marker)) {
                    is Marker.Owned ->
                        if (marker.ownerId == ownerId) {
                            removeDead(files).isEmpty()
                        } else {
                            (DuckDbScratchOwnership.probe(dir, marker.ownerId) as? OwnerState.Dead)?.proof?.use { removeDead(files).isEmpty() } ?: false
                        }
                    // Removed between the two reads: the second write decides.
                    Marker.Absent -> true
                    Marker.Damaged -> false
                }
            if (!removable || !tryWriteMarker(files)) {
                throw IOException("execution ${files.executionId} already has files in $dir that are live or nobody can vouch for; left untouched")
            }
        }
        val predating = files.existing()
        if (predating.isNotEmpty()) {
            Files.deleteIfExists(files.marker)
            throw IOException("execution ${files.executionId} already has files nobody can vouch for, left untouched: $predating")
        }
    }

    /**
     * Removes the database of [files] and then its marker; never follows a link. A top-level path of
     * the wrong type (a link or a file where the spill directory belongs) makes the whole database
     * unprovable: nothing is deleted. Returns what remains, with its bytes — empty when all is gone.
     */
    fun removeDead(files: DuckDbStagingFiles): Map<Path, Long> {
        if (!wellFormed(files)) return remainder(files)
        val failures = files.delete()
        failures.forEach { (path, e) ->
            log.warn("{}: DuckDB staging path {} of execution {} not deleted: {}", StagingErrorCodes.CLEANUP_FAILED, path, files.executionId, e.message)
        }
        if (failures.isEmpty()) {
            try {
                Files.deleteIfExists(files.marker)
            } catch (e: IOException) {
                log.warn("{}: DuckDB staging marker {} not deleted: {}", StagingErrorCodes.CLEANUP_FAILED, files.marker, e.message)
            }
        }
        return remainder(files)
    }

    private fun wellFormed(files: DuckDbStagingFiles): Boolean =
        listOf(files.database, files.wal, files.marker).all { !exists(it) || Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) } &&
            (!exists(files.tempDir) || Files.isDirectory(files.tempDir, LinkOption.NOFOLLOW_LINKS))

    private fun remainder(files: DuckDbStagingFiles): Map<Path, Long> = files.pathsWithMarker.filter(::exists).associateWith(::bytesOf)

    private fun tryWriteMarker(files: DuckDbStagingFiles): Boolean =
        try {
            DuckDbScratchOwnership.writeMarker(files, ownerId)
            true
        } catch (_: FileAlreadyExistsException) {
            false
        }

    private fun deleteOwnerFile(dead: DuckDbScratchOwnership.DeadOwner) {
        try {
            dead.deleteOwnerFile()
        } catch (e: IOException) {
            log.warn("{}: DuckDB staging owner file of {} not deleted: {}", StagingErrorCodes.CLEANUP_FAILED, dead.ownerId, e.message)
        }
    }

    private fun report(result: SweepResult) {
        if (result.removed.isNotEmpty()) {
            log.info("DuckDB staging scratch sweep removed {} path(s) left by crashed processes in {}: {}", result.removed.size, dir, result.removed)
        }
        if (result.counted.isNotEmpty()) {
            log.warn(
                "DuckDB staging scratch directory {} holds {} bytes in {} path(s) nobody can vouch for; they are never deleted " +
                    "and are counted against datapipelines.lake.admission.scratch-bytes until an operator clears the directory " +
                    "while no instance uses it: {}",
                dir,
                result.countedBytes,
                result.counted.size,
                result.counted.keys,
            )
        }
    }

    private companion object {
        fun exists(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

        /** lstat size of [path]; for a directory the regular files under it (links not followed); 0 if gone. */
        fun bytesOf(path: Path): Long =
            try {
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (attributes.isDirectory) treeBytes(path) else attributes.size()
            } catch (_: IOException) {
                0L
            } catch (_: UncheckedIOException) {
                // Files.walk reports a directory that vanished mid-walk this way.
                0L
            }

        fun treeBytes(root: Path): Long =
            Files.walk(root).use { paths ->
                paths
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .map { sizeOrZero(it) }
                    .toList()
                    .fold(0L, ::saturatingAdd)
            }

        fun sizeOrZero(path: Path): Long =
            try {
                Files.size(path)
            } catch (_: IOException) {
                0L
            }
    }
}

/** [a] + [b] for non-negative sizes, `Long.MAX_VALUE` instead of wrapping. */
internal fun saturatingAdd(
    a: Long,
    b: Long,
): Long = if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b
