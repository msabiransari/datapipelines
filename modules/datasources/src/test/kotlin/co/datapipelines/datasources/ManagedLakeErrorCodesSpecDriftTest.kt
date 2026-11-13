package co.datapipelines.datasources

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * #818 drift guard: the `lake.*` codes this module raises ([ManagedLakeErrorCodes]) against
 * **pipeline-contract.md §13.25**. `datasources` cannot depend on `pipeline-contract`, so it keeps its own copy;
 * every constant must be a §13.25 row, or a misspelling would ship a code no catalog maps. (The other §13.25 rows
 * are `modules/lake`'s, so this side is a subset, not the whole table: [DatasourceErrorCodesSpecDriftTest]'s
 * exact-set shape does not apply.)
 */
class ManagedLakeErrorCodesSpecDriftTest {
    @Test
    fun `every ManagedLakeErrorCodes constant is a section 13_25 row`() {
        val documented = parseSection()
        withClue("No lake.* codes parsed from §13.25 — the heading or table format changed") {
            documented.isEmpty() shouldBe false
        }
        val declared = declaredConstants()
        declared shouldHaveSize 2
        documented shouldContainAll declared
    }

    private companion object {
        const val SPEC_PATH = "docs/pipeline-contract.md"
        const val SECTION_START = "### 13.25 Managed lake"
        const val SECTION_END = "## 14."

        val TABLE_CODE = Regex("^\\|\\s*`([a-z0-9_]+(?:\\.[a-z0-9_]+)+)`\\s*\\|", RegexOption.MULTILINE)

        fun parseSection(): Set<String> {
            val text = TestFiles.repoFile(SPEC_PATH).readText()
            val start = text.indexOf(SECTION_START)
            check(start >= 0) { "'$SECTION_START' not found in $SPEC_PATH" }
            val end = text.indexOf(SECTION_END, start + SECTION_START.length)
            check(end > start) { "'$SECTION_END' not found after '$SECTION_START' in $SPEC_PATH" }
            return TABLE_CODE.findAll(text.substring(start, end)).map { it.groupValues[1] }.toSet()
        }

        fun declaredConstants(): List<String> =
            ManagedLakeErrorCodes::class.java.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                .map { field ->
                    field.isAccessible = true
                    field.get(null) as String
                }
    }
}
