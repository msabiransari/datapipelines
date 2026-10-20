package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/** #635 — editor colours have token names, and every token read has a declaration (#502). */
class EditorCssTokenAuditTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)
    private val sheets =
        FLOORS.keys.associateWith { name ->
            resolver.getResource("classpath:static/css/$name").inputStream.use { it.readBytes().decodeToString() }
        }

    private fun String.withoutComments(): String =
        replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)) { comment ->
            comment.value.replace(Regex("[^\\n]"), " ")
        }

    @Test
    fun `the three editor sheets carry no literal colour`() {
        val violations =
            sheets.flatMap { (name, css) ->
                css
                    .withoutComments()
                    .lines()
                    .withIndex()
                    .filter { (_, line) -> LITERAL_COLOUR.containsMatchIn(line) }
                    .map { (index, line) -> "$name:${index + 1} carries a literal colour: ${line.trim()}" }
            }
        withClue(violations.joinToString("\n")) { violations shouldBe emptyList() }
    }

    @Test
    fun `every editor token read has a declaration in first party or vendored css`() {
        val resources = DECLARATION_PATHS.flatMap { resolver.getResources(it).toList() }
        resources.size shouldBeGreaterThanOrEqual MIN_DECLARATION_FILES
        val declared =
            resources
                .flatMap { resource ->
                    val css = resource.inputStream.use { it.readBytes().decodeToString() }.withoutComments()
                    DECLARATION.findAll(css).map { it.groupValues[1] }.toList()
                }.toSet()
        println("635|declaration search=${DECLARATION_PATHS.joinToString()}|files=${resources.size}")
        val missing =
            sheets.flatMap { (name, css) ->
                css.withoutComments().lines().withIndex().flatMap { (index, line) ->
                    TOKEN_READ
                        .findAll(line)
                        .map { it.groupValues[1] }
                        .filterNot { it in declared }
                        .map { "$name:${index + 1} undeclared token $it" }
                        .toList()
                }
            }
        withClue(missing.joinToString("\n")) { missing shouldBe emptyList() }
    }

    @Test
    fun `each editor sheet sweep is non vacuous`() {
        sheets.forEach { (name, css) ->
            val floor = FLOORS.getValue(name)
            val reads = TOKEN_READ.findAll(css.withoutComments()).count()
            println("635|$name|reads=$reads|lines=${css.lines().size}")
            withClue(name) {
                reads shouldBeGreaterThanOrEqual floor.reads
                css.lines().size shouldBeGreaterThanOrEqual floor.lines
            }
        }
    }

    private data class Floor(
        val reads: Int,
        val lines: Int,
    )

    private companion object {
        // Empty allowlist: every colour in these sheets resolves through tokens.
        val LITERAL_COLOUR = Regex("""(?:#[0-9a-fA-F]{3,8}\b|\b(?:rgba?|hsla?|hwb|lab|lch|oklab|oklch|color)\()""")
        val TOKEN_READ = Regex("""var\(\s*(--[\w-]+)""")
        val DECLARATION = Regex("""(--[\w-]+)\s*:""")
        val DECLARATION_PATHS = listOf("classpath*:static/css/**/*.css", "classpath*:static/vendor/design-system/**/*.css")
        const val MIN_DECLARATION_FILES = 26
        val FLOORS =
            mapOf(
                "pipeline-editor.css" to Floor(reads = 480, lines = 2300),
                "template-editor.css" to Floor(reads = 45, lines = 220),
                "template-workspace.css" to Floor(reads = 50, lines = 230),
            )
    }
}
