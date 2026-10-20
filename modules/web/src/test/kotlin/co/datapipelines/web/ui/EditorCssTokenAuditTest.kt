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
    fun `the three editor sheets carry no unallowlisted literal colour`() {
        val violations =
            sheets.flatMap { (name, css) ->
                css
                    .withoutComments()
                    .lines()
                    .withIndex()
                    .filter { (_, line) -> LITERAL_COLOUR.containsMatchIn(line) }
                    .filterNot { (_, line) -> name == "pipeline-editor.css" && line.trim() in LEGACY_LITERALS }
                    .map { (index, line) -> "$name:${index + 1} carries a literal colour: ${line.trim()}" }
            }
        withClue(violations.joinToString("\n")) { violations shouldBe emptyList() }
        val editor =
            sheets
                .getValue("pipeline-editor.css")
                .withoutComments()
                .lines()
                .map { it.trim() }
        LEGACY_LITERALS.forEach { (line, allowance) ->
            withClue("$line — ${allowance.intent}; remove stale allowances when the declaration moves") {
                editor.count { it == line } shouldBe allowance.count
            }
        }
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

    private data class Allowance(
        val count: Int,
        val intent: String,
    )

    private companion object {
        // #634's closed CSS fence excludes these ten pre-existing RGB declarations. Each
        // exception pins its exact declaration and count; new literals and stale rows fail.
        // They were absent from the brief's hex-only inventory. Removal is a held follow-up.
        val LEGACY_LITERALS =
            mapOf(
                "box-shadow: var(--shadow-sm, 0 1px 2px rgb(15 23 42 / 0.06));" to
                    Allowance(3, "Legacy small elevation fallback; --shadow-sm is declared in the design system"),
                "box-shadow: var(--shadow-md, 0 4px 10px -2px rgb(15 23 42 / 0.1));" to
                    Allowance(3, "Legacy medium elevation fallback; --shadow-md is declared in the design system"),
                "box-shadow: var(--shadow-lg, 0 14px 28px -8px rgb(15 23 42 / 0.18));" to
                    Allowance(1, "Legacy large elevation fallback; --shadow-lg is declared in the design system"),
                "box-shadow: var(--shadow-md, 0 4px 12px -4px rgb(15 23 42 / 0.18));" to
                    Allowance(1, "Legacy dock elevation fallback; --shadow-md is declared in the design system"),
                "box-shadow: var(--shadow-xl, 0 20px 60px rgb(0 0 0 / 0.2));" to
                    Allowance(1, "Legacy modal elevation fallback; --shadow-xl is declared in the design system"),
                "background: rgb(0 0 0 / 0.5);" to
                    Allowance(1, "Existing error-modal scrim; replacing it with the house recipe changes a painted surface"),
            )

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
