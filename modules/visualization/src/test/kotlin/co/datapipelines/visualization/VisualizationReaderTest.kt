package co.datapipelines.visualization

import co.datapipelines.pipeline.ValidationFailure
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.visualization.DocumentFixtures.obj
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * [VisualizationReader]: the document binds whole, or every shape problem comes back at once with its path;
 * the four bounds refuse BEFORE their members are read; the key tables are the model's own properties.
 */
class VisualizationReaderTest {
    private val reader = VisualizationReader()

    @Test
    fun `the spec's worked document binds unchanged - every field where the spec puts it`() {
        val document = read(DocumentFixtures.visualization())
        document.name shouldBe DocumentFixtures.VISUALIZATION_NAME
        val body = document.body
        body.displayName shouldBe "Monthly revenue"
        body.renderer shouldBe RendererSpec(RendererKind.PLOTLY, "4")
        body.inputs.keys shouldBe setOf("revenue")
        body.inputs
            .getValue("revenue")
            .columns
            .map { it.name to it.type } shouldBe
            listOf("month" to LogicalType.DATE, "amount" to LogicalType.DECIMAL)
        body.transform shouldBe TransformBinding(ArtifactRef("finance/transforms/revenue_bars", 2), mapOf("rows" to "revenue"))
        body.bindings shouldBe mapOf("data[0].x" to "month_labels", "data[0].y" to "amounts")
        body.presentation?.tokens shouldBe mapOf("series" to "categorical")
        val case = body.tests!!.cases.single()
        case.fixtures
            .getValue("revenue")
            .single()
            .get("amount")
            .asText() shouldBe "10.5"
        case.assertions shouldBe listOf(Assertion(AssertionKind.RENDERED), Assertion(AssertionKind.TRACE_COUNT, equals = 1))
    }

    @Test
    fun `a JSON null is absent at every schema level - the defaults apply, a fixture's null value is kept`() {
        val tree = DocumentFixtures.visualization()
        tree
            .putNull("description")
            .putNull("transform")
            .putNull("bindings")
            .putNull("tests")
        val body = read(tree).body
        body.description shouldBe null
        body.transform shouldBe null
        body.bindings shouldBe emptyMap()
        body.tests shouldBe null
        val withNullValue = DocumentFixtures.visualization()
        withNullValue.obj("tests.cases[0].fixtures.revenue[0]").putNull("amount")
        read(withNullValue)
            .body.tests!!
            .cases
            .single()
            .fixtures
            .getValue("revenue")
            .single()
            .get("amount")
            .isNull shouldBe true
    }

    @Test
    fun `an unknown key is refused at EVERY level, each with its path - all of them in one read`() {
        val tree = DocumentFixtures.visualization()
        tree.put("colour", "red")
        tree.obj("renderer").put("theme", "dark")
        tree.obj("inputs.revenue").put("rows", 1)
        tree.obj("inputs.revenue.columns[0]").put("width", 1)
        tree.obj("transform").put("mode", "row")
        tree.obj("transform.template").put("id", "x")
        tree.obj("presentation").put("css", "x")
        tree.obj("tests").put("skip", true)
        tree.obj("tests.cases[0]").put("tags", "x")
        tree.obj("tests.cases[0].assertions[0]").put("within", 1)
        val failures = refused(tree)
        failures.map { it.code }.toSet() shouldBe setOf(VisualizationErrorCodes.BODY_INVALID)
        failures.map { it.details["reason"] }.toSet() shouldBe setOf(JsonScan.REASON_UNKNOWN_KEY)
        failures.map { it.path } shouldContainExactlyInAnyOrder
            listOf(
                "colour",
                "renderer.theme",
                "inputs.revenue.rows",
                "inputs.revenue.columns[0].width",
                "transform.mode",
                "transform.template.id",
                "presentation.css",
                "tests.skip",
                "tests.cases[0].tags",
                "tests.cases[0].assertions[0].within",
            )
    }

    @Test
    fun `a wrong JSON type is refused where it sits, never coerced - a fixture value is a scalar`() {
        val tree = DocumentFixtures.visualization()
        tree.obj("renderer").put("version", 4)
        tree.obj("inputs.revenue.columns[1]").put("nullable", "no")
        tree.putArray("config")
        tree.obj("bindings").put("data[0].x", 1)
        tree.obj("tests.cases[0].fixtures.revenue[0]").putObject("month")
        tree.obj("tests.cases[0].assertions[1]").put("equals", "1")
        val failures = refused(tree)
        failures.map { it.details["reason"] }.toSet() shouldBe setOf(JsonScan.REASON_WRONG_TYPE)
        failures.map { it.path } shouldContainExactlyInAnyOrder
            listOf(
                "renderer.version",
                "inputs.revenue.columns[1].nullable",
                "config",
                "bindings.data[0].x",
                "tests.cases[0].fixtures.revenue[0].month",
                "tests.cases[0].assertions[1].equals",
            )
    }

