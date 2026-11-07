package co.datapipelines.web.config

import co.datapipelines.lake.LakeBootCheck
import co.datapipelines.lake.LakeProperties
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Collections

/**
 * #845 (D4) — the egress proxy boot check against a scripted stand-in proxy on loopback: the stand-in records each
 * request's head and answers what the case scripts, so every refusal path and the green path are driven without a
 * container. The real proxy (Envoy) and a real store are `ManagedLakeEgressProxyE2eTest`'s.
 */
class ManagedLakeEgressCheckTest {
    private val proxies = mutableListOf<ScriptedProxy>()

    @AfterEach
    fun closeProxies() = proxies.forEach(ScriptedProxy::close)

    @Test
    fun `no proxy configured - nothing is contacted and the boot continues`() {
        ManagedLakeEgressCheck.verify(lake(egressProxy = ""))
        ManagedLakeEgressCheck.verify(lake(egressProxy = "   "))
    }

    @Test
    fun `a proxy that declares the configured max-age and reaches the store passes - both requests as specified`() {
        val proxy = scripted(declaration = ok("max-age=960"), store = storeAnswer(403))

        ManagedLakeEgressCheck.verify(lake(egressProxy = proxy.address, maxAge = 960))

        proxy.requestLines shouldContainExactly
            listOf("GET /dp-egress HTTP/1.1", "HEAD http://store.test:9000/dp-lake HTTP/1.1")
        withClue("the declaration request carries the selecting header") {
            proxy.heads.first().lowercase() shouldContain "x-dp-egress-check: 1"
        }
    }

    @Test
    fun `a proxy that does not answer at all is refused as unreachable`() {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

        refusalOf(lake(egressProxy = "127.0.0.1:$closedPort")) shouldContain "is unreachable"
    }

    @Test
    fun `a proxy declaring another max-age is refused naming both values`() {
        val proxy = scripted(declaration = ok("max-age=20"), store = storeAnswer(403))

        val message = refusalOf(lake(egressProxy = proxy.address, maxAge = 960))

        message shouldContain "'max-age=20'"
        message shouldContain "'max-age=960'"
        withClue("the store is not probed once the cap is wrong") { proxy.requestLines.size shouldBe 1 }
    }

