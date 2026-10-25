package co.datapipelines.integration

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A loopback SMTP relay in front of the real one (#442b) — the application's `datapipelines.mail`
 * points HERE, and every connection is forwarded byte for byte to [upstreamHost]:[upstreamPort],
 * except the next ones a test scripted with [failNext]:
 *
 * - [Fault.REFUSE] — the connection is closed before the relay's greeting: provably nothing was
 *   sent (the transport reports it as a connect failure, the retryable kind);
 * - [Fault.DROP_AFTER_DATA] — the whole message, its final `.` included, reaches the real relay;
 *   the relay's answer to it is swallowed and the client's connection closed. The relay HAS the
 *   message and the sender cannot know it — the ambiguous send, made real rather than simulated.
 *
 * The end of the data is the `CRLF.CRLF` sequence, which dot-stuffing makes unique to it; the
 * client sends the body only after the relay's `354`, so any byte the relay sends once that
 * sequence passed is its answer to the data.
 */
class SmtpInterposer(
    private val upstreamHost: String,
    private val upstreamPort: Int,
) : AutoCloseable {
    enum class Fault { REFUSE, DROP_AFTER_DATA }

    private val listener = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val faults = ConcurrentLinkedQueue<Fault>()
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val closed = AtomicBoolean(false)

    /** The port the application's mail configuration names. */
    val port: Int = listener.localPort

    /** Connections accepted, faulted ones included — what a test reads to know its fault was taken. */
    val accepted = AtomicInteger()

    private val acceptor = thread(isDaemon = true, name = "smtp-interposer") { acceptLoop() }

    /** The next accepted connection suffers [fault]; faults queue in order. */
    fun failNext(fault: Fault) {
        faults += fault
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val client =
                try {
                    listener.accept()
                } catch (_: IOException) {
                    return // closed
                }
            accepted.incrementAndGet()
            sockets += client
            when (val fault = faults.poll()) {
                Fault.REFUSE -> client.close()
                else -> serve(client, dropAfterData = fault == Fault.DROP_AFTER_DATA)
            }
        }
    }

    private fun serve(
        client: Socket,
        dropAfterData: Boolean,
    ) {
        val upstream =
            try {
                Socket(upstreamHost, upstreamPort).also { sockets += it }
            } catch (_: IOException) {
                client.close()
                return
            }
        val dataSent = AtomicBoolean(false)
        thread(isDaemon = true, name = "smtp-interposer-up") {
            pump(client.getInputStream(), upstream.getOutputStream()) { chunk, tail -> if (endsData(tail, chunk)) dataSent.set(true) }
            closeQuietly(upstream)
        }
        thread(isDaemon = true, name = "smtp-interposer-down") {
            val toClient = client.getOutputStream()
            pump(upstream.getInputStream(), SwallowingStream(toClient) { dropAfterData && dataSent.get() }) { _, _ -> }
            closeQuietly(client)
        }
    }

    /** Copies [from] to [to] until either side closes; [observe] sees each chunk and the four bytes before it. */
    private fun pump(
        from: InputStream,
        to: OutputStream,
        observe: (ByteArray, ByteArray) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER)
        var tail = ByteArray(0)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) return
                val chunk = buffer.copyOf(n)
                observe(chunk, tail)
                to.write(chunk)
                to.flush()
                tail = (tail + chunk).takeLast(TERMINATOR.size).toByteArray()
            }
        } catch (_: IOException) {
            // either side closed — the other pump closes its socket
        }
    }

    /** Whether the data terminator ends within [chunk], possibly begun in the [tail] before it. */
    private fun endsData(
        tail: ByteArray,
        chunk: ByteArray,
    ): Boolean {
        val joined = tail + chunk
        return (0..joined.size - TERMINATOR.size).any { i -> TERMINATOR.indices.all { joined[i + it] == TERMINATOR[it] } }
    }

    /** Forwards to [target] until [swallow] holds; from then on, closes it instead of writing. */
    private class SwallowingStream(
        private val target: OutputStream,
        private val swallow: () -> Boolean,
    ) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            if (swallow()) throw IOException("the relay's answer to the data is swallowed")
            target.write(b, off, len)
        }

        override fun flush() = target.flush()
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
            // already closed
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listener.close()
        sockets.forEach(::closeQuietly)
        acceptor.join(JOIN_MS)
    }

    private companion object {
        const val BACKLOG = 50
        const val BUFFER = 8_192
        const val JOIN_MS = 2_000L
        val TERMINATOR = "\r\n.\r\n".toByteArray(Charsets.US_ASCII)
    }
}
