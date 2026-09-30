package co.datapipelines.visualization

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.FileSystemResource

/**
 * `datapipelines.dashboards.*` held equal in SEVEN places (the [VisualizationConfigKeysSpecDriftTest] shape, plus
 * the three deploy mirrors): the doc (configuration.md §3.34 and its §5 template), `application.yml`,
 * `ConfigValidator`'s rule file (`DashboardRuntimeRules.kt`, keys, bounds AND relations — `app` cannot import this
 * module, so it carries twins), the deploy files, and [DashboardRuntimeKey]. And the YAML NEIGHBOURS (MISTAKES.md:
 * an inserted block silently reparents its neighbours): `visualization.*` above still binds under
 * `datapipelines.visualization.*`, `db-scheduler.*` below is still top-level, and no `dashboards` key escaped its
 * three groups.
 */
class DashboardRuntimeConfigKeysSpecDriftTest {
    private val expected = DashboardRuntimeKey.entries.map { it.path }.sorted()
    private val shipped: Map<String, Any?> by lazy {
        YamlPropertySourceLoader()
            .load("application.yml", FileSystemResource(VisualizationTestFiles.repoFile("modules/app/src/main/resources/application.yml")))
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { source -> source.propertyNames.map { it to source.getProperty(it) } }
            .toMap()
    }
    private val section: String by lazy {
        VisualizationTestFiles
            .read("docs/configuration.md")
            .substringAfter("### 3.34 The dashboard runtime (#10 L2)")
            .substringBefore("\n---")
    }

    @Test
    fun `the doc table, the doc template, application yml, the boot rule and the enum name the same ten keys`() {
        expected.size shouldBe 10
        withClue("configuration.md §3.34 table") { keysIn(section) shouldContainExactly expected }
        withClue("configuration.md §5 template") { templateKeys() shouldContainExactly expected }
        withClue("application.yml") {
            shipped.keys.filter { it.startsWith("${DashboardRuntimeKey.PREFIX}.") }.sorted() shouldContainExactly expected
        }
        withClue("DashboardRuntimeRules.kt") { boundsInRule().keys.sorted() shouldContainExactly expected }
    }

    @Test
    fun `the defaults agree - the enum, the doc table and application yml's placeholders`() {
        DashboardRuntimeKey.entries.forEach { key ->
            withClue(key.path) {
                PLACEHOLDER.find(shipped.getValue(key.path).toString())!!.groupValues[1] shouldBe key.default.toString()
                Regex("""\| `${Regex.escape(key.path)}` \| `(\d+)` \|""").find(section)!!.groupValues[1] shouldBe key.default.toString()
            }
        }
    }

    @Test
    fun `the deploy mirrors carry every key at its default - compose, defaults env and the secrets example`() {
        val compose = VisualizationTestFiles.read("deploy/compose.yml")
        val defaults = VisualizationTestFiles.read("deploy/env/defaults.env")
        val example = VisualizationTestFiles.read("deploy/secrets.env.example")
        DashboardRuntimeKey.entries.forEach { key ->
            val env =
                "DATAPIPELINES_DASHBOARDS_" +
                    key.key
                        .substringAfter('.')
                        .replace('-', '_')
                        .uppercase()
            withClue("${key.path} → $env") {
                compose.contains("$env: \${$env:-${key.default}}") shouldBe true
                defaults.lines().contains("$env=${key.default}") shouldBe true
                example.lines().contains("# $env=${key.default}") shouldBe true
                shipped.getValue(key.path).toString() shouldBe "\${$env:${key.default}}"
            }
        }
    }

    @Test
    fun `the boot rule's bounds are the enum's - app carries twins, and this is what keeps them twins`() {
        val bounds = boundsInRule()
        DashboardRuntimeKey.entries.forEach { key -> withClue(key.path) { bounds.getValue(key.path) shouldBe (key.min to key.max) } }
    }

