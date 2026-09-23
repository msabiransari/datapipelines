package co.datapipelines.scripting

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Purity, made mechanical (the CalculatorPurityTest pattern, transform-nodes design
 * §4.1/D-T4).
 *
 * The engine evaluates UNTRUSTED bodies as pure functions of their JSON input. That
 * property is worth nothing as prose, so two mechanical guards hold it, failing in
 * different directions:
 *
 * 1. **The build file** — the module's only PROJECT dependency is `typesystem` (the
 *    §4.2 row; `verifyModuleDependencies` checks the same edge against the table).
 *    Third-party edges (`jsonata`, `jackson-databind`, `slf4j-api`) are pinned, locked
 *    and checksum-verified through the version catalog — the ban is on INTERNAL edges
 *    a script engine could route I/O through.
 * 2. **The sources** — no import that could reach the outside world, and no read of an
 *    ambient clock, zone or random source. The clock exception is deliberate and
 *    structural: `ScriptEvaluationPool.SYSTEM` is the module's ONE ambient read — the
 *    injection point itself — and the test below asserts the count (exactly one,
 *    inside that file) instead of trusting it.
 */
class ScriptingPurityTest {
    @Test
    fun `the module declares exactly one project dependency - typesystem`() {
        val declared =
            PROJECT_DEPENDENCY
                .findAll(repoFile("modules/scripting/build.gradle.kts").readText())
                .map { it.groupValues[1] }
                .toList()

        withClue(
            "modules/scripting/build.gradle.kts must declare exactly `:modules:typesystem` and nothing " +
                "else. A second edge here is how a script engine acquires a database.",
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

        withClue(
            "An import in modules/scripting that reaches I/O, files, network, JDBC, the Spring " +
                "framework, or the ambient clock API. The engine's host fence is the absence of " +
                "any such capability in the module itself.",
        ) {
            offenders.shouldBeEmpty()
        }
    }

    @Test
    fun `no production source reads an ambient clock, zone or random source - except the one declared`() {
        val offenders =
            productionSources().flatMap { file ->
                if (file.name == "ScriptEvaluationPool.kt") {
                    // The injected clock's SYSTEM provider is the ONE ambient read: the
                    // injection point itself. Its count is asserted below, not here.
                    emptyList()
                } else {
                    file
                        .readLines()
                        // KDoc mentions the rule; a comment about `Instant.now()` is not a call to it.
                        .withIndex()
                        .filterNot { (_, line) -> line.trimStart().startsWith("*") || line.trimStart().startsWith("//") }
                        .filter { (_, line) -> AMBIENT.containsMatchIn(line) }
                        .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
                }
            }

        withClue(
            "A read of the ambient clock, default zone or a random source in modules/scripting. " +
                "The caller owns the clock (`EvaluationLimits.now`, `ScriptClock`); the engine " +
                "reading the wall itself would make a transform's output depend on when it ran.",
        ) {
            offenders.shouldBeEmpty()
        }

        // `clock.currentTimeMillis()` calls are INJECTED reads and legal anywhere;
        // the literal `System.currentTimeMillis` is the ambient read, allowed only in
        // the SYSTEM provider (counted here) and nowhere else in the module.
        val ambientReads =
            repoFile("modules/scripting/src/main/kotlin/co/datapipelines/scripting/ScriptEvaluationPool.kt")
                .readLines()
                .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                .count { it.contains("System.currentTimeMillis") }
        withClue("ScriptEvaluationPool.SYSTEM is the module's ONLY ambient clock read") {
            ambientReads shouldBe 1
        }
    }

    @Test
    fun `the scans actually looked at the production sources`() {
        // A guard scanning an empty directory is not a guard. Both scans above are
        // absence-assertions, which is exactly the shape that passes vacuously.
        productionSources().shouldNotBeEmpty()
    }

    private fun productionSources(): List<File> =
        repoFile("modules/scripting/src/main/kotlin")
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

        /**
         * Everything a pure evaluator must not even be ABLE to import: file and path
         * APIs, the network, JDBC, Spring, and `java.time.Clock` (the type; the pool
         * takes a [ScriptClock] instead, so the module never mentions the ambient API).
         */
        val FORBIDDEN_IMPORT =
            Regex("""^import (java\.(io|net|nio|sql)|javax\.|jakarta\.|org\.springframework\.|java\.time\.Clock)""")

        /**
         * Ambient state a pure function may not read. `now(` covers `Instant.now` and
         * `Clock.system...().instant()`'s siblings; `systemDefault` covers the zone.
         */
        val AMBIENT =
            Regex("""\b(now\(|currentTimeMillis|nanoTime|systemDefaultZone|systemDefault\(|Clock\.system|Math\.random|Random\()""")
    }
}
