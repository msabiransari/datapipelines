package co.datapipelines.visualization

import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/**
 * Model parity (design record 2026-11-06 §5.1, lane 860 A.2b): [ChartModel] — what the server's mechanical check
 * judges — and the runtime core's `model` (`datapipelines-dashboard-chart.js`) — what the browser draws — reach the
 * same categories, series, cells (the drawn float64, the exact decimal string, the aggregate's text) and mark count
 * over every case of ONE corpus.
 *
 * The JS side is never a committed snapshot: the Gradle task `chartParityEmit` (this module's build file) evaluates the
 * CURRENT core on node before every run of this test and writes [JS_RESULT]; a missing or failed evaluation fails the
 * build. The corpus lives once, in modules/web's test resources ([PARITY_ROOT], relative to this module's directory —
 * the test's working directory): the 15 fixtures of the 854 research set, the 5 valid chart examples joined to their
 * variant's rows, and the record's §4.1 numeric cases. Validation parity is the other corpus: [ChartExamplesTest]
 * asserts the product side of the same 25 examples and expectations the reference's `validation-parity.mjs` reads.
 */
class ChartModelParityTest {
    @Test
    fun `the JS result covers exactly the corpus, above the non-vacuity floors`() {
        JS.keys shouldBe CASES.keys
        CASES.size shouldBeGreaterThanOrEqual MIN_MODELLED
        CASES.values.count { !it.model().noData } shouldBeGreaterThanOrEqual MIN_NON_EMPTY
        EXAMPLE_EXPECTATIONS.path("examples").size() shouldBeGreaterThanOrEqual MIN_VALIDATION_CASES
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caseNames")
    fun `ChartModel equals the runtime core's model`(name: String) {
        differences(name, CASES.getValue(name).model(), JS.getValue(name)).shouldBeEmpty()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caseNames")
    fun `every modelled document is admitted by the production save path`(name: String) {
        val tree = CASES.getValue(name).spec.deepCopy<ObjectNode>()
        tree.put("name", "examples/visualizations/parity")
        val outcome =
            ValidatorFakes.Fakes().visualizationValidator().validate(
                ValidatorFakes.WORKSPACE,
                ValidatorFakes.visualizationDocument(tree),
            )
        withClue((outcome as? ArtifactValidation.Invalid)?.result?.failures) {
            outcome.shouldBeInstanceOf<ArtifactValidation.Valid<*>>()
        }
    }

    @Test
    fun `the numeric cases meet the record's values on both sides`() {
        val numeric = CASES.filterKeys { it.startsWith("numeric/") }
        numeric.size shouldBe NUMERIC_CASES
        numeric.forEach { (name, case) ->
            val expect = case.expect
            val js = JS.getValue(name)
            withClue(name) {
                expect.get("categories_full")?.let { want ->
                    val wanted = want.map(JsonNode::asText)
                    case.model().categories.map { it.full } shouldBe wanted
                    js.path("categories").map { it.path("full").asText() } shouldBe wanted
                }
                expect.get("exact")?.let { want ->
                    val wanted = want.map { row -> row.map(JsonNode::asText) }
                    case.model().cells.map { row -> row.map { it.exact } } shouldBe wanted
                    js.path("cells").map { row -> row.map { it.path("exact").asText() } } shouldBe wanted
                }
            }
        }
    }

    /** One corpus case: the admitted document and the rows of its variant, as the server's model reads them. */
    class Case(
        val spec: JsonNode,
        bindings: JsonNode,
        val expect: JsonNode,
    ) {
        private val types: Map<String, LogicalType> =
            spec
                .path("inputs")
                .flatMap { input -> input.path("columns") }
                .associate { it.path("name").asText() to LogicalType.valueOf(it.path("type").asText()) }

        private val rows: List<Map<String, Any?>> =
            (0 until bindings.maxOfOrNull { it.size() }.orZero()).map { i ->
                bindings.properties().associate { (field, values) -> field to wire(values.get(i)) }
            }

        fun model(): ChartModel = ChartModel.of(spec.path("config"), types, rows)

        private fun Int?.orZero(): Int = this ?: 0

        /** A delivered value as a JSON reader hands it over: a string, a number, a boolean or null. */
        private fun wire(node: JsonNode?): Any? =
            when {
                node == null || node.isNull -> null
                node.isTextual -> node.asText()
                node.isNumber -> node.numberValue()
                node.isBoolean -> node.asBoolean()
                else -> error("a binding value that is not a scalar: $node")
            }
    }

    companion object {
        /** The parity corpus, relative to this module's directory (the test JVM's working directory). */
        const val PARITY_ROOT = "../web/src/test/resources/chart-parity"

        /** The runtime core's model of every case, written by `chartParityEmit` before this run. */
        const val JS_RESULT = "build/chart-parity/js-model.json"

        const val MIN_VALIDATION_CASES = 20

        /** 25 at the first delivery; 860b's two fixtures and one numeric case raise it to 28 — dropping any corpus entry goes red. */
        const val MIN_MODELLED = 28
        const val MIN_NON_EMPTY = 15

        /**
         * The record's three §4.1 cases, the exact-category grammar case (the decimal grammar and the trim both sides
         * share) and the mean's half-way tie (the only case that tells HALF-EVEN from UP or HALF-UP), plus 860b R4's
         * shortest-digits case (a float category's text is the browser's: the smallest subnormal is `5e-324`, not the
         * JDK's `4.9E-324`).
         */
        const val NUMERIC_CASES = 6

        private val mapper = ArtifactJson.mapper

        private val CASES: Map<String, Case> by lazy {
            val root = File(PARITY_ROOT)
            val corpus = mapper.readTree(File(root, "corpus.json"))
            val fixture = { dir: String, name: String -> mapper.readTree(File(root, "$dir/$name.json")) }
            val cases = LinkedHashMap<String, Case>()
            corpus.path("fixtures").map(JsonNode::asText).forEach { name ->
                val f = fixture("fixtures", name)
                cases["fixture/$name"] = Case(f.path("spec"), f.path("bindings"), f.path("expect"))
            }
            corpus.path("examples").map(JsonNode::asText).forEach { name ->
                val doc = resource("/chart-examples/valid/$name.json")
                cases["example/$name"] = Case(doc, fixture("fixtures", name).path("bindings"), mapper.createObjectNode())
            }
            corpus.path("numeric").map(JsonNode::asText).forEach { name ->
                val f = fixture("numeric", name)
                cases["numeric/$name"] = Case(f.path("spec"), f.path("bindings"), f.path("expect"))
            }
            cases
        }

        private val JS: Map<String, JsonNode> by lazy {
            val result = File(JS_RESULT)
            check(result.isFile) { "no JS model at ${result.absolutePath}: chartParityEmit did not run before this test" }
            mapper
                .readTree(result)
                .path("cases")
                .properties()
                .associate { (name, model) -> name to model }
        }

        private val EXAMPLE_EXPECTATIONS: JsonNode by lazy { resource("/chart-examples/expectations.json") }

        /** A test-classpath JSON resource (this module's own chart examples). */
        private fun resource(path: String): JsonNode =
            checkNotNull(ChartModelParityTest::class.java.getResourceAsStream(path)) { "no test resource $path" }.use(mapper::readTree)

        @JvmStatic
        fun caseNames(): List<String> = CASES.keys.toList()

        /** Every field where the two models differ, each naming the case and the item or cell. */
        fun differences(
            name: String,
            kotlin: ChartModel,
            js: JsonNode,
        ): List<String> {
            val out = mutableListOf<String>()

            fun diff(
                path: String,
                k: Any?,
                j: Any?,
            ) {
                if (k != j) out += "$name $path: ChartModel=$k core=$j"
            }

            fun items(
                label: String,
                k: List<ChartModel.Item>,
                j: JsonNode,
            ) {
                diff("$label.size", k.size, j.size())
                k.zip(j.toList()).forEachIndexed { i, (ki, ji) ->
                    diff("$label[$i].key", ki.key, ji.path("key").asText())
                    diff("$label[$i].first", ki.first, ji.path("first").asInt())
                    diff("$label[$i].full", ki.full, ji.path("full").asText())
                }
            }

            items("categories", kotlin.categories, js.path("categories"))
            items("series", kotlin.series, js.path("series"))
            val cells = js.path("cells")
            diff("cells.size", kotlin.cells.size, cells.size())
            kotlin.cells.zip(cells.toList()).forEachIndexed { s, (kRow, jRow) ->
                diff("cells[$s].size", kRow.size, jRow.size())
                kRow.zip(jRow.toList()).forEachIndexed { c, (k, j) ->
                    diff("cells[$s][$c].num", k.num, j.get("num")?.takeUnless { it.isNull }?.asDouble())
                    diff("cells[$s][$c].exact", k.exact, j.get("exact")?.takeUnless { it.isNull }?.asText())
                    diff("cells[$s][$c].aggregate", k.aggregate, j.get("aggregate")?.takeUnless { it.isNull }?.asText())
                }
            }
            diff("markCount", kotlin.markCount, js.path("markCount").asInt())
            return out
        }
    }
}
