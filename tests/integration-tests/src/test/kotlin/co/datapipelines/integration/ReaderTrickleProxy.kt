package co.datapipelines.integration

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** S3 pass-through until armed; a real Parquet GET then receives the lander's endless 4 KiB/s response shape. */
internal class ReaderTrickleProxy(
    private val host: String,
    private val port: Int,
) : AutoCloseable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val armed = AtomicBoolean(false)
    val arrived = CountDownLatch(1)
    val bytes = AtomicLong()
    val endpoint: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        daemon { accept() }
    }

    /** Drop keepalives so the first request after arming has a complete request header. */
    fun arm() {
        armed.set(true)
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }

    private fun accept() {
        while (!server.isClosed) {
            try {
                val client = server.accept()
                sockets += client
                daemon { serve(client) }
            } catch (_: IOException) {
                return
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            val request = header(client.getInputStream())
            if (armed.get() && request.lineSequence().first().contains(".parquet") && request.startsWith("GET ")) {
                val output = client.getOutputStream()
                output.write(
                    "HTTP/1.1 200 OK\r\nContent-Length: 10000000\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n"
                        .toByteArray(
                            StandardCharsets.US_ASCII,
                        ),
                )
                arrived.countDown()
                while (!client.isClosed) {
                    output.write(ByteArray(CHUNK))
                    output.flush()
                    bytes.addAndGet(CHUNK.toLong())
                    Thread.sleep(SECOND_MILLIS)
                }
            } else {
                val upstream = Socket(host, port)
                sockets += upstream
                upstream.getOutputStream().write(request.toByteArray(StandardCharsets.ISO_8859_1))
                upstream.getOutputStream().flush()
                daemon { copy(client.getInputStream(), upstream) }
                copy(upstream.getInputStream(), client)
            }
        } catch (_: IOException) {
            // Test teardown or the reader closed the socket.
        }
    }

    private fun header(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < HEADER_LIMIT) {
            val byte = input.read()
            if (byte < 0) throw IOException("request ended before header")
            bytes.write(byte)
            if (bytes.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) return bytes.toString(StandardCharsets.ISO_8859_1)
        }
        throw IOException("oversized test request header")
    }

    private fun copy(
        input: InputStream,
        target: Socket,
    ) {
        try {
            input.copyTo(target.getOutputStream())
        } catch (_: IOException) {
            // closed by test
        } finally {
            // Carry EOF across the proxy: otherwise a closed upstream looks like a stalled keep-alive to its client.
            runCatching { target.shutdownOutput() }
        }
    }

    override fun close() {
        server.close()
        sockets.forEach { runCatching { it.close() } }
    }

    private fun daemon(body: () -> Unit) {
        Thread(body, "reader-trickle-proxy").apply { isDaemon = true }.start()
    }

    companion object {
        private const val BACKLOG = 50
        private const val CHUNK = 4096
        private const val SECOND_MILLIS = 1000L
        private const val HEADER_LIMIT = 65_536
        const val WAIT_SECONDS = 95L
        const val START_SECONDS = 15L
    }
}
