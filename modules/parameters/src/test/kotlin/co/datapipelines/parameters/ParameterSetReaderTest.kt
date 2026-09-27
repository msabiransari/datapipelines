package co.datapipelines.parameters

import co.datapipelines.parameters.ParameterSetFixtures.parameter
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.ValidationFailure
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.ParameterCardinality
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [ParameterSetReader]: the document either binds whole, or every shape problem comes back at once
 * with its path — never a Jackson stack trace, never a silently dropped key (lane B's A.1 gate).
 */
class ParameterSetReaderTest {
    private val reader = ParameterSetReader()

    @Test
    fun `the record's three examples bind into the model, nulls read as absent`() {
        val document = read(ParameterSetFixtures.fullSet())
        document.name shouldBe "acme/sales/region_filters"
        document.body.displayName shouldBe "Region filters"
        val (country, state, amount) = document.body.parameters
        country.kind shouldBe ParameterKind.SELECT
        country.source!!.constants!!.map { it.value.asText() } shouldBe listOf("USA", "CAN")
        country.source.constants
            .first()
            .isDefault shouldBe true
        country.source.kind shouldBe SelectorSourceKind.CONSTANTS
        state.source!!.template shouldBe TemplateRef("acme/sales/states_of_country.sql", 3)
        state.source.kind shouldBe SelectorSourceKind.TEMPLATE
        state.defaultValue shouldBe null // `"default_value": null` is absent
        state.hiddenExpression shouldBe null
        state.constraints shouldBe null
        state.dependsOn shouldBe listOf("country")
        state.disabledExpression!!["op"].asText() shouldBe "is_null"
        amount.type shouldBe LogicalType.DECIMAL
        amount.precision shouldBe 12
        amount.cardinality shouldBe ParameterCardinality.SINGLE
        amount.constraints!!.min!!.decimalValue() shouldBe BigDecimal.ZERO
        amount.presentation shouldBe Presentation(PresentationControl.NUMBER, DisplayFormat(kind = NumericFormatKind.CURRENCY))
        amount.declaration.default!!.intValue() shouldBe 0
    }

    @Test
    fun `defaults apply to what the author left out - SINGLE, not required, no dependencies`() {
        val body = read(ParameterSetFixtures.tree(ParameterSetFixtures.setJson(ParameterSetFixtures.textInput("city")))).body
        val city = body.parameters.single()
        city.cardinality shouldBe ParameterCardinality.SINGLE
        city.required shouldBe false
        city.dependsOn shouldBe emptyList()
        city.source shouldBe null
    }

    @Test
    fun `an unknown key is refused at EVERY level, each with its path - all of them in one read`() {
        val set = ParameterSetFixtures.fullSet()
        set.put("lable", "x")
        set.parameter(0).put("widget", "combo")
        (set.parameter(0)["source"] as ObjectNode).put("sql", "SELECT 1")
        ((set.parameter(0)["source"]["constants"] as ArrayNode)[1] as ObjectNode).put("isDefault", true)
        (set.parameter(1)["source"]["template"] as ObjectNode).put("alias", "s")
        (set.parameter(2)["constraints"] as ObjectNode).put("minimum", 0)
        (set.parameter(2)["presentation"] as ObjectNode).put("colour", "red")
        (set.parameter(2)["presentation"]["format"] as ObjectNode).put("digits", 2)

        val failures = refused(set)
        failures.map { it.code }.distinct() shouldBe listOf(ParameterErrorCodes.BODY_INVALID)
        failures.map { it.path } shouldContainExactlyInAnyOrder
            listOf(
                "lable",
                "parameters[0].widget",
                "parameters[0].source.sql",
                "parameters[0].source.constants[1].isDefault",
                "parameters[1].source.template.alias",
                "parameters[2].constraints.minimum",
                "parameters[2].presentation.colour",
                "parameters[2].presentation.format.digits",
            )
        failures.map { it.details["reason"] }.distinct() shouldBe listOf(ParameterSetReader.REASON_UNKNOWN_KEY)
    }

    @Test
    fun `a wrong JSON type is refused where it sits, never coerced`() {
        val set = ParameterSetFixtures.fullSet()
        set.put("display_name", 5)
        set.parameter(0).put("required", "yes")
        set.parameter(2).put("precision", "12")
        set.parameter(1).set<JsonNode>("depends_on", JsonNodeFactory.instance.textNode("country"))
        set.parameter(1).set<JsonNode>("hidden_expression", JsonNodeFactory.instance.textNode("country == null"))
        (set.parameter(0)["source"] as ObjectNode).put("constants", "USA,CAN")
        (set.parameter(1)["source"]["template"] as ObjectNode).put("version", "3")

        val failures = refused(set)
        failures.map { it.path to it.details["reason"] } shouldContainExactlyInAnyOrder
            listOf(
                "display_name" to ParameterSetReader.REASON_WRONG_TYPE,
                "parameters[0].required" to ParameterSetReader.REASON_WRONG_TYPE,
                "parameters[2].precision" to ParameterSetReader.REASON_WRONG_TYPE,
                "parameters[1].depends_on" to ParameterSetReader.REASON_WRONG_TYPE,
                "parameters[1].hidden_expression" to ParameterSetReader.REASON_WRONG_TYPE,
                "parameters[0].source.constants" to ParameterSetReader.REASON_WRONG_TYPE,
                "parameters[1].source.template.version" to ParameterSetReader.REASON_WRONG_TYPE,
            )
        failures.map { it.code }.distinct() shouldBe listOf(ParameterErrorCodes.BODY_INVALID)
    }

    @Test
    fun `a missing field is reported with its own code where it has one, body_invalid where it does not`() {
        val set = ParameterSetFixtures.fullSet()
        set.remove("name")
        set.remove("display_name")
        set.parameter(0).remove("label")
        set.parameter(0).remove("kind")
        set.parameter(1).remove("type")
        set.parameter(2).remove("name")
        ((set.parameter(0)["source"]["constants"] as ArrayNode)[0] as ObjectNode).putNull("value")
        ((set.parameter(0)["source"]["constants"] as ArrayNode)[1] as ObjectNode).remove("display_value")
        (set.parameter(1)["source"]["template"] as ObjectNode).remove("id")

        refused(set).map { it.code to it.path } shouldContainExactlyInAnyOrder
            listOf(
                ParameterErrorCodes.NAME_INVALID to "name",
                ParameterErrorCodes.LABEL_INVALID to "display_name",
                ParameterErrorCodes.LABEL_INVALID to "parameters[0].label",
                ParameterErrorCodes.KIND_INVALID to "parameters[0].kind",
                ParameterErrorCodes.TYPE_INVALID to "parameters[1].type",
                ParameterErrorCodes.NAME_INVALID to "parameters[2].name",
                ParameterErrorCodes.OPTION_INVALID to "parameters[0].source.constants[0].value",
                ParameterErrorCodes.OPTION_INVALID to "parameters[0].source.constants[1].display_value",
                ParameterErrorCodes.BODY_INVALID to "parameters[1].source.template.id",
            )
    }

    @Test
    fun `no parameters array at all is body_invalid - missing, and a non-object document is refused whole`() {
        val set = ParameterSetFixtures.fullSet().also { it.remove("parameters") }
        refused(set).single().let {
            it.code shouldBe ParameterErrorCodes.BODY_INVALID
            it.details["reason"] shouldBe ParameterSetReader.REASON_MISSING
        }
        refused(JsonNodeFactory.instance.arrayNode()).single().details["reason"] shouldBe ParameterSetReader.REASON_WRONG_TYPE
    }

    @Test
    fun `an out-of-catalogue enum value has its own code - NULL is not a declarable type`() {
        val set = ParameterSetFixtures.fullSet()
        set.parameter(0).put("type", "NULL")
        set.parameter(1).put("kind", "FIXED")
        set.parameter(1).put("cardinality", "MANY")
        (set.parameter(0)["presentation"] as ObjectNode).put("control", "slider")
        (set.parameter(2)["presentation"]["format"] as ObjectNode).put("kind", "money")

        refused(set).map { it.code to it.path } shouldContainExactlyInAnyOrder
            listOf(
                ParameterErrorCodes.TYPE_INVALID to "parameters[0].type",
                ParameterErrorCodes.KIND_INVALID to "parameters[1].kind",
                ParameterErrorCodes.CARDINALITY_INVALID to "parameters[1].cardinality",
                ParameterErrorCodes.PRESENTATION_INVALID to "parameters[0].presentation.control",
                ParameterErrorCodes.FORMAT_INVALID to "parameters[2].presentation.format.kind",
            )
    }

    @Test
    fun `the collection caps refuse BEFORE the members are read - work bounded by the config, not the document`() {
        val small = ParameterSetReader(ParametersConfig(maxParametersPerSet = 2, maxOptionsPerSelector = 1))
        // Three parameters, each carrying an unknown key: the cap is the ONLY failure — no member was walked.
        val tooMany = ParameterSetFixtures.fullSet()
        (0..2).forEach { tooMany.parameter(it).put("junk", 1) }
        (small.read(tooMany) as ParameterSetReadOutcome.Refused).result.failures.map { it.code } shouldBe
            listOf(ParameterErrorCodes.TOO_MANY_PARAMETERS)

        val twoOptions = ParameterSetFixtures.tree(ParameterSetFixtures.setJson(ParameterSetFixtures.countryJson())) as ObjectNode
        ((twoOptions.parameter(0)["source"]["constants"] as ArrayNode)[1] as ObjectNode).put("junk", 1)
        (small.read(twoOptions) as ParameterSetReadOutcome.Refused).result.failures.map { it.code to it.path } shouldBe
            listOf(ParameterErrorCodes.TOO_MANY_OPTIONS to "parameters[0].source.constants")

        // A two-parameter set (inside the cap) whose depends_on lists three entries: longer than the set can hold.
        val longDeps =
            ParameterSetFixtures.tree(
                ParameterSetFixtures.setJson(ParameterSetFixtures.countryJson(), ParameterSetFixtures.stateJson()),
            ) as ObjectNode
        longDeps.parameter(1).putArray("depends_on").let { deps -> repeat(3) { deps.add("country") } }
        (small.read(longDeps) as ParameterSetReadOutcome.Refused).result.failures.single { it.path == "parameters[1].depends_on" }.let {
            it.code shouldBe ParameterErrorCodes.BODY_INVALID
            it.details["reason"] shouldBe ParameterSetReader.REASON_TOO_LONG
        }
    }

    @Test
    fun `reflected input is bounded and stripped of control characters`() {
        val set = ParameterSetFixtures.fullSet()
        set.put("x".repeat(500) + "\nforged", 1)
        val failure = refused(set).single()
        (failure.path.length <= MAX_REFLECTED_PATH_LENGTH + 1) shouldBe true
        failure.message.contains('\n') shouldBe false
        (failure.details["key"] as String).length shouldBe MAX_REFLECTED_VALUE_LENGTH + 1
    }

    @Test
    fun `a deeply nested raw value costs the reader nothing - null-stripping walks the schema's levels, not the value`() {
        // 50,000 nested arrays as an INPUT's default_value: the pre-scan never looks inside a raw value,
        // and the null-stripping must not either (the host stack must never be the bound — 260).
        var deep: JsonNode = JsonNodeFactory.instance.textNode("x")
        repeat(50_000) { deep = JsonNodeFactory.instance.arrayNode().add(deep) }
        val set = ParameterSetFixtures.tree(ParameterSetFixtures.setJson(ParameterSetFixtures.textInput("city"))) as ObjectNode
        set.parameter(0).set<JsonNode>("default_value", deep)
        // Either binds (the validator then refuses the default's type) or is refused as a shape — never a StackOverflowError.
        reader.read(set)
    }

    @Test
    fun `readOrThrow carries every failure in the exception's details`() {
        val set = ParameterSetFixtures.fullSet().also { it.put("a", 1).put("b", 2) }
        val thrown = shouldThrow<ParameterSetValidationException> { reader.readOrThrow(set) }
        thrown.code shouldBe ParameterErrorCodes.BODY_INVALID
        (thrown.details["failures"] as List<*>).size shouldBe 2
    }

    @Test
    fun `the pre-scan's key tables are exactly the binding's properties - one definition, no drift`() {
        withClue("set level (+ the document's name)") {
            propertiesOf(ParameterSetBody::class.java) + "name" shouldBe
                ParameterSetReader.SET_KEYS
        }
        withClue("parameter level") { propertiesOf(ParameterDefinition::class.java) shouldBe ParameterSetReader.PARAMETER_KEYS }
        withClue("source level") { propertiesOf(SelectorSource::class.java) shouldBe ParameterSetReader.SOURCE_KEYS }
        withClue("option level") { propertiesOf(ConstantOption::class.java) shouldBe ParameterSetReader.OPTION_KEYS }
        withClue("template pin") { propertiesOf(TemplateRef::class.java) shouldBe ParameterSetReader.TEMPLATE_REF_KEYS }
        withClue("presentation") { propertiesOf(Presentation::class.java) shouldBe ParameterSetReader.PRESENTATION_KEYS }
        withClue("format") { propertiesOf(DisplayFormat::class.java) shouldBe ParameterSetReader.FORMAT_KEYS }
    }

    private fun read(tree: JsonNode): ParameterSetDocument = reader.read(tree).shouldBeInstanceOf<ParameterSetReadOutcome.Read>().document

    private fun refused(tree: JsonNode): List<ValidationFailure> =
        reader
            .read(tree)
            .shouldBeInstanceOf<ParameterSetReadOutcome.Refused>()
            .result.failures
            .also { it.isEmpty() shouldBe false }

    private fun propertiesOf(type: Class<*>): Set<String> {
        val mapper = ParameterSetJson.mapper
        return mapper.serializationConfig
            .introspect(mapper.constructType(type))
            .findProperties()
            .map { it.name }
            .toSet()
            .also { check(it.isNotEmpty()) { "no properties introspected for ${type.simpleName}" } }
    }
}
