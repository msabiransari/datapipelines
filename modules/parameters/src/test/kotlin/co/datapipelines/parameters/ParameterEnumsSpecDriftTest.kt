package co.datapipelines.parameters

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * enums.md §27–§30 (the wire tables) versus the four closed sets the parameter engine declares — the
 * `LearnedFactKindSpecDriftTest` shape: the doc's first column is parsed, the Kotlin wire values are
 * read, and a value on one side only is red.
 */
class ParameterEnumsSpecDriftTest {
    private val enums = ParametersTestFiles.read("docs/enums.md")

    @Test
    fun `each section's wire values are exactly the enum's`() {
        withClue("§27 ParameterKind") { values("## 27. `ParameterKind`") shouldBe ParameterKind.WIRE_VALUES.toSet() }
        withClue("§28 SelectorSourceKind") {
            values("## 28. `SelectorSourceKind`") shouldBe
                SelectorSourceKind.entries.map { it.wire }.toSet()
        }
        withClue("§29 PresentationControl") { values("## 29. `PresentationControl`") shouldBe PresentationControl.WIRE_VALUES.toSet() }
        withClue("§30 NumericFormatKind") { values("## 30. `NumericFormatKind`") shouldBe NumericFormatKind.WIRE_VALUES.toSet() }
    }

    /** The backticked first cells of the table under [heading], up to the next `---`. */
    private fun values(heading: String): Set<String> {
        val start = enums.indexOf(heading)
        check(start >= 0) { "'$heading' not found in enums.md" }
        val section = enums.substring(start).substringBefore("\n---")
        return ROW
            .findAll(section)
            .map { it.groupValues[1] }
            .toSet()
            .also { check(it.isNotEmpty()) { "no rows parsed under '$heading'" } }
    }

    private companion object {
        val ROW = Regex("^\\| `([a-zA-Z_]+)` \\|", RegexOption.MULTILINE)
    }
}
