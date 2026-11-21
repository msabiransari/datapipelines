package co.datapipelines.executor

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A loopback TCP proxy in front of a real database server (#1036), used to lose a commit's
 * acknowledgement on the WIRE — the one fault a driver-level double cannot model honestly,
 * because the question is what the pinned driver and the server do, not what a fake throws.
 *
 *  - [cutAll] closes every proxied connection in both directions: the driver's next call fails
 *    with an I/O error, and a COMMIT not yet forwarded never reaches the server, which rolls the
 *    session's open transaction back when it sees the socket close.
 *  - [dropReplies] keeps forwarding client bytes to the server but discards every server byte:
 *    a COMMIT is forwarded, the server commits, and the driver never hears back.
 *
 * Test-only, loopback-bound, daemon threads; it opens no outbound connection except to the
 * container's mapped port it was given.
 */
internal class FaultProxy(
    private val upstreamHost: String,
    private val upstreamPort: Int,
) : Closeable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
    private val links = ConcurrentLinkedQueue<Pair<Socket, Socket>>()

    @Volatile
    private var replies = true

    /** The loopback port to point a JDBC URL at. */
    val port: Int get() = server.localPort

    /** [jdbcUrl] with its first `host:port` replaced by this proxy's loopback address. */
    fun route(jdbcUrl: String): String = HOST_PORT.replaceFirst(jdbcUrl, "//$LOOPBACK:$port")

    init {
        Thread({ acceptLoop() }, "fault-proxy-accept-$port").apply { isDaemon = true }.start()
    }

    /** Discard every byte the server sends from now on, on every proxied connection. */
    fun dropReplies() {
        replies = false
    }

    /** Close every proxied connection in both directions. */
    fun cutAll() {
        while (true) {
            val (client, upstream) = links.poll() ?: return
            runCatching { client.close() }
            runCatching { upstream.close() }
        }
    }

    override fun close() {
        runCatching { server.close() }
        cutAll()
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val client =
                try {
                    server.accept()
                } catch (_: SocketException) {
                    return
                }
            val upstream = Socket(upstreamHost, upstreamPort)
            links += client to upstream
            pump(client.getInputStream(), upstream.getOutputStream(), towardsClient = false)
            pump(upstream.getInputStream(), client.getOutputStream(), towardsClient = true)
        }
    }

    private fun pump(
        from: InputStream,
        to: OutputStream,
        towardsClient: Boolean,
    ) {
        Thread({
            val buffer = ByteArray(BUFFER)
            runCatching {
                while (true) {
                    val read = from.read(buffer)
                    if (read < 0) break
                    if (!towardsClient || replies) {
                        to.write(buffer, 0, read)
                        to.flush()
                    }
                }
            }
            runCatching { to.close() }
        }, "fault-proxy-pump").apply { isDaemon = true }.start()
    }

    companion object {
        const val BACKLOG = 16
        const val BUFFER = 16 * 1024
        const val LOOPBACK = "127.0.0.1"
        val HOST_PORT = Regex("//[^/:;?]+:\\d+")

        /** The `host` and `port` of [jdbcUrl]'s first authority. */
        fun authorityOf(jdbcUrl: String): Pair<String, Int> {
            val authority = requireNotNull(HOST_PORT.find(jdbcUrl)) { "no host:port in the JDBC URL" }.value.removePrefix("//")
            return authority.substringBeforeLast(':') to authority.substringAfterLast(':').toInt()
        }
    }
}
