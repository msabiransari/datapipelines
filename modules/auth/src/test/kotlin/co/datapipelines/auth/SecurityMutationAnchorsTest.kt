package co.datapipelines.auth

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The per-push anchor guard for the security mutations (#470, `scripts/security-mutations/`).
 *
 * Each mutation declares a literal sed pair (FROM → TO in one production file) plus the ONE test
 * class and test name that must go red. `run.sh` refuses a pair whose FROM does not match its
 * target exactly once, and a mutation whose named test no longer exists reports NO-VERDICT —
 * a silence the weekly run surfaced only days later (three of the ten anchors were dark for two
 * weeks before this guard existed). Reading the same files the runner would (every numbered
 * mutation and the self-check), the guard asserts, per mutation:
 *
 *  1. TARGET exists; FROM occurs exactly once in it and TO zero times; neither string contains
 *     the other (swap()'s own ambiguity rule);
 *  2. EXPECT_CLASS resolves to a `SimpleName.kt` file under a test tree whose package
 *     declaration matches, and EXPECT_TEST is a substring of a `fun` declaration in it —
 *     the runner judges the JUnit XML by substring of the testcase name.
 *
 * It proves the anchors and the names only. It runs NO mutation and cannot prove that the named
 * test still DETECTS the defect — that verdict is the weekly `run.sh`'s to give (record §9/B6:
 * never per PR). Like the other source-reading drift tests, the repo root is found by walking up
 * from the working directory to the `settings.gradle.kts` (the shape of
 * `ObservabilityRedactionSpecDriftTest`).
 */
class SecurityMutationAnchorsTest {
    @Test
    fun `every security mutation anchors exactly once and names an existing test`() {
        val root = repoRoot()
        val dir = root.resolve("scripts/security-mutations")
        val numbered = dir.listFiles { f: File -> f.isFile && NUMBERED_FILE.matches(f.name) }.orEmpty().sortedBy { it.name }
        val selfcheck = dir.resolve("selfcheck").listFiles { f: File -> f.isFile && f.name.endsWith(".sh") }.orEmpty().sortedBy { it.name }
        withClue("scripts/security-mutations must hold the ten numbered mutations and the self-check") {
            (numbered.size + selfcheck.size >= MIN_MUTATION_FILES) shouldBe true
        }
        val testFiles = testFilesUnderTestTrees(root)
        val drifted = mutableListOf<String>()
        for (file in numbered + selfcheck) drift(file, root, testFiles, drifted)
        withClue("security-mutation anchors drifted — run.sh would refuse or report NO-VERDICT for each named id") {
            drifted shouldBe emptyList()
        }
    }

    private fun drift(
        file: File,
        root: File,
        testFiles: List<File>,
        drifted: MutableList<String>,
    ) {
        val id = file.name.removeSuffix(".sh")
        val vars = readVars(file)
        if (vars == null) {
            drifted += "$id: the mutation file could not be sourced for its variables"
            return
        }
        val target = root.resolve(vars.target)
        if (!target.isFile) {
            drifted += "$id: TARGET does not exist: ${vars.target}"
            return
        }
        if (vars.from.isEmpty() || vars.to.isEmpty()) {
            drifted += "$id: FROM and TO must both be non-empty"
            return
        }
        val text = target.readText()
        val fromCount = countOf(text, vars.from)
        val toCount = countOf(text, vars.to)
        if (fromCount != 1) drifted += "$id: FROM occurs $fromCount times in ${vars.target} (exactly once required): ${vars.from}"
        if (toCount != 0) drifted += "$id: TO already occurs $toCount times in ${vars.target} (zero required): ${vars.to}"
        if (vars.to.contains(vars.from)) drifted += "$id: TO contains FROM — swap() refuses the ambiguous pair"
        if (vars.from.contains(vars.to)) drifted += "$id: FROM contains TO — swap() refuses the ambiguous pair"
        val simpleName = vars.expectClass.substringAfterLast('.')
        val pkg = vars.expectClass.substringBeforeLast('.')
        val classFile = testFiles
            .filter { it.name == "$simpleName.kt" && it.readText().startsWith("package $pkg") }
            .firstOrNull()
        if (classFile == null) {
            drifted += "$id: EXPECT_CLASS resolves to no test-tree file with package $pkg: ${vars.expectClass}"
            return
        }
        val named = FUN_DECL.findAll(classFile.readText()).any { it.groupValues[1].contains(vars.expectTest) }
        if (!named) drifted += "$id: EXPECT_TEST names no fun in ${classFile.name}: ${vars.expectTest}"
    }

    private fun readVars(file: File): MutationVars? {
        val process =
            ProcessBuilder("bash", "-c", READ_VARS_SCRIPT, "read-vars", file.absolutePath).start()
        val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.errorStream.readBytes() // drained so a chatty stderr cannot block the process
        if (!process.waitFor(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly()
            return null
        }
        val parts = stdout.split('\u0000')
        if (parts.size < EXPECTED_VALUES + 1) return null
        return MutationVars(
            target = parts[2],
            from = parts[0],
            to = parts[1],
            expectClass = parts[3],
            expectTest = parts[4],
        )
    }

    private fun testFilesUnderTestTrees(root: File): List<File> {
        val kotlinFiles = mutableListOf<File>()
        walk(root, root, kotlinFiles)
        return kotlinFiles
    }

    private fun walk(
        dir: File,
        root: File,
        into: MutableList<File>,
    ) {
        dir.listFiles()?.forEach { entry ->
            when {
                entry.isDirectory && entry.name !in PRUNED_DIRS -> walk(entry, root, into)
                entry.isFile && entry.name.endsWith(".kt") &&
                    entry.relativeTo(root).invariantSeparatorsPath.contains("src/test/") -> into += entry
            }
        }
    }

    private fun countOf(
        haystack: String,
        needle: String,
    ): Int {
        var index = haystack.indexOf(needle)
        var count = 0
        while (index >= 0) {
            count += 1
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parent != null && !dir.resolve("settings.gradle.kts").isFile) dir = dir.parentFile
        return dir
    }

    private data class MutationVars(
        val target: String,
        val from: String,
        val to: String,
        val expectClass: String,
        val expectTest: String,
    )

    private companion object {
        const val MIN_MUTATION_FILES = 11
        const val EXPECTED_VALUES = 5
        const val READ_TIMEOUT_SECONDS = 30L
        const val READ_VARS_SCRIPT =
            "set -a; . \"\$1\"; printf '%s\\0' \"\$FROM\" \"\$TO\" \"\$TARGET\" \"\$EXPECT_CLASS\" \"\$EXPECT_TEST\""
        // The MutationVars mapping above must mirror READ_VARS_SCRIPT's printf order: FROM, TO, TARGET, EXPECT_CLASS, EXPECT_TEST.
        val NUMBERED_FILE = Regex("""[0-9][0-9]-.+\.sh""")
        val FUN_DECL = Regex("""fun\s+`([^`]*)`""")
        val PRUNED_DIRS = setOf("build", ".git", ".gradle", "node_modules", ".claude", ".kotlin", "dist")
    }
}
