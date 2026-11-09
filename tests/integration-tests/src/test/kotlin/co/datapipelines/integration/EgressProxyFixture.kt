package co.datapipelines.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * #862 — the SHIPPED egress proxy as the E2E suites run it: compose's `egress-proxy` image, and the Envoy config
 * RENDERED by `EgressProxyConfigMain` — the exact derivation the shipped `egress-config` renderer service runs — from
 * a suite's own store coordinates, with only the listener moved to a free loopback port (the container runs on the
 * host network, so it reaches recorders and stores in the test JVM).
 *
 * Reflection reaches the renderer because module-structure.md §5.11 allows integration-tests only `:modules:app`,
 * and app exposes nothing as `api`. Kotlin `object` members are INSTANCE methods: the receiver is the singleton.
 */
internal object EgressProxyFixture {
    private const val COMPOSE_PATH = "deploy/compose.yml"
    private const val TARGET = "co.datapipelines.web.config.EgressStoreTarget"
    private const val RENDERER = "co.datapipelines.web.config.EgressProxyConfigMain"
    private const val EXCHANGE_TIMEOUT_MILLIS = 10_000
    private val JSON = ObjectMapper()

    /** The proxy image compose ships (`services.egress-proxy.image`), read from the checked-in file. */
    fun shippedImage(): String {
        val parsed = Yaml().load<Map<String, Any?>>(composeFile().readText())
        val services = parsed["services"] as Map<*, *>
        return (services["egress-proxy"] as Map<*, *>)["image"] as String
    }

    /** `EgressStoreTarget.derive`'s answer for one store: what the proxy allowlists and what the engine is told. */
    class Derived(
        val domains: List<String>,
        val duckdbEndpoint: String,
        val target: Any,
    )

    fun derive(
        endpoint: String,
        region: String,
        bucket: String,
        pathStyle: Boolean,
    ): Derived {
        val targetClass = Class.forName(TARGET)
        val target =
            targetClass
                .getMethod("derive", String::class.java, String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                .invoke(targetClass.getDeclaredField("INSTANCE").get(null), endpoint, region, bucket, pathStyle)
        val type = Class.forName("$TARGET\$Target")
        @Suppress("UNCHECKED_CAST")
        return Derived(
            domains = type.getMethod("getDomains").invoke(target) as List<String>,
            duckdbEndpoint = type.getMethod("getDuckdbEndpoint").invoke(target) as String,
            target = target,
        )
    }

    /** `EgressProxyConfigMain.render(maxAge, target)` — the shipped renderer's output for [derived]. */
    fun rendered(
        derived: Derived,
        maxAgeSeconds: Long,
    ): String {
        val renderer = Class.forName(RENDERER)
        return renderer
            .getMethod("render", Long::class.javaPrimitiveType, Class.forName("$TARGET\$Target"))
            .invoke(renderer.getDeclaredField("INSTANCE").get(null), maxAgeSeconds, derived.target) as String
    }

    /**
     * [config] (the renderer's JSON) with its ONE listener moved to 127.0.0.1:[port] — parsed and re-serialized, so
     * nothing but the listener address can differ from what the renderer wrote. More or fewer listeners is refused.
     */
    fun relistened(
        config: String,
        port: Int,
    ): String {
        val tree = JSON.readTree(config) as ObjectNode
        val listeners = tree.at("/static_resources/listeners") as ArrayNode
        check(listeners.size() == 1) { "the rendered config carries ${listeners.size()} listeners, not exactly one" }
        val socket = listeners[0].at("/address/socket_address") as ObjectNode
        socket.put("address", "127.0.0.1")
        socket.put("port_value", port)
        return JSON.writeValueAsString(tree)
    }

    /** The shipped image running [config]; [extraHosts] name → IPv4 are the container's `/etc/hosts` lines. */
    fun start(
        config: String,
        extraHosts: Map<String, String> = emptyMap(),
    ): GenericContainer<*> =
        GenericContainer(DockerImageName.parse(shippedImage()))
            .withNetworkMode("host")
            .apply { extraHosts.forEach { (name, ip) -> withExtraHost(name, ip) } }
            .withCopyToContainer(Transferable.of(config), "/etc/envoy/envoy.yaml")
            .waitingFor(Wait.forLogMessage(".*all clusters initialized.*", 1).withStartupTimeout(Duration.ofSeconds(60)))
            .also { it.start() }

    /**
     * One raw request spoken to the proxy on loopback [port]. A non-CONNECT request carries `Connection: close`
     * and is read to the stream's end; a CONNECT answer is read to the end of its head (a tunnel stays open).
     * A read that times out returns what arrived, tagged `<timeout>`.
     */
    fun exchange(
        port: Int,
        request: String,
        afterHead: ((Socket) -> Unit)? = null,
    ): String =
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = EXCHANGE_TIMEOUT_MILLIS
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val headOnly = request.startsWith("CONNECT ")
            val received = answer(socket.getInputStream(), headOnly)
            if (headOnly && afterHead != null && received.startsWith("HTTP/1.1 200")) afterHead(socket)
            received
        }

