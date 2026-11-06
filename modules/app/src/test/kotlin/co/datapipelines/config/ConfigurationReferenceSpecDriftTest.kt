package co.datapipelines.config

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** Whole-reference coverage complements the domain-specific default and validation guards. */
class ConfigurationReferenceSpecDriftTest {
    private val inventory = ConfigurationReferenceInventory
    private val doc by lazy { File(inventory.root, "docs/configuration.md").readText() }
    private val resourceDir = File(inventory.root, "modules/app/src/main/resources")

    @Test
    fun `documented base template exactly matches every shipped key value and environment placeholder`() {
        val template = inventory.blocks(doc.substringAfter("## 5.").substringBefore("## 6.")).single()
        inventory.yaml(template) shouldBe inventory.yaml(File(resourceDir, "application.yml").readText())
    }

    @Test
    fun `every shipped posture file has an exact documented template`() {
        val shipped = resourceDir.listFiles().orEmpty().filter { it.name.startsWith("application-") && it.extension == "yml" }
        shipped.shouldNotBeEmpty()
        val blocks = inventory.blocks(doc.substringAfter("## 6.").substringBefore("## 7."))
        blocks.size shouldBe shipped.size
        shipped.forEach { file ->
            val block = blocks.single { it.lineSequence().first() == "# ${file.name}" }
            inventory.yaml(block) shouldBe inventory.yaml(file.readText())
        }
    }

    @Test
    fun `every shipped leaf has an explanatory reference row`() {
        val shipped = inventory.yaml(File(resourceDir, "application.yml").readText())
        shipped.keys.shouldNotBeEmpty()
        val rows = inventory.rows(doc.substringBefore("## 4."))
        rows.shouldNotBeEmpty()
        shipped.keys.filterNot { inventory.covered(it, rows) }.sorted() shouldContainExactly emptyList()
    }

    @Test
    fun `scalar defaults in explanatory tables agree with shipped fallback values`() {
        val shipped = inventory.yaml(File(resourceDir, "application.yml").readText())
        val defaults = inventory.scalarDefaults(doc.substringBefore("## 4.")).filterKeys { it in shipped }
        defaults.keys.shouldNotBeEmpty()
        defaults.forEach { (key, documented) ->
            val raw = shipped.getValue(key)
            val fallback = Regex("""^\$\{[^:}]+:(.*)}$""").matchEntire(raw)?.groupValues?.get(1) ?: raw
            (key to documented) shouldBe (key to fallback)
        }
    }

    @Test
    fun `every compiled property binding including unshipped fields has an explanatory row`() {
        val bindings = inventory.bindings()
        bindings.keys.shouldNotBeEmpty()
        val rows = inventory.rows(doc.substringBefore("## 4."))
        val missing = bindings.flatMap { (type, keys) -> keys.filterNot { inventory.covered(it, rows) }.map { "$type: $it" } }
        missing.sorted() shouldContainExactly emptyList()
    }

    @Test
    fun `direct property reads and key constants remain documented including refused legacy settings`() {
        val keys = inventory.directKeys()
        keys.shouldNotBeEmpty()
        val rows = inventory.rows(doc.substringBefore("## 4."))
        // Removed settings deliberately have refusal prose, not tunable rows.
        val removed = setOf("datapipelines.workspaces.provisioning-mode", "datapipelines.workspaces.open-join")
        keys.filterNot { inventory.covered(it, rows) || (it in removed && doc.contains("`$it`")) }.sorted() shouldContainExactly emptyList()
    }

    @Test
    fun `documented application rows still have a declaration`() {
        val known = inventory.bindings().values.flatten() + inventory.directKeys()
        val rows = inventory.rows(doc.substringBefore("## 4.")).filter { it.startsWith("datapipelines.") }
        rows
            .filterNot { inventory.covered(it, known.toSet()) || known.any { key -> inventory.covered(key, setOf(it)) } }
            .sorted() shouldContainExactly emptyList()
    }

    @Test
    fun `binding discovery walks actual constructor list map and array shapes`() {
        val keys = inventory.leaves(BindingFixture::class.java, "datapipelines.fixture")
        keys.sorted() shouldContainExactly
            listOf(
                "datapipelines.fixture.children[].timeout-seconds",
                "datapipelines.fixture.labels[]",
                "datapipelines.fixture.named.*.timeout-seconds",
                "datapipelines.fixture.nested.timeout-seconds",
            )
        keys.filterNot { inventory.covered(it, setOf("datapipelines.fixture.labels")) }.size shouldBe 3
    }

    @Test
    fun `source trace points to files that still exist`() {
        val trace = doc.substringAfter("## 8.").substringBefore("## 9.")
        val paths = Regex("`(modules/[^`]+\\.(?:kt|yml|xml))`").findAll(trace).map { it.groupValues[1] }.toSet()
        paths.shouldNotBeEmpty()
        paths.filterNot { File(inventory.root, it).isFile }.sorted() shouldContainExactly emptyList()
    }

    @Test
    fun `launcher arguments and sample defaults stay documented`() {
        val launchDoc = doc.substringAfter("## 10.").substringBefore("## Appendix")
        val launcher = File(inventory.root, "app.sh").readText()
        val caseFlags = Regex("(?m)^\\s*(--[a-z|-]+)\\)").findAll(launcher).flatMap { it.groupValues[1].split('|') }.toSet()
        caseFlags.shouldNotBeEmpty()
        caseFlags.filterNot { launchDoc.contains("`$it`") || launchDoc.contains("`$it ") }.sorted() shouldContainExactly emptyList()
        val defaults = inventory.scalarDefaults(launchDoc)
        val sample =
            Regex("(?m)^(SAMPLE_[A-Z_]+)=(.*)$")
                .findAll(File(inventory.root, "deploy/env/defaults.env").readText())
                .associate { it.groupValues[1] to it.groupValues[2] }
        sample.keys.shouldNotBeEmpty()
        sample.forEach { (key, value) -> (key to defaults[key]) shouldBe (key to value) }
        listOf("APP_COMPOSE_PROJECT", "APP_HOST_PORT", "HEALTH_WAIT_SECONDS", "LOADER_WAIT_SECONDS").forEach { key ->
            launcher.contains("\${$key:-${defaults.getValue(key)}}") shouldBe true
        }
    }

    @Test
    fun `inventory detects a missing row and changed defaults and environment mappings`() {
        val shipped = inventory.yaml("datapipelines:\n  probe: \${PROBE:10}")
        val changedDefault = inventory.yaml("datapipelines:\n  probe: \${PROBE:11}")
        val changedEnv = inventory.yaml("datapipelines:\n  probe: \${OTHER:10}")
        (shipped == changedDefault) shouldBe false
        (shipped == changedEnv) shouldBe false
        inventory.covered("datapipelines.probe", inventory.rows("Mention `datapipelines.probe` in prose.")) shouldBe false
        inventory.covered("datapipelines.probe", inventory.rows("| `datapipelines.probe` | `10` | Purpose |")) shouldBe true
        inventory.covered("datapipelines.providers[2].name", setOf("datapipelines.providers[].name")) shouldBe true
        inventory.covered("datapipelines.timeouts.lake", setOf("datapipelines.timeouts.<dialect>")) shouldBe true
        inventory.covered("datapipelines.timeouts.lake.extra", setOf("datapipelines.timeouts.<dialect>")) shouldBe false
    }

    private data class BindingFixture(
        val nested: NestedFixture,
        val children: List<NestedFixture>,
        val named: Map<String, NestedFixture>,
        val labels: Array<String>,
    )

    private data class NestedFixture(
        val timeoutSeconds: Int,
    )
}