    @Test
    fun `a forward proxy without the declaration route is refused`() {
        val proxy = scripted(declaration = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n", store = storeAnswer(403))

        refusalOf(lake(egressProxy = proxy.address)) shouldContain "answered 404"
    }

    @Test
    fun `a proxy that cannot reach the store is refused - its own gateway failure carries no store request id`() {
        val proxy = scripted(declaration = ok("max-age=960"), store = "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n")

        refusalOf(lake(egressProxy = proxy.address)) shouldContain "cannot reach the store http://store.test:9000/dp-lake"
    }

    @Test
    fun `a store's own 503 still proves the path - it carries the store request id`() {
        val proxy = scripted(declaration = ok("max-age=960"), store = storeAnswer(503))

        ManagedLakeEgressCheck.verify(lake(egressProxy = proxy.address))
    }

    @Test
    fun `a proxy that accepts and never answers is refused within the response bound`() {
        val proxy = scripted(declaration = null, store = null)
        val started = System.nanoTime()

        refusalOf(lake(egressProxy = proxy.address)) shouldContain "no answer within 5 s"

        (System.nanoTime() - started) / 1_000_000L shouldBeLessThan (ManagedLakeEgressCheck.RESPONSE_TIMEOUT_SECONDS + 2) * 1_000L
    }

    @Test
    fun `a malformed proxy value is refused before any request`() {
        refusalOf(lake(egressProxy = "http://egress-proxy:3128")) shouldContain "is not host:port"
    }

    @Test
    fun `the store is addressed the way DuckDB addresses it`() {
        fun uri(
            endpoint: String,
            pathStyle: Boolean,
        ) = ManagedLakeEgressCheck.storeProbeUri(LakeProperties.Storage(bucket = "dp-lake", endpoint = endpoint, pathStyle = pathStyle))

        uri("http://minio:9000", pathStyle = true) shouldBe URI("http://minio:9000/dp-lake")
        uri("https://minio.example:9000/", pathStyle = true) shouldBe URI("https://minio.example:9000/dp-lake")
        // Scheme-less = plain http, the adapter's secret reading (USE_SSL false).
        uri("minio:9000", pathStyle = true) shouldBe URI("http://minio:9000/dp-lake")
        // Blank = AWS over https; virtual-host style puts the bucket in the host.
        uri("", pathStyle = false) shouldBe URI("https://dp-lake.s3.amazonaws.com/")
        uri("", pathStyle = true) shouldBe URI("https://s3.amazonaws.com/dp-lake")
    }

    @Test
    fun `the well-formedness check is the adapter's grammar`() {
        ManagedLakeEgressCheck.isWellFormed("egress-proxy:3128") shouldBe true
        ManagedLakeEgressCheck.isWellFormed(" egress-proxy:3128 ") shouldBe true
        ManagedLakeEgressCheck.isWellFormed("egress-proxy") shouldBe false
        ManagedLakeEgressCheck.isWellFormed("egress-proxy:70000") shouldBe false
    }

    // ------------------ fixtures

    private fun refusalOf(properties: LakeProperties): String {
        val message = shouldThrow<IllegalStateException> { ManagedLakeEgressCheck.verify(properties) }.message.orEmpty()
        message shouldStartWith "${LakeBootCheck.Refusal.EGRESS_PROXY.id}: "
        return message
    }

    private fun lake(
        egressProxy: String,
        maxAge: Long = LakeProperties.DEFAULT_EGRESS_MAX_AGE_SECONDS,
    ) = LakeProperties(
        enabled = true,
        storage = LakeProperties.Storage(bucket = "dp-lake", endpoint = "http://store.test:9000", pathStyle = true),
        reads = LakeProperties.Reads(egressProxy = egressProxy, egressMaxAgeSeconds = maxAge),
    )

    private fun scripted(
        declaration: String?,
        store: String?,
    ) = ScriptedProxy(declaration, store).also(proxies::add)

    private fun ok(body: String) = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\n\r\n$body"

    private fun storeAnswer(status: Int) =
        "HTTP/1.1 $status Store\r\nx-amz-request-id: 1863E0D4C2A5\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

    /**
     * A loopback stand-in for the proxy: each connection's first request head is recorded; a request for
     * `/dp-egress` gets [declaration], any other gets [store]; a null answer holds the connection silent until close.
     */
    private class ScriptedProxy(
        private val declaration: String?,
        private val store: String?,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
        val heads: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val requestLines: List<String> get() = heads.map { it.substringBefore("\r\n") }
        val address = "127.0.0.1:${server.localPort}"

        init {
            Thread({ acceptLoop() }, "scripted-egress-proxy").apply { isDaemon = true }.start()
        }

        private fun acceptLoop() {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: return
                sockets += socket
                runCatching {
                    val head = readHead(socket)
                    heads += head
                    val answer = if (head.substringBefore("\r\n").contains(" /dp-egress ")) declaration else store
                    if (answer != null) {
                        socket.getOutputStream().write(answer.toByteArray(StandardCharsets.ISO_8859_1))
                        socket.getOutputStream().flush()
                    }
                }
            }
        }

        private fun readHead(socket: Socket): String {
            val input = socket.getInputStream()
            val head = ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b == -1) break
                head.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == CRLF_CRLF) break
            }
            return head.toString(StandardCharsets.ISO_8859_1)
        }

        override fun close() {
            server.close()
            sockets.forEach { runCatching { it.close() } }
        }

        private companion object {
            const val CRLF_CRLF = 0x0d0a0d0a
        }
    }
}
