package co.datapipelines.config

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.FileSystemResource
import java.io.File

/**
 * The `datapipelines.bootstrap.*` keys, pinned in the three places they have to agree:
 * `docs/configuration.md` §3.18 (the authority), `application.yml` (the shipped defaults) and
 * [ConfigValidator] (the §7 rule that reads them).
 *
 * ## Why this guard exists
 * Two failures it is aimed at, both of which are silent:
 *
 * 1. **Doc drift.** configuration.md is the declared single authority for config keys and
 *    `scripts/docs-audit.sh` enforces that every `datapipelines.*` key mentioned in any OTHER doc
 *    is defined there — but nothing checked the reverse direction, or that the code and the doc
 *    spell a key the same way. A renamed key would leave the doc describing a key that no longer
 *    exists and the audit would stay green.
 * 2. **YAML reparenting.** A block inserted at the wrong indent in `application.yml` silently
 *    re-parents its neighbours; the keys that break are the ones nobody touched. So this test
 *    loads the real file and asserts the new block binds **and** that the blocks around it still
 *    sit where they did.
 */
class BootstrapConfigKeysSpecDriftTest {
    private val loaded: Map<String, Any?> by lazy {
        YamlPropertySourceLoader()
            .load("application.yml", FileSystemResource(repoFile("modules/app/src/main/resources/application.yml")))
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { source -> source.propertyNames.map { name -> name to source.getProperty(name) } }
            .toMap()
    }

    @Test
    fun `configuration_md, application_yml and ConfigValidator name exactly the same bootstrap keys`() {
        val documented = keysIn(repoFile("docs/configuration.md").readText())
        val shipped = loaded.keys.filter { it.startsWith(PREFIX) }.sorted()
        val readByValidator = keysIn(repoFile("modules/app/src/main/kotlin/co/datapipelines/config/ConfigValidator.kt").readText())

        documented.shouldNotBeEmpty()
        documented shouldContainExactly EXPECTED
        shipped shouldContainExactly EXPECTED
        readByValidator shouldContainExactly EXPECTED
    }

    @Test
    fun `both bootstrap keys ship OFF by default`() {
        // "Unset = feature off" is the only switch (§3.18); the shipped default must therefore
        // resolve to empty when the env var is absent, exactly like bootstrap-admin-email.
        loaded["$PREFIX.datasources-file"] shouldBe "\${DATAPIPELINES_BOOTSTRAP_DATASOURCES_FILE:}"
        loaded["$PREFIX.examples-file"] shouldBe "\${DATAPIPELINES_BOOTSTRAP_EXAMPLES_FILE:}"
    }

    @Test
    fun `the neighbouring blocks still bind - the bootstrap block re-parented nothing`() {
        // The blocks that would have been swallowed by a wrongly-indented insert, plus the key
        // the §3.18 cross-key rule pairs with.
        loaded["datapipelines.auth.bootstrap-admin-email"] shouldBe "\${DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL:}"
        loaded["datapipelines.workspaces.provisioning-mode"] shouldBe "\${DATAPIPELINES_WORKSPACES_PROVISIONING_MODE:self-serve}"
        loaded["datapipelines.audit.retention-days"] shouldBe "\${DATAPIPELINES_AUDIT_RETENTION_DAYS:365}"
        loaded["datapipelines.observability.logging.format"] shouldBe "\${DATAPIPELINES_OBSERVABILITY_LOGGING_FORMAT:json}"
        loaded["datapipelines.ui.theme"] shouldBe "\${DATAPIPELINES_UI_THEME:saas}"
    }

    private fun keysIn(text: String): List<String> =
        KEY_REGEX
            .findAll(text)
            .map { it.value }
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
        const val PREFIX = "datapipelines.bootstrap"

        val EXPECTED = listOf("$PREFIX.datasources-file", "$PREFIX.examples-file")

        val KEY_REGEX = Regex("""datapipelines\.bootstrap\.[a-z0-9-]+""")
    }
}
