package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/** #726: every declared task tone paints both its background and ink with design tokens. */
class OverviewTaskToneCssTest {
    private val css =
        PathMatchingResourcePatternResolver(javaClass.classLoader)
            .getResource("classpath:static/css/app.css")
            .inputStream
            .use { it.readBytes().decodeToString() }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")

    @Test
    fun `every task tone has a token-backed background and ink rule`() {
        OverviewPage.TaskTone.entries.size shouldBeGreaterThanOrEqual 3
        OverviewPage.TaskTone.entries.forEach { tone ->
            withClue("task tone=${tone.wire}") {
                val selector = ".app-overview-task-icon[data-tone=\"${tone.wire}\"]"
                Regex(
                    """${Regex.escape(selector)}\s*\{\s*background:\s*var\(--[a-z0-9-]+\);\s*color:\s*var\(--[a-z0-9-]+\);\s*}""",
                ).containsMatchIn(css) shouldBe true
            }
        }
    }
}
