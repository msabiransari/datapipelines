package co.datapipelines.lake

import java.io.DataInputStream
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
 * E1 (#774)'s `PgProxy`, ported: a PostgreSQL-protocol-aware TCP proxy on loopback that drops the RESPONSE to a
 * catalog commit AFTER PostgreSQL committed it (§8a case A, "lost ack").
 *
 * Client → server bytes are forwarded verbatim and scanned for Iceberg's CAS text
 * `UPDATE iceberg_tables SET metadata_location`; server → client traffic is parsed as typed backend messages (no
 * SSL/GSS negotiation: `sslmode=disable&gssEncMode=disable`). When [arm]ed and a connection has sent the CAS,
 * every backend message after it is withheld, and on the `ReadyForQuery` that ends the autocommit statement both
 * sockets close: the client sees EOF instead of `UPDATE 1`. One drop per arming.
 *
 * [acceptsSinceArm] counts NEW client connections after arming — a reconnect-and-replay pool opens one to re-run
 * the commit (the red case), the fenced pool never does (the green case). [casSent] counts CAS statements seen.
 */
internal class PgResponseDropProxy(
    private val upstreamHost: String,
    private val upstreamPort: Int,
) : AutoCloseable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())

    @Volatile private var armed = false

    @Volatile private var everArmed = false
    private val dropped = CountDownLatch(1)

    val acceptsSinceArm = AtomicInteger()
    val casSent = AtomicInteger()

    /** What the proxy withheld (backend message types; the CommandComplete tag, e.g. `C:UPDATE 1`). */
    val withheld: MutableList<String> = CopyOnWriteArrayList()

    init {
        daemon("pgproxy-accept", ::acceptLoop)
    }

    val port: Int get() = server.localPort

    /** A JDBC URL to [database] through this proxy. `prepareThreshold=0`: every statement resends its text. */
    fun url(database: String): String = "jdbc:postgresql://127.0.0.1:$port/$database?sslmode=disable&gssEncMode=disable&prepareThreshold=0"

    fun arm() {
        everArmed = true
        armed = true
    }

    fun awaitDropped(seconds: Long): Boolean = dropped.await(seconds, TimeUnit.SECONDS)

    private fun acceptLoop() {
        while (!server.isClosed) {
            try {
                val client = server.accept()
                if (everArmed) acceptsSinceArm.incrementAndGet()
                val upstream = Socket(upstreamHost, upstreamPort)
                val connection = Connection(client, upstream)
                daemon("pgproxy-c2s", connection::clientToServer)
                daemon("pgproxy-s2c", connection::serverToClient)
            } catch (_: IOException) {
                return
            }
        }
    }

    private inner class Connection(
        private val client: Socket,
        private val upstream: Socket,
    ) {
        @Volatile private var commitSent = false
        private val window = ByteArray(CAS.size - 1)
        private var windowLength = 0

        fun clientToServer() {
            try {
                client.getInputStream().use { input ->
                    upstream.getOutputStream().use { output -> pumpClientBytes(input, output) }
                }
            } catch (_: IOException) {
                // closed
            } finally {
                closeBoth()
            }
        }

        /** Forwards the client's bytes verbatim, marking the connection once it has sent the CAS. */
        private fun pumpClientBytes(
            input: InputStream,
            output: OutputStream,
        ) {
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) return
                if (contains(buffer, n)) {
                    casSent.incrementAndGet()
                    if (armed) commitSent = true
                }
                output.write(buffer, 0, n)
                output.flush()
            }
        }

        /** Scans this read plus the tail of the previous one, so a match split across reads is still found. */
        private fun contains(
            buffer: ByteArray,
            n: Int,
        ): Boolean {
            val joined = ByteArray(windowLength + n)
            System.arraycopy(window, 0, joined, 0, windowLength)
            System.arraycopy(buffer, 0, joined, windowLength, n)
            val hit = indexOf(joined) >= 0
            val keep = minOf(window.size, joined.size)
            System.arraycopy(joined, joined.size - keep, window, 0, keep)
            windowLength = keep
            return hit
        }

        fun serverToClient() {
            try {
                DataInputStream(upstream.getInputStream()).use { input ->
                    client.getOutputStream().use { output -> pumpServerMessages(input, output) }
                }
            } catch (_: IOException) {
                // closed
            } finally {
                closeBoth()
            }
        }

        /** Reads typed backend messages; forwards each, or withholds every one after the CAS while armed. */
        private fun pumpServerMessages(
            input: DataInputStream,
            output: OutputStream,
        ) {
            while (true) {
                val type = input.read()
                if (type < 0) return
                val length = input.readInt()
                val body = ByteArray(length - Int.SIZE_BYTES)
                input.readFully(body)
                if (commitSent && armed) {
                    if (withhold(type, body)) return
                } else {
                    forward(output, type, length, body)
                }
            }
        }

        /** Withholds one message; true once the statement's `ReadyForQuery` is withheld and both sockets are cut. */
        private fun withhold(
            type: Int,
            body: ByteArray,
        ): Boolean {
            withheld += describe(type, body)
            if (type != READY_FOR_QUERY) return false
            armed = false
            dropped.countDown()
            closeBoth()
            return true
        }

        private fun forward(
            output: OutputStream,
            type: Int,
            length: Int,
            body: ByteArray,
        ) {
            output.write(type)
            output.write(
                byteArrayOf(
                    (length ushr 24).toByte(),
                    (length ushr 16).toByte(),
                    (length ushr 8).toByte(),
                    length.toByte(),
                ),
            )
            output.write(body)
            output.flush()
        }

        private fun describe(
            type: Int,
            body: ByteArray,
        ): String =
            if (type == COMMAND_COMPLETE) {
                "C:" + String(body, 0, maxOf(0, body.size - 1), Charsets.US_ASCII)
            } else {
                type.toChar().toString()
            }

        private fun closeBoth() {
            runCatching { client.close() }
            runCatching { upstream.close() }
        }
    }

    override fun close() = server.close()

    private companion object {
        val CAS = "UPDATE iceberg_tables SET metadata_location".toByteArray(Charsets.US_ASCII)
        const val READY_FOR_QUERY = 'Z'.code
        const val COMMAND_COMPLETE = 'C'.code
        const val BACKLOG = 50
        const val BUFFER = 16_384

        fun daemon(
            name: String,
            body: () -> Unit,
        ) = Thread(body, name).apply {
            isDaemon = true
            start()
        }

        fun indexOf(haystack: ByteArray): Int {
            for (i in 0..haystack.size - CAS.size) {
                if (CAS.indices.all { haystack[i + it] == CAS[it] }) return i
            }
            return -1
        }
    }
}
