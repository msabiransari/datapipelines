package co.datapipelines.datasources

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * #818d (record §10 RULING 4, second amendment, item 7) — a byte-level TCP proxy on loopback in front of an object
 * store that, once [stall]ed, ACCEPTS and then sends nothing: the stalled-store shape `ReaderTermination` is derived
 * against. Exemplar: the lake module's `PgResponseDropProxy` (E1 #774), without the protocol parsing.
 *
 * Before [stall] every byte is forwarded both ways. After it, nothing is forwarded in either direction on any
 * connection: an open keep-alive connection's request is read and dropped and its client waits for a response that
 * never comes, and a NEW connection is accepted (so the engine's connect timeout never fires) but never dialled
 * upstream. The sockets stay open until [close], so the client sees silence, never an EOF or a reset — only its own
 * read timeout ends a request.
 *
 * [acceptsSinceStall] counts connections opened after the stall (the engine's fresh-connection retries);
 * [stalledRequests] counts client writes swallowed after it; [awaitStalledRequest] waits for the first.
 */
internal class StallingTcpProxy(
    private val upstreamHost: String,
    private val upstreamPort: Int,
) : AutoCloseable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val firstStalledRequest = CountDownLatch(1)

    @Volatile private var stalled = false

    val acceptsSinceStall = AtomicInteger()
    val stalledRequests = AtomicInteger()

    init {
        daemon("stall-proxy-accept", ::acceptLoop)
    }

    /** `host:port` of the proxy, the form a LAKE `endpoint` property takes. */
    val endpoint: String get() = "127.0.0.1:${server.localPort}"

    /** From now on, nothing is forwarded on any connection, old or new. */
    fun stall() {
        stalled = true
    }

    fun awaitStalledRequest(seconds: Long): Boolean = firstStalledRequest.await(seconds, TimeUnit.SECONDS)

    private fun acceptLoop() {
        while (!server.isClosed) {
            try {
                val client = server.accept()
                sockets += client
                if (stalled) {
                    acceptsSinceStall.incrementAndGet()
                    daemon("stall-proxy-swallow") { pump(client.getInputStream(), null) }
                } else {
                    val upstream = Socket(upstreamHost, upstreamPort)
                    sockets += upstream
                    daemon("stall-proxy-c2s") { pump(client.getInputStream(), upstream.getOutputStream()) }
                    daemon("stall-proxy-s2c") { pump(upstream.getInputStream(), client.getOutputStream(), countStalled = false) }
                }
            } catch (_: IOException) {
                return
            }
        }
    }

    /** Copies [input] to [output] until stalled; after that reads and drops (a null [output] drops from the start). */
    private fun pump(
        input: InputStream,
        output: OutputStream?,
        countStalled: Boolean = true,
    ) {
        val buffer = ByteArray(BUFFER)
        try {
            var n = input.read(buffer)
            while (n >= 0) {
                forwardOrDrop(buffer, n, output, countStalled)
                n = input.read(buffer)
            }
        } catch (_: IOException) {
            // closed by close() or by the peer
        }
    }

    private fun forwardOrDrop(
        buffer: ByteArray,
        n: Int,
        output: OutputStream?,
        countStalled: Boolean,
    ) {
        if (stalled || output == null) {
            if (countStalled && n > 0) {
                stalledRequests.incrementAndGet()
                firstStalledRequest.countDown()
            }
            return
        }
        output.write(buffer, 0, n)
        output.flush()
    }

    override fun close() {
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
    }

    private fun daemon(
        name: String,
        body: () -> Unit,
    ) {
        Thread(body, name).apply { isDaemon = true }.start()
    }

    private companion object {
        const val BACKLOG = 50
        const val BUFFER = 8192
    }
}
