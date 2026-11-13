package co.datapipelines.staging

import co.datapipelines.staging.DuckDbScratchOwnership.Marker
import co.datapipelines.staging.DuckDbScratchOwnership.OwnerState
import org.slf4j.Logger
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
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
 * A marker read before the owner was proven dead is only a hint: another sweeper or a same-id open
 * may have removed that database under its own proof and a new owner claimed the id since. So every
 * delete re-reads the marker WHILE holding the dead owner's proof and goes ahead only when it still
 * names that owner. The proof makes the re-read final: a marker naming an owner is removed only by
 * that owner's factory or by the holder of its proof, and a new database cannot be created while a
 * marker for its id exists (create-new), so nobody can replace the marker between the re-read and
 * the delete (#803 review R1).
 *
 * A leftover's size is read without following links; one that vanished counts as zero, and any
 * other failure to read it is thrown — a leftover that cannot be measured never counts as free room
 * (#803 review R2).
 *
 * @param ownerId the id of the factory this housekeeper works for; its own databases are never swept.
 * @param log where the WARN and the cleanup failures go (server log; paths and sizes only).
 * @param afterMarkerRead runs after a marker was read and before the owner it names is probed —
 *   once per sweep after the markers are grouped, and at [claim] after the same-id marker read. A
 *   test seam that holds that window open, as the factory's `beforeSweep` does; nothing in
 *   production, and it decides nothing.
 */
internal class DuckDbScratchHousekeeper(
    private val dir: Path,
    private val ownerId: UUID,
    private val log: Logger,
    private val afterMarkerRead: () -> Unit = {},
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
     * @throws IOException the directory cannot be listed, or a leftover's size cannot be read.
     */
    fun sweep(): SweepResult {
        if (!exists(dir)) return SweepResult(emptyList(), emptyMap())
        DuckDbStagingFiles.checkScratchDir(dir)
        val tally = Tally()
        val inventory = inventory(DuckDbStagingFiles.listNames(dir), tally)
        val byOwner = byOwner(inventory.databaseIds, tally)
        afterMarkerRead()
        byOwner.forEach { (owner, databases) -> sweepOwner(owner, databases, tally) }
        (inventory.ownerFiles - byOwner.keys).forEach(::removeIfAbandoned)
        return tally.result().also(::report)
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
        if (!tryWriteMarker(files) && !(clearSameId(files) && tryWriteMarker(files))) {
            throw IOException(
                "execution ${files.executionId} already has files in $dir that are live or nobody can vouch for; left untouched",
            )
        }
        val predating = files.existing()
        if (predating.isNotEmpty()) {
            Files.deleteIfExists(files.marker)
            throw IOException("execution ${files.executionId} already has files nobody can vouch for, left untouched: $predating")
        }
    }

    /** What one sweep has removed and counted so far. */
    private class Tally {
        val removed = mutableListOf<Path>()
        val counted = linkedMapOf<Path, Long>()

        fun result(): SweepResult = SweepResult(removed.toList(), counted.toMap())
    }

    /** The directory's names sorted into database ids (a database path or a marker) and owner files. */
    private class Inventory(
        val databaseIds: Set<UUID>,
        val ownerFiles: Set<UUID>,
    )

    /** Sorts [names]; a name that is neither a database's nor an owner file's is counted. */
    private fun inventory(
        names: List<String>,
        tally: Tally,
    ): Inventory {
        val databaseIds = linkedSetOf<UUID>()
        val ownerFiles = linkedSetOf<UUID>()
        names.forEach { name ->
            val databaseId =
                DuckDbScratchNames.idIn(DuckDbScratchNames.DATABASE_PATH, name)
                    ?: DuckDbScratchNames.idIn(DuckDbScratchNames.MARKER, name)
            val owner = DuckDbScratchNames.idIn(DuckDbScratchNames.OWNER, name)
            when {
                databaseId != null -> databaseIds += databaseId
                owner != null -> ownerFiles += owner
                else -> dir.resolve(name).let { tally.counted[it] = bytesOf(it) }
            }
        }
        return Inventory(databaseIds, ownerFiles)
    }

    /** Groups the databases by the owner their marker names; one without a readable marker is counted. */
    private fun byOwner(
        databaseIds: Set<UUID>,
        tally: Tally,
    ): Map<UUID, List<DuckDbStagingFiles>> {
        val byOwner = linkedMapOf<UUID, MutableList<DuckDbStagingFiles>>()
        databaseIds.map { DuckDbStagingFiles(dir, it) }.forEach { files ->
            when (val marker = DuckDbScratchOwnership.readMarker(files.marker)) {
                is Marker.Owned -> byOwner.getOrPut(marker.ownerId) { mutableListOf() } += files
                Marker.Absent, Marker.Damaged -> tally.counted += remainder(files)
            }
        }
        return byOwner
    }

    /** A live owner's databases are left alone, an unprovable one's counted, a dead one's removed. */
    private fun sweepOwner(
        owner: UUID,
        databases: List<DuckDbStagingFiles>,
        tally: Tally,
    ) {
        when (val state = DuckDbScratchOwnership.probe(dir, owner)) {
            OwnerState.Live -> Unit
            OwnerState.Unprovable -> databases.forEach { tally.counted += remainder(it) }
            is OwnerState.Dead -> state.proof.use { dead -> removeAll(dead, databases, tally) }
        }
    }

    /**
     * Under [dead]'s proof: re-reads each database's marker, removes the database only when the
     * marker still names [dead], counts what stays, and removes the owner file once none remains.
     * A marker that now names another owner belongs to a database claimed after the grouping read —
     * like one created after the listing, it is not this sweep's; an absent or damaged one leaves
     * its files to be counted, as at grouping.
     */
    private fun removeAll(
        dead: DuckDbScratchOwnership.DeadOwner,
        databases: List<DuckDbStagingFiles>,
        tally: Tally,
    ) {
        var allGone = true
        databases.forEach { files ->
            val remaining =
                when (val now = DuckDbScratchOwnership.readMarker(files.marker)) {
                    is Marker.Owned -> if (now.ownerId == dead.ownerId) removeCounted(files, tally) else emptyMap()
                    Marker.Absent, Marker.Damaged -> remainder(files).also { tally.counted += it }
                }
            if (remaining.isNotEmpty()) allGone = false
        }
        if (allGone) deleteOwnerFile(dead)
    }

    /** Removes a database whose marker was just re-read under its dead owner's proof; tallies both outcomes. */
    private fun removeCounted(
        files: DuckDbStagingFiles,
        tally: Tally,
    ): Map<Path, Long> {
        val present = files.pathsWithMarker.filter(::exists)
        val remaining = removeDead(files)
        tally.removed += present - remaining.keys
        tally.counted += remaining
        return remaining
    }

    /** An owner file no marker names: removed only when its owner is provably dead. */
    private fun removeIfAbandoned(owner: UUID) {
        (DuckDbScratchOwnership.probe(dir, owner) as? OwnerState.Dead)?.proof?.use(::deleteOwnerFile)
    }

    /** Whether the same-id leftover is gone: removed (its owner dead, or this housekeeper's own) or already absent. */
    private fun clearSameId(files: DuckDbStagingFiles): Boolean {
        val marker = DuckDbScratchOwnership.readMarker(files.marker)
        afterMarkerRead()
        return when (marker) {
            is Marker.Owned -> removeIfOwnOrDead(marker.ownerId, files)

            // Removed between the two reads: the second write decides.
            Marker.Absent -> true

            Marker.Damaged -> false
        }
    }

    /**
     * [ownerId]'s own marker cannot change under it: only this factory or a holder of its proof
     * removes it, and no proof of a live owner exists. Another owner's is re-read under that owner's
     * proof first — a marker that no longer names it was claimed meanwhile and is never removed.
     */
    private fun removeIfOwnOrDead(
        owner: UUID,
        files: DuckDbStagingFiles,
    ): Boolean {
        if (owner == ownerId) return removeDead(files).isEmpty()
        val dead = DuckDbScratchOwnership.probe(dir, owner) as? OwnerState.Dead ?: return false
        return dead.proof.use {
            when (val now = DuckDbScratchOwnership.readMarker(files.marker)) {
                is Marker.Owned -> now.ownerId == owner && removeDead(files).isEmpty()

                // Removed meanwhile under the same proof: the second write decides.
                Marker.Absent -> true

                Marker.Damaged -> false
            }
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
            log.warn(
                "{}: DuckDB staging path {} of execution {} not deleted: {}",
                StagingErrorCodes.CLEANUP_FAILED,
                path,
                files.executionId,
                e.message,
            )
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
            log.info(
                "DuckDB staging scratch sweep removed {} path(s) left by crashed processes in {}: {}",
                result.removed.size,
                dir,
                result.removed,
            )
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

        /**
         * lstat size of [path]; for a directory the regular files under it (links not followed). A
         * path that vanished counts as 0; every other failure is thrown, never read as 0.
         *
         * @throws IOException a size or a directory listing could not be read (access denied, …).
         */
        fun bytesOf(path: Path): Long {
            val attributes =
                try {
                    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (_: NoSuchFileException) {
                    return 0L
                }
            return if (attributes.isDirectory) treeBytes(path) else attributes.size()
        }

        /** The regular files under [root], links not followed; an entry that vanished mid-walk counts as 0. */
        fun treeBytes(root: Path): Long {
            val total = TreeTotal()
            Files.walkFileTree(root, total)
            return total.bytes
        }
    }

    /** Sums regular-file sizes; rethrows every failure but a vanished entry. */
    private class TreeTotal : SimpleFileVisitor<Path>() {
        var bytes = 0L
            private set

        override fun visitFile(
            file: Path,
            attrs: BasicFileAttributes,
        ): FileVisitResult {
            if (attrs.isRegularFile) bytes = saturatingAdd(bytes, attrs.size())
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(
            file: Path,
            exc: IOException,
        ): FileVisitResult = if (exc is NoSuchFileException) FileVisitResult.CONTINUE else throw exc

        override fun postVisitDirectory(
            dir: Path,
            exc: IOException?,
        ): FileVisitResult = if (exc == null || exc is NoSuchFileException) FileVisitResult.CONTINUE else throw exc
    }
}

/** [a] + [b] for non-negative sizes, `Long.MAX_VALUE` instead of wrapping. */
internal fun saturatingAdd(
    a: Long,
    b: Long,
): Long = if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b
