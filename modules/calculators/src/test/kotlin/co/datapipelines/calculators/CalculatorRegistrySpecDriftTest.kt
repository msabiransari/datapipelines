package co.datapipelines.calculators

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate

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
    fun `every row's phrases are the registry's own`() {
        // 120/R2: the phrases are the lookup path an agent matches a question's words against,
        // so the doc's column is derived from the registry like the signature and example cells —
        // a kind whose doc phrases drift is a catalog telling the agent the wrong words.
        val wrong =
            CalculatorRegistry.KINDS.mapNotNull { kind ->
                val row = documented[kind.kind] ?: return@mapNotNull null
                val expected = CatalogFormat.phrases(kind)
                if (row.phrases == expected) null else "${kind.kind}: doc='${row.phrases}' registry='$expected'"
            }
        withClue("Phrases cells that disagree with the registry's declared phrases") {
            wrong.shouldBeEmpty()
        }
    }

    @Test
    fun `every kind lists at least one phrase - the non-vacuity floor`() {
        // A kind with no phrases is invisible to the R2 lookup: nothing the question could say
        // would ever match it. The floor is registry-side, not doc-side, so it holds even if the
        // doc's column were deleted outright.
        val silent = CalculatorRegistry.KINDS.filter { it.phrases.isEmpty() }.map { it.kind }
        withClue("Kinds with no phrases — a kind added without them fails here") {
            silent.shouldBeEmpty()
        }
    }

    @Test
    fun `no phrase is claimed by two kinds - the disjointness floor`() {
        // 123: the skill's lookup matches a question's words against these phrases, so a phrase
        // two kinds list forces a silent guess between them (a bare period noun like "last
        // quarter" sat on both prior_period and trailing_periods). Registry-side like the
        // vacuity floor above — the phrases are split by what each kind RETURNS, and this arm is
        // what keeps a future kind from re-sharing one.
        val shared =
            CalculatorRegistry.KINDS
                .flatMap { kind -> kind.phrases.map { phrase -> phrase to kind.kind } }
                .groupBy({ it.first }, { it.second })
                .filterValues { claimants -> claimants.size > 1 }
                .map { (phrase, claimants) -> "'$phrase' claimed by ${claimants.joinToString(", ")}" }
        withClue("Phrases listed by more than one kind — the lookup would have to guess") {
            shared.shouldBeEmpty()
        }
    }

    @Test
    fun `every documented example actually evaluates to its documented answer`() {
        // The strongest arm: the example is not just consistent between two files, it is
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

    @Test
    fun `the multi-output row shape renders - and its example check is not vacuous (121)`() {
        // No multi-output kind is registered yet (the two period kinds land in 121 commit C), so
        // the machinery's multi arm is proven on a fixture kind: the signature cell renders the
        // braced set, and the evaluate-the-example check compares a rendered MAP against the
        // documented answer — including failing when the map's values move.
        CatalogFormat.signature(MULTI_FIXTURE) shouldBe "`date` DATE → {start DATE, end DATE}"
        CatalogFormat.example(MULTI_FIXTURE) shouldBe "date=2026-08-14 → start=2026-08-01, end=2026-08-31"

        render(MULTI_FIXTURE.evaluate(ExampleInputs.of(MULTI_FIXTURE))) shouldBe MULTI_FIXTURE.example.output

        // The falsification, inline: a moved boundary must NOT equal the documented answer.
        val moved =
            SimpleKind(
                kind = "fixture",
                displayName = "Fixture",
                description = "A fixture.",
                phrases = listOf("fixture"),
                inputs = MULTI_FIXTURE.inputs,
                output = null,
                example = MULTI_FIXTURE.example,
                outputs = MULTI_FIXTURE.outputs,
            ) { mapOf("start" to LocalDate.of(2026, 8, 2), "end" to LocalDate.of(2026, 8, 31)) }
        (render(moved.evaluate(ExampleInputs.of(moved))) == moved.example.output) shouldBe false
    }

    /**
     * The catalog's rendering of an evaluation result: a scalar prints itself; a multi-output
     * kind's map prints `start=2026-04-01, end=2026-06-30` — insertion order, which the kind
     * contract pins to the declared output order (121, so the rendering matches `example.output`).
     */
    private fun render(value: Any?): String =
        when (value) {
            is Map<*, *> -> value.entries.joinToString(", ") { (k, v) -> "$k=$v" }
            else -> value?.toString() ?: "null"
        }

    /** One parsed row: the three cells this test compares. */
    private data class DocumentedKind(
        val signature: String,
        val phrases: String,
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
            .associate { m ->
                m.groupValues[1] to DocumentedKind(m.groupValues[2].trim(), m.groupValues[4].trim(), m.groupValues[5].trim())
            }
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

        /** `| \`kind\` | signature | description | phrases | example |` — five cells, none of them optional. */
        val ROW = Regex("^\\|\\s*`([a-z0-9_]+)`\\s*\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|\\s*$", RegexOption.MULTILINE)

        /** The throwaway two-output fixture kind (121): month bounds, deterministic. */
        val MULTI_FIXTURE =
            SimpleKind(
                kind = "fixture",
                displayName = "Fixture",
                description = "A fixture.",
                phrases = listOf("fixture"),
                inputs = listOf(input("date", co.datapipelines.typesystem.LogicalType.DATE, "The date.")),
                output = null,
                example = example("date" to "2026-08-14", output = "start=2026-08-01, end=2026-08-31"),
                outputs =
                    listOf(
                        CalculatorOutput("start", co.datapipelines.typesystem.LogicalType.DATE, "The first day."),
                        CalculatorOutput("end", co.datapipelines.typesystem.LogicalType.DATE, "The last day."),
                    ),
            ) { values ->
                val date = values["date"] as LocalDate
                mapOf("start" to date.withDayOfMonth(1), "end" to date.withDayOfMonth(date.lengthOfMonth()))
            }
    }
}
