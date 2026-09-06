package co.datapipelines.config

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The posture's defaults exist in TWO places, and this is why (§3.23, 075):
 *
 *  - `application-development.yml` / `application-hardened.yml` — the profile the posture
 *    selects. This is what a bare `java -jar` and a Kubernetes deployment get.
 *  - `deploy/env/posture/<posture>.env` — the env file a compose stack loads.
 *
 * The second is not redundancy. `deploy/compose.yml` passes EVERY `DATAPIPELINES_*`
 * variable the app binds with the same default `application.yml` ships (the T32
 * pass-through contract, which `compose-env-audit.sh` enforces), and an explicit
 * environment variable OUTRANKS a profile file. Under compose the profile's default
 * therefore never applies — so a posture whose defaults live only in the yml is a posture
 * that does nothing there. That is not a theory: the live gate for this round booted a
 * `hardened` stack and got `authoring=on`, which is the promotion receiver's entire
 * configuration, silently absent.
 *
 * Two authorities drift, so this test is the pin: for each posture, every default the yml
 * sets must be the value the env file sets, and the two files must name the SAME keys.
 * Neither can be edited alone.
 */
class PostureDefaultsSpecDriftTest {
    @Test
    fun `the development posture's yml defaults and env file agree, key for key`() {
        assertPostureAgrees("development")
    }

    @Test
    fun `the hardened posture's yml defaults and env file agree, key for key`() {
        assertPostureAgrees("hardened")
    }

    /**
     * Non-vacuity, and the fact that makes the pin worth having: the two postures must
     * actually DIFFER. A pair of files that both said `true` would satisfy every assertion
     * above while meaning nothing.
     */
    @Test
    fun `the two postures ship different defaults`() {
        val development = envFileValues("development")
        val hardened = envFileValues("hardened")

        development.keys.sorted() shouldContainExactly hardened.keys.sorted()
        development["DATAPIPELINES_DEPLOYMENT_AUTHORING_ENABLED"] shouldBe "true"
        hardened["DATAPIPELINES_DEPLOYMENT_AUTHORING_ENABLED"] shouldBe "false"
        hardened["DATAPIPELINES_AUTH_COOKIE_SECURE"] shouldBe "true"
    }

    private fun assertPostureAgrees(posture: String) {
        val yml = ymlDefaults(posture)
        val env = envFileValues(posture).filterKeys { it != POSTURE_KEY }

        yml.keys.shouldNotBeEmpty()
        env.keys.sorted() shouldContainExactly yml.keys.sorted()
        yml.forEach { (variable, default) -> env[variable] shouldBe default }
        envFileValues(posture)[POSTURE_KEY] shouldBe posture
    }

    /** `${'$'}{VAR:default}` occurrences in a posture profile → variable to shipped default. */
    private fun ymlDefaults(posture: String): Map<String, String> =
        PLACEHOLDER
            .findAll(repoFile("modules/app/src/main/resources/application-$posture.yml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** `KEY=value` lines in a posture env file (comments and blanks skipped). */
    private fun envFileValues(posture: String): Map<String, String> =
        repoFile("deploy/env/posture/$posture.env")
            .readLines()
            .mapNotNull { line ->
                line
                    .takeIf { it.isNotBlank() && !it.trimStart().startsWith("#") }
                    ?.substringBefore('=')
                    ?.let { key -> key to line.substringAfter('=') }
            }.toMap()

    /** The repo root is the nearest ancestor holding `settings.gradle.kts` (the house locator). */
    private fun repoFile(relative: String): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File(".").absolutePath}")
        }
        return File(dir, relative).also { check(it.isFile) { "missing $relative" } }
    }

    private companion object {
        const val POSTURE_KEY = "DATAPIPELINES_POSTURE"

        val PLACEHOLDER = Regex("""\$\{(DATAPIPELINES_[A-Z0-9_]+):([^}]*)\}""")
    }
}
