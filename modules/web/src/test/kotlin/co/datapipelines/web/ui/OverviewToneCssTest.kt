package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/** #642: every frame tone has a token bridge and both dot sites plus a bar fill. */
class OverviewToneCssTest {
    private val css =
        PathMatchingResourcePatternResolver(javaClass.classLoader)
            .getResource("classpath:static/css/app.css")
            .inputStream
            .use { it.readBytes().decodeToString() }

    @Test
    fun `seven tone words bridge tokens in root and paint dots and bars`() {
        println("Overview tones checked: ${TONES.joinToString()}")
        TONES.forEach { word ->
            withClue("tone=$word") {
                val token = "--overview-$word"
                val roots = Regex(""":root\s*\{([^}]+)}""").findAll(css).map { it.groupValues[1] }.toList()
                roots.any { Regex("""${Regex.escape(token)}:\s*var\(--[a-z0-9-]+\);""").containsMatchIn(it) } shouldBe true
                val dot = ".app-overview-dot[data-tone=\"$word\"]"
                val parent = "[data-tone=\"$word\"] > .app-overview-dot"
                Regex("""${Regex.escape(dot)},\s*${Regex.escape(parent)}\s*\{\s*background: var\($token\);\s*}""")
                    .containsMatchIn(css) shouldBe true
                val bar = ".app-overview-bar[data-tone=\"$word\"] .app-overview-bar-fill"
                css shouldContain "$bar { fill: var($token); }"
            }
        }
        Regex("""\.app-overview-dot\[data-tone="[a-z-]+"]""").findAll(css).count() shouldBeGreaterThanOrEqual TONES.size
        Regex("""\.app-overview-dot\s*\{[^}]*background: var\(--overview-discarded\);""")
            .containsMatchIn(css) shouldBe true
    }

    private companion object {
        val TONES = listOf("draft", "released", "mixed", "discarded", "success", "warning", "danger")
    }
}