    @Test
    fun `the boot rule's three relations are the config's - the same pairs, in the same direction`() {
        val rule = VisualizationTestFiles.read(RULE_FILE)
        val pairs =
            Regex("""Relation\(\s*"([a-z.-]+)",\s*"([a-z.-]+)",?\s*\)""")
                .findAll(rule)
                .map { it.groupValues[1] to it.groupValues[2] }
                .toList()
        pairs shouldContainExactly
            listOf(
                DashboardRuntimeKey.DEFAULT_REFRESH_SECONDS.path to DashboardRuntimeKey.MAX_REFRESH_SECONDS.path,
                DashboardRuntimeKey.MAX_BYTES_PER_SOURCE.path to DashboardRuntimeKey.MAX_BYTES_PER_REFRESH.path,
                DashboardRuntimeKey.MAX_EXECUTIONS_PER_REFRESH.path to
                    DashboardRuntimeKey.MAX_CONCURRENT_DASHBOARD_EXECUTIONS_PER_INSTANCE.path,
            )
        // …and the config refuses each pair the way the rule does (one past, naming both keys).
        assertThrows<IllegalArgumentException> { DashboardRuntimeConfig(defaultRefreshSeconds = 901) }
            .message!!
            .contains("default-refresh-seconds") shouldBe true
        assertThrows<IllegalArgumentException> { DashboardRuntimeConfig(maxBytesPerSource = 33_554_433) }
            .message!!
            .contains("max-bytes-per-refresh") shouldBe true
        assertThrows<IllegalArgumentException> { DashboardRuntimeConfig(maxExecutionsPerRefresh = 41) }
            .message!!
            .contains("max-concurrent-dashboard-executions-per-instance") shouldBe true
    }

    @Test
    fun `the properties binding twin flattens to the same config as the defaults`() {
        DashboardRuntimeProperties().toConfig() shouldBe DashboardRuntimeConfig()
    }

    @Test
    fun `the neighbours did not move - visualization still binds under datapipelines, db-scheduler stays top-level`() {
        listOf("max-visualizations-per-dashboard", "max-columns-per-input").forEach { key ->
            withClue(key) { shipped.containsKey("datapipelines.visualization.$key") shouldBe true }
        }
        shipped.containsKey("db-scheduler.table-name") shouldBe true
        shipped.keys.none { it.startsWith("datapipelines.dashboards.") && it.count { c -> c == '.' } != 3 } shouldBe true
        shipped.keys.none { it.startsWith("datapipelines.visualization.dashboards") } shouldBe true
        shipped.keys.none { it.startsWith("datapipelines.persistence.dashboards") } shouldBe true
        shipped.keys.none { it.startsWith("dashboards.") } shouldBe true
    }

    /** Every key in the template block, reconstructed from its two-level nesting (`admission` / `results` / `timeouts`). */
    private fun templateKeys(): List<String> {
        val block =
            VisualizationTestFiles
                .read("docs/configuration.md")
                .substringAfter("  dashboards:                      # §3.34")
                .substringBefore("\n\n")
        var group = ""
        return block
            .lines()
            .drop(1)
            .mapNotNull { line ->
                GROUP.matchEntire(line)?.let {
                    group = it.groupValues[1]
                    null
                } ?: KEY_LINE.matchEntire(line)?.let { "${DashboardRuntimeKey.PREFIX}.$group.${it.groupValues[1]}" }
            }.sorted()
    }

    private fun boundsInRule(): Map<String, Pair<Long, Long>> =
        BOUND.findAll(VisualizationTestFiles.read(RULE_FILE)).associate { m ->
            m.groupValues[1] to (m.groupValues[2].replace("_", "").toLong() to m.groupValues[3].replace("_", "").toLong())
        }

    private fun keysIn(text: String): List<String> =
        KEY
            .findAll(text)
            .map { it.value }
            .distinct()
            .sorted()
            .toList()

    private companion object {
        const val RULE_FILE = "modules/app/src/main/kotlin/co/datapipelines/config/DashboardRuntimeRules.kt"
        val KEY = Regex("""datapipelines\.dashboards\.(?:admission|results|timeouts)\.[a-z]+(?:-[a-z]+)*""")
        val GROUP = Regex("""^ {4}([a-z]+):$""")
        val KEY_LINE = Regex("""^ {6}([a-z]+(?:-[a-z]+)*): .*$""")
        val PLACEHOLDER = Regex("""^\$\{[A-Z0-9_]+:([^}]*)\}$""")
        val BOUND = Regex("""Bound\(key = "([a-z.-]+)", min = ([0-9_]+), max = ([0-9_]+)\)""")
    }
}