    @Test
    fun `a missing field is reported with its own code where it has one, body_invalid where it does not`() {
        val tree = DocumentFixtures.visualization()
        tree.remove("name")
        tree.remove("renderer")
        tree.remove("config")
        tree.obj("transform").remove("template")
        tree.obj("inputs.revenue.columns[0]").remove("type")
        val failures = refused(tree).associate { it.path to it.code }
        failures shouldBe
            mapOf(
                "name" to VisualizationErrorCodes.NAME_INVALID,
                "renderer" to VisualizationErrorCodes.BODY_INVALID,
                "config" to VisualizationErrorCodes.BODY_INVALID,
                "transform.template" to VisualizationErrorCodes.BODY_INVALID,
                "inputs.revenue.columns[0].type" to VisualizationErrorCodes.BODY_INVALID,
            )
    }

    @Test
    fun `an out-of-vocabulary literal has its field's code - renderer kind, column type, assertion kind`() {
        val tree = DocumentFixtures.visualization()
        tree.obj("renderer").put("kind", "vega")
        tree.obj("inputs.revenue.columns[0]").put("type", "UUID")
        tree.obj("tests.cases[0].assertions[0]").put("kind", "snapshot")
        refused(tree).map { it.code to it.path } shouldContainExactlyInAnyOrder
            listOf(
                VisualizationErrorCodes.RENDERER_UNSUPPORTED to "renderer.kind",
                VisualizationErrorCodes.INPUT_CONTRACT_INVALID to "inputs.revenue.columns[0].type",
                VisualizationErrorCodes.TEST_CASE_INVALID to "tests.cases[0].assertions[0].kind",
            )
        // The reserved kinds BIND — refusing them is the validator's (renderer_unsupported, reason reserved).
        read(DocumentFixtures.visualization().also { it.obj("renderer").put("kind", "svg") }).body.renderer.kind shouldBe RendererKind.SVG
    }

    @Test
    fun `the four bounds refuse BEFORE their members are read - the work is bounded by the config, not the document`() {
        val tight =
            VisualizationReader(
                VisualizationConfig(maxCasesPerVisualization = 1, maxFixtureRowsPerCase = 1, maxBindingsPerVisualization = 1),
            )
        val tree = DocumentFixtures.visualization()
        // Every member of the over-long collections is itself malformed: only the bound may be reported.
        tree.obj("bindings").put("layout.title.text", 7)
        (tree.obj("tests").get("cases") as ArrayNode).add(JsonNodeFactory.instance.textNode("not a case"))
        val failures =
            tight
                .read(tree)
                .shouldBeInstanceOf<ReadOutcome.Refused>()
                .result.failures
        failures.map { it.path to it.details["reason"] } shouldContainExactlyInAnyOrder
            listOf("bindings" to JsonScan.REASON_TOO_MANY, "tests.cases" to JsonScan.REASON_TOO_MANY)
        failures.first { it.path == "bindings" }.details["config_key"] shouldBe VisualizationKey.MAX_BINDINGS_PER_VISUALIZATION.path

        val rows = DocumentFixtures.visualization()
        (rows.obj("tests.cases[0].fixtures").get("revenue") as ArrayNode).add(JsonNodeFactory.instance.numberNode(1))
        VisualizationReader(VisualizationConfig(maxFixtureRowsPerCase = 1))
            .read(rows)
            .shouldBeInstanceOf<ReadOutcome.Refused>()
            .result.failures
            .map { it.path to it.details["reason"] } shouldBe
            listOf("tests.cases[0].fixtures" to JsonScan.REASON_TOO_MANY)

        val big = DocumentFixtures.visualization()
        big.obj("config.layout.title").put("text", "x".repeat(VisualizationKey.MAX_CONFIG_BYTES.min.toInt()))
        VisualizationReader(VisualizationConfig(maxConfigBytes = VisualizationKey.MAX_CONFIG_BYTES.min.toInt()))
            .read(big)
            .shouldBeInstanceOf<ReadOutcome.Refused>()
            .result.failures
            .map { it.path to it.details["reason"] } shouldBe listOf("config" to JsonScan.REASON_TOO_LARGE)
    }

    @Test
    fun `a configuration nested a hundred thousand deep is refused too_deep by a bounded walk, never the host stack`() {
        var deep: JsonNode = JsonNodeFactory.instance.textNode("x")
        repeat(100_000) { deep = JsonNodeFactory.instance.arrayNode().add(deep) }
        val tree = DocumentFixtures.visualization()
        tree.obj("config.layout").set<JsonNode>("annotations", deep)
        refused(tree).map { it.path to it.details["reason"] } shouldBe listOf("config" to JsonScan.REASON_TOO_DEEP)
    }

