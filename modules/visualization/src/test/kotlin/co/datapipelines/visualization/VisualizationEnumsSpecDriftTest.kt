package co.datapipelines.visualization

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * enums.md §31–§37 (the wire tables) versus the seven closed vocabularies this module declares — the
 * `ParameterEnumsSpecDriftTest` shape: the doc's first column is parsed, the Kotlin wire values are read, and a
 * value on one side only is red. `TestRunStatus` is also V42's CHECK list (FlywayMigrationIntegrationTest pins that).
 */
class VisualizationEnumsSpecDriftTest {
    private val enums = VisualizationTestFiles.read("docs/enums.md")

    @Test
    fun `each section's wire values are exactly the enum's`() {
        withClue("§31 RendererKind") { values("## 31. `RendererKind`") shouldBe RendererKind.WIRE_VALUES.toSet() }
        withClue("§32 AssertionKind") { values("## 32. `AssertionKind`") shouldBe AssertionKind.WIRE_VALUES.toSet() }
        withClue("§33 TestRunStatus") { values("## 33. `TestRunStatus`") shouldBe TestRunStatus.entries.map { it.name }.toSet() }
        withClue("§34 DashboardObjectType") {
            values("## 34. `DashboardObjectType`") shouldBe DashboardObjectType.entries.map { it.wire }.toSet()
        }
        withClue("§35 ActionScope") { values("## 35. `ActionScope`") shouldBe ActionScope.WIRE_VALUES.toSet() }
        withClue("§36 StateSetting") { values("## 36. `StateSetting`") shouldBe StateSetting.WIRE_VALUES.toSet() }
        withClue("§37 LayoutPosition") { values("## 37. `LayoutPosition`") shouldBe LayoutPosition.WIRE_VALUES.toSet() }
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
        val ROW = Regex("^\\| `([a-zA-Z_0-9]+)` \\|", RegexOption.MULTILINE)
    }
}
