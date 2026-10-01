package co.datapipelines.auth

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
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
 * The close contract (342-c): [close] terminates the ACCEPTOR and every live session —
 * established (mid-transfer) and connecting (mid-`connect`) alike. It owns this through a
 * registry, not through interruption: a blocked socket read or connect does not respond to
 * `Thread.interrupt`, so closing the sockets is the only thing that reliably ends the pump
 * threads and aborts an in-flight connect. Every accepted connection is registered before
 * it is served, so close cannot miss it even when it wins a race against task submission
 * (a submission rejected by the shutting-down executor closes that client instead of
 * leaking it). The pumps are reaped with bounded joins — close never blocks on a worker,
 * and no pump outlives its sockets.
 *
 * A twin of this class lives in `tests/integration-tests` — the two modules share no test-fixtures
 * artifact, and a Gradle module for ~200 lines is its own defect.
 * [StableOriginForwarderTest] pins the ownership property, the forwarding behavior, the
 * close/termination contract and the independent-process witness.
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

    /**
     * Closes the listener and terminates every live session — established or connecting.
     * Idempotent, and safe to call from any thread: the sockets are closed exactly once
     * (here or in the serving task's own cleanup), which is what unblocks and ends the
     * pump threads. Never blocks on a worker.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        // Closing the sockets is the termination mechanism: a pump blocked in read() gets a
        // SocketException, a connect in flight is aborted. Interruption alone cannot do either.
        connections.forEach { it.closeBoth() }
        connections.clear()
        executor.shutdownNow() // interrupts the serving tasks; they finish closing in their finally
    }

    @Volatile
    private var target: InetSocketAddress? = null

    private val closed = AtomicBoolean(false)

    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    /** Every accepted-but-maybe-not-yet-served connection, registered the moment accept returns. */
    private val connections: MutableSet<Session> = ConcurrentHashMap.newKeySet()

    init {
        Thread({ acceptLoop() }, "stable-origin-$originPort-accept").apply { isDaemon = true }.start()
    }

    /** One accepted connection: the origin-side socket and, once connected, the target-side twin. */
    private class Session(val client: Socket) {
        @Volatile
        var app: Socket? = null

        /** Closes both ends; safe from any thread, any number of times. */
        fun closeBoth() {
            val appSocket = app
            runCatching { client.close() }
            runCatching { appSocket?.close() }
        }
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val client =
                try {
                    listener.accept()
                } catch (_: IOException) {
                    break // close() closed the listener; any other listener death ends forwarding too
                }
            val session = Session(client)
            connections.add(session)
            if (closed.get()) {
                // close() won the race between accept and registration — its sweep may have
                // already passed; close here so the client cannot leak.
                session.closeBoth()
                connections.remove(session)
                break
            }
            try {
                executor.submit { serve(session) }
            } catch (_: RejectedExecutionException) {
                // The executor was shut down between the check and the submit: close() owns
                // the teardown, and the accepted client must not outlive it.
                session.closeBoth()
                connections.remove(session)
                break
            }
        }
    }

    private fun serve(session: Session) {
        try {
            val address = target
            if (address == null) {
                return // nothing to forward to; the finally drops the client
            }
            val app = Socket()
            // Register the target-side socket BEFORE connecting, so close() aborts an
            // in-flight connect instead of leaving the serving task blocked in it.
            session.app = app
            try {
                app.connect(address, CONNECT_TIMEOUT_MS)
            } catch (_: IOException) {
                return // target refused, went away, or close() aborted the connect
            }
            // Two half-duplex pumps; an EOF or error in one direction propagates as a
            // half-close to the other side. The pumps end when their sockets end: EOF, the
            // peer's close, or this forwarder's close() closing both sockets under them.
            val toApp =
                pumpThread("to-app") {
                    pump(session.client.getInputStream(), app.getOutputStream())
                    runCatching { app.shutdownOutput() }
                }
            val toClient =
                pumpThread("to-client") {
                    pump(app.getInputStream(), session.client.getOutputStream())
                    runCatching { session.client.shutdownOutput() }
                }
            toApp.start()
            toClient.start()
            awaitPumps(toApp, toClient)
        } finally {
            connections.remove(session)
            session.closeBoth()
        }
    }

    /** A named DAEMON platform thread, so close-path assertions can account for every worker. */
    private fun pumpThread(
        direction: String,
        body: () -> Unit,
    ): Thread = Thread(body, "stable-origin-$originPort-$direction").apply { isDaemon = true }

    /**
     * Waits for the pumps to finish — with bounded joins, tolerant of interruption, never
     * unbounded: the pumps end on EOF or on the sockets being closed by [close] (or by the
     * serving task's own finally), not on being interrupted.
     */
    private fun awaitPumps(vararg pumps: Thread) {
        while (pumps.any { it.isAlive } && !closed.get()) {
            try {
                pumps.forEach { it.join(POLL_MS) }
            } catch (_: InterruptedException) {
                // close() interrupted this serving task — re-check the loop conditions and
                // keep reaping; the sockets, closed by close() and by the finally, are what
                // end the pumps.
            }
        }
        if (closed.get()) {
            // Torn down mid-flight: the finally below closes both sockets; a bounded final
            // join reaps the pumps once the closed sockets unblock them.
            pumps.forEach { pump ->
                try {
                    pump.join(JOIN_ON_CLOSE_MS)
                } catch (_: InterruptedException) {
                }
            }
        }
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
            // A direction ends on EOF, on the peer's close, or on this forwarder's close()
            // closing the socket under the read. The sockets are closed by [serve]'s
            // finally and by [close] — never left open by a pump's exit alone.
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val BUFFER_BYTES = 8 * 1024
        private const val POLL_MS = 100L
        private const val JOIN_ON_CLOSE_MS = 2_000L

        /** Binds the origin LOOPBACK-ONLY and starts its accept loop. */
        fun open(): StableOriginForwarder =
            StableOriginForwarder(
                ServerSocket().apply {
                    bind(InetSocketAddress(InetAddress.getByName("localhost"), 0))
                },
            )
    }
}
