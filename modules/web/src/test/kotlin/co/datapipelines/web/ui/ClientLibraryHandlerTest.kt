package co.datapipelines.web.ui

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.web.HttpRequestHandler
import org.springframework.web.context.support.StaticWebApplicationContext
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The client library's two handlers by themselves (#1064 D1, G6): the mapping the framework's own registry builds from
 * [ClientLibraryConfig], asked through the framework's own lookup, so the patterns, the path the handler is given and
 * the closed list of names are the production ones. The headers through the whole application are
 * `StaticCacheHeadersE2eTest`'s.
 */
class ClientLibraryHandlerTest {
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

    /** #1065 D5 adds the theme sheets' pattern, whose names are a closed list (`ClientLibraryThemesTest`). */
    @Test
    fun `the two patterns name the two files exactly, beside the theme sheets' pattern`() {
        mapping.urlMap.keys.sorted() shouldBe
            listOf("/css/{name:datapipelines-theme-[a-z]+\\.css}", "/css/{name:datapipelines\\.css}", "/js/{name:datapipelines\\.js}")
    }

    @Test
    fun `each name answers its joined bytes with the content validator and the re-check rule`() {
        assertSoftly {
            for ((path, bytes) in listOf(
                ClientLibrary.SCRIPT_PATH to ClientLibraryConfig.library.script,
                ClientLibrary.STYLESHEET_PATH to ClientLibraryConfig.library.stylesheet,
            )) {
                val response = get(path).shouldNotBeNull()
                val etag = "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + "\""
                val size = response.contentAsByteArray.size
                println("1064|handler|$path|status=${response.status} bytes=$size etag=${response.getHeader("ETag")}")
                withClue(path) {
                    response.status shouldBe 200
                    response.contentAsByteArray.contentEquals(bytes) shouldBe true
                    response.getHeader("Cache-Control") shouldBe "no-cache"
                    response.getHeader("ETag") shouldBe etag
                    response.getHeader("Last-Modified").shouldBeNull()
                    get(path, "If-None-Match" to etag).shouldNotBeNull().status shouldBe 304
                }
            }
        }
    }

    @Test
    fun `a near-miss name is not the library's - no handler of the two takes it`() {
        assertSoftly {
            for (path in NEAR_MISSES) {
                val chain = mapping.getHandler(MockHttpServletRequest("GET", path))
                println("1064|handler|near-miss|$path|handler=${chain?.handler}")
                withClue("$path is not one of the two names") { chain.shouldBeNull() }
            }
        }
    }

    @Test
    fun `a query string changes no byte of the answer`() {
        val plain = get(ClientLibrary.SCRIPT_PATH).shouldNotBeNull()
        val queried = get(ClientLibrary.SCRIPT_PATH, query = "v=2&name=../app.css").shouldNotBeNull()
        queried.status shouldBe 200
        queried.contentAsByteArray.contentEquals(plain.contentAsByteArray) shouldBe true
    }

    /** Looks [path] up through the mapping and lets its handler answer; null when no handler of the two takes it. */
    private fun get(
        path: String,
        vararg headers: Pair<String, String>,
        query: String? = null,
    ): MockHttpServletResponse? {
        val request = MockHttpServletRequest("GET", path).apply { queryString = query }
        headers.forEach { (name, value) -> request.addHeader(name, value) }
        val chain = mapping.getHandler(request) ?: return null
        val response = MockHttpServletResponse()
        // The mapping's own interceptors expose the path within the mapping, as the dispatcher runs them.
        chain.interceptorList.forEach { it.preHandle(request, response, chain.handler) }
        (chain.handler as HttpRequestHandler).handleRequest(request, response)
        return response
    }

    private class ExposedRegistry(
        context: StaticWebApplicationContext,
        servletContext: MockServletContext,
    ) : ResourceHandlerRegistry(context, servletContext) {
        fun mapping() = handlerMapping
    }

    private companion object {
        val NEAR_MISSES =
            listOf(
                "/js/datapipelines.js.map",
                "/js/datapipelines.min.js",
                "/js/datapipelinesXjs",
                "/js/datapipelines.jsx",
                "/css/datapipelines.min.css",
                "/css/datapipelines.js",
                "/js/datapipelines.css",
                "/js/js-rendered/datapipelines.js",
                "/datapipelines.js",
            )
    }
}
