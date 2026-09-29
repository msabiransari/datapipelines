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
 * §7 / §3.33 (#10) — the dashboard documents' bounds at boot. The shipped defaults pass the production rule;
 * one past a bound and a non-integer each refuse NAMING the key; an unset block is no violation. The
 * key/bound agreement with `VisualizationKey` is `VisualizationConfigKeysSpecDriftTest`'s (in
 * `modules/visualization`, which reads this rule's source).
 */
class VisualizationRulesTest {
    private fun violations(values: Map<String, String?>): List<String> =
        ConfigValidator.validate(ConfigSnapshots.valid().copy(visualization = values)).violations

    @Test
    fun `the shipped defaults pass the production rule`() {
        val defaults = VisualizationRules.KEYS.associateWith { defaultOf(it) }
        defaults.values.all { it != null } shouldBe true
        violations(defaults) shouldContainExactly emptyList()
    }

    @Test
    fun `one past a bound and a non-integer each refuse, naming the key`() {
        violations(mapOf("datapipelines.visualization.max-visualizations-per-dashboard" to "501")).single() shouldStartWith
            "datapipelines.visualization.max-visualizations-per-dashboard"
        violations(mapOf("datapipelines.visualization.max-config-bytes" to "1023")).single() shouldStartWith
            "datapipelines.visualization.max-config-bytes"
        violations(mapOf("datapipelines.visualization.max-bindings-per-visualization" to "many")).single() shouldStartWith
            "datapipelines.visualization.max-bindings-per-visualization"
        violations(mapOf("datapipelines.visualization.max-fixture-rows-per-case" to "100000")) shouldContainExactly emptyList()
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
