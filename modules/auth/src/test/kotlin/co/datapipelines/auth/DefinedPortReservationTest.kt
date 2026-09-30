package co.datapipelines.auth

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * The ownership property the DEFINED_PORT suites lean on (#334): a reserved port cannot be
 * taken by another binder, the release hands it over, and the bare pre-pick shape the suites
 * used before #334 never had that property — its port is takeable the instant it is picked,
 * which is the race the gate on ccba12bf lost.
 */
class DefinedPortReservationTest {
    @Test
    fun `a second binder is refused while the reservation is held`() {
        val reservation = DefinedPortReservation.reserve()
        try {
            secondBinderTakes(reservation.port) shouldBe false // the other JVM's bind fails with EADDRINUSE
        } finally {
            reservation.release()
        }
    }

    @Test
    fun `the port binds after the release - what Tomcat does next`() {
        val reservation = DefinedPortReservation.reserve()
        reservation.release()
        secondBinderTakes(reservation.port) shouldBe true // the handoff: bindable once released
    }

    @Test
    fun `release is idempotent - the server port supplier may be read many times`() {
        val reservation = DefinedPortReservation.reserve()
        reservation.release()
        reservation.release()
    }

    /** The negative that keeps the mechanism honest: the old shape leaks by construction. */
    @Test
    fun `the bare pre-pick shape does not hold the port - the race #334 closed`() {
        val port = ServerSocket(0).use { it.localPort } // released immediately
        secondBinderTakes(port) shouldBe true // any JVM on the box can take it before Tomcat binds
    }

    /** Binds the port wildcard, like the parallel fork that lost the gate its port. */
    private fun secondBinderTakes(port: Int): Boolean =
        try {
            ServerSocket().use { it.bind(InetSocketAddress(port)) }
            true
        } catch (_: BindException) {
            false
        }
}
