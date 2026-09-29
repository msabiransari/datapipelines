package co.datapipelines.visualization

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.FileSystemResource

/**
 * `datapipelines.visualization.*` held equal in FIVE places (the `ParametersConfigKeysSpecDriftTest` shape): the
 * doc (configuration.md §3.33 and its §5 template), `application.yml`, `ConfigValidator`'s rule file
 * (`VisualizationRules.kt`, keys AND bounds — `app` cannot import this module, so it carries twins), and
 * [VisualizationKey]. And the YAML NEIGHBOURS (MISTAKES.md: an inserted block silently reparents its
 * neighbours): the persistence block above still binds under `datapipelines.persistence.*`, and
 * `db-scheduler.*` below is still top-level.
 */
class VisualizationConfigKeysSpecDriftTest {
    private val expected = VisualizationKey.entries.map { it.path }.sorted()
    private val shipped: Map<String, Any?> by lazy {
        YamlPropertySourceLoader()
            .load("application.yml", FileSystemResource(VisualizationTestFiles.repoFile("modules/app/src/main/resources/application.yml")))
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { source -> source.propertyNames.map { it to source.getProperty(it) } }
            .toMap()
    }
    private val section: String by lazy {
        VisualizationTestFiles
            .read(
                "docs/configuration.md",
            ).substringAfter("### 3.33 Visualizations and dashboards (#10)")
            .substringBefore("\n---")
    }

    @Test
    fun `the doc table, the doc template, application yml, the boot rule and the enum name the same five keys`() {
        val doc = VisualizationTestFiles.read("docs/configuration.md")
        val template = doc.substringAfter("  visualization:                   # §3.33").substringBefore("\n\n")
        val templateKeys =
            Regex("""^\s{4}([a-z-]+):""", RegexOption.MULTILINE).findAll(template).map { "${VisualizationKey.PREFIX}.${it.groupValues[1]}" }
        expected.size shouldBe 5
        withClue("configuration.md §3.33 table") { keysIn(section) shouldContainExactly expected }
        withClue("configuration.md §5 template") { templateKeys.sorted().toList() shouldContainExactly expected }
        withClue("application.yml") {
            shipped.keys.filter { it.startsWith("${VisualizationKey.PREFIX}.") }.sorted() shouldContainExactly expected
        }
        withClue("VisualizationRules.kt") { keysIn(VisualizationTestFiles.read(RULE_FILE)) shouldContainExactly expected }
    }

    @Test
    fun `the defaults agree - the enum, the doc table and application yml's placeholders`() {
        VisualizationKey.entries.forEach { key ->
            withClue(key.path) {
                PLACEHOLDER.find(shipped.getValue(key.path).toString())!!.groupValues[1] shouldBe key.default.toString()
                Regex("""\| `${Regex.escape(key.path)}` \| `(\d+)` \|""").find(section)!!.groupValues[1] shouldBe key.default.toString()
            }
        }
    }

    @Test
    fun `the boot rule's bounds are the enum's - app carries twins, and this is what keeps them twins`() {
        val bounds =
            BOUND.findAll(VisualizationTestFiles.read(RULE_FILE)).associate { m ->
                m.groupValues[1] to (m.groupValues[2].replace("_", "").toLong() to m.groupValues[3].replace("_", "").toLong())
            }
        bounds.keys.sorted() shouldContainExactly expected
        VisualizationKey.entries.forEach { key -> withClue(key.path) { bounds.getValue(key.path) shouldBe (key.min to key.max) } }
    }

    @Test
    fun `the neighbours did not move - persistence binds under datapipelines, db-scheduler stays top-level`() {
        listOf("enabled", "writers", "audit.enabled").forEach { key ->
            withClue(key) { shipped.containsKey("datapipelines.persistence.$key") shouldBe true }
        }
        shipped.containsKey("db-scheduler.table-name") shouldBe true
        shipped.keys.none { it.startsWith("datapipelines.visualization.") && it.count { c -> c == '.' } != 2 } shouldBe true
        shipped.keys.none { it.startsWith("datapipelines.persistence.visualization") } shouldBe true
    }

    private fun keysIn(text: String): List<String> =
        KEY
            .findAll(text)
            .map { it.value }
            .distinct()
            .sorted()
            .toList()

    private companion object {
        const val RULE_FILE = "modules/app/src/main/kotlin/co/datapipelines/config/VisualizationRules.kt"
        val KEY = Regex("""datapipelines\.visualization\.[a-z]+(?:-[a-z]+)*""")
        val PLACEHOLDER = Regex("""^\$\{[A-Z0-9_]+:([^}]*)\}$""")
        val BOUND = Regex("""Bound\(key = "([a-z.-]+)", min = ([0-9_]+), max = ([0-9_]+)\)""")
    }
}
