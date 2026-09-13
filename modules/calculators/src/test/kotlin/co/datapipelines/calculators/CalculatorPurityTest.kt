package co.datapipelines.calculators

import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Purity, made mechanical (calculators design §0.4 / C12).
 *
 * A calculator kind is a **pure function of its inputs**: the executor evaluates one at an
 * arbitrary DAG position, the validator type-checks one at save time with no execution at all,
 * and a future editor preview would evaluate one in a browser round-trip. All three uses rest on
 * the kind reading nothing but its arguments.
 *
 * Prose cannot hold that. Two mechanical guards do, and they fail in different directions:
 *
 * 1. **The build file** — the module's only project dependency is `typesystem`. `dag`,
 *    `datasources` and `web` are not reachable, so a kind cannot open a connection even by
 *    accident. (Gradle's `verifyModuleDependencies` checks the same edge against
 *    module-structure §4.2; this checks the file a developer actually edits, and names the line.)
 * 2. **The sources** — no import that could reach the outside world, and no read of an ambient
 *    clock, zone or random source. The second half matters more than the first: `Instant.now()`
 *    needs no dependency at all, and a kind that read the wall clock would be *impure while
 *    looking completely ordinary*. "Today" arrives as `$current_date`, which the executor fixed
 *    once at execution start — precisely so that two nodes of one run cannot disagree about it.
 */
class CalculatorPurityTest {
    @Test
    fun `the module declares exactly one project dependency - typesystem`() {
        val declared =
            PROJECT_DEPENDENCY
                .findAll(repoFile("modules/calculators/build.gradle.kts").readText())
                .map { it.groupValues[1] }
                .toList()

        withClue(
            "modules/calculators/build.gradle.kts must declare exactly `:modules:typesystem` and nothing " +
                "else. A second edge here is how a `pure` kind acquires a database.",
        ) {
            declared shouldContainExactly listOf(":modules:typesystem")
        }
    }

    @Test
    fun `no production source imports anything that could reach the outside world`() {
        val offenders =
            productionSources().flatMap { file ->
                file
                    .readLines()
                    .withIndex()
                    .filter { (_, line) -> FORBIDDEN_IMPORT.containsMatchIn(line) }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
            }

        withClue("An import in modules/calculators that reaches I/O, a framework, or JDBC") {
            offenders.shouldBeEmpty()
        }
    }

    @Test
    fun `no production source reads an ambient clock, zone or random source`() {
        val offenders =
            productionSources().flatMap { file ->
                file
                    .readLines()
                    .withIndex()
                    // KDoc mentions the rule; a comment about `Instant.now()` is not a call to it.
                    .filterNot { (_, line) -> line.trimStart().startsWith("*") || line.trimStart().startsWith("//") }
                    .filter { (_, line) -> AMBIENT.containsMatchIn(line) }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
            }

        withClue(
            "A read of the ambient clock, default zone or a random source in modules/calculators. " +
                "`current_date` and `current_timestamp` are Context keys the executor fixes once per " +
                "execution; a kind that reads the clock itself makes two nodes of one run disagree.",
        ) {
            offenders.shouldBeEmpty()
        }
    }

    @Test
    fun `the scans actually looked at the production sources`() {
        // A guard scanning an empty directory is not a guard. Both scans above are
        // absence-assertions, which is exactly the shape that passes vacuously.
        productionSources().shouldNotBeEmpty()
    }

    @Test
    fun `every kind evaluates to its declared shape - a scalar for single kinds, the declared key set for multi`() {
        // 121/D1's contract, made mechanical over the whole registry: a single-output kind
        // returns ONE value, a multi-output kind returns a map whose key set EQUALS the declared
        // output names — a missing key is a reader binding null, an extra one is a write nobody
        // validated. Evaluating through the documented example (ExampleInputs) rather than a
        // fixture of this test's own is what keeps the check honest about what the catalog
        // promises.
        val offenders = CalculatorRegistry.KINDS.mapNotNull { kind -> shapeOffense(kind, kind.evaluate(ExampleInputs.of(kind))) }
        withClue("A kind whose example result contradicts its declared output shape") {
            offenders.shouldBeEmpty()
        }
    }

