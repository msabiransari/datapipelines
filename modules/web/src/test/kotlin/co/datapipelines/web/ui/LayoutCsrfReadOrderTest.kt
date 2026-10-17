package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * #554 merge follow-up — every layout reads the CSRF token before it writes the head.
 *
 * Spring Security's token is DEFERRED: the `dp_csrf` cookie is written the first time a render
 * reads `_csrf`, and a cookie can only be set before the response commits. The layouts read it
 * on `<body>`, so the cookie depended on the rendered head staying inside Tomcat's 8 KB response
 * buffer. #554's head comments crossed it: `/settings` answered 200 with no `dp_csrf`, a logout
 * POST was refused 403, and Gate A on the batch went red in three integration classes
 * (JarSmokeE2eTest, LocalLoginE2eTest, WriteSurfaceStampingE2eTest), on every page the default
 * layout renders. The read now sits on `<html>` (`th:with`, evaluated before the start tag is
 * written), so no head size can commit the response first.
 *
 * Read from the template source: the property is WHERE the read sits relative to the head, which
 * a MockMvc render cannot observe (it never commits a response).
 */
class LayoutCsrfReadOrderTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    /** Template path → its source, for every template that reads the CSRF token. */
    private val readers: Map<String, String> =
        resolver
            .getResources("classpath*:templates/**/*.html")
            .toList()
            .filter { it.filename != null }
            .map { it.uri.path.substringAfter("/templates/") to it.inputStream.readBytes().decodeToString() }
            .filter { (_, html) -> CSRF_READ in html }
            .toMap()

    @Test
    fun `both layouts read the token`() {
        readers.keys shouldContainAll LAYOUTS
    }

    @Test
    fun `every read sits on the html start tag, before the head`() {
        readers.forEach { (path, html) ->
            val htmlTag = html.indexOf("<html")
            val htmlTagEnd = html.indexOf('>', htmlTag)
            val head = html.indexOf("<head>")
            withClue(path) {
                htmlTag shouldBeGreaterThan -1
                head shouldBeGreaterThan htmlTagEnd
                Regex(Regex.escape(CSRF_READ)).findAll(html).count() shouldBe 1
                html.indexOf(CSRF_READ) shouldBeGreaterThan htmlTag
                html.indexOf(CSRF_READ) shouldBeLessThan htmlTagEnd
            }
        }
    }

    companion object {
        private val LAYOUTS = listOf("layouts/default.html", "layouts/auth.html")

        /** The expression that materialises the deferred token. */
        private const val CSRF_READ = "\${_csrf"
    }
}
