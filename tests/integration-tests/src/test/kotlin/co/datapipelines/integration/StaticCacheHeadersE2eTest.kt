package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/**
 * **Static files' cache headers, every file, over HTTP** (#1023 D2; the owner's ruling of 2026-11-16, D1).
 *
 * Font files under `/vendor/fonts/` are kept a year (`max-age=31536000, public, immutable`); every other file under
 * `/css/`, `/js/` (the chart engine's included) and `/vendor/` is kept and re-checked on every use (`no-cache`), by a
 * content validator: its `ETag` is the SHA-256 of the bytes served, so a file whose bytes change is answered in full
 * whatever time the build stamped. Everything else keeps the header it had (row 6).
 *
 * The files are a LIST THIS TEST BUILDS by walking the classpath locations the handlers serve, never a hand-picked
 * sample, with floors so a walk that finds nothing is red, not green over nothing. Set up as
 * [DraftFirstGoldenPathE2eTest] is: the whole application on a random port, plain HTTP requests with no session.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StaticCacheHeadersE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    /** Every font file under `static/vendor/fonts/`, as request paths. */
    private val fonts: List<String> by lazy {
        walk(STATIC_ROOT, "vendor/fonts/").map { "/$it" }.also { println("1023|fonts|count=${it.size}") }
    }

    /** Every other file of the re-check rule: css, js, vendor outside fonts, and every name the engine lists. */
    private val rechecked: List<String> by lazy {
        val css = walk(STATIC_ROOT, "css/").map { "/$it" }
        val js = walk(STATIC_ROOT, "js/").map { "/$it" }
        val vendor = walk(STATIC_ROOT, "vendor/").filterNot { it.startsWith("vendor/fonts/") }.map { "/$it" }
        val engineOnClasspath = walk(engineRoot(), "").toSet()
        val engine = engineNames().map { name -> name.also { check(it in engineOnClasspath) { "engine file $it not on the classpath" } } }
        val enginePaths = engine.map { ENGINE_PREFIX + it }
        println(
            "1023|others|css=${css.size} js=${js.size} vendor-outside-fonts=${vendor.size} engine=${enginePaths.size} " +
                "total=${css.size + js.size + vendor.size + enginePaths.size}",
        )
        css + js + vendor + enginePaths
    }

    @Test
    fun `the walk found the floors, and says which kind of resource the application serves`() {
        withClue("font files under static/vendor/fonts/ (floor $FONT_FLOOR)") { fonts.size shouldBeGreaterThanOrEqual FONT_FLOOR }
        withClue("re-checked files (floor $OTHER_FLOOR)") { rechecked.size shouldBeGreaterThanOrEqual OTHER_FLOOR }
        // The test's classloader is the application's: the kind printed here is the kind the handlers resolve
        // (a file inside a packaged jar → hashed once and kept; a file on disk → hashed on every request, D3).
        val kinds =
            resolver
                .getResources("classpath*:$STATIC_ROOT/css/**")
                .filter(::isFile)
                .groupingBy { if (it.isFile) "file-on-disk" else "inside-a-jar(${it.url.protocol})" }
                .eachCount()
        println("1023|resource-kind|$kinds")
        val webmvc = Class.forName("org.springframework.web.servlet.DispatcherServlet").`package`.implementationVersion
        println("1023|spring-webmvc|$webmvc")
        // The framework's default handler also serves these prefixes from the other default static locations; the
        // handlers of #1023 serve only `static/`, so a file there would stop being served. There is none.
        val hidden =
            OTHER_STATIC_ROOTS.flatMap { root ->
                STATIC_PREFIXES.flatMap { prefix -> resolver.getResources("classpath*:$root${prefix}**").filter(::isFile) }
            }
        withClue("files under another default static location that the #1023 handlers would hide") {
            hidden.map { it.url.toString() }.shouldBeEmpty()
        }
    }

    @Test
    fun `every font file is kept a year, public and immutable, and its own validator answers 304`() {
        assertSoftly {
            for (path in fonts) {
                val first = get(path)
                withClue("$path first answer") {
                    first.statusCode() shouldBe 200
                    val cacheControl = first.header("Cache-Control")
                    cacheControl.split(", ").toSet() shouldBe FONT_DIRECTIVES
                }
                val etag = first.header("ETag")
                withClue("$path ETag") { etag shouldNotBe "" }
                val again = get(path, "If-None-Match" to etag)
                withClue("$path asked with its own validator") {
                    again.statusCode() shouldBe 304
                    again.body().size shouldBe 0
                }
            }
        }
        println("1023|font-header|${get(fonts.first()).header("Cache-Control")}")
        printCacheHeaders("font", fonts.first())
    }

    @Test
    fun `every other file is re-checked by its content, 304 on its own validator and 200 on another`() {
        assertSoftly {
            for (path in rechecked) {
                val first = get(path)
                val etag = first.header("ETag")
                withClue("$path first answer") {
                    first.statusCode() shouldBe 200
                    first.header("Cache-Control") shouldBe RECHECK_HEADER
                    etag shouldNotBe ""
                    // D2.4: the validator IS the content served.
                    etag shouldBe "\"${sha256(first.body())}\""
                }
                val same = get(path, "If-None-Match" to etag)
                withClue("$path asked with its own validator") {
                    same.statusCode() shouldBe 304
                    same.body().size shouldBe 0
                    same.header("Cache-Control") shouldBe RECHECK_HEADER
                }
                val other = get(path, "If-None-Match" to "\"${"0".repeat(SHA256_HEX_LENGTH)}\"")
                withClue("$path asked with a different validator") {
                    other.statusCode() shouldBe 200
                    other.body().toList() shouldBe first.body().toList()
                }
            }
        }
        println("1023|recheck-header|${get(rechecked.first()).header("Cache-Control")}")
        printCacheHeaders("recheck", rechecked.first())
    }

    @Test
    fun `everything outside the ruling keeps the header it has`() {
        val rows =
            listOf(
                Row("GET /site/css/site.css", "/site/css/site.css", emptyList(), 200, null, Expect.Exactly("max-age=3600, public")),
                Row("GET /", "/", emptyList(), 200, null, Expect.Exactly(PUBLIC_PAGE_WINDOW)),
                Row("GET /docs", "/docs", emptyList(), 200, null, Expect.Exactly(PUBLIC_PAGE_WINDOW)),
                Row("GET /login", "/login", emptyList(), 200, null, Expect.Contains("no-store")),
                Row(
                    "GET /dashboard (text/html)",
                    "/dashboard",
                    listOf("Accept" to "text/html"),
                    DASHBOARD_SIGNED_OUT_STATUS,
                    DASHBOARD_SIGNED_OUT_LOCATION,
                    Expect.Contains("no-store"),
                ),
                Row(
                    "GET /api/v1/executions (json)",
                    "/api/v1/executions",
                    listOf("Accept" to "application/json"),
                    401,
                    null,
                    Expect.Contains("no-store"),
                ),
                Row("GET /favicon.ico", "/favicon.ico", emptyList(), FAVICON_STATUS, null, Expect.Exactly(FAVICON_CACHE_CONTROL)),
                Row("GET /robots.txt", "/robots.txt", emptyList(), ROBOTS_STATUS, null, Expect.Exactly(ROBOTS_CACHE_CONTROL)),
            )
        assertSoftly {
            for (row in rows) {
                val response = get(row.path, *row.headers.toTypedArray())
                val cacheControl = response.header("Cache-Control")
                val location = response.header("Location")
                println("1023|unchanged|${row.label}|status=${response.statusCode()} location=$location cache-control=$cacheControl")
                withClue(row.label) {
                    response.statusCode() shouldBe row.status
                    if (row.location != null) location.removePrefix("http://localhost:$port") shouldBe row.location
                    when (val expect = row.cacheControl) {
                        is Expect.Exactly -> cacheControl shouldBe expect.value
                        is Expect.Contains -> cacheControl shouldContain expect.value
                    }
                }
            }
        }
    }

    @Test
    fun `the engine's allowlist still refuses a file that exists on its classpath`() {
        val orderFile = "LOAD-ORDER.txt"
        withClue("the order file is on the classpath, so only the allowlist can refuse it") {
            (javaClass.classLoader.getResource("${engineRoot()}/$orderFile") != null) shouldBe true
        }
        withClue("the order file is not a listed name") { (orderFile in engineNames()) shouldBe false }
        val response = get(ENGINE_PREFIX + orderFile)
        println("1023|allowlist|status=${response.statusCode()} cache-control=${response.header("Cache-Control")}")
        response.statusCode() shouldBe 404
        response.header("Cache-Control") shouldNotContain "max-age=31536000"
    }

    @Test
    fun `no controller mapping answers under the three static prefixes`() {
        val patterns = controllerPatterns()
        val underStatic = patterns.filter { p -> STATIC_PREFIXES.any { p == it.trimEnd('/') || p.startsWith(it) } }.sorted()
        println("1023|controller-mappings|prefixes=$STATIC_PREFIXES scanned=${patterns.size} found=$underStatic")
        withClue("a controller mapping under ${STATIC_PREFIXES}: its answer is not a resource handler's; give it a row") {
            patterns.size shouldBeGreaterThanOrEqual 1
            underStatic.shouldBeEmpty()
        }
    }

    /**
     * Every pattern of every controller mapping the running application registered (the framework's
     * `requestMappingHandlerMapping`), read by reflection: spring-webmvc is the application's, not on this module's
     * compile classpath.
     */
    private fun controllerPatterns(): List<String> {
        val mapping = context.getBean("requestMappingHandlerMapping")
        val methods = mapping.javaClass.getMethod("getHandlerMethods").invoke(mapping) as Map<*, *>
        return methods.keys.flatMap { info ->
            val patterns = requireNotNull(info).javaClass.getMethod("getPatternValues").invoke(info) as Set<*>
            patterns.map { it.toString() }
        }
    }

    private fun get(
        path: String,
        vararg headers: Pair<String, String>,
    ): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().timeout(Duration.ofSeconds(30))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    /** The caching headers of one answer and of its 304, as the browser receives them (the handback's tables). */
    private fun printCacheHeaders(
        label: String,
        path: String,
    ) {
        val first = get(path)
        val again = get(path, "If-None-Match" to first.header("ETag"))
        for ((kind, response) in listOf("200" to first, "304" to again)) {
            val shown = CACHE_HEADERS.joinToString(" | ") { "$it: ${response.header(it).ifEmpty { "(absent)" }}" }
            println("1023|headers|$label|$path|${response.statusCode()} (asked for $kind)|$shown")
        }
    }

    private fun HttpResponse<*>.header(name: String): String = headers().allValues(name).joinToString(", ")

    /** Every readable file under `classpath*:<root>/<sub>`, as paths relative to [root]; sorted, no duplicates. */
    private fun walk(
        root: String,
        sub: String,
    ): List<String> {
        val roots = resolver.getResources("classpath*:$root/").map { it.url.toString() }
        check(roots.isNotEmpty()) { "classpath location $root/ not found" }
        return resolver
            .getResources("classpath*:$root/$sub**")
            .filter(::isFile)
            .map { resource ->
                val url = resource.url.toString()
                val base = roots.firstOrNull { url.startsWith(it) } ?: error("resource $url is under none of $roots")
                url.removePrefix(base)
            }.distinct()
            .sorted()
    }

    private fun isFile(resource: Resource): Boolean = resource.isReadable && !resource.url.toString().endsWith("/")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // The engine's object is on the application's runtime classpath, not on this module's compile classpath
    // (`:modules:app` brings `web` and `js-rendered` as implementation dependencies), so it is read by reflection:
    // the REAL list the handler's allowlist uses, never a copy parsed here.
    private fun engineObject(): Class<*> = Class.forName("co.datapipelines.jsrendered.JsRenderedEngineFiles")

    private fun engineRoot(): String = engineObject().getField("ROOT").get(null) as String

    private fun engineNames(): List<String> {
        val type = engineObject()
        val instance = type.getField("INSTANCE").get(null)

        @Suppress("UNCHECKED_CAST")
        return type.getMethod("getNames").invoke(instance) as List<String>
    }

    private data class Row(
        val label: String,
        val path: String,
        val headers: List<Pair<String, String>>,
        val status: Int,
        val location: String?,
        val cacheControl: Expect,
    )

    private sealed interface Expect {
        data class Exactly(
            val value: String,
        ) : Expect

        data class Contains(
            val value: String,
        ) : Expect
    }

    private companion object {
        const val STATIC_ROOT = "static"
        const val ENGINE_PREFIX = "/js/js-rendered/"
        const val FONT_FLOOR = 20
        const val OTHER_FLOOR = 100
        const val SHA256_HEX_LENGTH = 64
        const val RECHECK_HEADER = "no-cache"
        val FONT_DIRECTIVES = setOf("max-age=31536000", "public", "immutable")
        val STATIC_PREFIXES = listOf("/css/", "/js/", "/vendor/")
        val OTHER_STATIC_ROOTS = listOf("META-INF/resources", "resources", "public")
        val CACHE_HEADERS = listOf("Cache-Control", "Pragma", "Expires", "ETag", "Last-Modified", "Vary")

        /** `PublicPage` and the docs controller: `CacheControl.maxAge(5, MINUTES).cachePublic()`. */
        const val PUBLIC_PAGE_WINDOW = "max-age=300, public"

        /** The security chain's default, which every answer outside the ruling keeps. */
        private const val DEFAULT_NO_STORE = "no-cache, no-store, max-age=0, must-revalidate"

        // Row 6's values the brief leaves to the base, read on base 01ec928b0 before the code change (2026-11-17):
        // a signed-out page request is redirected to the login page; the icon and robots.txt carry the default.
        const val DASHBOARD_SIGNED_OUT_STATUS = 302
        val DASHBOARD_SIGNED_OUT_LOCATION: String? = "/login"
        const val FAVICON_STATUS = 200
        const val FAVICON_CACHE_CONTROL = DEFAULT_NO_STORE
        const val ROBOTS_STATUS = 200
        const val ROBOTS_CACHE_CONTROL = DEFAULT_NO_STORE

        val resolver = PathMatchingResourcePatternResolver()

        // The application's wiring, as DraftFirstGoldenPathE2eTest's: the module's shared Postgres and Redis, a
        // random JWT secret and encryption key, one stubbed OIDC provider. No request of this class carries a session.
        private const val SECRET_BYTES = 32
        private val random = SecureRandom()
        private val oidc = OidcDiscoveryStub()
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = randomSecret()

        private fun randomSecret(): String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

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
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
