package co.datapipelines.datasources.semantics

import co.datapipelines.datasources.TestFiles
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Drift guard: the `semantics.*` error codes in **pipeline-contract.md §13.15** versus
 * [SemanticsErrorCodes] — the [co.datapipelines.datasources.DatasourceErrorCodesSpecDriftTest]
 * shape, applied to the learned semantic layer's mirror.
 *
 * `datasources` cannot depend on `pipeline-contract`, so the recorder keeps its own copy of the
 * codes it raises. That copy is only trustworthy if it cannot drift from the catalog — so this
 * test reads the document, parses §13.15, and drives its assertions **from the parsed values**.
 * Add a semantics code to the spec and this fails until the constant exists; misspell a
 * constant and it fails until the document agrees. The third side of the mirror —
 * `PipelineErrorCodes.Semantics` — is held to the same rows by `PipelineErrorCodesSpecDriftTest`.
 */
class SemanticsErrorCodesSpecDriftTest {
    private val documented: Set<String> = parseSemanticsCodesFromSpec()
    private val declared: Map<String, String> = declaredConstants()

    @Test
    fun `the parse found semantics codes - guards against a silent empty parse`() {
        withClue("No semantics.* codes parsed from §13.15 — the heading or table format changed") {
            documented.isEmpty() shouldBe false
        }
    }

    @Test
    fun `the declared constants are exactly the documented semantics codes`() {
        declared.values shouldContainExactlyInAnyOrder documented
    }

    @Test
    fun `every constant appears verbatim in the spec`() {
        val specText = TestFiles.repoFile(SPEC_PATH).readText()
        val fabricated = declared.filterValues { !specText.contains("`$it`") }
        withClue("SemanticsErrorCodes constants pipeline-contract.md does not define") {
            fabricated.map { (field, code) -> "$field = $code" }.shouldBeEmpty()
        }
    }

    private companion object {
        const val SPEC_PATH = "docs/pipeline-contract.md"
        const val SECTION_START = "### 13.15 Learned semantics"
        const val SECTION_END = "## 14. Pipeline Lifecycle Operations"

        /** First cell of a markdown table row when it is a backticked lowercase code. */
        val TABLE_CODE = Regex("^\\|\\s*`([a-z0-9_]+(?:\\.[a-z0-9_]+)+)`\\s*\\|", RegexOption.MULTILINE)

        fun parseSemanticsCodesFromSpec(): Set<String> {
            val text = TestFiles.repoFile(SPEC_PATH).readText()
            val start = text.indexOf(SECTION_START)
            check(start >= 0) { "'$SECTION_START' not found in $SPEC_PATH" }
            val end = text.indexOf(SECTION_END, start + SECTION_START.length)
            check(end > start) { "'$SECTION_END' not found after '$SECTION_START' in $SPEC_PATH" }
            return TABLE_CODE
                .findAll(text.substring(start, end))
                .map { it.groupValues[1] }
                .filter { it.startsWith("semantics.") }
                .toSet()
        }

        fun declaredConstants(): Map<String, String> =
            SemanticsErrorCodes::class.java.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                .associate { field ->
                    field.isAccessible = true
                    field.name to (field.get(null) as String)
                }
    }
}
