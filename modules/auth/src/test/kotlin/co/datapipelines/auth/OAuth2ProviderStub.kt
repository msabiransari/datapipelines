package co.datapipelines.auth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * An in-process, GitHub-SHAPED OAuth 2.0 provider (#655) — the oracle for the `protocol: oauth2`
 * login, since no live GitHub OAuth app exists for the test suite. The JDK's own [HttpServer] on
 * an ephemeral loopback port, like [OidcDiscoveryStub]:
 *
 * - `GET /login/oauth/authorize` → 302 back to the request's `redirect_uri` with a `code` and the
 *   request's own `state` (no login page: the stub "user" has already consented);
 * - `POST /login/oauth/access_token` → a JSON token response (`token_type: bearer`, no ID token,
 *   no refresh token — what GitHub returns for an OAuth app);
 * - `GET /user` → [user], the `/user` document;
 * - `GET /user/emails` → [emailsStatus] with [emails], the `/user/emails` rows;
 * - every `/.well-known` path → 404 — GitHub is not discoverable, and [wellKnownHits] counts the
 *   attempts, so a test can prove a registration was built without a single discovery fetch.
 *
 * Every request is [recorded] with its method, path, query, the headers a test asserts on and the
 * form body, so the evidence can show what Spring actually sent (the token call's `Accept`, the
 * bearer on the user reads, the authorize request's `state` and `code_challenge`).
 */
class OAuth2ProviderStub : AutoCloseable {
    /** One request as the stub saw it. */
    data class Recorded(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, String>,
        val body: String,
    )

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool()

    val recorded: MutableList<Recorded> = CopyOnWriteArrayList()

    val base: String get() = "http://127.0.0.1:${server.address.port}"

    /** The `/user` JSON; tests replace it to plant a null `name` or another account. */
    @Volatile var user: String = GITHUB_USER

    /** The `/user/emails` JSON and status; tests plant an unverified primary, no primary, or a 500. */
    @Volatile var emails: String = VERIFIED_PRIMARY

    @Volatile var emailsStatus: Int = OK

    @Volatile var tokenDelayMillis: Long = 0

    @Volatile var userDelayMillis: Long = 0

    @Volatile var emailsDelayMillis: Long = 0

    @Volatile var tokenStatus: Int = OK

    @Volatile var tokenBody: String = TOKEN_RESPONSE

    val wellKnownHits: Int get() = recorded.count { it.path.contains("/.well-known") }

    init {
        server.executor = executor
        server.createContext("/") { exchange -> exchange.use { handle(it) } }
        server.start()
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    fun requestsTo(path: String): List<Recorded> = recorded.filter { it.path == path }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
        val path = exchange.requestURI.path
        recorded +=
            Recorded(
                method = exchange.requestMethod,
                path = path,
                query = exchange.requestURI.rawQuery,
                headers = RECORDED_HEADERS.mapNotNull { h -> exchange.requestHeaders.getFirst(h)?.let { h to it } }.toMap(),
                body = body,
            )
        when (path) {
            AUTHORIZE -> authorize(exchange)
            TOKEN -> delayedJson(exchange, tokenDelayMillis, tokenStatus, tokenBody)
            USER -> delayedJson(exchange, userDelayMillis, OK, user)
            EMAILS -> delayedJson(exchange, emailsDelayMillis, emailsStatus, emails)
            else -> json(exchange, NOT_FOUND, """{"message":"Not Found"}""")
        }
    }

    private fun delayedJson(
        exchange: HttpExchange,
        delayMillis: Long,
        status: Int,
        payload: String,
    ) {
        Thread.sleep(delayMillis)
        json(exchange, status, payload)
    }

    private fun authorize(exchange: HttpExchange) {
        val params = query(exchange.requestURI)
        val redirect = params.getValue("redirect_uri")
        val state = java.net.URLEncoder.encode(params.getValue("state"), StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Location", "$redirect?code=$CODE&state=$state")
        exchange.sendResponseHeaders(FOUND, -1)
    }

    private fun json(
        exchange: HttpExchange,
        status: Int,
        payload: String,
    ) {
        val bytes = payload.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    companion object {
        const val AUTHORIZE = "/login/oauth/authorize"
        const val TOKEN = "/login/oauth/access_token"
        const val USER = "/user"
        const val EMAILS = "/user/emails"
        const val CODE = "stub-code"
        const val ACCESS_TOKEN = "gho_stubAccessToken655"
        const val TOKEN_RESPONSE = """{"access_token":"$ACCESS_TOKEN","token_type":"bearer","scope":"read:user,user:email"}"""

        private const val OK = 200
        private const val FOUND = 302
        private const val NOT_FOUND = 404

        private val RECORDED_HEADERS = listOf("Accept", "Authorization", "Content-Type")

        /** GitHub's `/user` shape: numeric `id`, `login`, nullable `name`, the UNVERIFIED public `email`. */
        const val GITHUB_USER =
            """{"id":4242,"login":"octo-alice","name":"Alice Octo","email":"public-alias@elsewhere.example",""" +
                """"avatar_url":"https://avatars.githubusercontent.com/u/4242?v=4"}"""

        /**
         * The primary address is one no other suite of this module provisions: the module's
         * Postgres is shared (SharedPostgres), and the link-once rule (#187) refuses a second
         * identity for an address — OidcLoginIntegrationTest's Keycloak Alice went red on
         * `identity_mismatch` while this suite used her address.
         */
        const val VERIFIED_PRIMARY =
            """[{"email":"spare@datapipelines.co","primary":false,"verified":true,"visibility":null},""" +
                """{"email":"Octo-Alice@Datapipelines.CO","primary":true,"verified":true,"visibility":"private"}]"""

        fun query(uri: URI): Map<String, String> =
            uri.rawQuery
                .orEmpty()
                .split('&')
                .filter { it.isNotEmpty() }
                .associate {
                    URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to
                        URLDecoder.decode(it.substringAfter('=', ""), StandardCharsets.UTF_8)
                }
    }
}
