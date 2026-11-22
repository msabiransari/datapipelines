package co.datapipelines.web.ui

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HttpServletBean

/**
 * Any origin may use a font file, and only a font file's SUCCESSFUL answer says so (#1064 D4): the filter by itself,
 * driven by a stand-in handler that answers the way the framework's resource handler does in each outcome — its
 * success headers first, then the body, a 304 with none, or an error sent after the success headers (the 416).
 * The same outcomes through the whole application are `StaticCacheHeadersE2eTest`'s.
 */
class FontAnyOriginTest {
    private enum class Outcome(
        val status: Int,
    ) {
        BODY(200),
        PARTIAL(206),
        NOT_MODIFIED(304),
        HEAD_FLUSHED(200),
        MISSING(404),
        UNSATISFIABLE(416),
        REFUSED_WITH_BODY(403),
    }

    /** What the framework's handler (or the chain, for the 403) does for [outcome], in its order. */
    private fun answer(
        outcome: Outcome,
        response: HttpServletResponse,
    ) {
        response.setHeader("Cache-Control", "max-age=31536000, public, immutable")
        when (outcome) {
            Outcome.BODY -> response.outputStream.write(BYTES)
            Outcome.PARTIAL -> {
                response.status = 206
                response.outputStream.write(BYTES, 0, 4)
            }
            Outcome.NOT_MODIFIED -> response.status = 304
            Outcome.HEAD_FLUSHED -> response.flushBuffer()
            Outcome.MISSING -> response.sendError(404)
            Outcome.UNSATISFIABLE -> {
                response.status = 206
                response.setHeader("Content-Range", "bytes */${BYTES.size}")
                response.sendError(416)
            }
            Outcome.REFUSED_WITH_BODY -> {
                response.status = 403
                response.writer.write("{\"error\":{}}")
            }
        }
    }

    private fun run(
        method: String,
        path: String,
        outcome: Outcome,
        origin: String? = FOREIGN,
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest(method, path).apply { if (origin != null) addHeader("Origin", origin) }
        val response = MockHttpServletResponse()
        val servlet =
            object : HttpServletBean() {
                override fun service(
                    req: jakarta.servlet.ServletRequest,
                    res: jakarta.servlet.ServletResponse,
                ) = answer(outcome, res as HttpServletResponse)
            }
        FontErrorCacheFilter().doFilter(request, response, MockFilterChain(servlet))
        return response
    }

    @Test
    fun `a successful answer of a font file allows any origin, with no credentials header`() {
        assertSoftly {
            for (method in listOf("GET", "HEAD")) {
                for (outcome in listOf(Outcome.BODY, Outcome.PARTIAL, Outcome.NOT_MODIFIED, Outcome.HEAD_FLUSHED)) {
                    for (origin in listOf(FOREIGN, null)) {
                        val response = run(method, FONT, outcome, origin)
                        println("1064|D4|unit|$method $outcome origin=$origin|status=${response.status} acao=${response.getHeader(ACAO)}")
                        withClue("$method $outcome, Origin=$origin") {
                            response.status shouldBe outcome.status
                            response.getHeaderValues(ACAO) shouldBe listOf("*")
                            response.getHeader("Access-Control-Allow-Credentials") shouldBe null
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `an answer of 400 or above on the font path never allows an origin - the 416 sent after the success headers included`() {
        assertSoftly {
            for (method in listOf("GET", "HEAD")) {
                for (outcome in listOf(Outcome.MISSING, Outcome.UNSATISFIABLE, Outcome.REFUSED_WITH_BODY)) {
                    val response = run(method, FONT, outcome)
                    println("1064|D4|unit|$method $outcome|status=${response.status} acao=${response.getHeader(ACAO)}")
                    withClue("$method $outcome") {
                        response.status shouldBe outcome.status
                        response.getHeader(ACAO) shouldBe null
                    }
                }
            }
        }
    }

    @Test
    fun `off the font path no answer allows an origin`() {
        assertSoftly {
            for (path in listOf("/css/app.css", ClientLibrary.SCRIPT_PATH, ClientLibrary.STYLESHEET_PATH, "/vendor/fonts/", "/api/v1/x")) {
                val response = run("GET", path, Outcome.BODY)
                withClue(path) { response.getHeader(ACAO) shouldBe null }
            }
        }
    }

    private companion object {
        const val ACAO = "Access-Control-Allow-Origin"
        const val FONT = "/vendor/fonts/noto-sans/noto-sans-latin-wght-normal.woff2"
        const val FOREIGN = "https://host.example"
        val BYTES = ByteArray(64) { it.toByte() }
    }
}