    /** The answer on [input]: to the stream's end, or to the end of the head when [headOnly]; `<timeout>` tagged. */
    private fun answer(
        input: InputStream,
        headOnly: Boolean,
    ): String {
        val received = StringBuilder()
        try {
            var b = input.read()
            while (b != -1) {
                received.append(b.toChar())
                b = if (headOnly && received.endsWith("\r\n\r\n")) -1 else input.read()
            }
        } catch (_: SocketTimeoutException) {
            received.append("<timeout>")
        }
        return received.toString()
    }

    fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    private fun composeFile(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, COMPOSE_PATH)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$COMPOSE_PATH not found walking up from ${File("").absolutePath}")
    }
}

/**
 * A loopback HTTP/1.1 endpoint that records every TCP connection it ACCEPTS and every request head on it (keep-alive
 * honoured, so a pooling proxy's reused connection is still read request by request). [respond] null = a forbidden
 * destination: it still answers `200 forbidden-reached`, so a leak is visible on the client side too — but the
 * evidence is [connections], the attempt as the destination saw it.
 */
internal class RecordingServer(
    bind: InetAddress,
    port: Int,
    val label: String,
    private val respond: ((Request) -> ByteArray)? = null,
) : AutoCloseable {
    data class Request(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
    ) {
        val host: String? get() = headers["host"]
    }

    private val server = ServerSocket(port, BACKLOG, bind)
    private val open = Collections.synchronizedList(mutableListOf<Socket>())
    val connections = AtomicInteger()
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
    val port: Int get() = server.localPort

    init {
        Thread({ acceptLoop() }, "egress-recorder-$label").apply { isDaemon = true }.start()
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return
            connections.incrementAndGet()
            open += socket
            Thread({ serve(socket) }, "egress-recorder-$label-conn").apply { isDaemon = true }.start()
        }
    }

    private fun serve(socket: Socket) {
        runCatching {
            socket.soTimeout = IDLE_MILLIS
            val input = socket.getInputStream().buffered()
            val output = socket.getOutputStream()
            var head = readHead(input)
            while (head != null) {
                val request = parse(head)
                requests += request
                request.headers["content-length"]?.toLongOrNull()?.let { input.skipNBytes(it) }
                output.write(respond?.invoke(request) ?: FORBIDDEN_ANSWER)
                output.flush()
                head = if (request.headers["connection"].equals("close", ignoreCase = true)) null else readHead(input)
            }
        }
        runCatching { socket.close() }
    }

    private fun parse(head: String): Request {
        val lines = head.split("\r\n").filter { it.isNotEmpty() }
        val parts = lines.first().split(" ")
        val headers = lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        return Request(parts[0], parts.getOrElse(1) { "" }, headers)
    }

    private fun readHead(input: InputStream): String? {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b == -1) return null
            head.append(b.toChar())
        }
        return head.toString()
    }

    override fun close() {
        server.close()
        open.forEach { runCatching { it.close() } }
    }

    companion object {
        private const val BACKLOG = 50
        private const val IDLE_MILLIS = 30_000
        private val FORBIDDEN_ANSWER =
            "HTTP/1.1 200 OK\r\nContent-Length: 17\r\nConnection: close\r\n\r\nforbidden-reached".toByteArray(Charsets.ISO_8859_1)

        /** A port free on EVERY one of [addresses] (127.0.0.0/8 is all loopback on Linux), retried a bounded time. */
        fun portFreeOn(vararg addresses: InetAddress): Int {
            repeat(PORT_ATTEMPTS) {
                val candidate = EgressProxyFixture.freePort()
                val free =
                    addresses.all { address ->
                        runCatching { ServerSocket(candidate, 1, address).close() }.isSuccess
                    }
                if (free) return candidate
            }
            error("no port free on all of ${addresses.toList()} in $PORT_ATTEMPTS attempts")
        }

        private const val PORT_ATTEMPTS = 20
    }
}
