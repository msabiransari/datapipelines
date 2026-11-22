package co.datapipelines.web.ui

import co.datapipelines.web.TestRepoFiles
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.web.HttpRequestHandler
import org.springframework.web.context.support.StaticWebApplicationContext
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

/**
 * One sheet per theme, switched by ONE attribute (#1065 D5, A.3, Security): `/css/datapipelines-theme-<name>.css` for
 * the closed list of names, each the vendor theme sheet scoped under `.dp-scope[data-theme="<name>"]`, served by the
 * mapping the framework's own registry builds from [ClientLibraryConfig] (the pattern, the path the handler is given
 * and the closed list are the production ones). The answers through the whole application, with the firewall's
 * refusals, are `StaticCacheHeadersE2eTest`'s.
 */
class ClientLibraryThemesTest {
    private val mapping: SimpleUrlHandlerMapping by lazy {
        val servletContext = MockServletContext()
        val context =
            StaticWebApplicationContext().apply {
                setServletContext(servletContext)
                refresh()
            }
        val registry = ExposedRegistry(context, servletContext)
        ClientLibraryConfig().addResourceHandlers(registry)
        (registry.mapping() as SimpleUrlHandlerMapping).apply { applicationContext = context }
    }

    @Test
    fun `the names are the theme sheets of the design system's directory, light left out`() {
        val files =
            File(repoRoot(), THEMES_DIR)
                .listFiles { file -> file.isFile && file.extension == "css" }
                .orEmpty()
                .map { it.nameWithoutExtension }
                .sorted()
        println("1065|D5|themes|files=$files list=${ClientLibrary.THEMES}")
        withClue("theme files (floor $THEME_FLOOR), light among them") {
            files.size shouldBeGreaterThanOrEqual THEME_FLOOR
            ("light" in files) shouldBe true
        }
        ClientLibrary.THEMES.sorted() shouldBe files - "light"
    }

    @Test
    fun `each theme URL answers the vendor sheet scoped under its attribute, with the re-check rule and the content validator`() {
        assertSoftly {
            for (name in ClientLibrary.THEMES) {
                val path = "/css/datapipelines-theme-$name.css"
                val vendor = TestRepoFiles.read("$THEMES_DIR/$name.css")
                val expected = ScopedStylesheet.scope(vendor, ".dp-scope[data-theme=\"$name\"]").text.toByteArray(Charsets.UTF_8)
                val response = get(path).shouldNotBeNull()
                val etag = "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected)) + "\""
                println(
                    "1065|A3|handler|$path|status=${response.status} bytes=${response.contentAsByteArray.size} etag=${response.getHeader(
                        "ETag",
                    )}",
                )
                withClue(path) {
                    response.status shouldBe 200
                    response.contentAsByteArray.contentEquals(expected) shouldBe true
                    response.getHeader("Cache-Control") shouldBe "no-cache"
                    response.getHeader("ETag") shouldBe etag
                    response.getHeader("Last-Modified").shouldBeNull()
                    get(path, "If-None-Match" to etag).shouldNotBeNull().status shouldBe 304
                    get(path, query = "v=2&name=../app.css").shouldNotBeNull().contentAsByteArray.contentEquals(expected) shouldBe true
                }
            }
        }
    }

    @Test
    fun `a name of the pattern that is not in the list reaches the theme handler and finds no resource`() {
        assertSoftly {
            for (path in listOf("/css/datapipelines-theme-light.css", "/css/datapipelines-theme-nope.css")) {
                val refused = shouldThrow<NoResourceFoundException> { get(path) }
                println("1065|A3|handler|refused|$path|${refused.javaClass.simpleName}: ${refused.message}")
            }
        }
    }

    @Test
    fun `a name outside the pattern is not the theme handler's`() {
        assertSoftly {
            for (path in NEAR_MISSES) {
                val chain = mapping.getHandler(MockHttpServletRequest("GET", path))
                println("1065|A3|handler|near-miss|$path|handler=${chain?.handler}")
                withClue("$path is no name of the library's three patterns") { chain.shouldBeNull() }
            }
        }
    }

    @Test
    fun `each theme sheet is built once - two reads return the same bytes and every theme source is read once`() {
        val reads = mutableMapOf<String, Int>()
        val counting: (String) -> String = { name ->
            reads.merge(name, 1, Int::plus)
            ":root { --x: 1px }"
        }
        val library = ClientLibrary(emptyList(), counting, counting)
        val first = library.theme("dark")
        library.theme("dark") shouldBeSameInstanceAs first
        library.theme("ocean") shouldBeSameInstanceAs library.theme("ocean")
        println("1065|A3|built-once|reads=$reads")
        withClue("every theme source read exactly once across the reads (the nine texts are built once and kept)") {
            reads.keys shouldBe ClientLibrary.THEMES.map { "vendor/design-system/themes/$it.css" }.toSet()
            reads.filterValues { it != 1 } shouldBe emptyMap()
        }
        String(first, Charsets.UTF_8) shouldBe ".dp-scope[data-theme=\"dark\"] {\n& { --x: 1px }\n}\n"
        shouldThrow<IllegalArgumentException> { library.theme("light") }
    }

    /** Looks [path] up through the mapping and lets its handler answer; null when no handler takes it. */
    private fun get(
        path: String,
        vararg headers: Pair<String, String>,
        query: String? = null,
    ): MockHttpServletResponse? {
        val request = MockHttpServletRequest("GET", path).apply { queryString = query }
        headers.forEach { (name, value) -> request.addHeader(name, value) }
        val chain = mapping.getHandler(request) ?: return null
        val response = MockHttpServletResponse()
        chain.interceptorList.forEach { it.preHandle(request, response, chain.handler) }
        (chain.handler as HttpRequestHandler).handleRequest(request, response)
        return response
    }

    private fun repoRoot(): File =
        requireNotNull(
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").exists() },
        ) { "no settings.gradle.kts above ${System.getProperty("user.dir")}" }

    private class ExposedRegistry(
        context: StaticWebApplicationContext,
        servletContext: MockServletContext,
    ) : ResourceHandlerRegistry(context, servletContext) {
        fun mapping() = handlerMapping
    }

    private companion object {
        const val THEMES_DIR = "modules/web/src/main/resources/static/vendor/design-system/themes"
        const val THEME_FLOOR = 2
        val NEAR_MISSES =
            listOf(
                "/css/datapipelines-theme-Dark.css",
                "/css/datapipelines-theme-.css",
                "/css/datapipelines-theme-dark.css.map",
                "/css/datapipelines-theme-dark-2.css",
                "/js/datapipelines-theme-dark.css",
                "/css/themes/datapipelines-theme-dark.css",
            )
    }
}
