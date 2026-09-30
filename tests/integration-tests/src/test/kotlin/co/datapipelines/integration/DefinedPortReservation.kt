package co.datapipelines.integration

import java.net.ServerSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * A port RESERVED until the instant Tomcat binds it (#334).
 *
 * The DEFINED_PORT suites must know their port BEFORE the context starts — the redirect URI
 * is absolute and configured from `datapipelines.auth.base-url`, not derived from the
 * request (auth.md §5.2) — so they cannot use `RANDOM_PORT`. The shape they replaced held
 * the answer differently: `ServerSocket(0).use { it.localPort }` picks a port and releases
 * it IMMEDIATELY, so between that moment and Tomcat's bind — class-load through container
 * startup and context refresh, seconds under the best conditions — any other JVM on the box
 * (a parallel Gradle fork, a lane's own test run) can take the port. The gate on ccba12bf
 * lost exactly that race (`PortInUseException: Port 22283`, four forks, lanes building
 * beside it). The loss is likelier under load for a second reason: `ServerSocket(0)` draws
 * from the OS ephemeral range, the same range every outgoing connection's source port comes
 * from, so a just-released port is precisely what the kernel hands to the next `connect()`.
 *
 * The reservation closes the window: the socket is bound LOOPBACK-ONLY and stays OPEN — the
 * port cannot be taken by another binder while held — and [release] is called from the
 * `server.port` property supplier, the LAST read of the port before the connector starts.
 * What remains is the same-thread handoff inside `onRefresh` (property fetch → factory →
 * bind), microseconds on one thread; the check-then-act gap of minutes is gone. `release`
 * is idempotent: the supplier can be read any number of times before the bind.
 *
 * A twin of this class lives in `tests/integration-tests` for `MailNoticesE2eTest` — the
 * two modules share no test-fixtures artifact, and a Gradle module for ~40 lines is its own
 * defect. [DefinedPortReservationTest] pins the ownership property and documents the leak
 * the old shape had.
 */
class DefinedPortReservation private constructor(
    @Volatile private var socket: ServerSocket?,
) {
    /** The port Tomcat will bind — valid for the life of the reservation. */
    val port: Int = requireNotNull(socket).localPort

    /**
     * Releases the reservation so Tomcat can take the port. Idempotent; safe to call after
     * a failed bind too — the port is simply free.
     */
    fun release() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        /**
         * Reserves an ephemeral port by holding a loopback-bound listening socket until
         * [release]. Loopback only: the reservation is a lock, not a service — it exposes
         * nothing beyond the host while it is held.
         */
        fun reserve(): DefinedPortReservation =
            DefinedPortReservation(
                ServerSocket().apply {
                    bind(InetSocketAddress(InetAddress.getByName("localhost"), 0))
                },
            )
    }
}
