package co.datapipelines.config

import co.datapipelines.logging.ObservabilityLoggingFormatPostProcessor
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import java.io.File

/**
 * The observability keys (configuration.md §3.15) and the three operator-facing management keys (§3.14),
 * pinned in the places they have to agree: the doc's tables (the authority), `application.yml` (the shipped
 * placeholders), the doc's §5 full template, and the one binding that reads §3.15
 * ([ObservabilityLoggingFormatPostProcessor.SWITCH_KEY]).
 *
 * #708 removed the two `tracing` keys — declared, documented and never wired — with no deprecation window
 * (the failure-flow and telemetry record §17 row 12). A key that returns in any one place without the other
 * three is red here, by name. The management rows are the scrape's boundary (observability.md §4.2): the
 * port, its loopback default, and the exposure list that must stay exactly `health,prometheus`.
 *
 * The [MailConfigKeysSpecDriftTest] shape; the untouched neighbours of the edited yml block are asserted too,
 * because a block edited at the wrong depth silently re-parents what follows it.
 */
class ObservabilityConfigKeysSpecDriftTest {
    private val shipped: Map<String, Any?> by lazy { load("application.yml", FileSystemResource(repoFile(YML))) }
    private val configurationMd: String by lazy { repoFile("docs/configuration.md").readText() }

    /** §5's full `application.yml` template, parsed as YAML. */
    private val template: Map<String, Any?> by lazy {
        val section = section(configurationMd, "## 5. ", "## 6. ")
        val block = TEMPLATE_BLOCK.find(section)?.groupValues?.get(1) ?: error("configuration.md §5 has no yaml block")
        load("configuration.md §5", ByteArrayResource(block.toByteArray()))
    }

    @Test
    fun `configuration_md §3_15, application_yml, the §5 template and the binding name exactly the same observability keys`() {
        val documented = firstCellKeys(section(configurationMd, "### 3.15 ", "### 3.16 "))
        val inYaml = shipped.keys.filter { it.startsWith("$PREFIX.") }.sorted()
        val inTemplate = template.keys.filter { it.startsWith("$PREFIX.") }.sorted()

        withClue("configuration.md §3.15 rows") { documented shouldContainExactly EXPECTED }
        withClue("application.yml keys under $PREFIX") { inYaml shouldContainExactly EXPECTED }
        withClue("configuration.md §5 template keys under $PREFIX") { inTemplate shouldContainExactly EXPECTED }
        ObservabilityLoggingFormatPostProcessor.SWITCH_KEY shouldBe EXPECTED.single()
    }

    @Test
    fun `the management port, its address and its exposure list agree in §3_14, application_yml and the §5 template`() {
        val documented = firstCellKeys(section(configurationMd, "### 3.14 ", "### 3.15 ")).filter { it in MANAGEMENT }
        withClue("configuration.md §3.14 rows") { documented.sorted() shouldContainExactly MANAGEMENT.keys.sorted() }
        MANAGEMENT.forEach { (key, value) ->
            withClue("application.yml $key") { shipped[key] shouldBe value }
            withClue("configuration.md §5 template $key") { template[key] shouldBe value }
        }
    }

    @Test
    fun `the neighbouring keys still bind - the observability block re-parented nothing`() {
        shipped["datapipelines.audit.retention-days"] shouldBe "\${DATAPIPELINES_AUDIT_RETENTION_DAYS:365}"
        shipped["datapipelines.bootstrap.datasources-file"] shouldBe "\${DATAPIPELINES_BOOTSTRAP_DATASOURCES_FILE:}"
        shipped["management.health.diskspace.enabled"] shouldBe false
    }

    private fun load(
        name: String,
        resource: Resource,
    ): Map<String, Any?> =
        YamlPropertySourceLoader()
            .load(name, resource)
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { source -> source.propertyNames.map { key -> key to source.getProperty(key) } }
            .toMap()

    /** The text from the line starting with [start] to the next line starting with [end]. */
    private fun section(
        text: String,
        start: String,
        end: String,
    ): String {
        val lines = text.lines()
        val from = lines.indexOfFirst { it.startsWith(start) }
        check(from >= 0) { "configuration.md has no '$start' heading" }
        val to = lines.drop(from + 1).indexOfFirst { it.startsWith(end) }
        check(to >= 0) { "configuration.md has no '$end' heading after '$start'" }
        return lines.subList(from, from + 1 + to).joinToString("\n")
    }

    /** The backticked key in the first cell of every table row. */
    private fun firstCellKeys(section: String): List<String> =
        section
            .lineSequence()
            .mapNotNull { FIRST_CELL_KEY.find(it.trim())?.groupValues?.get(1) }
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
        const val YML = "modules/app/src/main/resources/application.yml"
        const val PREFIX = "datapipelines.observability"

        val EXPECTED = listOf("$PREFIX.logging.format")

        /** The three operator-facing management keys and their shipped values (the scrape's boundary, §4.2). */
        val MANAGEMENT =
            mapOf(
                "management.server.port" to "\${MANAGEMENT_SERVER_PORT:9090}",
                "management.server.address" to "\${MANAGEMENT_SERVER_ADDRESS:127.0.0.1}",
                "management.endpoints.web.exposure.include" to "health,prometheus",
            )

        val FIRST_CELL_KEY = Regex("""^\|\s*`([a-z][a-z0-9.-]*\.[a-z0-9.-]+)`\s*\|""")
        val TEMPLATE_BLOCK = Regex("""(?s)```yaml\n(.*?)\n```""")
    }
}
