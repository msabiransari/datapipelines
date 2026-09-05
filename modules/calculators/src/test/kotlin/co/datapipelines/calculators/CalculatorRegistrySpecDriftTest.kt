package co.datapipelines.calculators

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Drift guard: the catalog in **docs/calculators.md §2** versus [CalculatorRegistry].
 *
 * The document is what an author reads and what an agent is pointed at; the registry is what
 * actually runs. Both live in this repository under the same hand, so nothing but a test that
 * reads the DOCUMENT and drives its assertions from the parsed rows can keep them honest — the
 * same discipline as `NodeTypeSpecDriftTest` and `PipelineErrorCodesSpecDriftTest`.
 *
 * It guards more than the kind list, because the kind list is the least likely thing to drift.
 * Each row's signature cell is **rebuilt** from the registry through [CatalogFormat] and compared
 * as a string, so a renamed input, a changed type, an input that quietly became optional, and a
 * worked example whose answer moved all fail here. Falsifying it in either direction — delete a
 * row, or add a kind without one — is a one-line experiment, and both were run.
 */
class CalculatorRegistrySpecDriftTest {
    private val documented: Map<String, DocumentedKind> = parseCatalog()

    @Test
    fun `the parse found rows - guards against a silent empty parse`() {
        // A heading rename or a table reformat would otherwise turn every assertion below into a
        // vacuous pass over an empty map. A floor, plus a name nobody would delete casually.
        withClue("No calculator rows parsed from docs/calculators.md §2 — the heading or table format changed") {
            documented.size shouldBeGreaterThan 1
        }
        documented.keys.contains("fiscal_quarter") shouldBe true
    }

    @Test
    fun `every documented kind is in the registry`() {
        val missing = documented.keys - CalculatorRegistry.NAMES.toSet()
        withClue("Kinds documented in calculators.md §2 that CalculatorRegistry does not ship") {
            missing.sorted().shouldBeEmpty()
        }
    }

    @Test
    fun `every registered kind is documented`() {
        val missing = CalculatorRegistry.NAMES.toSet() - documented.keys
        withClue("Kinds in CalculatorRegistry with no row in calculators.md §2") {
            missing.sorted().shouldBeEmpty()
        }
    }

    @Test
    fun `the document lists the kinds in registry order`() {
        // Order is not cosmetic here: the catalog is read top to bottom by a human deciding which
        // kind to use, and a document whose order drifts from the registry's is a document being
        // maintained by hand against something that moved.
        documented.keys.toList() shouldContainExactly CalculatorRegistry.NAMES
    }

    @Test
    fun `every row's signature is the registry's own`() {
        val wrong =
            CalculatorRegistry.KINDS.mapNotNull { kind ->
                val row = documented[kind.kind] ?: return@mapNotNull null
                val expected = CatalogFormat.signature(kind)
                if (row.signature == expected) null else "${kind.kind}: doc='${row.signature}' registry='$expected'"
            }
        withClue("Signature cells that disagree with the registry (input names, types, optionality, output)") {
            wrong.shouldBeEmpty()
        }
    }

    @Test
    fun `every row's example is the registry's own`() {
        val wrong =
            CalculatorRegistry.KINDS.mapNotNull { kind ->
                val row = documented[kind.kind] ?: return@mapNotNull null
                val expected = CatalogFormat.example(kind)
                if (row.example == expected) null else "${kind.kind}: doc='${row.example}' registry='$expected'"
            }
        withClue("Example cells that disagree with the registry's declared example") {
            wrong.shouldBeEmpty()
        }
    }

    @Test
    fun `every documented example actually evaluates to its documented answer`() {
        // The strongest of the six: the example is not just consistent between two files, it is
        // TRUE. A kind whose behaviour changes fails here even if somebody dutifully updated both
        // the doc and the declaration to match the new (wrong) answer.
        val wrong =
            CalculatorRegistry.KINDS.mapNotNull { kind ->
                val actual = runCatching { render(kind.evaluate(ExampleInputs.of(kind))) }
                when {
                    actual.isFailure -> {
                        "${kind.kind}: evaluating the example threw ${actual.exceptionOrNull()?.message}"
                    }

                    actual.getOrNull() != kind.example.output -> {
                        "${kind.kind}: example says '${kind.example.output}', evaluation gives '${actual.getOrNull()}'"
                    }

                    else -> {
                        null
                    }
                }
            }
        withClue("Documented examples that do not evaluate to their documented answer") {
            wrong.shouldBeEmpty()
        }
    }

    private fun render(value: Any?): String = value?.toString() ?: "null"

    /** One parsed row: the two cells this test compares. */
    private data class DocumentedKind(
        val signature: String,
        val example: String,
    )

    private fun parseCatalog(): Map<String, DocumentedKind> {
        val text = repoFile("docs/calculators.md").readText()
        val start = text.indexOf(SECTION_START)
        check(start >= 0) { "'$SECTION_START' not found in docs/calculators.md" }
        val end = text.indexOf(SECTION_END, start + SECTION_START.length)
        check(end > start) { "'$SECTION_END' not found after '$SECTION_START' in docs/calculators.md" }
        return ROW
            .findAll(text.substring(start, end))
            .associate { m -> m.groupValues[1] to DocumentedKind(m.groupValues[2].trim(), m.groupValues[4].trim()) }
    }

    /** The repo root is the nearest ancestor holding `settings.gradle.kts` (the house locator). */
    private fun repoFile(relative: String): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File(".").absolutePath}")
        }
        return File(dir, relative).also { check(it.isFile) { "missing $relative" } }
    }

    private companion object {
        const val SECTION_START = "## 2. The catalog"
        const val SECTION_END = "## 3. When it fails"

        /** `| \`kind\` | signature | description | example |` — four cells, none of them optional. */
        val ROW = Regex("^\\|\\s*`([a-z0-9_]+)`\\s*\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|\\s*$", RegexOption.MULTILINE)
    }
}
