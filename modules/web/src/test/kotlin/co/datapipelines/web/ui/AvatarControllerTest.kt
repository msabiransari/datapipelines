package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.User
import co.datapipelines.auth.UserRepository
import co.datapipelines.auth.WorkspaceContext
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
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
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The avatar proxy's fence (#197, hardened by #246), exercised over the REAL transport: the
 * controller, the allowlist and [AvatarImageFetcher] run against an in-JVM HTTP server,
 * because the behaviors under test (redirects never followed, the size cap and the deadline
 * on the read, raster content only) are properties of the WIRE — a stubbed fetcher would
 * assert them by assuming them. Each refusal answers 404 without leaking why; the request
 * counts the in-JVM server actually received prove the "no network hop" claims. Every test
 * that fetches uses its OWN path: the controller's cache is shared by the class, and a reused
 * URL would read a previous test's cache entry instead of proving this test's fetch.
 *
 * The fence admits no port in a picture URL (#246), and the in-JVM server cannot listen on
 * port 80, so stored URLs are written portless and [LoopbackFetcher] moves the wire to the
 * server's ephemeral port — the only substitution; every fence runs as in production.
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

    private val hits = ConcurrentHashMap<String, Int>()

    /** Counted down by the dribbling provider when a write fails — the client let the connection go. */
    private val slowAbandoned = CountDownLatch(1)

    /** Releases the stalling provider; a test that never releases it is bounded by [STALL_MAX]. */
    private val stallRelease = CountDownLatch(1)

    // Handlers run on their own threads: a slow provider must not stall the next test's fetch.
    private val serverThreads = Executors.newCachedThreadPool()
    private val server = startImageServer()

    private val controller =
        AvatarController(userRepository, AvatarHosts(authProperties), LoopbackFetcher())

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
        stallRelease.countDown()
        server.stop(0)
        serverThreads.shutdownNow()
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

    /** A stored picture URL as an IdP writes one: no port ([LoopbackFetcher] finds the server). */
    private fun url(path: String): String = "http://127.0.0.1$path"

    /** The in-JVM server's real address, port included — where a provider genuinely answers. */
    private fun serverUrl(path: String): String = "http://127.0.0.1:${server.address.port}$path"

    /**
     * The shutdown window (246's security pass, observation a): a fetcher that has been
     * closed — the context shutting down with this request in flight — can arm no deadline.
     * Before the fix the scheduler's refusal escaped `read()` as an exception (a 500 and one
     * unclosed connection); now it is one more quiet refusal, after the request went out.
     */
    @Test
    fun `a closed fetcher refuses the request in flight instead of throwing`() {
        authenticate()
        every { userRepository.findById(userId) } returns userWith(url("/ok"))
        val closed = LoopbackFetcher().also { it.close() }
        val shuttingDown = AvatarController(userRepository, AvatarHosts(authProperties), closed)

        shuttingDown.avatar().statusCode.value() shouldBe 404
        // The refusal is the READ's, after the request went out (a validation refusal would
        // leave the server untouched); the JDK client may resend an idempotent GET once after
        // the early close, so the count is a floor, not an exact one.
        hitCount("/ok") shouldBeGreaterThanOrEqual 1
    }

    /**
     * The production fetcher with the wire moved: a portless URI (the only kind the fence
     * admits) reaches the in-JVM server's ephemeral port; a URI naming a port goes exactly
     * where it says.
     */
    private inner class LoopbackFetcher : AvatarImageFetcher() {
        override fun fetch(uri: URI): AvatarImage? =
            super.fetch(
                if (uri.port == -1) URI(uri.scheme, uri.userInfo, uri.host, server.address.port, uri.path, uri.query, null) else uri,
            )
    }

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
        every { userRepository.findById(userId) } returns userWith("http://user@127.0.0.1/ok")
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
    fun `a picture URL naming a port answers 404 without a network hop`() {
        authenticate()
        val before = totalHits()
        // The allowlisted host at the port where a provider really answers: without the port
        // fence this fetch reaches the in-JVM server (the base did, 200).
        every { userRepository.findById(userId) } returns userWith(serverUrl("/port"))
        controller.avatar().statusCode.value() shouldBe 404
        hitCount("/port") shouldBe 0
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
    fun `an SVG answer is refused - only raster types are served`() {
        authenticate()
        every { userRepository.findById(userId) } returns userWith(url("/svg"))
        controller.avatar().statusCode.value() shouldBe 404
        hitCount("/svg") shouldBe 1 // fetched, then refused on its type — not refused for another reason
    }

    @Test
    fun `each raster type is served with its type verbatim`() {
        authenticate()
        RASTER.forEach { (path, type) ->
            every { userRepository.findById(userId) } returns userWith(url(path))
            val answer = controller.avatar()
            withClue(type) {
                answer.statusCode.value() shouldBe 200
                answer.headers.contentType shouldBe MediaType.parseMediaType(type)
            }
        }
    }

    @Test
    fun `a body past the size cap is refused`() {
        authenticate()
        every { userRepository.findById(userId) } returns userWith(url("/big"))
        controller.avatar().statusCode.value() shouldBe 404
    }

    @Test
    fun `a body that dribbles past the deadline is refused within it and the connection is let go`() {
        authenticate()
        every { userRepository.findById(userId) } returns userWith(url("/slow"))

        val (status, elapsed) = timed { controller.avatar().statusCode.value() }

        println("avatar deadline witness: /slow status=$status elapsed=${elapsed.toMillis()} ms")
        withClue("elapsed=${elapsed.toMillis()} ms, status=$status") {
            elapsed shouldBeLessThan DOCUMENTED_DEADLINE + DRIBBLE_INTERVAL
            elapsed shouldBeGreaterThanOrEqualTo DOCUMENTED_DEADLINE // refused BY the deadline, not before it
            status shouldBe 404
        }
        // The event, not a sleep: the provider's next write fails once the client has closed.
        slowAbandoned.await(ABANDON_WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `a body that stalls after its headers is refused at the deadline`() {
        authenticate()
        every { userRepository.findById(userId) } returns userWith(url("/stall"))
        try {
            val (status, elapsed) = timed { controller.avatar().statusCode.value() }

            println("avatar deadline witness: /stall status=$status elapsed=${elapsed.toMillis()} ms")
            withClue("elapsed=${elapsed.toMillis()} ms, status=$status") {
                elapsed shouldBeLessThan DOCUMENTED_DEADLINE + DRIBBLE_INTERVAL
                elapsed shouldBeGreaterThanOrEqualTo DOCUMENTED_DEADLINE
                status shouldBe 404
            }
        } finally {
            stallRelease.countDown()
        }
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

    private fun <T> timed(block: () -> T): Pair<T, Duration> {
        val start = System.nanoTime()
        val result = block()
        return result to Duration.ofNanos(System.nanoTime() - start)
    }

    /** The wire the fetcher talks to: real statuses, real headers, and a per-path hit count. */
    private fun startImageServer(): HttpServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.executor = serverThreads

        fun image(
            path: String,
            type: String = "image/png",
        ) {
            server.createContext(path) { exchange ->
                this@AvatarControllerTest.hits.merge(path, 1, Int::plus)
                exchange.responseHeaders.add("Content-Type", type)
                exchange.sendResponseHeaders(200, pictureBytes.size.toLong())
                exchange.responseBody.use { it.write(pictureBytes) }
            }
        }

        image("/ok")
        image("/cached")
        image("/port")
        image("/svg", type = "image/svg+xml")
        RASTER.forEach { (path, type) -> image(path, type) }
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
        server.createContext("/slow") { exchange ->
            this@AvatarControllerTest.hits.merge("/slow", 1, Int::plus)
            exchange.responseHeaders.add("Content-Type", "image/png")
            // Headers at once, then one byte per interval — twice the deadline's worth, so a read
            // with no deadline runs to this answer's end (the base: 200 after ~10 s).
            exchange.sendResponseHeaders(200, 0)
            try {
                repeat(DRIBBLE_BYTES) {
                    exchange.responseBody.write(0)
                    exchange.responseBody.flush()
                    Thread.sleep(DRIBBLE_INTERVAL.toMillis())
                }
                exchange.responseBody.close()
            } catch (_: IOException) {
                slowAbandoned.countDown()
            } finally {
                exchange.close()
            }
        }
        server.createContext("/stall") { exchange ->
            this@AvatarControllerTest.hits.merge("/stall", 1, Int::plus)
            exchange.responseHeaders.add("Content-Type", "image/png")
            // Headers promising a body that never comes; the connection stays open until released.
            exchange.sendResponseHeaders(200, pictureBytes.size.toLong())
            stallRelease.await(STALL_MAX.toMillis(), TimeUnit.MILLISECONDS)
            runCatching { exchange.close() }
        }
        server.createContext("/redirect") { exchange ->
            this@AvatarControllerTest.hits.merge("/redirect", 1, Int::plus)
            // The real address: were the redirect followed, /ok's count would show it.
            exchange.responseHeaders.add("Location", serverUrl("/ok"))
            exchange.sendResponseHeaders(302, -1)
        }
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(404, -1)
        }
        server.start()
        return server
    }

    private companion object {
        /** auth.md §11.1's budget for the whole fetch — the expectation comes from the doc, not the constant under test. */
        val DOCUMENTED_DEADLINE: Duration = Duration.ofSeconds(5)

        /** The dribbling provider's pace; the refusal must land within one of these past the deadline. */
        val DRIBBLE_INTERVAL: Duration = Duration.ofMillis(500)

        const val DRIBBLE_BYTES = 20

        /** Bounds the stalling provider when the fetch never lets go (the base: ~15 s, then EOF). */
        val STALL_MAX: Duration = Duration.ofSeconds(15)

        const val ABANDON_WAIT_SECONDS = 5L

        val RASTER =
            listOf(
                "/raster/png" to "image/png",
                "/raster/jpeg" to "image/jpeg",
                "/raster/gif" to "image/gif",
                "/raster/webp" to "image/webp",
            )
    }
}
