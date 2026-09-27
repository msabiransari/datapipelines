package co.datapipelines.config

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.FileSystemResource
import java.io.File

/**
 * §7 / §3.30 (#194) — the parameter engine's bounds at boot. The shipped defaults (read out of
 * application.yml's placeholders) pass the production rules; one past a bound, a non-integer and the
 * cross-key rule each refuse NAMING the key. The key/bound agreement with the engine's own table is
 * `ParametersConfigKeysSpecDriftTest`'s (in `modules/parameters`, which can read this rule's source).
 */
class ParametersRulesTest {
    private fun violations(values: Map<String, String?>): List<String> =
        ConfigValidator.validate(ConfigSnapshots.valid().copy(parameters = values)).violations

    @Test
    fun `the shipped defaults pass the production rules`() {
        val defaults = ParametersRules.KEYS.associateWith { defaultOf(it) }
        defaults.values.all { it != null } shouldBe true
        violations(defaults) shouldContainExactly emptyList()
    }

    @Test
    fun `one past a bound, a non-integer and a selector timeout past its evaluate each refuse, naming the key`() {
        violations(mapOf("datapipelines.parameters.max-parameters-per-set" to "257")).single() shouldStartWith
            "datapipelines.parameters.max-parameters-per-set"
        violations(mapOf("datapipelines.parameters.max-waiting-selector-queries" to "-1")).single() shouldStartWith
            "datapipelines.parameters.max-waiting-selector-queries"
        violations(mapOf("datapipelines.parameters.max-regex-steps" to "lots")).single() shouldStartWith
            "datapipelines.parameters.max-regex-steps"
        violations(
            mapOf(
                "datapipelines.parameters.evaluate-timeout-seconds" to "5",
                "datapipelines.parameters.selector-query-timeout-seconds" to "6",
            ),
        ).single() shouldStartWith "datapipelines.parameters.selector-query-timeout-seconds"
        violations(mapOf("datapipelines.parameters.max-parameters-per-set" to "256")) shouldContainExactly emptyList()
    }

    @Test
    fun `an unset block is not a violation - application yml always supplies it`() {
        violations(emptyMap()) shouldContainExactly emptyList()
    }

    private fun defaultOf(key: String): String? {
        val shipped =
            YamlPropertySourceLoader()
                .load("application.yml", FileSystemResource(repoFile("modules/app/src/main/resources/application.yml")))
                .filterIsInstance<EnumerablePropertySource<*>>()
                .firstNotNullOfOrNull { it.getProperty(key)?.toString() }
        return shipped?.let { Regex("""^\$\{[A-Z0-9_]+:([^}]*)\}$""").find(it)?.groupValues?.get(1) }
    }

    private fun repoFile(relative: String): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File(".").absolutePath}")
        }
        return File(dir, relative).also { check(it.isFile) { "missing $relative" } }
    }
}
