package co.datapipelines.integration

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **A suite's scratch database is created ONCE — never behind a property accessor or a function.**
 *
 * `SharedE2e.scratchDatabase(name)` DROPS the named database `WITH (FORCE)` and recreates it, which
 * terminates every live connection to it: the app's pooled source connections see
 * `FATAL: terminating connection due to administrator command` (SQLSTATE 57P01) on their next use.
 * Two suites declared the database as `private val source get() = SharedE2e.scratchDatabase …`,
 * so EVERY read re-dropped it — `DriverManager.getConnection(source.jdbcUrl, source.username,
 * source.password)` dropped it three times in one line. Whether the app then borrowed a dead pooled
 * connection depended on Hikari's 500 ms alive-bypass window: green on every developer box and gate,
 * red on CI run 36872021211 (2026-10-01, `DashboardRuntimeE2eTest`'s membership-revocation case,
 * `source_failed` instead of a held source). A rule no test enforces is a wish — this guard is the
 * enforcement, in the `OrderedTestAnnotationGuardTest` style: a cheap text scan that names file and
 * line, with a non-vacuity floor so an empty scan cannot read as clean.
 *
 * The legitimate shapes are a `val` initialiser (companion or instance) and `by lazy { … }`; the
 * refused shapes are a `get()` accessor (same line or the line above) and a function whose body
 * calls `scratchDatabase` — both run once per READ, not once per suite.
 */
class ScratchDatabaseOnceGuardTest {
    @Test
    fun `every scratch database is created once, never behind a getter or a function`() {
        val offenders = offenders()

        withClue(
            "A `get()` accessor or a function around SharedE2e.scratchDatabase drops the database WITH (FORCE) " +
                "on every read and terminates the app's live connections to it — declare it as a `val` " +
                "(or `by lazy`) so the suite creates it once.",
        ) {
            offenders.shouldBeEmpty()
        }
    }

    /** The offender list, and the non-vacuity floor the scan's scope must clear. */
    private fun offenders(): List<String> {
        val offenders = mutableListOf<String>()
        var sites = 0
        testSourceFiles().forEach { file ->
            val lines = file.readLines()
            lines.withIndex().forEach { (index, line) ->
                if (!SCRATCH_CALL.containsMatchIn(line)) return@forEach
                sites++
                val previous = (index - 1 downTo maxOf(0, index - 3)).map { lines[it] }.firstOrNull { it.isNotBlank() }
                when {
                    ACCESSOR_ON_LINE.containsMatchIn(line) -> {
                        offenders += "${file.path}:${index + 1}: scratchDatabase behind a get() accessor"
                    }

                    previous != null && ACCESSOR_OPENS.containsMatchIn(previous) -> {
                        offenders += "${file.path}:${index + 1}: scratchDatabase inside a get() accessor"
                    }

                    FUNCTION_ON_LINE.containsMatchIn(line) -> {
                        offenders += "${file.path}:${index + 1}: scratchDatabase behind a function"
                    }
                }
            }
        }
        withClue("No SharedE2e.scratchDatabase call found in the test sources — the scan is vacuous, not clean") {
            sites shouldBeGreaterThanOrEqual SITE_FLOOR
        }
        return offenders
    }

    /**
     * Every test source under `modules/` and `tests/` at the repository root — resolved by walking
     * UP from the working directory, the `ArchitectureGuardTest.modulesDirectory` pattern.
     */
    private fun testSourceFiles(): List<File> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "modules/pipeline-contract").isDirectory)) dir = dir.parentFile
        val root = checkNotNull(dir) { "repository root not found walking up from ${File("").absolutePath}" }
        return listOf("modules", "tests").flatMap { top ->
            val base = File(root, top)
            if (!base.isDirectory) return@flatMap emptyList<File>()
            base
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.path.contains("${File.separator}src${File.separator}test${File.separator}") }
                .filterNot { it.path.contains("${File.separator}build${File.separator}") }
                .toList()
        }
    }

    private companion object {
        /** The tree held 39 call sites at the guard's birth (2026-10-01), every one a `val` initialiser. */
        const val SITE_FLOOR = 20

        /** A CALL — `SharedE2e.scratchDatabase(` or `.scratchDatabase(` — never the definition in `SharedE2e`. */
        val SCRATCH_CALL = Regex("\\.scratchDatabase\\(")

        /** `val source get() = SharedE2e.scratchDatabase …` — the accessor and the call on one line. */
        val ACCESSOR_ON_LINE = Regex("\\bget\\(\\)\\s*(=|\\{).*\\.scratchDatabase\\(")

        /** A multi-line accessor: the line above opened `get() =` or `get() {`. */
        val ACCESSOR_OPENS = Regex("\\bget\\(\\)\\s*(=|\\{)\\s*$")

        /** `fun source() = SharedE2e.scratchDatabase …` — a function body is a call per invocation. */
        val FUNCTION_ON_LINE =
            Regex("^\\s*(?:private |internal |protected |public )?fun\\s+\\w+\\s*\\([^)]*\\)[^=]*=.*\\.scratchDatabase\\(")
    }
}
