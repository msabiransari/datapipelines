package co.datapipelines.web.ui

import co.datapipelines.auth.SecurityHeaders
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * The font path's error answers stay not storable (#1023 round 3): the wrapper by itself, its three values against
 * the chain's own writer, and the filter's reach (the font handler's predicate, nothing else).
 */
class FontErrorCacheFilterTest {
    private enum class Call { SET_STATUS, SEND_ERROR, SEND_ERROR_WITH_MESSAGE }

    private fun HttpServletResponse.call(
        call: Call,
        status: Int,
    ) = when (call) {
        Call.SET_STATUS -> setStatus(status)
        Call.SEND_ERROR -> sendError(status)
        Call.SEND_ERROR_WITH_MESSAGE -> sendError(status, "refused")
    }

    /** A response the font handler has prepared: the one-year rule, nothing else of the chain's. */
    private fun preparedFontAnswer(): MockHttpServletResponse = MockHttpServletResponse().apply { setHeader("Cache-Control", FONT_RULE) }

    private fun MockHttpServletResponse.cacheHeaders(): List<String?> =
        CHAIN_HEADER_NAMES.map { name -> getHeaderValues(name).joinToString(", ").ifEmpty { null } }

    @Test
    fun `a status of 400 or above, set or sent, replaces the font rule with the chain's three`() {
        assertSoftly {
            for (call in Call.entries) {
                for (status in ERROR_STATUSES) {
                    val target = preparedFontAnswer()
                    FontErrorCacheFilter.NotStorableOnError(target).call(call, status)
                    withClue("$call($status)") {
                        target.status shouldBe status
                        target.cacheHeaders() shouldBe NOT_STORABLE
                    }
                }
            }
        }
    }

    @Test
    fun `a status below 400 touches nothing - the font rule stays alone`() {
        assertSoftly {
            for (status in SUCCESS_STATUSES) {
                val target = preparedFontAnswer()
                FontErrorCacheFilter.NotStorableOnError(target).call(Call.SET_STATUS, status)
                withClue("SET_STATUS($status)") {
                    target.status shouldBe status
                    target.cacheHeaders() shouldBe listOf(FONT_RULE, null, null)
                }
            }
            // sendError below 400 is not an error answer the handler gives, but the wrapper must still not act on it.
            for (call in listOf(Call.SEND_ERROR, Call.SEND_ERROR_WITH_MESSAGE)) {
                val target = preparedFontAnswer()
                FontErrorCacheFilter.NotStorableOnError(target).call(call, LAST_NON_ERROR)
                withClue("$call($LAST_NON_ERROR)") { target.cacheHeaders() shouldBe listOf(FONT_RULE, null, null) }
            }
        }
    }

    @Test
    fun `the three values are what the chain's own writer answers on another path`() {
        val request = MockHttpServletRequest("GET", "/css/app.css")
        val response = MockHttpServletResponse()
        SecurityHeaders.cacheControlWriter().writeHeaders(request, response)
        println("1023|r3|chain-writer|/css/app.css|${response.cacheHeaders()}")
        response.cacheHeaders() shouldBe NOT_STORABLE
    }

    @Test
    fun `the filter wraps exactly the requests the font handler answers`() {
        val wrapped =
            listOf(
                "GET" to "/vendor/fonts/noto-sans/noto-sans-latin-wght-normal.woff2",
                "HEAD" to "/vendor/fonts/noto-sans/noto-sans-latin-wght-normal.woff2",
                "GET" to "/vendor/fonts/no-such-font-1023.woff2",
                "GET" to "/vendor/fonts/noto-sans",
            )
        val passed =
            listOf(
                "GET" to "/css/app.css",
                "GET" to "/api/v1/executions",
                "GET" to "/vendor/fonts",
                "GET" to "/vendor/fonts/",
                "GET" to "/vendor/fontsX/x.woff2",
                "GET" to "/api/vendor/fonts/x.woff2",
                "POST" to "/vendor/fonts/noto-sans/noto-sans-latin-wght-normal.woff2",
                "OPTIONS" to "/vendor/fonts/noto-sans/noto-sans-latin-wght-normal.woff2",
            )
        assertSoftly {
            for ((method, path) in wrapped) {
                val response = MockHttpServletResponse()
                withClue("$method $path is wrapped") {
                    seenBy(method, path, response).shouldBeInstanceOf<FontErrorCacheFilter.NotStorableOnError>()
                }
            }
            for ((method, path) in passed) {
                val response = MockHttpServletResponse()
                withClue("$method $path passes unwrapped") { seenBy(method, path, response) shouldBeSameInstanceAs response }
            }
        }
    }

    @Test
    fun `an error outside the font path keeps whatever the response carried`() {
        val response = MockHttpServletResponse()
        FontErrorCacheFilter().doFilter(
            MockHttpServletRequest("GET", "/css/missing.css"),
            response,
            FilterChain { _, answer -> (answer as HttpServletResponse).sendError(HttpServletResponse.SC_NOT_FOUND) },
        )
        response.cacheHeaders() shouldBe listOf(null, null, null)
    }

    /** The response object the rest of the chain receives for [method] [path]. */
    private fun seenBy(
        method: String,
        path: String,
        response: MockHttpServletResponse,
    ): ServletResponse {
        var seen: ServletResponse? = null
        FontErrorCacheFilter().doFilter(MockHttpServletRequest(method, path), response, FilterChain { _, answer -> seen = answer })
        return requireNotNull(seen) { "the filter did not continue the chain for $method $path" }
    }

    private companion object {
        const val FONT_RULE = "max-age=31536000, public, immutable"
        const val LAST_NON_ERROR = 399
        val CHAIN_HEADER_NAMES = listOf("Cache-Control", "Pragma", "Expires")
        val NOT_STORABLE =
            listOf(FontErrorCacheFilter.CACHE_CONTROL, FontErrorCacheFilter.PRAGMA, FontErrorCacheFilter.EXPIRES)
        val ERROR_STATUSES = listOf(400, 404, 412, 416, 500)
        val SUCCESS_STATUSES = listOf(200, 206, 304, LAST_NON_ERROR)
    }
}
