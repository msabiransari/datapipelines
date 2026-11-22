package co.datapipelines.web.ui

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Another theme replaces the client library's light theme WHOLE (#1064 D3, A.6, G7): the library's stylesheet carries
 * `themes/light.css`, and a host that wants another theme loads that theme's sheet after it. That is only a whole theme
 * if every sheet under `themes/` sets every custom property `light.css` sets — a property the later sheet leaves out
 * would keep the light value. Every sheet is read from the repository directory (none is named here), with floors so
 * an empty read is red.
 */
class ThemeSheetsReplaceLightTest {
    @Test
    fun `every theme sheet sets every custom property the light sheet sets`() {
        val root =
            requireNotNull(
                generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                    .firstOrNull { File(it, "settings.gradle.kts").exists() },
            ) { "no settings.gradle.kts above ${System.getProperty("user.dir")}" }
        val sheets =
            File(root, THEMES)
                .listFiles { file -> file.isFile && file.extension == "css" }
                .orEmpty()
                .sortedBy { it.name }
        val light = customProperties(sheets.single { it.name == "light.css" })
        println("1064|A6|light-properties=${light.size} sheets=${sheets.map { it.name }}")
        withClue("non-vacuity: the light sheet's custom properties (floor $PROPERTY_FLOOR) and the theme sheets (floor $SHEET_FLOOR)") {
            light.size shouldBeGreaterThanOrEqual PROPERTY_FLOOR
            sheets.size shouldBeGreaterThanOrEqual SHEET_FLOOR
        }
        assertSoftly {
            for (sheet in sheets) {
                val missing = (light - customProperties(sheet)).sorted()
                println("1064|A6|${sheet.name}|missing=${missing.size}")
                withClue("themes/${sheet.name} leaves out a custom property light.css sets, so the light value would stay") {
                    missing.shouldBeEmpty()
                }
            }
        }
    }

    /** Every custom property a declaration in [sheet] sets (comments removed first; a `var()` use is not a set). */
    private fun customProperties(sheet: File): Set<String> =
        DECLARATION
            .findAll(COMMENT.replace(sheet.readText(), ""))
            .map { it.groupValues[1] }
            .toSet()

    private companion object {
        const val THEMES = "modules/web/src/main/resources/static/vendor/design-system/themes"
        const val PROPERTY_FLOOR = 40
        const val SHEET_FLOOR = 2
        val COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val DECLARATION = Regex("""(--[A-Za-z0-9_-]+)\s*:""")
    }
}