    @Test
    fun `the shape check is not vacuous - it accepts a well-formed multi kind and refuses a wrong key set`() {
        // Every kind in the registry is single-output until the two period kinds land (121
        // commit C), so the registry sweep above exercises only the single arm. This fixture —
        // the throwaway multi-output kind the lane's brief allows — proves the multi arm can
        // fail: a map missing a declared name, or carrying an undeclared one, must be caught.
        val good =
            multiFixture { mapOf("start" to it["date"], "end" to it["date"]) }
        shapeOffense(good, good.evaluate(ExampleInputs.of(good))) shouldBe null

        val missing = multiFixture { mapOf("start" to it["date"]) }
        shapeOffense(missing, missing.evaluate(ExampleInputs.of(missing))) shouldBe
            "fixture: declared outputs [end] missing from the result"

        val extra = multiFixture { mapOf("start" to it["date"], "end" to it["date"], "middle" to it["date"]) }
        shapeOffense(extra, extra.evaluate(ExampleInputs.of(extra))) shouldBe
            "fixture: result carries undeclared outputs [middle]"
    }

    /** One kind's shape verdict, or null when the result honours the declaration. */
    private fun shapeOffense(
        kind: CalculatorKind,
        result: Any?,
    ): String? =
        when {
            kind.outputs.isEmpty() && result is Map<*, *> -> {
                "${kind.kind}: single-output kind returned a Map"
            }

            kind.outputs.isEmpty() -> {
                null
            }

            result !is Map<*, *> -> {
                "${kind.kind}: multi-output kind returned a ${result?.javaClass?.simpleName ?: "null"}, not a map"
            }

            else -> {
                val declared = kind.outputs.map { it.name }
                val missing = declared.filterNot { it in result.keys }
                val extra = result.keys.filterNot { it in declared }
                when {
                    missing.isNotEmpty() -> "${kind.kind}: declared outputs $missing missing from the result"
                    extra.isNotEmpty() -> "${kind.kind}: result carries undeclared outputs $extra"
                    else -> null
                }
            }
        }

    /** The throwaway two-output fixture kind (121): `start` and `end`, both DATE. */
    private fun multiFixture(body: (Map<String, Any?>) -> Map<String, Any?>): CalculatorKind =
        SimpleKind(
            kind = "fixture",
            displayName = "Fixture",
            description = "A fixture.",
            phrases = listOf("fixture"),
            inputs = listOf(input("date", LogicalType.DATE, "The date.")),
            output = null,
            example = example("date" to "2026-08-14", output = "start=2026-08-14, end=2026-08-14"),
            outputs =
                listOf(
                    CalculatorOutput("start", LogicalType.DATE, "The first day."),
                    CalculatorOutput("end", LogicalType.DATE, "The last day."),
                ),
            body = body,
        )

    private fun productionSources(): List<File> =
        repoFile("modules/calculators/src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /** The repo root is the nearest ancestor holding `settings.gradle.kts` (the house locator). */
    private fun repoFile(relative: String): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File(".").absolutePath}")
        }
        return File(dir, relative).also { check(it.exists()) { "missing $relative" } }
    }

    private companion object {
        val PROJECT_DEPENDENCY = Regex("""project\("(:[^"]+)"\)""")

        val FORBIDDEN_IMPORT =
            Regex("""^import (java\.(io|net|nio|sql)|javax\.|jakarta\.|org\.springframework\.|com\.fasterxml\.)""")

        /**
         * Ambient state a pure function may not read. `now(` covers `Instant.now`, `LocalDate.now`
         * and `Clock.systemUTC().instant()`'s siblings; `systemDefault` covers the zone.
         */
        val AMBIENT = Regex("""\b(now\(|currentTimeMillis|nanoTime|systemDefaultZone|systemDefault\(|Math\.random|Random\()""")
    }
}