    @Test
    fun `the name is outside the body - a stored body never carries it, and a body naming itself does not bind`() {
        val document = read(DocumentFixtures.visualization())
        ArtifactJson.writeBody(document.body) shouldNotContain "\"name\":\"${DocumentFixtures.VISUALIZATION_NAME}\""
        ArtifactJson.mapper.readTree(ArtifactJson.writeBody(document.body)).has("name") shouldBe false
        val withName = ArtifactJson.mapper.readTree(ArtifactJson.writeBody(document.body)) as ObjectNode
        withName.put("name", DocumentFixtures.VISUALIZATION_NAME)
        shouldThrow<UnrecognizedPropertyException> { ArtifactJson.readVisualization(withName.toString()) }
    }

    @Test
    fun `a bind exception is body_invalid, never a 500 - the bind step behind the pre-scan answers a refusal`() {
        val drifted = DocumentFixtures.visualization().put("display_name", 5)
        val refused =
            DocumentBinding
                .bind(drifted, VisualizationBody::class.java, VisualizationErrorCodes.BODY_INVALID)
                .shouldBeInstanceOf<ReadOutcome.Refused>()
        refused.result.failures
            .single()
            .code shouldBe VisualizationErrorCodes.BODY_INVALID
        refused.result.failures
            .single()
            .details["reason"] shouldBe JsonScan.REASON_WRONG_TYPE
    }

    @Test
    fun `reflected input is bounded and stripped of control characters`() {
        val tree = DocumentFixtures.visualization()
        tree.put("x".repeat(500) + "\nforged", 1)
        val failure = refused(tree).single()
        (failure.path.length <= MAX_REFLECTED_PATH_LENGTH + 1) shouldBe true
        failure.message.contains('\n') shouldBe false
        (failure.details["key"] as String).length shouldBe MAX_REFLECTED_VALUE_LENGTH + 1
    }

    @Test
    fun `readOrThrow carries every failure in the exception's details`() {
        val tree = DocumentFixtures.visualization().also { it.put("a", 1).put("b", 2) }
        val thrown = shouldThrow<ArtifactValidationException> { reader.readOrThrow(tree) }
        thrown.code shouldBe VisualizationErrorCodes.BODY_INVALID
        (thrown.details["failures"] as List<*>).size shouldBe 2
    }

    @Test
    fun `the pre-scan's key tables are exactly the binding's properties - one definition, no drift`() {
        withClue(
            "document (+ the name)",
        ) { propertiesOf(VisualizationBody::class.java) + "name" shouldBe VisualizationReader.DOCUMENT_KEYS }
        withClue("renderer") { propertiesOf(RendererSpec::class.java) shouldBe VisualizationReader.RENDERER_KEYS }
        withClue("input") { propertiesOf(InputContract::class.java) shouldBe VisualizationReader.INPUT_KEYS }
        withClue("column") { propertiesOf(InputColumn::class.java) shouldBe VisualizationReader.COLUMN_KEYS }
        withClue("transform") { propertiesOf(TransformBinding::class.java) shouldBe VisualizationReader.TRANSFORM_KEYS }
        withClue("reference") { propertiesOf(ArtifactRef::class.java) shouldBe JsonScan.REF_KEYS }
        withClue("presentation") { propertiesOf(VisualizationPresentation::class.java) shouldBe VisualizationReader.PRESENTATION_KEYS }
        withClue("tests") { propertiesOf(VisualizationTests::class.java) shouldBe VisualizationReader.TESTS_KEYS }
        withClue("case") { propertiesOf(TestCase::class.java) shouldBe VisualizationReader.CASE_KEYS }
        withClue("assertion") { propertiesOf(Assertion::class.java) shouldBe VisualizationReader.ASSERTION_KEYS }
    }

    private fun read(tree: JsonNode): VisualizationDocument =
        reader.read(tree).shouldBeInstanceOf<ReadOutcome.Read<VisualizationDocument>>().document

    private fun refused(tree: JsonNode): List<ValidationFailure> =
        reader
            .read(tree)
            .shouldBeInstanceOf<ReadOutcome.Refused>()
            .result.failures
            .also { it.isEmpty() shouldBe false }
}

/** The JSON property names Jackson binds [type] with — the key tables' authority. */
internal fun propertiesOf(type: Class<*>): Set<String> {
    val mapper = ArtifactJson.mapper
    return mapper.serializationConfig
        .introspect(mapper.constructType(type))
        .findProperties()
        .map { it.name }
        .toSet()
        .also { check(it.isNotEmpty()) { "no properties introspected for ${type.simpleName}" } }
}
