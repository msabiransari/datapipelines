package co.datapipelines.lake.delivery

import org.apache.iceberg.io.FileIO
import org.apache.iceberg.io.InputFile
import org.apache.iceberg.io.OutputFile
import org.apache.iceberg.io.SeekableInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The object store as the sweep's metadata read meets it (B.0): every file answers [bytes], healthily, stalled or
 * trickling. A stalled read blocks on a latch and IGNORES interrupts, because a read on a real socket does; a trickling
 * read serves one byte per [TRICKLE_MILLIS] until released. After [release] a stream serves the rest at once.
 */
internal class ScriptedFileIo(
    private val bytes: ByteArray,
    @Volatile var mode: Mode = Mode.HEALTHY,
) : FileIO {
    enum class Mode { HEALTHY, STALL, TRICKLE, FAIL }

    private val released = CountDownLatch(1)

    /** How many streams were opened: the count of reads that were ever submitted to the store. */
    val streamsEntered = AtomicInteger()

    /** How many streams were closed: a read that ended, whichever way. */
    val readsEnded = AtomicInteger()
    val bytesServed = AtomicLong()

    /** How many times the client itself was closed. */
    val closes = AtomicInteger()

    /** The thread that entered the latest stream: the read's own thread, never asked of a live store. */
    @Volatile
    var readerThread: Thread? = null

    fun release() = released.countDown()

    override fun close() {
        closes.incrementAndGet()
    }

    override fun newInputFile(path: String): InputFile = ScriptedInput(path)

    override fun newOutputFile(path: String): OutputFile = error("the sweep's metadata read writes nothing: $path")

    override fun deleteFile(path: String): Unit = error("the sweep's metadata read deletes nothing: $path")

    private inner class ScriptedInput(
        private val path: String,
    ) : InputFile {
        override fun getLength(): Long = bytes.size.toLong()

        override fun newStream(): SeekableInputStream {
            streamsEntered.incrementAndGet()
            readerThread = Thread.currentThread()
            return ScriptedStream(mode)
        }

        override fun location(): String = path

        override fun exists(): Boolean = true
    }

    private inner class ScriptedStream(
        private val script: Mode,
    ) : SeekableInputStream() {
        private var position = 0

        override fun getPos(): Long = position.toLong()

        override fun seek(newPos: Long) {
            position = newPos.toInt()
        }

        override fun read(): Int {
            when (script) {
                Mode.HEALTHY -> Unit
                Mode.STALL -> awaitReleaseIgnoringInterrupts()
                Mode.TRICKLE -> pauseWhileTrickling()
                Mode.FAIL -> throw IOException("the scripted store refused the read")
            }
            // A stalled read released into a failing store fails as it wakes.
            if (mode == Mode.FAIL) throw IOException("the scripted store failed the read")
            if (position >= bytes.size) return -1
            bytesServed.incrementAndGet()
            return bytes[position++].toInt() and BYTE_MASK
        }

        override fun close() {
            readsEnded.incrementAndGet()
        }

        private fun awaitReleaseIgnoringInterrupts() {
            while (released.count > 0) {
                try {
                    released.await()
                } catch (_: InterruptedException) {
                    // A read on a real socket does not notice an interrupt.
                }
            }
        }

        private fun pauseWhileTrickling() {
            try {
                released.await(TRICKLE_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // As above; the next byte is simply due.
            }
        }
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val TRICKLE_MILLIS = 100L
    }
}
