package co.datapipelines.auth

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A minimal, in-process JWK Set endpoint (a Keycloak-shaped issuer's `/certs`, #686).
 * Like [OidcDiscoveryStub], the JDK's own `HttpServer` on an ephemeral loopback port and
 * no container — the full Keycloak Testcontainer is reserved for `OidcLoginIntegrationTest`.
 *
 * Only `GET <certsUri>` is served, with [jwks] as the JSON body and a per-request
 * [delayMillis] knob so a test can hold the answer past the configured read budget.
 * Every request is recorded; nothing here signs anything.
 */
class JwksStub : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool()
    private val recorded: MutableList<String> = CopyOnWriteArrayList()

    /** The issuer URI a test configures on its `ClientRegistration`; the certs URI hangs off it. */
    val issuer: String get() = "http://127.0.0.1:${server.address.port}"

    /** The `jwkSetUri` a test configures on its `ClientRegistration`. */
    val certsUri: String get() = "$issuer$CERTS_PATH"

    /** The JWK Set document served; tests point it at a per-run RSA key. */
    @Volatile var jwks: String = EMPTY_KEY_SET

    /** Per-response stall; a test sets it past the configured read timeout. */
    @Volatile var delayMillis: Long = 0

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            recorded += exchange.requestURI.path
            Thread.sleep(delayMillis)
            val body = jwks.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    /** Every request to this exact path, in arrival order. */
    fun requestsTo(path: String): List<String> = recorded.filter { it == path }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        const val CERTS_PATH = "/certs"

        /** A syntactically valid key set with no keys in it. */
        const val EMPTY_KEY_SET = """{"keys":[]}"""
    }
}
