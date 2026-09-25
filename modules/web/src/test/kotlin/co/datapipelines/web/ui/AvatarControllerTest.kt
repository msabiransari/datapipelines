package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.User
import co.datapipelines.auth.UserRepository
import co.datapipelines.auth.WorkspaceContext
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The avatar proxy's fence (#197), exercised over the REAL transport: the controller, the
 * allowlist and [AvatarImageFetcher] run against an in-JVM HTTP server, because the
 * behaviors under test (redirects never followed, the size cap on the read, image content
 * only) are properties of the WIRE — a stubbed fetcher would assert them by assuming them.
 * Each refusal answers 404 without leaking why; the request counts the in-JVM server
 * actually received prove the "no network hop" claims. Every test that fetches uses its
 * OWN path: the controller's cache is shared by the class, and a reused URL would read a
 * previous test's cache entry instead of proving this test's fetch.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AvatarControllerTest {
    private val userRepository = mockk<UserRepository>()

    private val pictureBytes = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) // a PNG signature

    private val authProperties =
        AuthProperties(
            oidc =
                AuthProperties.Oidc(
                    providers =
                        listOf(
                            AuthProperties.Provider(
                                name = "google",
                                clientId = "id",
                                clientSecret = "secret",
                                issuerUri = "https://accounts.google.com",
                                pictureHosts = listOf("127.0.0.1"),
                            ),
                        ),
                ),
        )

    private val controller =
        AvatarController(userRepository, AvatarHosts(authProperties), AvatarImageFetcher())

    private val server = startImageServer()
    private val hits = mutableMapOf<String, Int>()

    private fun hitCount(path: String): Int = hits.getOrDefault(path, 0)

    /** Every request the in-JVM server has seen so far — the "no network hop" baseline. */
    private fun totalHits(): Int = hits.values.sum()

    private val userId = UUID.randomUUID()

    private val principal =
        AuthenticatedPrincipal(
            userId = userId,
            email = "test@example.com",
            displayName = "Test User",
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext(UUID.randomUUID(), "acme"),
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @AfterAll
    fun stopServer() {
        server.stop(0)
    }

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun userWith(pictureUrl: String?): User =
        User(
            id = userId,
            email = "test@example.com",
            displayName = "Test User",
            profilePictureUrl = pictureUrl,
            provider = "google",
            providerSubject = "sub",
            isActive = true,
            isAdmin = false,
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-01T00:00:00Z"),
        )

    private fun url(path: String): String = "http://127.0.0.1:${server.address.port}$path"

    @Test
    fun `an anonymous request is refused without reading any user`() {
        val answer = controller.avatar()
        answer.statusCode.value() shouldBe 404
    }

    @Test
    fun `a user with no stored picture answers 404`() {
        authenticate()
        every { userRepository.findById(userId) } returns userWith(null)
        controller.avatar().statusCode.value() shouldBe 404
    }

    @Test
    fun `a picture on a host the allowlist does not name answers 404 without a network hop`() {
        authenticate()
        val before = totalHits()
        every { userRepository.findById(userId) } returns userWith("https://pic.example/not-allowlisted.png")
        controller.avatar().statusCode.value() shouldBe 404
        totalHits() shouldBe before
    }

    @Test
    fun `a URL with a userinfo component answers 404 without a network hop`() {
        authenticate()
        val before = totalHits()
        every { userRepository.findById(userId) } returns userWith("http://user@127.0.0.1:${server.address.port}/ok")
        controller.avatar().statusCode.value() shouldBe 404
        totalHits() shouldBe before
    }

    @Test
    fun `a non-http scheme answers 404 without a network hop`() {
        authenticate()
        val before = totalHits()
        every { userRepository.findById(userId) } returns userWith("ftp://127.0.0.1/x.png")
        controller.avatar().statusCode.value() shouldBe 404
        totalHits() shouldBe before
    }

    @Test
    fun `an allowlisted picture is served with its image type and private browser caching`() {
        authenticate()
        every { userRepository.findById(userId) } returns userWith(url("/ok"))

        val answer = controller.avatar()

        answer.statusCode.value() shouldBe 200
        answer.headers.contentType shouldBe MediaType.IMAGE_PNG
        answer.body!! shouldBe pictureBytes
        answer.headers.cacheControl shouldContain "private"
        answer.headers.cacheControl shouldContain "max-age=300"
        hitCount("/ok") shouldBe 1
    }

    @Test
    fun `a second request within the TTL is served from the cache - one provider fetch`() {
        authenticate()
        every { userRepository.findById(userId) } returns userWith(url("/cached"))
        controller.avatar()
        controller.avatar()
        hitCount("/cached") shouldBe 1
    }

    @Test
    fun `a non-image content type is refused`() {
        authenticate()
        every { userRepository.findById(userId) } returns userWith(url("/html"))
        controller.avatar().statusCode.value() shouldBe 404
    }

    @Test
    fun `a body past the size cap is refused`() {
        authenticate()
        every { userRepository.findById(userId) } returns userWith(url("/big"))
        controller.avatar().statusCode.value() shouldBe 404
    }

    @Test
    fun `a redirect is refused, never followed`() {
        authenticate()
        every { userRepository.findById(userId) } returns userWith(url("/redirect"))
        val beforeOk = hitCount("/ok")
        controller.avatar().statusCode.value() shouldBe 404
        // The 302 came from /redirect; the target it named was never fetched.
        hitCount("/ok") shouldBe beforeOk
    }

    @Test
    fun `a picture-host entry that is not a bare hostname refuses startup`() {
        listOf("https://pic.example", "pic.example/path", "*.example", "pic.example:8443", "u@pic.example").forEach { bad ->
            val props =
                AuthProperties(
                    oidc =
                        AuthProperties.Oidc(
                            providers = listOf(AuthProperties.Provider(name = "p", issuerUri = "https://p", pictureHosts = listOf(bad))),
                        ),
                )
            val error = runCatching { AvatarHosts(props) }.exceptionOrNull()
            error.shouldBeInstanceOf<IllegalArgumentException>()
            error.message shouldContain bad
        }
    }

    @Test
    fun `the cache bounds its entries and admits nothing past the size floor`() {
        val cache = AvatarCache(maxEntries = 2, ttl = Duration.ofSeconds(60))
        val image = AvatarImage(pictureBytes, MediaType.IMAGE_PNG)
        cache.put("a", image)
        cache.put("b", image)
        cache.put("c", image)
        cache.get("a") shouldBe null // evicted, the bound held
        cache.get("c") shouldBe image

        val big = AvatarImage(ByteArray(AvatarController.CACHE_ADMIT_MAX_BYTES + 1), MediaType.IMAGE_PNG)
        cache.put("big", big)
        cache.get("big") shouldBe null

        val expiring = AvatarCache(maxEntries = 2, ttl = Duration.ZERO)
        expiring.put("x", image)
        expiring.get("x") shouldBe null
    }

    /** The wire the fetcher talks to: real statuses, real headers, and a per-path hit count. */
    private fun startImageServer(): HttpServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)

        fun image(path: String) {
            server.createContext(path) { exchange ->
                this@AvatarControllerTest.hits.merge(path, 1, Int::plus)
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.sendResponseHeaders(200, pictureBytes.size.toLong())
                exchange.responseBody.use { it.write(pictureBytes) }
            }
        }

        image("/ok")
        image("/cached")
        server.createContext("/html") { exchange ->
            this@AvatarControllerTest.hits.merge("/html", 1, Int::plus)
            exchange.responseHeaders.add("Content-Type", "text/html")
            val body = "<html></html>".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/big") { exchange ->
            this@AvatarControllerTest.hits.merge("/big", 1, Int::plus)
            exchange.responseHeaders.add("Content-Type", "image/png")
            // The wire carries twice the cap; the fetcher must give up at the cap's read, not trust a length.
            exchange.sendResponseHeaders(200, AvatarController.MAX_BYTES.toLong() * 2)
            exchange.responseBody.use { it.write(ByteArray(AvatarController.MAX_BYTES * 2)) }
        }
        server.createContext("/redirect") { exchange ->
            this@AvatarControllerTest.hits.merge("/redirect", 1, Int::plus)
            exchange.responseHeaders.add("Location", url("/ok"))
            exchange.sendResponseHeaders(302, -1)
        }
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(404, -1)
        }
        server.start()
        return server
    }
}
