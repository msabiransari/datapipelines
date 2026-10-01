package co.datapipelines.auth

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.BindException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The ownership property the DEFINED_PORT suites lean on (#334, second round): the origin's
 * port is held by its listener for the WHOLE suite life — nothing is ever released, so there
 * is no handoff window for a contender to win, at startup or at any later moment. The first
 * #334 delivery held the port until the `server.port` supplier read and released it there;
 * an independent witness bound the port immediately after that release and owned it, and the
 * server's own bind then failed — a smaller window, not a closed one. These tests pin the
 * corrected property, including at exactly that old release point, and keep the negative
 * that keeps the mechanism honest: the bare pre-pick shape leaks by construction.
 *
 * They also pin the close contract (342-c): close() terminates established and connecting
 * sessions as well as the acceptor, ends every pump it started, survives interruption and
 * rejected submission, and never blocks on a worker — each proven with real sockets and
 * bounded, event-synchronized assertions.
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
     * The real-socket regression 342-c fixes: close() lands UNDER a live, proven,
     * bidirectionally idle connection. Both peers must terminate — the client sees EOF (or
     * the reset), the target side sees the session end — and every worker the forwarder
     * started must be gone. The delivered cleanup interrupted the serving task while it
     * was joining its pumps and skipped the socket close: the client stayed open, bytes
     * kept flowing after close, and both pump threads outlived the forwarder. The failure
     * messages name the surviving peer or worker, so a reintroduced defect is red on
     * exactly that.
     */
    @Test
    fun `close during a live transfer terminates both peers and every owned worker`() {
        StableOriginForwarder.open().use { origin ->
            val (target, targetEnded) = echoTargetUntilEof()
            origin.forwardTo(target.localPort)

            val client = Socket(InetAddress.getByName("localhost"), origin.originPort)
            try {
                // The session is established and PROVEN: a round trip has crossed both pumps.
                client.getOutputStream().write("ping".toByteArray(StandardCharsets.US_ASCII))
                client.getOutputStream().flush()
                client.getInputStream().readNBytes(4) shouldBe "ping".toByteArray(StandardCharsets.US_ASCII)

                origin.close()

                val clientClosed = CountDownLatch(1)
                Thread({
                    try {
                        client.getInputStream().read()
                    } catch (_: IOException) {
                    }
                    runCatching { client.close() }
                    clientClosed.countDown()
                }, "client-eof-probe").apply { isDaemon = true }.start()

                check(clientClosed.await(PEER_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "the client peer stayed OPEN across close() — no EOF within " +
                        "$PEER_CLOSE_TIMEOUT_SECONDS s; surviving forwarder workers: " +
                        forwarderWorkerNames(origin.originPort)
                }
                check(targetEnded.await(PEER_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "the target peer stayed OPEN across close() — the target side never saw " +
                        "the session end within $PEER_CLOSE_TIMEOUT_SECONDS s"
                }
                awaitWorkersGone(origin.originPort)
            } finally {
                runCatching { client.close() }
                target.close()
            }
        }
    }

    /**
     * A session caught in the CONNECT phase must die with close() too. The target's backlog
     * is full and never drained, so the serving task's connect blocks in SYN retransmission
     * — a state thread interruption cannot unwind. Closing the registered sockets is what
     * aborts it; the client must see the session end and no worker may survive.
     */
    @Test
    fun `close during a blocked target connect terminates the connecting session`() {
        StableOriginForwarder.open().use { origin ->
            val target = ServerSocket()
            target.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
            val backlogFiller = Socket()
            try {
                backlogFiller.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), target.localPort))
                origin.forwardTo(target.localPort)

                val client = Socket(InetAddress.getByName("localhost"), origin.originPort)
                try {
                    // The origin-side connection is up (kernel backlog); the serving task is
                    // in — or a moment from entering — a connect that cannot complete.
                    origin.close()

                    val clientClosed = CountDownLatch(1)
                    Thread({
                        try {
                            client.getInputStream().read()
                        } catch (_: IOException) {
                        }
                        runCatching { client.close() }
                        clientClosed.countDown()
                    }, "connecting-client-probe").apply { isDaemon = true }.start()
                    check(clientClosed.await(PEER_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        "a session blocked in the target connect stayed OPEN across close() " +
                            "(no EOF within $PEER_CLOSE_TIMEOUT_SECONDS s); surviving workers: " +
                            forwarderWorkerNames(origin.originPort)
                    }
                    awaitWorkersGone(origin.originPort)
                } finally {
                    runCatching { client.close() }
                }
            } finally {
                runCatching { backlogFiller.close() }
                target.close()
            }
        }
    }

    /**
     * The failed-connect case: a refused target connect drops the client promptly (the
     * serving task's own cleanup closes the session) and costs the forwarder nothing —
     * the next session forwards normally.
     */
    @Test
    fun `a failed target connect drops the client and the forwarder keeps serving`() {
        var port = -1
        StableOriginForwarder.open().use { origin ->
            port = origin.originPort
            // A port nothing listens on — same acceptance as the bare pre-pick negative
            // below: the holder is closed and the connect follows immediately.
            val deadPort = ServerSocket(0).use { it.localPort }
            origin.forwardTo(deadPort)

            val client = Socket(InetAddress.getByName("localhost"), origin.originPort)
            try {
                val clientClosed = CountDownLatch(1)
                Thread({
                    try {
                        client.getInputStream().read()
                    } catch (_: IOException) {
                    }
                    runCatching { client.close() }
                    clientClosed.countDown()
                }, "refused-connect-probe").apply { isDaemon = true }.start()
                check(clientClosed.await(PEER_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "a refused target connect left the client open (no EOF within " +
                        "$PEER_CLOSE_TIMEOUT_SECONDS s); surviving workers: " +
                        forwarderWorkerNames(origin.originPort)
                }
            } finally {
                runCatching { client.close() }
            }

            // The forwarder is unharmed: point it at a live target and forward again.
            val target = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
            val echoed = CountDownLatch(1)
            Thread({
                target.accept().use { app ->
                    val payload = app.getInputStream().readNBytes(4)
                    app.getOutputStream().write(payload)
                    app.getOutputStream().flush()
                }
                echoed.countDown()
            }, "echo-target").apply { isDaemon = true }.start()
            origin.forwardTo(target.localPort)
            Socket(InetAddress.getByName("localhost"), origin.originPort).use { retry ->
                retry.getOutputStream().write("ping".toByteArray(StandardCharsets.US_ASCII))
                retry.getOutputStream().flush()
                retry.getInputStream().readNBytes(4) shouldBe "ping".toByteArray(StandardCharsets.US_ASCII)
            }
            echoed.await(10, TimeUnit.SECONDS) shouldBe true
            target.close()
        }
        awaitWorkersGone(port)
    }

    /** The normal-EOF case: the client goes away on its own — the target sees it, pumps end. */
    @Test
    fun `normal EOF tears the session down - the target sees the close and no worker survives`() {
        var port = -1
        StableOriginForwarder.open().use { origin ->
            port = origin.originPort
            val (target, targetEnded) = echoTargetUntilEof()
            try {
                origin.forwardTo(target.localPort)

                Socket(InetAddress.getByName("localhost"), origin.originPort).use { client ->
                    client.getOutputStream().write("ping".toByteArray(StandardCharsets.US_ASCII))
                    client.getOutputStream().flush()
                    client.getInputStream().readNBytes(4) shouldBe "ping".toByteArray(StandardCharsets.US_ASCII)
                } // the client closes here: the normal EOF path

                check(targetEnded.await(PEER_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "the target side never saw the client's EOF within $PEER_CLOSE_TIMEOUT_SECONDS s"
                }
            } finally {
                target.close()
            }
        }
        // The close above ended the forwarder for good: no worker of any kind survives it.
        awaitWorkersGone(port)
    }

    /**
     * Close at startup, then once more: the acceptor must be dead — a fresh connect is
     * refused promptly, not accepted into a void — and no worker may survive either close.
     */
    @Test
    fun `close ends the acceptor, repeats safely, and leaves no workers behind`() {
        val origin = StableOriginForwarder.open()
        val port = origin.originPort
        origin.close()
        origin.close()
        val (outcome, socket) = connectOutcome(port)
        runCatching { socket?.close() }
        check(outcome == "refused") {
            "the acceptor outlived close(): a fresh connect after close was $outcome"
        }
        awaitWorkersGone(port)
    }

    /**
     * The submission race, asserted from the outside in every interleaving: a client whose
     * connect lands beside close() is either refused by the dead acceptor or accepted and
     * then closed by the teardown — it is NEVER left open, whatever order wins.
     */
    @Test
    fun `a client that races close is terminated either way`() {
        val origin = StableOriginForwarder.open()
        val port = origin.originPort
        val (outcome, socket) = connectOutcome(port)
        origin.close()
        if (outcome == "connected" && socket != null) {
            val closed = CountDownLatch(1)
            Thread({
                try {
                    socket.getInputStream().read()
                } catch (_: IOException) {
                }
                runCatching { socket.close() }
                closed.countDown()
            }, "race-client-probe").apply { isDaemon = true }.start()
            check(closed.await(PEER_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "a client accepted beside close() was left open (no EOF within " +
                    "$PEER_CLOSE_TIMEOUT_SECONDS s); surviving workers: ${forwarderWorkerNames(port)}"
            }
        } else {
            runCatching { socket?.close() }
        }
        awaitWorkersGone(port)
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
        StableOriginForwarder.open().use { origin ->
            contenderTakes(javaBinary(), contenderSource, origin.originPort) shouldBe false
            origin.close()
            contenderTakes(javaBinary(), contenderSource, origin.originPort) shouldBe true
        }
    }

    @Test
    fun `close is idempotent`() {
        val origin = StableOriginForwarder.open()
        origin.close()
        origin.close()
    }

    /**
     * The contender harness's own failure path: a child that never finishes must produce a
     * BOUNDED diagnostic failure — output drained, child destroyed and reaped — never an
     * unbounded blocking read after a timed-out wait (the delivered helper read the child's
     * output BEFORE checking its wait result, hanging forever on a stalled child).
     */
    @Test
    fun `a stalled contender is destroyed and reaped with a bounded diagnostic`() {
        val source = Files.createTempFile("stalled-contender-", ".java")
        source.writeText(
            """
            import java.nio.file.Files;
            public class StalledContender {
                public static void main(String[] args) throws Exception {
                    System.out.println("PID=" + ProcessHandle.current().pid());
                    System.out.flush();
                    Thread.sleep(600_000); // far past any bound the harness may use
                }
            }
            """.trimIndent(),
        )
        val started = System.currentTimeMillis()
        // The harness runs on its own thread so a DELIVERED-SHAPE helper (which blocks on a
        // read of a still-running child) reds here instead of hanging the whole suite.
        val result = java.util.concurrent.atomic.AtomicReference<Result<Boolean>>()
        val harness =
            Thread({
                result.set(runCatching { contenderTakes(javaBinary(), source, 0, timeoutSeconds = 3) })
            }, "stalled-contender-harness").apply { isDaemon = true }
        harness.start()
        harness.join(30_000)
        check(!harness.isAlive) {
            "the contender harness blocked past its own 3 s timeout — a stalled child was " +
                "not bounded (a blocking read of a running child's output)"
        }
        val elapsedMs = System.currentTimeMillis() - started
        val failure = result.get()
        val message = failure?.exceptionOrNull()?.message ?: "no failure was produced"
        check(failure != null && failure.isFailure) { "a stalled contender did not fail the harness: $message" }
        check(elapsedMs < 30_000) { "the stalled contender took $elapsedMs ms to fail — not bounded" }
        check("did not finish" in message) { "the diagnostic does not name the stall: $message" }
        val pid = Regex("PID=(\\d+)").find(message)?.groupValues?.get(1)
            ?: error("the diagnostic did not drain the stalled child's output: $message")
        // The harness killed and reaped its child: no stalled JVM may survive the test.
        val deadline = System.currentTimeMillis() + 5_000
        while (ProcessHandle.of(pid.toLong()).isPresent) {
            check(System.currentTimeMillis() < deadline) {
                "the stalled contender survived the harness timeout (pid $pid still alive)"
            }
            Thread.sleep(50)
        }
        Files.deleteIfExists(source)
    }

    /** The negative that keeps the mechanism honest: the old shape leaks by construction. */
    @Test
    fun `the bare pre-pick shape does not hold the port - the race #334 closed`() {
        val port = ServerSocket(0).use { it.localPort } // released immediately
        secondBinderTakes(port) shouldBe true // any JVM on the box can take it before Tomcat binds
    }

    /** A loopback echo target that answers one 4-byte payload and then reads until the session ends. */
    private fun echoTargetUntilEof(): Pair<ServerSocket, CountDownLatch> {
        val target = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val ended = CountDownLatch(1)
        Thread({
            try {
                target.accept().use { app ->
                    val payload = app.getInputStream().readNBytes(4)
                    app.getOutputStream().write(payload)
                    app.getOutputStream().flush()
                    while (app.getInputStream().read() != -1) {
                        // drain until the peer goes away or the session is torn down
                    }
                }
            } catch (_: IOException) {
                // close()'s teardown surfaces here as a socket error; the session is over
            } finally {
                ended.countDown()
            }
        }, "echo-target").apply { isDaemon = true }.start()
        return target to ended
    }

    /** Every worker thread this forwarder instance started, by name. */
    private fun forwarderWorkerNames(originPort: Int): List<String> =
        Thread.getAllStackTraces().keys.filter { it.name.startsWith("stable-origin-$originPort-") }.map { it.name }

    /** The workers must all be gone within a bounded wait — never an unbounded sleep. */
    private fun awaitWorkersGone(originPort: Int) {
        val deadline = System.currentTimeMillis() + WORKERS_GONE_TIMEOUT_MS
        while (forwarderWorkerNames(originPort).isNotEmpty()) {
            check(System.currentTimeMillis() < deadline) {
                "forwarder workers survived past close(): ${forwarderWorkerNames(originPort)}"
            }
            Thread.sleep(50)
        }
    }

    /** Connects on a probe thread; returns ("refused"|"connected"|"error: …", the socket if any). */
    private fun connectOutcome(port: Int): Pair<String, Socket?> {
        var outcome = "error: the probe connect never finished"
        var socket: Socket? = null
        val done = CountDownLatch(1)
        Thread({
            val probe = Socket()
            try {
                probe.connect(InetSocketAddress(InetAddress.getByName("localhost"), port), 2_000)
                outcome = "connected"
                socket = probe
            } catch (_: ConnectException) {
                runCatching { probe.close() }
                outcome = "refused"
            } catch (e: IOException) {
                runCatching { probe.close() }
                outcome = "error: $e"
            } finally {
                done.countDown()
            }
        }, "connect-probe").apply { isDaemon = true }.start()
        check(done.await(10, TimeUnit.SECONDS)) { "the probe connect never finished for port $port" }
        return outcome to socket
    }

    private fun javaBinary(): String = ProcessHandle.current().info().command().orElse("java")

    /**
     * Spawns the contender JVM and reports whether it could bind the port. The wait is
     * bounded and CHECKED BEFORE ANY DECISION: on a timeout the child is destroyed, reaped
     * and its (concurrently drained) output goes into the diagnostic — a stalled child
     * fails bounded, never blocks the harness on a read of a process that is still running.
     * The drain runs on its own thread because destroying a process closes its output pipe
     * mid-read; a post-mortem read would throw instead of yield what arrived.
     */
    private fun contenderTakes(
        javaBin: String,
        source: Path,
        port: Int,
        timeoutSeconds: Long = 60,
    ): Boolean {
        val verdict = Files.createTempFile("port-contender-", ".txt")
        try {
            val process =
                ProcessBuilder(javaBin, source.toString(), port.toString(), verdict.toString())
                    .redirectErrorStream(true)
                    .start()
            val output = ByteArrayOutputStream()
            val drainer =
                Thread({
                    try {
                        process.inputStream.copyTo(output)
                    } catch (_: IOException) {
                        // the pipe is closed by destroyForcibly — what arrived is captured
                    }
                }, "contender-output-drain").apply { isDaemon = true }
            drainer.start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                val reaped = process.waitFor(10, TimeUnit.SECONDS)
                drainer.join(JOIN_DRAINED_MS)
                error(
                    "the contender process did not finish in $timeoutSeconds seconds " +
                        "(destroyed forcibly, reaped=$reaped): ${output.toStringTail()}",
                )
            }
            drainer.join(JOIN_DRAINED_MS) // the exited child's pipe drains to EOF, then ends
            // A missing or malformed verdict is a FAILURE, never a refusal — a witness that
            // could not run must not read as one that was refused.
            return when (val text = verdict.readText().trim()) {
                "took=true" -> true
                "took=false" -> false
                else -> error("contender produced no verdict (exit=$process.exitValue()): '$text' / ${output.toStringTail()}")
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

    private companion object {
        const val PEER_CLOSE_TIMEOUT_SECONDS = 5L
        const val WORKERS_GONE_TIMEOUT_MS = 5_000L
        const val JOIN_DRAINED_MS = 2_000L
    }

    /** The drained child output, for a diagnostic; the pipe may have been closed mid-drain. */
    private fun ByteArrayOutputStream.toStringTail(): String = toString(StandardCharsets.UTF_8).trim()
}
