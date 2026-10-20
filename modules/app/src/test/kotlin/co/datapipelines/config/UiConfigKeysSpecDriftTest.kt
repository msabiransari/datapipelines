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
 * The `datapipelines.ui.*` keys (configuration.md §3.10), pinned in the three places they have to
 * agree: the doc (the authority), `application.yml` (the shipped defaults) and [ConfigValidator]
 * (the §7 rules that read them). #598b added the second key, `actively-developed-days`; the shape
 * and the targets are [OrgConfigKeysSpecDriftTest]'s:
 *
 * 1. **Doc drift** — nothing else checks that the code and the doc spell a key the same way, or
 *    that a documented key is shipped and read at all.
 * 2. **YAML reparenting** — the `ui:` block sits mid-tree, between `templates:` and
 *    `executions:`; a line inserted at the wrong depth closes its block and silently re-parents
 *    whatever follows, so this test asserts the neighbours still bind.
 */
class UiConfigKeysSpecDriftTest {
    private val shipped: Map<String, Any?> by lazy { load("modules/app/src/main/resources/application.yml") }

    @Test
    fun `configuration_md, application_yml and ConfigValidator name exactly the same ui keys`() {
        val documented = keysIn(repoFile("docs/configuration.md").readText())
        val inYaml = shipped.keys.filter { it.startsWith("$PREFIX.") }.sorted()
        val readByValidator = keysIn(repoFile("modules/app/src/main/kotlin/co/datapipelines/config/ConfigValidator.kt").readText())

        documented.shouldNotBeEmpty()
        documented shouldContainExactly EXPECTED
        inYaml shouldContainExactly EXPECTED
        readByValidator shouldContainExactly EXPECTED
    }

    @Test
    fun `the shipped defaults are the documented ones`() {
        shipped["$PREFIX.theme"] shouldBe "\${DATAPIPELINES_UI_THEME:dark}"
        shipped["$PREFIX.actively-developed-days"] shouldBe "\${DATAPIPELINES_UI_ACTIVELY_DEVELOPED_DAYS:14}"
        defaultOf("$PREFIX.actively-developed-days") shouldBe "${co.datapipelines.web.ui.UiProperties.DEFAULT_ACTIVELY_DEVELOPED_DAYS}"
    }

    @Test
    fun `the shipped ui defaults pass the production rules`() {
        // §7's closing rule, for this block: what a deployment that sets neither variable runs on
        // must satisfy the production checks — a broken default is fixed at the data.
        val report =
            ConfigValidator.validate(
                ConfigSnapshots.valid().copy(uiActivelyDevelopedDays = defaultOf("$PREFIX.actively-developed-days")),
            )
        report.violations shouldContainExactly emptyList()
    }

    @Test
    fun `the neighbouring blocks still bind - the ui block re-parented nothing`() {
        shipped["datapipelines.templates.max-body-chars"] shouldBe "\${DATAPIPELINES_TEMPLATES_MAX_BODY_CHARS:262144}"
        shipped["datapipelines.executions.event-retention-days"] shouldBe "\${DATAPIPELINES_EXECUTIONS_EVENT_RETENTION_DAYS:7}"
        shipped["datapipelines.executions.error-detail"] shouldBe "\${DATAPIPELINES_EXECUTIONS_ERROR_DETAIL:full}"
        shipped["datapipelines.audit.retention-days"] shouldBe "\${DATAPIPELINES_AUDIT_RETENTION_DAYS:365}"
    }

    /** The default out of a `${'$'}{VAR:default}` placeholder — what an unset variable resolves to. */
    private fun defaultOf(key: String): String {
        val raw = checkNotNull(shipped[key]?.toString()) { "application.yml has no $key" }
        return checkNotNull(PLACEHOLDER_DEFAULT.find(raw)) { "$key is not a placeholder with a default: $raw" }
            .groupValues[1]
    }

    private fun load(relative: String): Map<String, Any?> =
        YamlPropertySourceLoader()
            .load(relative, FileSystemResource(repoFile(relative)))
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { source -> source.propertyNames.map { name -> name to source.getProperty(name) } }
            .toMap()

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
        const val PREFIX = "datapipelines.ui"

        /** `${'$'}{VAR:default}` — group 1 is the default. */
        val PLACEHOLDER_DEFAULT = Regex("""^\$\{[A-Z0-9_]+:([^}]*)\}${'$'}""")

        val EXPECTED = listOf("$PREFIX.actively-developed-days", "$PREFIX.theme")

        val KEY_REGEX = Regex("""datapipelines\.ui\.[a-z0-9-]+""")
    }
}
