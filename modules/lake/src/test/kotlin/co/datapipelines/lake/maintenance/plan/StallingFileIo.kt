package co.datapipelines.lake.maintenance.plan

import org.apache.iceberg.io.FileIO
import org.apache.iceberg.io.InputFile
import org.apache.iceberg.io.OutputFile
import org.apache.iceberg.io.SeekableInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The faults the planner's readers meet, shared by every client the readers build (one per reader). A table is told
 * apart by its location, `<root>/<namespace>/<table id>/`.
 *
 * - [stall]: the table's next manifest stream blocks until [release], IGNORING interrupts (an object-store read that
 *   never answers; the property `ScriptedFileIo.STALL` models, here over the REAL manifests in MinIO, because the
 *   measurer reads a real snapshot);
 * - [fail]: every read of the table's metadata file throws at once;
 * - [metadataReads]: the tables whose metadata file was opened, in order: the attempt sequence;
 * - [metadataReaders]: for each such open, the thread (the reader) that opened it.
 */
internal class StallControl {
    private val stalls = ConcurrentHashMap<UUID, Stall>()
    private val failing: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    val metadataReads: MutableList<UUID> = CopyOnWriteArrayList()
    val metadataReaders: MutableList<Pair<UUID, Thread>> = CopyOnWriteArrayList()
    val clientsBuilt = AtomicInteger()
    val clientsClosed = AtomicInteger()

    class Stall {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)

        /** The reader thread blocked in this stall, set before [entered] opens. */
        @Volatile
        var thread: Thread? = null
    }

    fun stall(table: UUID): Stall = Stall().also { stalls[table] = it }

    fun release(table: UUID) {
        stalls.remove(table)?.released?.countDown()
    }

    fun releaseAll() = stalls.keys.toList().forEach(::release)

    fun fail(table: UUID) {
        failing += table
    }

    fun heal(table: UUID) {
        failing -= table
    }

    internal fun opening(path: String) {
        val table = tableOf(path) ?: return
        if (path.endsWith(".metadata.json") || path.endsWith(".metadata.json.gz")) {
            metadataReads += table
            metadataReaders += table to Thread.currentThread()
            if (table in failing) error("scripted read failure")
        }
        if (path.endsWith(".avro")) stalls[table]?.let(::block)
    }

    private fun block(stall: Stall) {
        stall.thread = Thread.currentThread()
        stall.entered.countDown()
        while (true) {
            try {
                if (stall.released.await(1, TimeUnit.HOURS)) return
            } catch (_: InterruptedException) {
                // ignored on purpose: a stuck store read does not answer an interrupt
            }
        }
    }

    private fun tableOf(path: String): UUID? =
        TABLE
            .find(path)
            ?.groupValues
            ?.get(1)
            ?.let(UUID::fromString)

    companion object {
        private val TABLE = Regex("/[a-z0-9_]+/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/")
    }
}

/** A client over [delegate] (the reader's own `metadataFileIo()`) that consults [control] before every stream. */
internal class StallingFileIo(
    private val delegate: FileIO,
    private val control: StallControl,
) : FileIO {
    init {
        control.clientsBuilt.incrementAndGet()
    }

    override fun newInputFile(path: String): InputFile {
        val file = delegate.newInputFile(path)
        return object : InputFile by file {
            override fun newStream(): SeekableInputStream {
                control.opening(path)
                return file.newStream()
            }
        }
    }

    override fun newOutputFile(path: String): OutputFile = delegate.newOutputFile(path)

    override fun deleteFile(path: String) = delegate.deleteFile(path)

    override fun close() {
        control.clientsClosed.incrementAndGet()
        delegate.close()
    }
}
