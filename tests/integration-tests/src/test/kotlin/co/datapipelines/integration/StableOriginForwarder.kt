package co.datapipelines.integration

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The suite's STABLE ORIGIN (#334, second round): a loopback listener bound at [open] and
 * owned — in the kernel, by this socket — until [close], with every accepted connection
 * forwarded over real TCP to the application's own listener.
 *
 * Why this shape: the DEFINED_PORT suites must name their origin before the context starts,
 * because the redirect URI is absolute and configured from `datapipelines.auth.base-url`,
 * never derived from the request (auth.md §5.2). The two shapes that came before both
 * handed the port back before Tomcat could bind it: the bare `ServerSocket(0).use { }`
 * pre-pick released instantly (the ccba12bf gate lost it: `PortInUseException: Port
 * 22283`), and the first #334 delivery released inside the `server.port` supplier — the
 * LAST read before the connector starts — which still left a window a contender could win:
 * an independent witness binding right after that release owned the port, and the server's
 * own bind failed. Ownership that changes hands is not ownership.
 *
 * Here nothing changes hands. The listener takes the origin port at class-load and holds it
 * for the whole suite life; the APPLICATION binds a different, kernel-allocated loopback
 * port (`server.port=0` — allocation is atomic, there is nothing to race) and the origin is
 * aimed at it with [forwardTo] once startup reports the real port. Every redirect, callback
 * and mail-link assertion names the origin, so the flows exercise the full path a browser
 * sees: a real connection to the origin listener, real bytes forwarded both directions to
 * the application's socket, real answers back. Forwarding goes ONLY to the explicitly
 * assigned loopback target; with no target assigned (nothing can call before a test does)
 * the connection is dropped. [close] is idempotent and runs in the suite's `@AfterAll`, on
 * success and failure alike.
 *
 * A twin of this class lives in `modules/auth` — the two modules share no test-fixtures
 * artifact, and a Gradle module for ~100 lines is its own defect.
 * [StableOriginForwarderTest] pins the ownership property, the forwarding behavior and the
 * independent-process witness.
 */
class StableOriginForwarder private constructor(
    private val listener: ServerSocket,
) : AutoCloseable {
    /** The origin port every suite assertion and redirect URI names — held for the suite's life. */
    val originPort: Int = listener.localPort

    /**
     * Aims the forwarder at the application's own loopback listener. Loopback only, by
     * construction: the target address is fixed here; callers name only a port.
     */
    fun forwardTo(port: Int) {
        target = InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)
    }

    /** Closes the listener and every live forwarding connection. Idempotent. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        executor.shutdownNow() // interrupts the pumps; their sockets close as they unwind
    }

    @Volatile
    private var target: InetSocketAddress? = null

    private val closed = AtomicBoolean(false)

    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    init {
        Thread({ acceptLoop() }, "stable-origin-$originPort-accept").apply { isDaemon = true }.start()
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val client =
                try {
                    listener.accept()
                } catch (_: IOException) {
                    break // close() closed the listener; any other listener death ends forwarding too
                }
            executor.submit { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        val address = target
        if (address == null) {
            runCatching { client.close() }
            return
        }
        val app =
            try {
                Socket().apply { connect(address, CONNECT_TIMEOUT_MS) }
            } catch (_: IOException) {
                runCatching { client.close() }
                return
            }
        // Two half-duplex pumps; an EOF or error in one direction propagates as a half-close
        // to the other side, and both sockets close when both pumps are done.
        val toApp =
            Thread({
                pump(client.getInputStream(), app.getOutputStream())
                runCatching { app.shutdownOutput() }
            }, "stable-origin-$originPort-to-app")
        val toClient =
            Thread({
                pump(app.getInputStream(), client.getOutputStream())
                runCatching { client.shutdownOutput() }
            }, "stable-origin-$originPort-to-client")
        toApp.start()
        toClient.start()
        toApp.join()
        toClient.join()
        runCatching { client.close() }
        runCatching { app.close() }
    }

    private fun pump(
        input: InputStream,
        output: OutputStream,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: IOException) {
            // A direction ends on EOF or on the peer's close; the other pump notices through
            // its own socket. The sockets are closed by [serve] once both pumps return.
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val BUFFER_BYTES = 8 * 1024

        /** Binds the origin LOOPBACK-ONLY and starts its accept loop. */
        fun open(): StableOriginForwarder =
            StableOriginForwarder(
                ServerSocket().apply {
                    bind(InetSocketAddress(InetAddress.getByName("localhost"), 0))
                },
            )
    }
}
