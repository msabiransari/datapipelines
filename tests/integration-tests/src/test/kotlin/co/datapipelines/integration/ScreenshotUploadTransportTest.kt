package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.restassured.builder.ResponseBuilder
import io.restassured.http.Header
import io.restassured.http.Headers
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The real application's embedded Tomcat, default drain bound and production filter, with synthetic uploads. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = [DatapipelinesApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScreenshotUploadTransportTest {
    @LocalServerPort
    private var port = 0

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `declared cap plus one receives the exact refusal with the default bounded drain`() {
        declaredRefusal(CAP + 1)
    }

    @Test
    fun `abandoned gigabyte declaration closes after bounded swallow`() {
        swallowCap() shouldBe SWALLOW_CAP
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT
            val headers = "POST $PATH HTTP/1.1\r\nHost: localhost\r\nContent-Type: image/png\r\nContent-Length: $ABANDONED_BODY\r\n\r\n"
            val output = socket.getOutputStream()
            output.write(headers.toByteArray(Charsets.US_ASCII))
            // Supply just beyond the default drain bound, abandoning the rest of the declaration.
            output.write(ByteArray(SWALLOW_CAP + BUFFER_SIZE))
            output.flush()
            val response = socket.getInputStream().readAllBytes().toString(Charsets.UTF_8)
            response.substringBefore("\r\n").split(' ')[1] shouldBe "413"
            println(
                "event=transport.abandoned declared=$ABANDONED_BODY supplied=${SWALLOW_CAP + BUFFER_SIZE} closed=true response=$response",
            )
        }
    }

    @Test
    fun `467 cap plus one reaches a client whose writer meets the close before its reader takes the response`() {
        forcedLosingSide(CAP + 1)
    }

    @Test
    fun `467 a declaration at the drain bound closes cleanly after the whole body`() {
        forcedLosingSide(DRAIN_BOUND)
    }

    @Test
    fun `560 a declaration above the drain bound closes after a bounded read - the 413 on the wire, the writer meets the reset`() {
        forcedClose(LARGE_BODY)
    }

    /**
     * #467: the losing interleaving, forced. [HeldResponseRelay] withholds the server's response from the client until
     * the server has closed; if that close came before the whole declared body was forwarded, or was a reset, the relay
     * resets the client with the response still withheld — the client whose writer met the reset before its reader
     * took the 413. The server's small receive buffer bounds what the kernels can hold unread, so the relay's count
     * decides "before the whole body" without a clock.
     */
    private fun forcedLosingSide(size: Int) {
        val body = SizedBody(size)
        withServerReceiveBuffer(FORCED_RECEIVE_BUFFER) {
            HeldResponseRelay(port).use { relay ->
                try {
                    val response =
                        ScreenshotUploadTransport.post(
                            relay.port,
                            PATH,
                            HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream { body }, size.toLong()),
                        )
                    response.statusCode shouldBe 413
                    response.jsonPath().getString("error.code") shouldBe "visualization.test.screenshot_too_large"
                    response.jsonPath().getInt("error.details.cap_bytes") shouldBe CAP
                } finally {
                    relay.await()
                    println("event=transport.forced declared=$size supplied=${body.readBytes} ${relay.events()}")
                }
                relay.declared shouldBe size.toLong()
                relay.early shouldBe false
                // The refusal leaves before the drain: the 413 reached the relay while the body was still being forwarded.
                relay.forwardedAtResponse shouldBeLessThan size.toLong()
            }
        }
    }

    /**
     * #560: above the drain bound the LARGE upload case no longer samples the natural race — it forces the outcome the
     * record documents (rest-api §22): the server's 413 is on the wire, the server closes after Tomcat's bounded
     * swallow, and the client still writing meets the reset. Deterministic by count, not by clock: above the drain
     * bound the route reads nothing and Tomcat swallows at most 2 MiB, so of a 64 MiB declaration the server reads
     * only the swallow plus what the kernels buffer (the forced receive buffer, the relay's send buffer); the relay
     * cannot forward what the server did not read, so at the close its forwarded count is a few MiB against the 64 MiB
     * declaration and `early` is true by the count alone — the relay resets the client with the response still
     * withheld, and the JDK client's send throws. The old case asserted the winning side (the 413 read before the
     * writer met the reset), a race the runner could lose (CI 39053309921); the losing side is the documented one, so
     * it is the asserted one.
     */
    private fun forcedClose(size: Int) {
        swallowCap() shouldBe SWALLOW_CAP
        val body = SizedBody(size)
        withServerReceiveBuffer(FORCED_RECEIVE_BUFFER) {
            HeldResponseRelay(port).use { relay ->
                val thrown: IOException?
                try {
                    thrown =
                        try {
                            val response =
                                ScreenshotUploadTransport.post(
                                    relay.port,
                                    PATH,
                                    HttpRequest.BodyPublishers.fromPublisher(
                                        HttpRequest.BodyPublishers.ofInputStream { body },
                                        size.toLong(),
                                    ),
                                )
                            // A response is not this case's side; the relay's counts below name what moved.
                            println("event=transport.forced-close.client unexpected status=${response.statusCode}")
                            null
                        } catch (error: IOException) {
                            // "header parser received no bytes" and "Connection reset" are the same documented outcome;
                            // the message text is not pinned.
                            println("event=transport.forced-close.client type=${error.javaClass.name} message=${error.message}")
                            error
                        }
                } finally {
                    relay.await()
                    println("event=transport.forced-close declared=$size supplied=${body.readBytes} ${relay.events()}")
                }
                relay.declared shouldBe size.toLong()
                // The losing side, decided by the relay's count without a clock: the server closed before the whole
                // declared body was forwarded, or on a reset — either way the client was reset, response withheld.
                relay.early shouldBe true
                // The server DID refuse before it closed: the withheld response is the route's 413, never delivered.
                relay.heldStatus.startsWith("HTTP/1.1 413") shouldBe true
                // The code is judged only when the whole envelope survived the close; a truncated one is logged above.
                if (relay.closeKind == "FIN") {
                    Regex("\"code\"\\s*:\\s*\"visualization.test.screenshot_too_large\"").containsMatchIn(relay.heldBody) shouldBe true
                }
                println("event=transport.forced-close.held close_kind=${relay.closeKind} body=${relay.heldBody.take(400)}")
                // The read was bounded: the route did NOT drain to the bound — red if DRAIN_BYTES ever covers this size.
                relay.forwardedAtClose shouldBeLessThan DRAIN_BOUND.toLong()
                relay.forwardedAtClose shouldBeLessThan size.toLong()
                println(
                    "event=transport.forced-close.bound forwarded_at_close=${relay.forwardedAtClose} " +
                        "drain_bound=$DRAIN_BOUND swallow=$SWALLOW_CAP " +
                        "receive_buffer=$FORCED_RECEIVE_BUFFER relay_buffer=$BUFFER_SIZE",
                )
                // The client's side of the documented outcome: its writer met the reset mid-body, never a response.
                check(thrown != null) { "the client received a response instead of meeting the reset" }
                body.readBytes shouldBeLessThan size
            }
        }
    }

    private fun declaredRefusal(size: Int) {
        swallowCap() shouldBe SWALLOW_CAP
        val body = SizedBody(size)
        WireCapture(port).use { wire ->
            try {
                val response =
                    ScreenshotUploadTransport.post(
                        wire.port,
                        PATH,
                        HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream { body }, size.toLong()),
                    )
                response.statusCode shouldBe 413
                response.jsonPath().getString("error.code") shouldBe "visualization.test.screenshot_too_large"
                response.jsonPath().getInt("error.details.cap_bytes") shouldBe CAP
                println(
                    "event=transport.declared declared=$size supplied=${body.readBytes} " +
                        "status=${response.statusCode} body=${response.asString()}",
                )
            } finally {
                wire.await()
                println(
                    "event=transport.writer declared=$size supplied=${body.readBytes} " +
                        "forwarded=${wire.forwarded.get()} response=${wire.response}",
                )
            }
            wire.declared shouldBe size.toLong()
            wire.response
                .lineSequence()
                .last { it.startsWith("HTTP/1.1 ") }
                .split(' ')[1] shouldBe "413"
        }
    }

    /** Loopback relay records the real server response even when the synchronous upload client loses it. */
    internal class WireCapture(
        serverPort: Int,
    ) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val workers = Executors.newFixedThreadPool(2)
        val port: Int = listener.localPort
        val forwarded = AtomicLong()
        var declared = -1L
            private set
        var chunked = false
            private set
        var response = ""
            private set
        private val relay =
            workers.submit {
                listener.accept().use { client ->
                    Socket("127.0.0.1", serverPort).use { server ->
                        client.soTimeout = SOCKET_TIMEOUT
                        server.soTimeout = SOCKET_TIMEOUT
                        val request = workers.submit { forwardRequest(client, server) }
                        val bytes = server.getInputStream().readNBytes(BUFFER_SIZE)
                        response = bytes.toString(Charsets.UTF_8)
                        client.getOutputStream().write(bytes)
                        client.getOutputStream().flush()
                        client.close()
                        request.get(SOCKET_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
                    }
                }
            }

        private fun forwardRequest(
            client: Socket,
            server: Socket,
        ) {
            val input = client.getInputStream()
            val output = server.getOutputStream()
            val headers = ByteArrayOutputStream()
            while (headers.size() < BUFFER_SIZE) {
                val byte = input.read()
                check(byte >= 0) { "Client closed before request headers" }
                headers.write(byte)
                if (headers.toString(Charsets.US_ASCII).endsWith("\r\n\r\n")) break
            }
            val headerText = headers.toString(Charsets.US_ASCII)
            chunked = Regex("(?im)^Transfer-Encoding: chunked").containsMatchIn(headerText)
            check(headerText.endsWith("\r\n\r\n")) { "Request headers exceed capture bound" }
            declared = Regex("(?im)^Content-Length: ([0-9]+)")
                .find(headerText)
                ?.groupValues
                ?.get(1)
                ?.toLong() ?: -1L
            output.write(headers.toByteArray())
            output.flush()
            val buffer = ByteArray(BUFFER_SIZE)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    forwarded.addAndGet(count.toLong())
                }
            } catch (error: IOException) {
                // Backend EOF closes the client while its old writer may still be sending. The wire assertion stands.
                println("event=transport.relay forwarded=${forwarded.get()} failure=${error.javaClass.simpleName}")
            }
        }

        fun await() {
            relay.get(SOCKET_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
        }

        override fun close() {
            listener.close()
            workers.shutdownNow()
        }
    }

    /**
     * The #467 relay: the request is forwarded through a small send buffer, each chunk counted BEFORE it is written, and
     * the server's response is held until the server closes. A close after the whole declared body and on a FIN
     * delivers the response and half-closes; any other close resets the client without it. Every step is logged with
     * its offset from the accept, the exchange's capture. #560's case additionally reads the withheld response's
     * status line ([heldStatus]), the close kind ([closeKind]) and the forwarded count at the close ([forwardedAtClose]).
     */
    internal class HeldResponseRelay(
        serverPort: Int,
    ) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val workers = Executors.newFixedThreadPool(2)
        private val log = ConcurrentLinkedQueue<String>()
        private val started = System.nanoTime()
        private val forwarded = AtomicLong()
        val port: Int = listener.localPort

        @Volatile
        var declared = -1L
            private set

        @Volatile
        var early = true
            private set

        @Volatile
        var forwardedAtResponse = -1L
            private set

        /** The forwarded count when the server closed — what the relay could not make the server read. */
        @Volatile
        var forwardedAtClose = -1L
            private set

        /** The close as the relay saw it: `FIN` or `RST(<message>)`. */
        @Volatile
        var closeKind = ""
            private set

        /** The withheld response's status line — proof the server refused before it closed (#560). */
        @Volatile
        var heldStatus = ""
            private set

        @Volatile
        var heldBody = ""
            private set

        private val relay =
            workers.submit {
                listener.accept().use { client ->
                    Socket().use { server ->
                        client.soTimeout = SOCKET_TIMEOUT
                        server.soTimeout = SOCKET_TIMEOUT
                        server.sendBufferSize = BUFFER_SIZE
                        server.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), serverPort))
                        val request = workers.submit { forwardRequest(client, server) }
                        val held = ByteArrayOutputStream()
                        val close = readUntilClose(server, held)
                        closeKind = close
                        forwardedAtClose = forwarded.get()
                        early = forwarded.get() < declared || close != "FIN"
                        heldBody = held.toString(Charsets.UTF_8)
                        event("server.close kind=$close held=${held.size()} forwarded=${forwarded.get()} early=$early")
                        if (early) {
                            client.setSoLinger(true, 0)
                            event("client.reset response_withheld=true")
                        } else {
                            client.getOutputStream().write(held.toByteArray())
                            client.shutdownOutput()
                            event("client.response status=${held.toString(Charsets.UTF_8).substringBefore("\r\n")}")
                            request.get(SOCKET_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
                        }
                    }
                }
            }

        private fun readUntilClose(
            server: Socket,
            held: ByteArrayOutputStream,
        ): String {
            val buffer = ByteArray(BUFFER_SIZE)
            val input = server.getInputStream()
            return try {
                var count = input.read(buffer)
                while (count >= 0) {
                    if (held.size() == 0) {
                        forwardedAtResponse = forwarded.get()
                        heldStatus = String(buffer, 0, count, Charsets.UTF_8).substringBefore("\r\n")
                        event("server.response first_bytes=$count forwarded=$forwardedAtResponse status=$heldStatus")
                    }
                    held.write(buffer, 0, count)
                    count = input.read(buffer)
                }
                "FIN"
            } catch (error: SocketException) {
                "RST(${error.message})"
            }
        }

        private fun forwardRequest(
            client: Socket,
            server: Socket,
        ) {
            val input = client.getInputStream()
            val output = server.getOutputStream()
            val headers = ByteArrayOutputStream()
            while (!headers.toString(Charsets.US_ASCII).endsWith("\r\n\r\n")) {
                val byte = input.read()
                check(byte >= 0 && headers.size() < BUFFER_SIZE) { "Request headers incomplete or over the capture bound" }
                headers.write(byte)
            }
            declared = Regex("(?im)^Content-Length: ([0-9]+)")
                .find(headers.toString(Charsets.US_ASCII))
                ?.groupValues
                ?.get(1)
                ?.toLong() ?: -1L
            output.write(headers.toByteArray())
            val buffer = ByteArray(BUFFER_SIZE)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    // Counted before the write: a chunk still blocked in it is not yet the server's.
                    forwarded.addAndGet(count.toLong())
                    output.write(buffer, 0, count)
                }
                event("client.eof forwarded=${forwarded.get()}")
            } catch (error: IOException) {
                event("request.stop forwarded=${forwarded.get()} failure=${error.javaClass.simpleName}: ${error.message}")
            }
        }

        private fun event(text: String) {
            log += "+%.3fms %s".format((System.nanoTime() - started) / NANOS_PER_MILLI, text)
        }

        fun events(): String = log.joinToString(" | ")

        fun await() {
            relay.get(SOCKET_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
        }

        override fun close() {
            listener.close()
            workers.shutdownNow()
        }

        private companion object {
            const val NANOS_PER_MILLI = 1_000_000.0
        }
    }

    /**
     * Sets the connector's per-socket receive buffer (`socket.rxBufSize`, applied at each accept) for [block] and puts
     * the previous value back, so the class's other cases keep the kernel's autotuned buffer.
     */
    private fun withServerReceiveBuffer(
        bytes: Int,
        block: () -> Unit,
    ) {
        val protocol = protocolHandler()
        val getEndpoint =
            generateSequence<Class<*>>(protocol.javaClass) { it.superclass }
                .firstNotNullOf { type -> type.declaredMethods.firstOrNull { it.name == "getEndpoint" && it.parameterCount == 0 } }
                .apply { isAccessible = true }
        val socketProperties = getEndpoint.invoke(protocol).let { it.javaClass.getMethod("getSocketProperties").invoke(it) }
        val field = socketProperties.javaClass.getDeclaredField("rxBufSize").apply { isAccessible = true }
        val previous = field.get(socketProperties)
        field.set(socketProperties, bytes)
        try {
            block()
        } finally {
            field.set(socketProperties, previous)
        }
    }

    /** The module exposes app alone at compile time; inspect the actual runtime connector without adding a dependency. */
    private fun swallowCap(): Int {
        val protocol = protocolHandler()
        return protocol.javaClass.getMethod("getMaxSwallowSize").invoke(protocol) as Int
    }

    private fun protocolHandler(): Any {
        val server = context.javaClass.getMethod("getWebServer").invoke(context)
        val tomcat = server.javaClass.getMethod("getTomcat").invoke(server)
        val connector = tomcat.javaClass.getMethod("getConnector").invoke(tomcat)
        return connector.javaClass.getMethod("getProtocolHandler").invoke(connector)
    }

    /** Generated body with fixed memory and a count of bytes pulled by the HTTP client's writer. */
    private class SizedBody(
        val size: Int,
    ) : ByteArrayInputStream(ByteArray(0)) {
        var readBytes = 0
            private set

        override fun read(): Int {
            if (readBytes == size) return -1
            readBytes++
            return 0
        }

        override fun available(): Int = size - readBytes

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (length == 0) return 0
            if (readBytes == size) return -1
            val count = minOf(length, size - readBytes)
            buffer.fill(0, offset, offset + count)
            readBytes += count
            return count
        }
    }

    private companion object {
        const val CAP = 4 * 1024 * 1024
        const val SWALLOW_CAP = 2 * 1024 * 1024

        /** The route's refused-body drain (#467): the cap plus Tomcat's default swallow, `ScreenshotUploadRoute.DRAIN_BYTES`. */
        const val DRAIN_BOUND = CAP + SWALLOW_CAP

        /** Small enough that a server closing mid-body cannot hide the unread tail in its kernel buffer (#467). */
        const val FORCED_RECEIVE_BUFFER = 64 * 1024
        const val LARGE_BODY = 64 * 1024 * 1024
        const val ABANDONED_BODY = 1024 * 1024 * 1024
        const val BUFFER_SIZE = 8192
        const val SOCKET_TIMEOUT = 10000
        const val TARGET_ID = "00000000-0000-0000-0000-000000000451"
        const val PATH = "/api/v1/visualizations/$TARGET_ID/tests/sessions/$TARGET_ID/screenshot"
        val JWT_SECRET = E2eSession.newSecret()
        val ENCRYPTION_KEY = E2eSession.newSecret()
        val oidc = OidcDiscoveryStub()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { SharedE2e.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { SharedE2e.postgres.username }
            registry.add("spring.datasource.password") { SharedE2e.postgres.password }
            registry.add("datapipelines.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @AfterAll
        fun closeOidc() {
            oidc.close()
        }
    }

    /** Streaming HTTP/1.1 without Expect receives the final response while Tomcat applies its bounded refused-body drain. */
    internal object ScreenshotUploadTransport {
        private val client =
            HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(SOCKET_TIMEOUT.toLong()))
                .build()

        fun post(
            port: Int,
            path: String,
            body: HttpRequest.BodyPublisher,
            capability: String? = null,
            requestTimeout: Duration = Duration.ofMillis(SOCKET_TIMEOUT.toLong()),
        ): Response {
            val request =
                HttpRequest
                    .newBuilder(URI("http://127.0.0.1:$port$path"))
                    .timeout(requestTimeout)
                    .expectContinue(false)
                    .header("Content-Type", "image/png")
                    .apply { capability?.let { header("DP-Upload-Token", it) } }
                    .POST(body)
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            return ResponseBuilder()
                .setStatusCode(response.statusCode())
                .setBody(response.body())
                .setHeaders(Headers(response.headers().map().flatMap { (name, values) -> values.map { Header(name, it) } }))
                .setContentType(response.headers().firstValue("Content-Type").orElse("application/json"))
                .build()
        }
    }
}
