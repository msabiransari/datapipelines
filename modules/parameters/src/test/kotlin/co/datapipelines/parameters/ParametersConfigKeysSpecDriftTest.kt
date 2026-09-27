package co.datapipelines.parameters

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.FileSystemResource

/**
 * `datapipelines.parameters.*` held equal in FIVE places (the `*ConfigKeysSpecDriftTest` shape): the doc
 * (configuration.md §3.30 — the one definition, D8 — and its §5 template), `application.yml` (the
 * shipped defaults), `ConfigValidator`'s rule file (`ParametersRules.kt`, keys AND bounds — `app`
 * cannot import this module, so it carries twins), and [ParametersKey] (defaults and bounds, which
 * both [ParametersConfig] and [ParametersProperties] read). And the YAML NEIGHBOURS (MISTAKES.md: an
 * inserted block silently reparents its neighbours): the scheduler block above still binds under
 * `datapipelines.scheduler.*`, and `db-scheduler.*` below is still top-level.
 */
class ParametersConfigKeysSpecDriftTest {
    private val expected = ParametersKey.entries.map { it.path }.sorted()
    private val shipped: Map<String, Any?> by lazy {
        YamlPropertySourceLoader()
            .load("application.yml", FileSystemResource(ParametersTestFiles.repoFile("modules/app/src/main/resources/application.yml")))
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { source -> source.propertyNames.map { it to source.getProperty(it) } }
            .toMap()
    }

    @Test
    fun `the doc table, the doc template, application yml, the boot rule and the enum name the same fifteen keys`() {
        val doc = ParametersTestFiles.read("docs/configuration.md")
        val section = doc.substringAfter("### 3.30 Parameter engine (#194)").substringBefore("\n---")
        val table =
            KEY
                .findAll(section)
                .map { it.value }
                .distinct()
                .sorted()
                .toList()
        val template = doc.substringAfter("  parameters:                      # §3.30").substringBefore("\n\n")
        val templateKeys =
            Regex("""^\s{4}([a-z-]+):""", RegexOption.MULTILINE).findAll(template).map {
                "${ParametersKey.PREFIX}.${it.groupValues[1]}"
            }
        val rule = ParametersTestFiles.read(RULE_FILE)
        expected.size shouldBe 15
        withClue("configuration.md §3.30 table") { table shouldContainExactly expected }
        withClue("configuration.md §5 template") { templateKeys.sorted().toList() shouldContainExactly expected }
        withClue("application.yml") {
            shipped.keys.filter { it.startsWith("${ParametersKey.PREFIX}.") }.sorted() shouldContainExactly
                expected
        }
        withClue("ParametersRules.kt") {
            KEY
                .findAll(rule)
                .map { it.value }
                .distinct()
                .sorted()
                .toList() shouldContainExactly expected
        }
    }

    @Test
    fun `the defaults agree - the enum, the doc table and application yml's placeholders`() {
        val section =
            ParametersTestFiles
                .read(
                    "docs/configuration.md",
                ).substringAfter("### 3.30 Parameter engine (#194)")
                .substringBefore("\n---")
        ParametersKey.entries.forEach { key ->
            withClue(key.path) {
                val placeholder = shipped.getValue(key.path).toString()
                PLACEHOLDER.find(placeholder)!!.groupValues[1] shouldBe key.default.toString()
                Regex("""\| `${Regex.escape(key.path)}` \| `(\d+)` \|""").find(section)!!.groupValues[1] shouldBe key.default.toString()
            }
        }
    }

    @Test
    fun `the boot rule's bounds are the enum's - app carries twins, and this is what keeps them twins`() {
        val bounds =
            BOUND.findAll(ParametersTestFiles.read(RULE_FILE)).associate { m ->
                m.groupValues[1] to
                    (
                        m.groupValues[2].replace("_", "").toLong() to
                            m.groupValues[3]
                                .takeIf { it != "null" }
                                ?.replace("_", "")
                                ?.toLong()
                    )
            }
        bounds.keys.sorted() shouldContainExactly expected
        ParametersKey.entries.forEach { key -> withClue(key.path) { bounds.getValue(key.path) shouldBe (key.min to key.max) } }
    }

    @Test
    fun `the neighbours did not move - scheduler binds under datapipelines, db-scheduler stays top-level`() {
        listOf("threads", "max-schedules-per-workspace", "enabled").forEach { key ->
            withClue(key) { shipped.containsKey("datapipelines.scheduler.$key") shouldBe true }
        }
        shipped.containsKey("db-scheduler.table-name") shouldBe true
        shipped.keys.none {
            it.startsWith(
                "datapipelines.parameters.scheduler",
            ) || it.startsWith("datapipelines.parameters.db-scheduler")
        } shouldBe
            true
    }

    private companion object {
        const val RULE_FILE = "modules/app/src/main/kotlin/co/datapipelines/config/ParametersRules.kt"
        val KEY = Regex("""datapipelines\.parameters\.[a-z]+(?:-[a-z]+)*""")
        val PLACEHOLDER = Regex("""^\$\{[A-Z0-9_]+:([^}]*)\}$""")
        val BOUND = Regex("""Bound\(key = "([a-z.-]+)", min = ([0-9_]+), max = ([0-9_]+|null)\)""")
    }
}
