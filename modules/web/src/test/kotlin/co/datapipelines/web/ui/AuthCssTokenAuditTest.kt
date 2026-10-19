package co.datapipelines.web.ui

import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/** #619 — ceremony stylesheet holds the same no-literal-colour contract as app.css. */
class AuthCssTokenAuditTest {
    private val css =
        PathMatchingResourcePatternResolver(javaClass.classLoader)
            .getResource("classpath:static/css/auth.css")
            .inputStream
            .readBytes()
            .decodeToString()

    @Test
    fun `auth css carries no literal colour`() {
        val violations =
            css
                .lines()
                .withIndex()
                .filter { (_, line) -> LITERAL_COLOUR.containsMatchIn(line) }
                // No graph fallbacks or theme swatches live on the ceremony page.
                .filterNot { (_, line) -> line.trimStart().startsWith("*") || line.trimStart().startsWith("/*") }
                .map { (index, line) -> "auth.css:${index + 1} carries a literal colour: ${line.trim()}" }
        violations shouldBe emptyList()
    }

    @Test
    fun `the token sweep is non vacuous`() {
        val reads = Regex("var\\(--").findAll(css).count()
        println("619|auth-css|token reads=$reads")
        reads shouldBeGreaterThanOrEqual MIN_TOKEN_READS
    }

    private companion object {
        val LITERAL_COLOUR = Regex("""(?:#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?\b|\b(?:rgba?|hsla?)\()""")
        const val MIN_TOKEN_READS = 40
    }
}
