package co.datapipelines.config

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The posture's defaults live in ONE place, and this test is why (§3.23; 075 wrote the
 * first version of it, 081 inverted it).
 *
 * 075 kept the posture's defaults in TWO places — `application-<posture>.yml` and a
 * `deploy/env/posture/<posture>.env` — and this test pinned the copies together. The
 * second copy existed for one reason: `deploy/compose.yml` passed every variable as
 * `${'$'}{VAR:-default}`, an explicit environment variable outranks a profile file, so under
 * compose the profile's default never applied and a `hardened` stack booted with
 * `authoring=on`.
 *
 * 081 removed the second copy instead of pinning it, because the owner's ruling leaves ONE
 * settings file and it cannot hold two postures' values at once. The fix is in
 * `deploy/compose.yml`: the two posture-dependent keys are passed in Compose's VALUELESS
 * mapping form (`DATAPIPELINES_AUTH_COOKIE_SECURE:` with nothing after the colon), the
 * only form that leaves a variable UNSET when no file supplies it — measured against this
 * Compose, one container per form: `${'$'}{VAR:-}` and `${'$'}{VAR:+…}` both set it to the empty
 * string, `VAR:` does not set it at all. `scripts/compose-env-audit.sh` check 7 is the
 * same rule from the compose file's side.
 *
 * So the assertion turns around. It used to be "the env files repeat the ymls"; it is now:
 *
 *  - both profile ymls declare EVERY posture-dependent key, and the two postures differ;
 *  - `deploy/env/defaults.env` names NONE of them — a tracked default would outrank the
 *    profile again, for every posture at once;
 *  - the posture env files, and the four other env files 081 collapsed, are gone.
 */
class PostureDefaultsSpecDriftTest {
    @Test
    fun `both posture profiles declare every posture-dependent key`() {
        ymlDefaults("development").keys.shouldNotBeEmpty()
        ymlDefaults("development").keys.sorted() shouldContainExactly POSTURE_KEYS.sorted()
        ymlDefaults("hardened").keys.sorted() shouldContainExactly POSTURE_KEYS.sorted()
    }

    /**
     * Non-vacuity, and the fact that makes the pin worth having: the two postures must
     * actually DIFFER. A pair of files that both said `true` would satisfy every other
     * assertion here while meaning nothing.
     */
    @Test
    fun `the two postures ship different defaults`() {
        val development = ymlDefaults("development")
        val hardened = ymlDefaults("hardened")

        development["DATAPIPELINES_DEPLOYMENT_AUTHORING_ENABLED"] shouldBe "true"
        hardened["DATAPIPELINES_DEPLOYMENT_AUTHORING_ENABLED"] shouldBe "false"
        development["DATAPIPELINES_AUTH_COOKIE_SECURE"] shouldBe ""
        hardened["DATAPIPELINES_AUTH_COOKIE_SECURE"] shouldBe "true"
    }

    /**
     * The inversion itself. `defaults.env` is loaded by every loader for every posture, so
     * a posture-dependent key in it is one posture's answer imposed on both.
     */
    @Test
    fun `defaults env names no posture-dependent key`() {
        val defaults = repoFile("deploy/env/defaults.env").readLines()

        POSTURE_KEYS.forEach { key ->
            defaults.filter { it.contains("$key=") } shouldBe emptyList()
        }
    }

    /** The compose half, so a merge cannot split the two rules apart. */
    @Test
    fun `compose passes both posture keys in the valueless form`() {
        val compose = repoFile("deploy/compose.yml").readText()

        POSTURE_KEYS.forEach { key -> compose shouldContain "\n      $key:\n" }
    }

    /** 081 deleted them; a resurrected file would silently take precedence again. */
    @Test
    fun `the env files 081 collapsed are gone`() {
        listOf(
            "deploy/env/posture",
            "deploy/env/posture/development.env",
            "deploy/env/posture/hardened.env",
            "deploy/env/laptop.env",
            "deploy/env/demo.env",
            "deploy/env/example.env",
            "deploy/env/secrets.env.example",
        ).forEach { relative -> File(repoRoot(), relative).exists() shouldBe false }
    }

    /** `${'$'}{VAR:default}` occurrences in a posture profile → variable to shipped default. */
    private fun ymlDefaults(posture: String): Map<String, String> =
        PLACEHOLDER
            .findAll(repoFile("modules/app/src/main/resources/application-$posture.yml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** The repo root is the nearest ancestor holding `settings.gradle.kts` (the house locator). */
    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File(".").absolutePath}")
        }
        return dir
    }

    private fun repoFile(relative: String): File =
        File(repoRoot(), relative).also { check(it.isFile) { "missing $relative" } }

    private companion object {
        /**
         * The posture table's two DEFAULTS (docs/environments.md §2). The table's other
         * four rows are REFUSALS — `ConfigValidator` §7 rules, which no file can carry.
         * The same list is `POSTURE_VARS` in scripts/compose-env-audit.sh, and the first
         * test fails if a profile yml and this list disagree.
         */
        val POSTURE_KEYS = listOf(
            "DATAPIPELINES_DEPLOYMENT_AUTHORING_ENABLED",
            "DATAPIPELINES_AUTH_COOKIE_SECURE",
        )

        val PLACEHOLDER = Regex("""\$\{(DATAPIPELINES_[A-Z0-9_]+):([^}]*)\}""")
    }
}
