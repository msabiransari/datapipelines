package co.datapipelines.templates

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Drift guard for [TemplatesProperties] against configuration.md §3.9 — the **single authority**
 * for key names and defaults (D8).
 *
 * The properties class mirrors that table for the code path; a mirror nobody checks is how two
 * sources of truth start disagreeing. Here the row is parsed out of the document and compared,
 * so changing a default in one place without the other fails the build.
 */
class TemplatesPropertiesSpecDriftTest {
    private val defaults = TemplatesProperties()

    @Test
    fun `every templates key this module reads has its section 3-9 default`() {
        mapOf(
            "datapipelines.templates.cache-size" to defaults.cacheSize.toString(),
            "datapipelines.templates.render-timeout-ms" to defaults.renderTimeoutMs.toString(),
            "datapipelines.templates.max-body-chars" to defaults.maxBodyChars.toString(),
        ).forEach { (key, code) ->
            withClue("configuration.md §3.9 row for `$key`") { specDefault(key) shouldBe code }
        }
    }

    @Test
    fun `the validator's fallback default is the same number as the property's`() {
        // TemplateValidator carries its own default so a directly-constructed validator (every
        // unit test) is bounded too. Two constants for one setting is exactly the drift this
        // class exists to catch.
        TemplateValidator.DEFAULT_MAX_BODY_CHARS shouldBe defaults.maxBodyChars
    }

    private companion object {
        const val SPEC_PATH = "docs/configuration.md"

        /**
         * The `Default` cell of the §3.9 table row for [key]. The table is
         * `| \`key\` | \`default\` | description |`, and the default is read as the second cell
         * with its backticks and any thousands separators removed.
         */
        fun specDefault(key: String): String {
            val row =
                TemplateFixtures
                    .repoFile(SPEC_PATH)
                    .readLines()
                    .firstOrNull { it.trimStart().startsWith("| `$key`") }
                    ?: error("configuration.md has no row for `$key`")
            return row
                .split("|")[2]
                .trim()
                .removeSurrounding("`")
                .replace("_", "")
                .replace(",", "")
        }
    }
}
