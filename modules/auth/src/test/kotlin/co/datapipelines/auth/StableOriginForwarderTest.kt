package co.datapipelines.auth

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText

/**
 * The ownership property the DEFINED_PORT suites lean on (#334, second round): the origin's
 * port is held by its listener for the WHOLE suite life — nothing is ever released, so there
 * is no handoff window for a contender to win, at startup or at any later moment. The first
 * #334 delivery held the port until the `server.port` supplier read and released it there;
 * an independent witness bound the port immediately after that release and owned it, and the
 * server's own bind then failed — a smaller window, not a closed one. These tests pin the
 * corrected property, including at exactly that old release point, and keep the negative
 * that keeps the mechanism honest: the bare pre-pick shape leaks by construction.
 */
class StableOriginForwarderTest {
    @Test
    fun `a second binder is refused while the origin is held`() {
        StableOriginForwarder.open().use { origin ->
            secondBinderTakes(origin.originPort) shouldBe false // the other JVM's bind fails with EADDRINUSE
        }
    }

    /**
     * The boundary the first delivery got wrong: its supplier released the port exactly
     * here — after the property read, before the application had bound. Under the
     * forwarder the origin is STILL owned at that point, after a target has been assigned
     * and again after it — a binder at the old release point finds the port taken.
     */
    @Test
    fun `ownership survives the handoff boundary - a binder at the old release point is still refused`() {
        StableOriginForwarder.open().use { origin ->
            val target = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
            origin.forwardTo(target.localPort) // what the suite's @BeforeEach does once Tomcat is up
            secondBinderTakes(origin.originPort) shouldBe false
            target.close() // even with the target gone, the ORIGIN is still held
            secondBinderTakes(origin.originPort) shouldBe false
        }
    }

    /** The forwarder forwards: bytes sent to the origin come back from the assigned target. */
    @Test
    fun `a request through the origin reaches the assigned target and the answer comes back`() {
        StableOriginForwarder.open().use { origin ->
            val echoed = java.util.concurrent.CountDownLatch(1)
            val target = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
            Thread({
                target.accept().use { app ->
                    val payload = app.getInputStream().readNBytes(4)
                    app.getOutputStream().write(payload)
                    app.getOutputStream().flush()
                }
                echoed.countDown()
            }, "echo-target").apply { isDaemon = true }.start()
            origin.forwardTo(target.localPort)

            Socket(InetAddress.getByName("localhost"), origin.originPort).use { client ->
                client.getOutputStream().write("ping".toByteArray(StandardCharsets.US_ASCII))
                client.getOutputStream().flush()
                client.getInputStream().readNBytes(4) shouldBe "ping".toByteArray(StandardCharsets.US_ASCII)
            }
            echoed.await(10, TimeUnit.SECONDS) shouldBe true
            target.close()
        }
    }

    /** Nothing is forwarded until a target is assigned — and the listener survives the attempt. */
    @Test
    fun `with no target assigned the connection is dropped and the origin keeps serving`() {
        StableOriginForwarder.open().use { origin ->
            Socket(InetAddress.getByName("localhost"), origin.originPort).use { probe ->
                probe.getInputStream().read() shouldBe -1 // closed, nothing forwarded anywhere
            }
            secondBinderTakes(origin.originPort) shouldBe false // still owned, still bound
        }
    }

    /**
     * The cross-JVM proof (the orchestrator's witness, made permanent): an independent
     * process — its own JVM, spawned through the single-file source launcher — is refused
     * while the origin is held, and takes the port once it is closed. One JVM's in-process
     * bind attempt alone does not establish ownership across builds.
     */
    @Test
    fun `an independent process is refused while the origin is held and takes the port after close`() {
        val contenderSource =
            Path.of(
                requireNotNull(javaClass.classLoader.getResource("contender/PortContender.java")) {
                    "the independent-process contender source must be on the test classpath"
                }.toURI(),
            )
        val javaBin =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse("java")
        StableOriginForwarder.open().use { origin ->
            contenderTakes(javaBin, contenderSource, origin.originPort) shouldBe false
            origin.close()
            contenderTakes(javaBin, contenderSource, origin.originPort) shouldBe true
        }
    }

    @Test
    fun `close is idempotent`() {
        val origin = StableOriginForwarder.open()
        origin.close()
        origin.close()
    }

    /** The negative that keeps the mechanism honest: the old shape leaks by construction. */
    @Test
    fun `the bare pre-pick shape does not hold the port - the race #334 closed`() {
        val port = ServerSocket(0).use { it.localPort } // released immediately
        secondBinderTakes(port) shouldBe true // any JVM on the box can take it before Tomcat binds
    }

    /** Spawns the contender JVM and reports whether it could bind the port. */
    private fun contenderTakes(
        javaBin: String,
        source: Path,
        port: Int,
    ): Boolean {
        val verdict = Files.createTempFile("port-contender-", ".txt")
        try {
            val process =
                ProcessBuilder(javaBin, source.toString(), port.toString(), verdict.toString())
                    .redirectErrorStream(true)
                    .start()
            val finished = process.waitFor(2, TimeUnit.MINUTES)
            val output = process.inputStream.readBytes().decodeToString() // consume, so a chatty contender never blocks
            check(finished) { "the contender process did not finish in 2 minutes" }
            // A missing or malformed verdict is a FAILURE, never a refusal — a witness that
            // could not run must not read as one that was refused.
            return when (val text = verdict.readText().trim()) {
                "took=true" -> true
                "took=false" -> false
                else -> error("contender produced no verdict (exit=$process.exitValue()): '$text' / $output")
            }
        } finally {
            Files.deleteIfExists(verdict)
        }
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
