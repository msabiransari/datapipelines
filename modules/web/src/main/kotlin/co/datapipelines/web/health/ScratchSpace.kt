package co.datapipelines.web.health

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** What a path is, as far as the scratch signal needs to know (#806). */
enum class ScratchPathKind {
    /** The path exists and is a directory (a link to one is followed, as `createDirectories` would). */
    DIRECTORY,

    /** The path exists and is not a directory. */
    NOT_DIRECTORY,

    /** The path is confirmed absent. */
    ABSENT,
}

/**
 * The three file-system readings the DuckDB scratch signal takes (#806), behind one seam so tests control every
 * one of them. Every method may throw; a throw means "could not be read", never a value.
 */
interface ScratchSpaceReader {
    /**
     * What [path] is.
     *
     * @throws IOException when the path can be confirmed neither present nor absent (access denied on a parent,
     *   an I/O error) — never answered as [ScratchPathKind.ABSENT].
     */
    fun kind(path: Path): ScratchPathKind

    /** Whether the process may create entries in [directory] (write and search permission). */
    fun canWrite(directory: Path): Boolean

    /** The usable bytes of the file store [path] lives on. */
    fun usableBytes(path: Path): Long
}

/** The production [ScratchSpaceReader]: one `stat`, the access checks and the file store's usable space. */
class FileSystemScratchSpaceReader : ScratchSpaceReader {
    override fun kind(path: Path): ScratchPathKind =
        try {
            if (Files.readAttributes(path, BasicFileAttributes::class.java).isDirectory) {
                ScratchPathKind.DIRECTORY
            } else {
                ScratchPathKind.NOT_DIRECTORY
            }
        } catch (_: NoSuchFileException) {
            ScratchPathKind.ABSENT
        }

    override fun canWrite(directory: Path): Boolean = Files.isWritable(directory) && Files.isExecutable(directory)

    override fun usableBytes(path: Path): Long = Files.getFileStore(path).usableSpace
}

/**
 * The DuckDB staging scratch directory as the health indicator and the `usable` gauge read it (#806): the
 * directory itself when it exists, otherwise the nearest existing parent — the one `Files.createDirectories`
 * would create it under at the first DuckDB open (the directory does not exist until then).
 *
 * Reads nothing inside the directory and creates nothing: a fixed number of `stat`s (one per path component at
 * most) and one file-store read per call.
 */
class ScratchSpace(
    /** The configured scratch directory; absolute, operator configuration. Logged, never exposed (§6.4). */
    val directory: Path,
    private val reader: ScratchSpaceReader,
) {
    /** The nearest existing path at or above [directory], with what it is. */
    data class Nearest(
        val path: Path,
        val kind: ScratchPathKind,
    ) {
        init {
            require(kind != ScratchPathKind.ABSENT) { "the nearest existing path cannot be absent" }
        }
    }

    /**
     * Walks [directory] from its root down and stops at the first component that is absent or not a directory —
     * nothing beneath either can exist, and a `stat` under a regular file is an error, not an absence.
     *
     * @throws IOException a component could be confirmed neither present nor absent, or the root is absent.
     */
    fun nearestExisting(): Nearest {
        val root = directory.root ?: throw IOException("the scratch directory $directory has no root")
        val rootKind = reader.kind(root)
        if (rootKind == ScratchPathKind.ABSENT) throw IOException("the root $root of the scratch directory is absent")
        var nearest = Nearest(root, rootKind)
        var current = root
        for (name in directory) {
            if (nearest.kind != ScratchPathKind.DIRECTORY) break
            current = current.resolve(name)
            val kind = reader.kind(current)
            if (kind == ScratchPathKind.ABSENT) break
            nearest = Nearest(current, kind)
        }
        return nearest
    }

    /** The usable bytes for the scratch directory: its own file store, or its nearest existing parent's. */
    fun usableBytes(): Long = reader.usableBytes(nearestExisting().path)

    /** The process's write permission on [path] — the seam's reading, unchanged. */
    fun canWrite(path: Path): Boolean = reader.canWrite(path)

    /** [ScratchSpaceReader.usableBytes] of [path] — the seam's reading, unchanged. */
    fun usableBytes(path: Path): Long = reader.usableBytes(path)
}
