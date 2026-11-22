package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.ObjectMapper
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
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.UUID

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
 *
 * The client library's two files (#1064 D1) are on no classpath location, so the walk adds them by name; their own
 * case holds that each is the join of its sources fetched one by one (A.2). A font file's successful answer allows any
 * origin and only a font file's successful answer does (#1064 D4): every font case below sends a foreign `Origin`
 * and reads `Access-Control-Allow-Origin` beside the cache headers (A.3).
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
        val library = LIBRARY_FILES.map { it.first } + themes.map { THEME_PREFIX + it + ".css" }
        val engineOnClasspath = walk(engineRoot(), "").toSet()
        val engine = engineNames().map { name -> name.also { check(it in engineOnClasspath) { "engine file $it not on the classpath" } } }
        val enginePaths = engine.map { ENGINE_PREFIX + it }
        println(
            "1023|others|css=${css.size} js=${js.size} vendor-outside-fonts=${vendor.size} engine=${enginePaths.size} " +
                "library=${library.size} total=${css.size + js.size + vendor.size + enginePaths.size + library.size}",
        )
        css + js + vendor + enginePaths + library
    }

    /**
     * The names of the client library's theme sheets (#1065 D5): the vendor theme files on the classpath minus `light`,
     * read from the walk and not from the class (`ClientLibraryThemesTest` holds the class's closed list equal to them).
     */
    private val themes: List<String> by lazy {
        walk(STATIC_ROOT, THEMES_DIR).map { it.removePrefix(THEMES_DIR).removeSuffix(".css") }.filter { it != "light" }.sorted()
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
                STATIC_PREFIXES.flatMap { prefix -> resolver.getResources("classpath*:$root$prefix**").filter(::isFile) }
            }
        withClue("files under another default static location that the #1023 handlers would hide") {
            hidden.map { it.url.toString() }.shouldBeEmpty()
        }
    }

    @Test
    fun `every font file is kept a year, public and immutable, and its own validator answers 304`() {
        assertSoftly {
            for (path in fonts) {
                val first = get(path, FOREIGN_ORIGIN)
                withClue("$path first answer") {
                    first.statusCode() shouldBe 200
                    first.shouldAllowAnyOrigin()
                    val cacheControl = first.header("Cache-Control")
                    cacheControl.split(", ").toSet() shouldBe FONT_DIRECTIVES
                    // #1023 round 2: the security chain writes none of its cache headers on a font file.
                    first.header("Pragma") shouldBe ""
                    first.header("Expires") shouldBe ""
                }
                val etag = first.header("ETag")
                withClue("$path ETag") { etag shouldNotBe "" }
                val again = get(path, "If-None-Match" to etag, FOREIGN_ORIGIN)
                withClue("$path asked with its own validator") {
                    again.statusCode() shouldBe 304
                    again.shouldAllowAnyOrigin()
                    again.body().size shouldBe 0
                    again.header("Pragma") shouldBe ""
                    again.header("Expires") shouldBe ""
                }
            }
        }
        println("1023|font-header|${get(fonts.first()).header("Cache-Control")}")
        val first = get(fonts.first(), FOREIGN_ORIGIN).originHeaders()
        println("1064|D4|a|walk|fonts=${fonts.size} GET 200 and 304, each with Origin ${FOREIGN_ORIGIN.second}|$first")
        val noOrigin = get(fonts.first())
        println("1064|D4|a|GET ${fonts.first()} with no Origin|${noOrigin.originHeaders()}")
        withClue("a GET with no Origin header: the star all the same (it does not depend on the request)") {
            noOrigin.statusCode() shouldBe 200
            noOrigin.shouldAllowAnyOrigin()
        }
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
                    // The chain's two legacy headers stay beside the handler's rule (round 2 changes fonts only).
                    first.header("Pragma") shouldBe CHAIN_PRAGMA
                    first.header("Expires") shouldBe CHAIN_EXPIRES
                }
                val same = get(path, "If-None-Match" to etag)
                withClue("$path asked with its own validator") {
                    same.statusCode() shouldBe 304
                    same.body().size shouldBe 0
                    same.header("Cache-Control") shouldBe RECHECK_HEADER
                    same.header("Pragma") shouldBe CHAIN_PRAGMA
                    same.header("Expires") shouldBe CHAIN_EXPIRES
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
                val pragma = response.header("Pragma")
                val expires = response.header("Expires")
                println(
                    "1023|unchanged|${row.label}|status=${response.statusCode()} location=$location cache-control=$cacheControl " +
                        "pragma=$pragma expires=$expires",
                )
                withClue(row.label) {
                    response.statusCode() shouldBe row.status
                    if (row.location != null) location.removePrefix("http://localhost:$port") shouldBe row.location
                    when (val expect = row.cacheControl) {
                        is Expect.Exactly -> cacheControl shouldBe expect.value
                        is Expect.Contains -> cacheControl shouldContain expect.value
                    }
                    // Round 2: every row outside the font rule keeps the chain's two legacy headers, as on the base.
                    pragma shouldBe CHAIN_PRAGMA
                    expires shouldBe CHAIN_EXPIRES
                }
            }
        }
    }

    /**
     * The security chain leaves its cache headers off a request the font handler answers, and only there (round 2).
     * An ERROR answer on the font path is not storable all the same (round 3, #1028): a name under the font prefix that
     * does not exist (404, the application's not-found envelope), GET and HEAD, and a `Range` an existing font file
     * cannot satisfy (416, sent after the handler wrote the font rule) carry the chain's three headers, set by
     * `FontErrorCacheFilter`. The near misses keep the chain's headers. The encoded and dot-segment forms are refused
     * by the firewall before any handler runs, and since round 3 that 400 carries the three too (the filter wraps the
     * response ahead of the chain); the encoded slash is the container's own 400, before any filter, and carries none,
     * as on the base. Every row here is an error: never the year, never `public`.
     */
    @Test
    fun `the font rule reaches only the font files - a missing name, the near misses and refused forms`() {
        val woff2 = fonts.first { it.endsWith(".woff2") }
        val unsatisfiable = listOf("Range" to UNSATISFIABLE_RANGE)
        val missingInFamily = "/vendor/fonts/noto-sans/no-such-font-1023.woff2"
        val rows =
            listOf(
                Near("a missing font name", "/vendor/fonts/no-such-font-1023.woff2", 404, MISSING_FONT_CACHE),
                Near("a missing name in a family", missingInFamily, 404, MISSING_FONT_CACHE),
                Near("a family directory", "/vendor/fonts/noto-sans", 404, MISSING_FONT_CACHE),
                Near("HEAD of a missing font name", "/vendor/fonts/no-such-font-1023.woff2", 404, MISSING_FONT_CACHE, "HEAD"),
                Near("HEAD of a missing name in a family", missingInFamily, 404, MISSING_FONT_CACHE, "HEAD"),
                Near("HEAD of a family directory", "/vendor/fonts/noto-sans", 404, MISSING_FONT_CACHE, "HEAD"),
                Near("an unsatisfiable range of a font file", woff2, 416, MISSING_FONT_CACHE, "GET", unsatisfiable),
                Near("HEAD, an unsatisfiable range of a font file", woff2, 416, MISSING_FONT_CACHE, "HEAD", unsatisfiable),
                Near("a missing stylesheet (off the font path)", "/css/no-such-file-1023.css", 404, CHAIN_CACHE),
                Near("a missing API route (off the font path)", "/api/v1/no-such-route-1023", 401, CHAIN_CACHE),
                Near("a longer segment", "/vendor/fontsX/noto-sans/noto-sans-latin-wght-normal.woff2", 404, CHAIN_CACHE),
                Near("the prefix under the api", "/api/vendor/fonts/noto-sans/noto-sans-latin-wght-normal.woff2", 401, CHAIN_CACHE),
                Near("the prefix without a file", "/vendor/fonts", 404, CHAIN_CACHE),
                Near("the prefix's directory", "/vendor/fonts/", 404, CHAIN_CACHE),
                Near("an encoded dot segment", "/vendor/fonts/%2e%2e/css/app.css", 400, FIREWALL_ON_FONT_PATH_CACHE),
                Near("a dot segment", "/vendor/fonts/../css/app.css", 400, FIREWALL_ON_FONT_PATH_CACHE),
                Near("an encoded slash", "/vendor/fonts/noto-sans%2fnoto-sans-latin-wght-normal.woff2", 400, REFUSED_CACHE),
            )
        headOfAFontFileKeepsTheYearAndAllowsAnyOrigin()
        assertSoftly {
            for (row in rows) {
                // A foreign Origin on every row of the font path (D4 row b); off it, none (an API route would take it
                // as a cross-origin request of its own rule).
                val origin = if (row.path.startsWith(FONT_PREFIX)) listOf(FOREIGN_ORIGIN) else emptyList()
                val response = send(row.method, row.path, *(row.headers + origin).toTypedArray())
                val seen = CHAIN_HEADER_NAMES.map { response.header(it) }
                println("1064|D4|b|${row.label}|${row.method} ${row.path} origin=${origin.isNotEmpty()}|${response.originHeaders()}")
                println(
                    "1023|font-rule-reach|${row.label}|${row.method} ${row.path} ${row.headers}|status=${response.statusCode()}|" +
                        "${CHAIN_HEADER_NAMES.zip(seen)}",
                )
                withClue("${row.label}: ${row.method} ${row.path} (an error answer: the chain's no-store policy, never the year)") {
                    response.statusCode() shouldBe row.status
                    seen shouldBe row.cache
                    response.header(ACAO) shouldBe ""
                    response.header("Cache-Control") shouldNotContain "max-age=31536000"
                    response.header("Cache-Control") shouldNotContain "public"
                }
            }
        }
    }

    /** HEAD of a font file, with a foreign `Origin`: the year alone, and D4's header (row a). */
    private fun headOfAFontFileKeepsTheYearAndAllowsAnyOrigin() {
        val head =
            client.send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port${fonts.first()}"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header(FOREIGN_ORIGIN.first, FOREIGN_ORIGIN.second)
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        println("1023|font-rule-reach|HEAD ${fonts.first()}|status=${head.statusCode()}|${CHAIN_HEADER_NAMES.map { head.header(it) }}")
        println("1064|D4|a|HEAD ${fonts.first()}|${head.originHeaders()}")
        withClue("HEAD of a font file: the year alone") {
            head.statusCode() shouldBe 200
            head.shouldAllowAnyOrigin()
            head.header("Cache-Control").split(", ").toSet() shouldBe FONT_DIRECTIVES
            head.header("Pragma") shouldBe ""
            head.header("Expires") shouldBe ""
        }
    }

    /**
     * The other outcomes of the font handler for a GET or HEAD of an existing file (round 3's outcome list): a range it
     * can satisfy is a success (206) and keeps the font rule alone; an `If-Match` that does not match is ignored for a
     * safe method (the framework's precondition check never answers 412 to GET or HEAD), so it is the 200 and the font
     * rule. And an error the security chain itself answers on a font path, an MCP key used off its surface (403), is
     * not storable either: the filter wraps the response ahead of the chain.
     */
    @Test
    fun `the font handler's other outcomes - a range, a precondition and the chain's own refusal`() {
        val woff2 = fonts.first { it.endsWith(".woff2") }
        val successes =
            listOf(
                Triple("a satisfiable range", listOf("Range" to "bytes=0-9"), 206),
                Triple("a non-matching If-Match", listOf("If-Match" to "\"no-such-etag-1023\""), 200),
            )
        assertSoftly {
            for ((label, headers, status) in successes) {
                for (method in listOf("GET", "HEAD")) {
                    val response = send(method, woff2, *(headers + FOREIGN_ORIGIN).toTypedArray())
                    val seen = CHAIN_HEADER_NAMES.map { response.header(it) }
                    println("1023|font-outcome|$label|$method $woff2|status=${response.statusCode()}|$seen")
                    println("1064|D4|a|$label|$method $woff2|${response.originHeaders()}")
                    withClue("$label: $method $woff2 (a success: the year alone)") {
                        response.statusCode() shouldBe status
                        response.shouldAllowAnyOrigin()
                        response.header("Cache-Control").split(", ").toSet() shouldBe FONT_DIRECTIVES
                        response.header("Pragma") shouldBe ""
                        response.header("Expires") shouldBe ""
                    }
                }
            }
        }
        val key = seedMcpKey()
        val refused = send("GET", woff2, API_KEY_HEADER to key.plaintext, FOREIGN_ORIGIN)
        val seen = CHAIN_HEADER_NAMES.map { refused.header(it) }
        println("1064|D4|b|an MCP key off its surface|GET $woff2|${refused.originHeaders()}")
        println("1023|font-outcome|an MCP key off its surface|GET $woff2|status=${refused.statusCode()}|${CHAIN_HEADER_NAMES.zip(seen)}")
        withClue("an MCP key off its surface: GET $woff2 (the chain's own error answer: no-store, never the year)") {
            refused.statusCode() shouldBe 403
            seen shouldBe MISSING_FONT_CACHE
            refused.header(ACAO) shouldBe ""
        }
    }

    /**
     * The client library's two files through the whole application (#1064 A.2, Security): no session, the content type
     * and `nosniff` of their kind (read from a source file's answer, not written here), the re-check rule and the
     * content validator (the walk holds both as well), and the body equal to the join of every listed source fetched
     * one by one from its own URL, each followed by one newline: the engine's files in the engine's order (read from
     * the engine's own object), the runtime's five from the vendor manifest, the six sheets from this test's list.
     */
    @Test
    fun `the client library's two files are the join of their sources fetched one by one, and nothing else answers`() {
        val runtime =
            ObjectMapper()
                .readTree(get("/vendor/design-system/vendor-manifest.json").body())
                .path("datapipelines-dashboard-runtime")
                .path("files")
                .map { "/" + it.asText() }
        val sources =
            mapOf(
                LIBRARY_FILES[0].first to engineNames().map { ENGINE_PREFIX + it } + runtime,
                LIBRARY_FILES[1].first to LIBRARY_SHEETS,
            )
        assertSoftly {
            for ((path, kindOf) in LIBRARY_FILES) {
                val answer = get(path, FOREIGN_ORIGIN)
                val sibling = get(kindOf)
                val parts = sources.getValue(path)
                val bodies = parts.map { it to get(it).also { r -> check(r.statusCode() == 200) { it } }.body() + NEWLINE }
                // Since #1065 the stylesheet is the join passed once through the scoping (D1); the script is the join.
                val expected =
                    if (path == STYLESHEET) {
                        listOf("the six sheets' join, scoped" to scoped(String(bodies.flatMap { it.second.toList() }.toByteArray()), SCOPE))
                    } else {
                        bodies
                    }
                val shown = (CACHE_HEADERS + LIBRARY_HEADERS).joinToString(" ") { "$it=${answer.header(it)}" }
                println("1064|A2|$path|status=${answer.statusCode()} bytes=${answer.body().size} sources=${parts.size} $shown")
                withClue("$path through the application, no session") {
                    answer.statusCode() shouldBe 200
                    answer.header("Content-Type") shouldBe sibling.header("Content-Type")
                    answer.header("X-Content-Type-Options") shouldBe "nosniff"
                    answer.header("Cache-Control") shouldBe RECHECK_HEADER
                    answer.header("ETag") shouldBe "\"${sha256(answer.body())}\""
                    answer.header("Last-Modified") shouldBe ""
                    answer.header(ACAO) shouldBe ""
                    firstDifference(expected, answer.body()) shouldBe null
                }
                val queried = get("$path?v=2&name=../app.css")
                withClue("$path with a query string: the same bytes") {
                    queried.statusCode() shouldBe 200
                    queried.body().toList() shouldBe answer.body().toList()
                }
            }
            for (path in LIBRARY_NEAR_MISSES + "/css/app.css" + "/api/v1/executions") {
                val response = get(path, FOREIGN_ORIGIN)
                println("1064|D4|c|GET $path|${response.originHeaders()}")
                withClue("off the font path: $path") { response.header(ACAO) shouldBe "" }
                if (path in LIBRARY_NEAR_MISSES) withClue("$path is not one of the two names") { response.statusCode() shouldBe 404 }
            }
        }
    }

    /**
     * The theme sheets through the whole application (#1065 A.3, D5, Security): no session; each of the eight names
     * answers the vendor theme sheet fetched from its own URL, scoped under its attribute, with the stylesheet's
     * headers; a query string changes no byte; a name of the pattern outside the list and the forms the firewall or
     * the container refuses answer what the Routes row names, never a stylesheet. The vendor theme paths and the six
     * source sheets answer their classpath bytes, unchanged.
     */
    @Test
    fun `the theme sheets are each vendor theme scoped under its attribute, and the refused forms keep their answers`() {
        val sibling = get(STYLESHEET)
        withClue("theme names (floor $THEME_FLOOR)") { themes.size shouldBeGreaterThanOrEqual THEME_FLOOR }
        assertSoftly {
            for (name in themes) {
                val path = "$THEME_PREFIX$name.css"
                val vendor = get("/$THEMES_DIR$name.css").also { check(it.statusCode() == 200) { name } }.body()
                val expected = scoped(String(vendor), "$SCOPE[data-theme=\"$name\"]")
                val answer = get(path, FOREIGN_ORIGIN)
                val shown = (CACHE_HEADERS + LIBRARY_HEADERS).joinToString(" ") { "$it=${answer.header(it)}" }
                println("1065|A3|theme|$path|status=${answer.statusCode()} bytes=${answer.body().size} $shown")
                withClue("$path through the application, no session") {
                    answer.statusCode() shouldBe 200
                    answer.header("Content-Type") shouldBe sibling.header("Content-Type")
                    answer.header("X-Content-Type-Options") shouldBe "nosniff"
                    answer.header("Cache-Control") shouldBe RECHECK_HEADER
                    answer.header("ETag") shouldBe "\"${sha256(answer.body())}\""
                    answer.header("Last-Modified") shouldBe ""
                    answer.header(ACAO) shouldBe ""
                    answer.body().contentEquals(expected) shouldBe true
                    get(path, "If-None-Match" to answer.header("ETag")).statusCode() shouldBe 304
                    get("$path?v=2&name=../app.css").body().contentEquals(expected) shouldBe true
                }
            }
            for ((path, status) in THEME_REFUSED) {
                val response = get(path)
                println(
                    "1065|A3|theme-refused|$path|status=${response.statusCode()} type=${response.header("Content-Type")} " +
                        "bytes=${response.body().size}",
                )
                withClue("$path: $status, and no stylesheet") {
                    response.statusCode() shouldBe status
                    response.header("Content-Type") shouldNotContain "text/css"
                }
            }
            for (path in themes.map { "/$THEMES_DIR$it.css" } + "/${THEMES_DIR}light.css" + LIBRARY_SHEETS) {
                val response = get(path)
                val file = requireNotNull(javaClass.classLoader.getResource("$STATIC_ROOT$path")) { path }.readBytes()
                println("1065|A3|source-unchanged|$path|status=${response.statusCode()} bytes=${response.body().size} file=${file.size}")
                withClue("$path answers its classpath bytes") {
                    response.statusCode() shouldBe 200
                    response.body().contentEquals(file) shouldBe true
                }
            }
        }
    }

    /** The scoping transform the application serves through (`ScopedStylesheet`, read by reflection), as UTF-8 bytes. */
    private fun scoped(
        source: String,
        scope: String,
    ): ByteArray {
        val type = Class.forName("co.datapipelines.web.ui.ScopedStylesheet")
        val result = type.getMethod("scope", String::class.java, String::class.java).invoke(type.getField("INSTANCE").get(null), source, scope)
        return (result.javaClass.getMethod("getText").invoke(result) as String).toByteArray(Charsets.UTF_8)
    }

    /** An MCP key in a workspace of its own, in the shape the key suites seed (`DependencyGuardsE2eTest`). */
    private fun seedMcpKey(): E2eAuth.SeededKey {
        val workspace = UUID.randomUUID().toString()
        val admin = UUID.randomUUID().toString()
        val identity = UUID.randomUUID().toString()
        val key = E2eAuth.generateKey("scache-mcp-1023")
        sql("INSERT INTO workspaces (id, name, display_name) VALUES ('$workspace', 'scache-${workspace.take(8)}', 'Static cache E2E')")
        sql(
            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                "('$admin', 'scache-$admin@e2e.test', 'SC Admin', 'test', 'scache-$admin', TRUE, FALSE)",
        )
        sql("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$workspace', '$admin', 'workspace_admin')")
        sql(
            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                "('$identity', '${key.id.lowercase()}@keys.invalid', '${key.name}', 'key', '${key.id}', TRUE, FALSE, 'service')",
        )
        sql(
            "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role) VALUES " +
                "('${key.id}', '$identity', '$admin', '${key.name}', '${key.hash}', '$workspace', 'mcp', 'workspace_admin')",
        )
        return key
    }

    private fun sql(statement: String) {
        val postgres = SharedE2e.postgres
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
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
    ): HttpResponse<ByteArray> = send("GET", path, *headers)

    private fun send(
        method: String,
        path: String,
        vararg headers: Pair<String, String>,
    ): HttpResponse<ByteArray> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(30))
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

    /** Null when [actual] is every part's bytes in order and nothing else; otherwise the first part whose bytes differ. */
    private fun firstDifference(
        parts: List<Pair<String, ByteArray>>,
        actual: ByteArray,
    ): String? {
        var offset = 0
        for ((path, bytes) in parts) {
            val end = offset + bytes.size
            if (end > actual.size || !actual.copyOfRange(offset, end).contentEquals(bytes)) return "the first differing source is $path"
            offset = end
        }
        return if (offset == actual.size) null else "${actual.size - offset} bytes after the last source"
    }

    private fun HttpResponse<*>.originHeaders(): String = "status=${statusCode()} $ACAO=${header(ACAO)} $ACAC=${header(ACAC)}"

    /** #1064 D4: exactly one `Access-Control-Allow-Origin: *`, and never a credentials header beside it. */
    private fun HttpResponse<*>.shouldAllowAnyOrigin() {
        headers().allValues(ACAO) shouldBe listOf("*")
        header(ACAC) shouldBe ""
    }

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

    private data class Near(
        val label: String,
        val path: String,
        val status: Int,
        val cache: List<String>,
        val method: String = "GET",
        val headers: List<Pair<String, String>> = emptyList(),
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
        const val FONT_PREFIX = "/vendor/fonts/"
        const val ACAO = "Access-Control-Allow-Origin"
        const val ACAC = "Access-Control-Allow-Credentials"
        val FOREIGN_ORIGIN = "Origin" to "https://host.example"
        val NEWLINE = "\n".toByteArray()

        /** The client library's two files (#1064 D1), each with a source file of its kind whose content type it shares. */
        val LIBRARY_FILES = listOf("/js/datapipelines.js" to "/js/datapipelines-dashboard.js", "/css/datapipelines.css" to "/css/app.css")

        /** The stylesheet's six sources in the example's order (#1064 D2), written here and not read from the class. */
        val LIBRARY_SHEETS =
            listOf(
                "/vendor/design-system/tokens.css",
                "/vendor/design-system/themes/light.css",
                "/vendor/design-system/base.css",
                "/vendor/design-system/primitives.css",
                "/css/app.css",
                "/css/dashboards.css",
            )
        const val STYLESHEET = "/css/datapipelines.css"

        /** The scope selector of #1065 D1, written here: the class the runtime sets on the two elements. */
        const val SCOPE = ".dp-scope"
        const val THEME_PREFIX = "/css/datapipelines-theme-"
        const val THEMES_DIR = "vendor/design-system/themes/"
        const val THEME_FLOOR = 2

        /** The five refused theme paths of #1065's Routes row, each with the status it answers. */
        val THEME_REFUSED =
            listOf(
                "/css/datapipelines-theme-light.css" to 404,
                "/css/datapipelines-theme-nope.css" to 404,
                "/css/../css/datapipelines-theme-dark.css" to 400,
                "/css/%2e%2e/css/datapipelines-theme-dark.css" to 400,
                "/css/datapipelines-theme-dark.css%2f..%2fapp.css" to 400,
            )
        val LIBRARY_HEADERS = listOf("Content-Type", "X-Content-Type-Options", ACAO)
        val LIBRARY_NEAR_MISSES = listOf("/js/datapipelines.js.map", "/css/datapipelines.min.css")
        val FONT_DIRECTIVES = setOf("max-age=31536000", "public", "immutable")
        val STATIC_PREFIXES = listOf("/css/", "/js/", "/vendor/")
        val OTHER_STATIC_ROOTS = listOf("META-INF/resources", "resources", "public")
        val CACHE_HEADERS = listOf("Cache-Control", "Pragma", "Expires", "ETag", "Last-Modified", "Vary")

        /** `PublicPage` and the docs controller: `CacheControl.maxAge(5, MINUTES).cachePublic()`. */
        const val PUBLIC_PAGE_WINDOW = "max-age=300, public"

        /** The security chain's default, which every answer outside the ruling keeps. */
        private const val DEFAULT_NO_STORE = "no-cache, no-store, max-age=0, must-revalidate"

        /** The chain's two legacy cache headers, read on the base before round 2; kept on every answer but a font's. */
        const val CHAIN_PRAGMA = "no-cache"
        const val CHAIN_EXPIRES = "0"

        /** `Cache-Control`, `Pragma`, `Expires` of one answer, in that order ("" = absent). */
        val CHAIN_HEADER_NAMES = listOf("Cache-Control", "Pragma", "Expires")
        val CHAIN_CACHE = listOf(DEFAULT_NO_STORE, CHAIN_PRAGMA, CHAIN_EXPIRES)

        /**
         * An error answer under the font prefix (a missing name, an unsatisfiable range): the chain's three, byte for
         * byte what the base answered before round 2 (`base-before.log`). The chain's matcher judges the path, not the
         * file, so `FontErrorCacheFilter` sets them when the status is an error (round 3, #1028).
         */
        val MISSING_FONT_CACHE = CHAIN_CACHE

        /** A first byte past the end of every font file: the handler answers 416 after writing its font rule. */
        const val UNSATISFIABLE_RANGE = "bytes=999999999-"

        const val API_KEY_HEADER = "DP-API-Key"

        /** A request the container refuses before any filter (an encoded slash; read on the base and unchanged): none. */
        val REFUSED_CACHE = listOf("", "", "")

        /**
         * A request the security chain's firewall refuses under the font prefix: none of the three on the base and in
         * round 2 (the firewall answers before the chain's writer), the three since round 3, because
         * `FontErrorCacheFilter` wraps the response ahead of the chain and the 400 is an error answer.
         */
        val FIREWALL_ON_FONT_PATH_CACHE = CHAIN_CACHE

        // Row 6's values the brief leaves to the base, read on base 01ec928b0 before the code change (2026-11-17):
        // a signed-out page request is redirected to the login page; the icon and robots.txt carry the default.
        const val DASHBOARD_SIGNED_OUT_STATUS = 302
        const val DASHBOARD_SIGNED_OUT_LOCATION = "/login"
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
