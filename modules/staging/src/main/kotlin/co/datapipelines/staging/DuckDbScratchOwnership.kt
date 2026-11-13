package co.datapipelines.staging

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The names the leftover sweep recognises in the scratch directory (staging.md §8.5, #803). Each is
 * matched whole, with a canonical lower-case UUID; anything else is a name nobody can vouch for.
 */
internal object DuckDbScratchNames {
    private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

    /** One of a database's three top-level paths: `exec_<id>.duckdb`, `.duckdb.wal` or `.tmp`. */
    val DATABASE_PATH = Regex("exec_($UUID_PATTERN)\\.(?:duckdb|duckdb\\.wal|tmp)")

    /** A database's ownership marker. */
    val MARKER = Regex("dp-marker_($UUID_PATTERN)")

    /** A factory's owner lock file. */
    val OWNER = Regex("dp-owner_($UUID_PATTERN)\\.lock")

    fun marker(executionId: UUID): String = "dp-marker_$executionId"

    fun owner(ownerId: UUID): String = "dp-owner_$ownerId.lock"

    /** The id a [regex] match of [name] carries, or null when [name] is not exactly that shape. */
    fun idIn(
        regex: Regex,
        name: String,
    ): UUID? = regex.matchEntire(name)?.let { canonicalUuid(it.groupValues[1]) }

    /** [text] as a UUID only when it is the canonical form of one. */
    fun canonicalUuid(text: String): UUID? =
        try {
            UUID.fromString(text).takeIf { it.toString() == text }
        } catch (_: IllegalArgumentException) {
            null
        }
}

/**
 * The ownership proof of the DuckDB scratch directory (staging.md §8.5, #803; probe record A0).
 *
 * Measured on the pinned driver: DuckDB holds a POSIX record lock on an open database, and closing
 * ANY descriptor this process has on that file drops it — so liveness is never tested by opening a
 * database file. Instead every factory holds, for the life of the process, an exclusive lock on its
 * own owner file `dp-owner_<owner id>.lock`, and writes `dp-marker_<execution id>` (the owner id,
 * forced to disk) before the database file exists. Another process tests the owner with
 * `tryLock`: held means live; acquired means its process is gone, and the prober keeps the lock
 * while it removes that owner's files, so two sweepers cannot act on one owner.
 *
 * A Java file lock is also a POSIX record lock, so the same rule binds inside one JVM: a second
 * channel on an owner file this JVM holds would drop that lock when closed (measured, A0 probe 4).
 * [held] is therefore asked first — an owner this JVM holds, as a factory or as a sweeper, is never
 * opened; only an owner no part of this process holds is probed.
 */
internal object DuckDbScratchOwnership {
    private val held: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private const val MARKER_MAX_BYTES = 64
    private const val OWNER_ATTEMPTS = 3
    private val OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------")

    /** Registers a new owner id as held by this JVM; memory only. */
    fun newOwner(): UUID = UUID.randomUUID().also { check(held.add(it)) { "owner id collision" } }

    /** True when this JVM holds [ownerId], as a live factory or as a sweeper of a dead owner. */
    fun isHeldHere(ownerId: UUID): Boolean = ownerId in held

    /**
     * Creates (or reopens) [ownerId]'s owner file in [dir] and locks it, returning the channel that
     * must stay open for the life of the owner. A sweeper may have unlinked the file between the
     * open and the lock (it saw no lock and no database of this owner yet); the file's presence is
     * therefore checked after the lock, and a vanished one is created again.
     * [beforeLock] is a scheduling-only test seam after an open, before its lock; production does nothing there.
     */
    fun lockOwnerFile(
        dir: Path,
        ownerId: UUID,
        beforeLock: () -> Unit = {},
    ): FileChannel {
        val path = dir.resolve(DuckDbScratchNames.owner(ownerId))
        repeat(OWNER_ATTEMPTS) {
            val channel = openOwnerFile(path)
            try {
                beforeLock()
                channel.lock()
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return channel
            } catch (e: IOException) {
                channel.close()
                throw e
            }
            channel.close()
        }
        throw IOException("the DuckDB staging owner file $path vanished $OWNER_ATTEMPTS times after it was locked")
    }

    private fun openOwnerFile(path: Path): FileChannel =
        if ("posix" in path.fileSystem.supportedFileAttributeViews()) {
            FileChannel.open(
                path,
                setOf(CREATE, READ, WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE),
            )
        } else {
            FileChannel.open(path, CREATE, READ, WRITE, LinkOption.NOFOLLOW_LINKS)
        }

    /** Writes [files]' marker naming [ownerId], exclusively and forced to disk; throws when one exists. */
    fun writeMarker(
        files: DuckDbStagingFiles,
        ownerId: UUID,
    ) {
        FileChannel.open(files.marker, CREATE_NEW, WRITE).use { channel ->
            val bytes = ByteBuffer.wrap(ownerId.toString().toByteArray(Charsets.US_ASCII))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
    }

    /**
     * Reads a marker as data from disk: only a regular file (never through a link) of at most
     * [MARKER_MAX_BYTES] whose whole content is a canonical UUID names an owner. Anything else is
     * [Marker.Damaged] — never "dead", so never a reason to delete.
     */
    fun readMarker(path: Path): Marker {
        val attributes =
            try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return Marker.Absent
            }
        if (!attributes.isRegularFile || attributes.size() > MARKER_MAX_BYTES) return Marker.Damaged
        val text = readSmall(path) ?: return Marker.Damaged
        return DuckDbScratchNames.canonicalUuid(text)?.let { Marker.Owned(it) } ?: Marker.Damaged
    }

    /** At most [MARKER_MAX_BYTES] + 1 bytes of [path] as ASCII, never through a link; null when unreadable. */
    private fun readSmall(path: Path): String? =
        try {
            FileChannel.open(path, READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.allocate(MARKER_MAX_BYTES + 1)
                do {
                    val read = channel.read(buffer)
                } while (read > 0 && buffer.hasRemaining())
                String(buffer.array(), 0, buffer.position(), Charsets.US_ASCII)
            }
        } catch (_: IOException) {
            null
        }

    /**
     * Tests [ownerId] without opening any database file: [OwnerState.Live] when this JVM holds it
     * or another process holds its owner file's lock; [OwnerState.Dead] when the lock was acquired
     * (the proof keeps it, and this JVM's claim on the id, until closed — everything that owner left
     * may be removed meanwhile); [OwnerState.Unprovable] when its owner file is missing, not a
     * regular file, or unreadable — never a reason to delete.
     * [beforeLock] is a scheduling-only test seam after opening the candidate, before trying its lock.
     */
    fun probe(
        dir: Path,
        ownerId: UUID,
        beforeLock: () -> Unit = {},
    ): OwnerState {
        if (!held.add(ownerId)) return OwnerState.Live
        val path = dir.resolve(DuckDbScratchNames.owner(ownerId))
        val state =
            try {
                lockIfAbandoned(path, beforeLock)
            } catch (_: IOException) {
                OwnerState.Unprovable
            }
        if (state !is OwnerState.Dead) held.remove(ownerId)
        return state
    }

    private fun lockIfAbandoned(
        path: Path,
        beforeLock: () -> Unit,
    ): OwnerState {
        val fileKey = regularFileKey(path) ?: return OwnerState.Unprovable
        val ownerId = requireNotNull(DuckDbScratchNames.idIn(DuckDbScratchNames.OWNER, path.fileName.toString()))
        val channel = FileChannel.open(path, READ, WRITE, LinkOption.NOFOLLOW_LINKS)
        val lock =
            try {
                beforeLock()
                tryLock(channel)
            } catch (e: IOException) {
                channel.close()
                throw e
            }
        val sameFile =
            try {
                regularFileKey(path) == fileKey
            } catch (e: IOException) {
                channel.close()
                throw e
            }
        // A lock on an unlinked inode proves nothing about a replacement at the same pathname.
        if (lock != null && sameFile) {
            return OwnerState.Dead(DeadOwner(ownerId, path, channel, fileKey))
        }
        // No part of this JVM holds another lock on this owner (held, above).
        channel.close()
        return if (lock == null && sameFile) OwnerState.Live else OwnerState.Unprovable
    }

    /** A provider's stable regular-file identity; absent, non-regular or identity-less is unprovable. */
    private fun regularFileKey(path: Path): Any? =
        try {
            Files
                .readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                .takeIf { it.isRegularFile }
                ?.fileKey()
        } catch (_: NoSuchFileException) {
            null
        }

    private fun tryLock(channel: FileChannel): FileLock? =
        try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }

    /** What [probe] could establish about an owner. */
    sealed interface OwnerState {
        /** Held by this JVM or by another live process: nothing of it is touched or counted. */
        data object Live : OwnerState

        /** Neither live nor provably dead: its databases are counted, never deleted. */
        data object Unprovable : OwnerState

        /** Proven dead; [proof] holds its lock until closed. */
        data class Dead(
            val proof: DeadOwner,
        ) : OwnerState
    }

    /** A marker's reading. */
    sealed interface Marker {
        /** No marker file. */
        data object Absent : Marker

        /** Present but not a marker this code wrote: nobody can vouch for its database. */
        data object Damaged : Marker

        /** A well-formed marker naming its owner. */
        data class Owned(
            val ownerId: UUID,
        ) : Marker
    }

    /** An owner proven dead, its lock held by this JVM until [close]. */
    class DeadOwner internal constructor(
        val ownerId: UUID,
        private val ownerFile: Path,
        private val channel: FileChannel,
        private val fileKey: Any,
    ) : AutoCloseable {
        /** Removes the owner file itself; only once none of its databases remains. */
        fun deleteOwnerFile() {
            if (regularFileKey(ownerFile) == fileKey) Files.deleteIfExists(ownerFile)
        }

        override fun close() {
            try {
                channel.close()
            } finally {
                held.remove(ownerId)
            }
        }
    }
}
