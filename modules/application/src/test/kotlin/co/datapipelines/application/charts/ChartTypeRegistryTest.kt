package co.datapipelines.application.charts

import co.datapipelines.jsrendered.JsRenderedEngineFiles
import co.datapipelines.visualization.ChartSchemas
import co.datapipelines.visualization.ChartType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * R-REG (design record 2026-11-14-js-rendered-charts §4.2; brief 985 A.5, G4): the chart types are ONE closed list,
 * held six ways that must be equal — the `ChartType` enum, the schema files' `config.mark.type` constants, the
 * skeleton's `familyConfig` branches, the engine's template files, the names `JsRendered.types()` returns in the
 * sandbox, and enums.md §63 — and each type's channel descriptor on the enum is the one its template registers, node
 * for node. Every type has at least five chart fixtures, one of them the empty case.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChartTypeRegistryTest {
    private val sandbox = JsRenderedSandbox()
    private val json = jacksonObjectMapper()

    @AfterAll
    fun close() = sandbox.close()

    /** `JsRendered.types()` in the sandbox: `[{name, channels}]`. */
    private val registered: List<Map<String, Any?>> by lazy {
        json.readValue(sandbox.evaluate("return JSON.stringify(JsRendered.types());", null) as String)
    }

    private fun schema(file: String): JsonNode = json.readTree(ChartSchemas.bytes(file))

    private val enumValues: Set<String> get() = ChartType.WIRE_VALUES.toSet()

    @Test
    fun `the six sets of chart types are equal`() {
        val enums = enumValues
        enums.shouldNotBeEmpty()
        val sets =
            mapOf(
                "schema mark.type constants" to schemaConstants(),
                "skeleton familyConfig branches" to skeletonBranches(),
                "engine template files" to templateFiles(),
                "JsRendered.types() in the sandbox" to registered.map { it["name"] as String }.toSet(),
                "enums.md §63" to enumsSection(),
            )
        sets
            .filterValues { it != enums }
            .mapValues { (_, v) -> "enum $enums vs $v" }
            .entries
            .toList() shouldBe emptyList()
    }

    @Test
    fun `each type's descriptor on the enum is the one its template registers, node for node`() {
        registered.forEach { t ->
            val type = checkNotNull(ChartType.fromWire(t["name"] as String)) { "no ChartType for ${t["name"]}" }
            t["channels"] shouldBe type.channels.map { it.wire() }
        }
        registered.size shouldBe ChartType.entries.size
    }

    @Test
    fun `a choice the engine registers has disjoint type sets`() {
        val overlaps = mutableListOf<String>()

        fun walk(
            node: Any?,
            where: String,
        ) {
            if (node !is Map<*, *>) return
            if (node["node"] == "oneOf") {
                val all = (node["of"] as List<*>).flatMap { (it as Map<*, *>)["types"] as List<*> }
                if (all.size != all.toSet().size) overlaps += "$where: $all"
                (node["of"] as List<*>).size shouldBeGreaterThanOrEqual 2
            }
            node.values.forEach { child ->
                if (child is List<*>) child.forEach { walk(it, where) } else walk(child, where)
            }
        }
        var choices = 0
        registered.forEach { t ->
            (t["channels"] as List<*>).forEach { c ->
                val ch = c as Map<*, *>
                if ((ch["node"] as Map<*, *>)["node"] == "oneOf") choices++
                walk(ch["node"], "${t["name"]}.${ch["key"]}")
            }
        }
        choices shouldBeGreaterThanOrEqual 1
        overlaps shouldBe emptyList()
    }

    @Test
    fun `every type has at least five chart fixtures, one of them the empty case`() {
        ChartGuardFiles.fixtureTypes().toSet() shouldBe enumValues
        ChartType.entries.forEach { type ->
            val names = ChartGuardFiles.chartFixtures(type.fixturesDirectory)
            names.size shouldBeGreaterThanOrEqual MIN_FIXTURES
            val empty =
                names.filter { name ->
                    val out = ChartGuardFiles.resource("${ChartGuardFiles.EMITTED}/${type.wire}/$name/$FULL_FRAME.output.json")
                    json.readTree(out)["state"].asText() == "no-data"
                }
            empty.shouldNotBeEmpty()
        }
    }

    private fun schemaConstants(): Set<String> =
        ChartSchemas.FILES
            .filter { it != ChartSchemas.SKELETON }
            .map { file ->
                val c = schema(file).at("/\$defs/config/properties/mark/properties/type/const")
                check(c.isTextual) { "$file has no config.mark.type constant" }
                c.asText()
            }.toSet()

    private fun skeletonBranches(): Set<String> {
        val branches = schema(ChartSchemas.SKELETON).at("/\$defs/familyConfig/oneOf")
        check(branches.isArray && branches.size() > 0) { "the skeleton has no familyConfig branches" }
        return branches
            .map { b ->
                val ref = b["\$ref"].asText()
                check(ref.endsWith(".schema.json#/\$defs/config")) { "unexpected familyConfig branch $ref" }
                ref.removeSuffix(".schema.json#/\$defs/config")
            }.toSet()
    }

    private fun templateFiles(): Set<String> =
        JsRenderedEngineFiles.names
            .filter { it.startsWith("types/") }
            .map { it.removePrefix("types/").removeSuffix(".js") }
            .toSet()

    private fun enumsSection(): Set<String> {
        val doc = ChartGuardFiles.repoFile("docs/enums.md").readText()
        val start = doc.indexOf("## 63. `ChartType`")
        check(start >= 0) { "§63 ChartType not found in docs/enums.md" }
        return ROW
            .findAll(doc.substring(start).substringBefore("\n---"))
            .map { it.groupValues[1] }
            .toSet()
            .also { check(it.isNotEmpty()) { "no rows parsed under §63" } }
    }

    private companion object {
        const val MIN_FIXTURES = 5
        const val FULL_FRAME = "960x540"
        val ROW = Regex("^\\| `([a-z][a-z0-9_]*)` \\|", RegexOption.MULTILINE)
    }
}
