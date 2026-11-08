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
 * The object store as the sweep's metadata read meets it (B.0): every file answers [padding] ASCII spaces then [bytes]
 * (JSON allows leading whitespace, so a file of any size parses without a literal of that size), healthily, stalled,
 * trickling or paced. A read asking for many bytes gets what a socket has: everything at once when healthy, one byte
 * per [TRICKLE_MILLIS] when trickling, [PACE_CHUNK] bytes per [PACE_MILLIS] when paced. A stalled read blocks on a latch
 * and IGNORES interrupts, because a read on a real socket does. After [release] a stream serves the rest at once.
 */
internal class ScriptedFileIo(
    private val bytes: ByteArray,
    @Volatile var mode: Mode = Mode.HEALTHY,
    private val padding: Int = 0,
) : FileIO {
    enum class Mode { HEALTHY, STALL, TRICKLE, PACED, FAIL }

    private val released = CountDownLatch(1)
    private val size = padding.toLong() + bytes.size

    /** How many streams were opened: the count of reads that were ever submitted to the store. */
    val streamsEntered = AtomicInteger()

    /** How many streams were closed: a read that ended, whichever way. */
    val readsEnded = AtomicInteger()
    val bytesServed = AtomicLong()

    /** Bytes the latest stream served: a read that starts again starts from byte 0. */
    val latestStreamBytes = AtomicLong()

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
        override fun getLength(): Long = size

        override fun newStream(): SeekableInputStream {
            streamsEntered.incrementAndGet()
            latestStreamBytes.set(0)
            readerThread = Thread.currentThread()
            return ScriptedStream(mode)
        }

        override fun location(): String = path

        override fun exists(): Boolean = true
    }

    private inner class ScriptedStream(
        private val script: Mode,
    ) : SeekableInputStream() {
        private var position = 0L

        override fun getPos(): Long = position

        override fun seek(newPos: Long) {
            position = newPos
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (len == 0) return 0
            val available =
                when (script) {
                    Mode.HEALTHY -> len
                    Mode.STALL -> awaitReleaseIgnoringInterrupts().let { len }
                    Mode.TRICKLE -> if (pause(TRICKLE_MILLIS)) len else 1
                    Mode.PACED -> if (pause(PACE_MILLIS)) len else minOf(len, PACE_CHUNK)
                    Mode.FAIL -> throw IOException("the scripted store refused the read")
                }
            // A stalled read released into a failing store fails as it wakes.
            if (mode == Mode.FAIL) throw IOException("the scripted store failed the read")
            if (position >= size) return -1
            val served = minOf(available.toLong(), size - position).toInt()
            for (i in 0 until served) b[off + i] = byteAt(position + i)
            position += served
            bytesServed.addAndGet(served.toLong())
            latestStreamBytes.addAndGet(served.toLong())
            return served
        }

        override fun close() {
            readsEnded.incrementAndGet()
        }

        private fun byteAt(at: Long): Byte = if (at < padding) SPACE else bytes[(at - padding).toInt()]

        private fun awaitReleaseIgnoringInterrupts() {
            while (released.count > 0) {
                try {
                    released.await()
                } catch (_: InterruptedException) {
                    // A read on a real socket does not notice an interrupt.
                }
            }
        }

        /** Waits [millis] for the next bytes; true when the store was released meanwhile and serves the rest at once. */
        private fun pause(millis: Long): Boolean =
            try {
                released.await(millis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // As above; the next bytes are simply due.
                released.count == 0L
            }
    }

    companion object {
        private const val BYTE_MASK = 0xFF
        private const val SPACE = ' '.code.toByte()
        const val TRICKLE_MILLIS = 100L
        const val PACE_MILLIS = 100L
        const val PACE_CHUNK = 64 * 1024
    }
}
