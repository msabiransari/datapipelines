package co.datapipelines.config

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `CHECK_COUNT` is hand-maintained and the startup log quotes it: "Configuration validated (N
 * checks, configuration.md §7)". It has drifted before — 021/F10 found the literal claiming 10
 * against 9 functions — and nothing failed, because a number in a log line has no other reader.
 *
 * This is that reader. The literal is checked against the `check*` functions [ConfigValidator]
 * actually declares, so the next check added without touching the constant turns the build red
 * instead of quietly making the boot log lie.
 *
 * Source text rather than reflection, deliberately: the functions are `private` members of a
 * companion object, and Kotlin's compilation of those is an implementation detail this guard
 * must not depend on. It is the same shape [BootstrapConfigKeysSpecDriftTest] uses to read
 * `application.yml` and `configuration.md`.
 */
class ConfigValidatorCheckCountTest {
    @Test
    fun `CHECK_COUNT equals the number of check functions ConfigValidator declares`() {
        val declared = checkFunctionNames()

        // Non-vacuity: a regex that matched nothing would otherwise "pass" against a zeroed
        // constant, and a guard that cannot go red is not a guard.
        declared.size shouldBeGreaterThan 1
        declared shouldContain "checkRequiredKeys"

        declared.size shouldBe ConfigValidator.CHECK_COUNT
    }

    private fun checkFunctionNames(): List<String> =
        CHECK_FUNCTION
            .findAll(repoFile("modules/app/src/main/kotlin/co/datapipelines/config/ConfigValidator.kt").readText())
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()

    /** The repo root is the nearest ancestor holding `settings.gradle.kts` (the house locator). */
    private fun repoFile(relative: String): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File(".").absolutePath}")
        }
        return File(dir, relative).also { check(it.isFile) { "missing $relative" } }
    }

    private companion object {
        val CHECK_FUNCTION = Regex("""fun (check[A-Z][A-Za-z0-9]*)\(""")
    }
}
