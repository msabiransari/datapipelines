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
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
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
    fun `large declared upload observes early refusal instead of writing through the closed connection`() {
        declaredRefusal(LARGE_BODY)
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
                    "event=transport.declared declared=$size supplied=${body.readBytes} status=${response.statusCode} body=${response.asString()}",
                )
            } finally {
                wire.await()
                println(
                    "event=transport.writer declared=$size supplied=${body.readBytes} forwarded=${wire.forwarded.get()} response=${wire.response}",
                )
            }
            wire.declared shouldBe size.toLong()
            if (size == LARGE_BODY) body.readBytes shouldBeLessThan size
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

    /** The module exposes app alone at compile time; inspect the actual runtime connector without adding a dependency. */
    private fun swallowCap(): Int {
        val server = context.javaClass.getMethod("getWebServer").invoke(context)
        val tomcat = server.javaClass.getMethod("getTomcat").invoke(server)
        val connector = tomcat.javaClass.getMethod("getConnector").invoke(tomcat)
        val protocol = connector.javaClass.getMethod("getProtocolHandler").invoke(connector)
        return protocol.javaClass.getMethod("getMaxSwallowSize").invoke(protocol) as Int
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
            registry.add("spring.data.redis.host") { SharedE2e.redis.host }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
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
}

/** Streaming HTTP/1.1 without Expect receives the final response while Tomcat applies its bounded refused-body drain. */
internal object ScreenshotUploadTransport {
    private const val TIMEOUT_SECONDS = 10L
    private val client =
        HttpClient
            .newBuilder()
            .version(
                HttpClient.Version.HTTP_1_1,
            ).connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build()

    fun post(
        port: Int,
        path: String,
        body: HttpRequest.BodyPublisher,
        capability: String? = null,
    ): Response {
        val request =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
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
