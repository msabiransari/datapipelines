package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.ParameterCardinality
import co.datapipelines.typesystem.ParameterConstraints
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * pipeline-contract §6.1/§6.2/§12.7 — the declaration's `constraints` and `cardinality` (#194,
 * parameter-engine record P28): checked at save by the shared validator, so nothing that execute
 * would refuse reaches the database.
 */
class ParameterConstraintRulesTest {
    private val validator = Fixtures.validator()
    private val deserializer = PipelineDeserializer()
    private val workspaceId = java.util.UUID.randomUUID()

    // ---------------------------------------------------------------- cardinality

    @Test
    fun `MULTI is refused at save until the dashboard round adopts list binding`() {
        val failure = failuresFor("regions" to Parameter(LogicalType.STRING, cardinality = ParameterCardinality.MULTI)).single()

        failure.code shouldBe Validation.CARDINALITY_UNSUPPORTED
        failure.path shouldBe "parameters.regions.cardinality"
        failure.details["value"] shouldBe "MULTI"
        failure.details["supported"] shouldBe listOf("SINGLE")
    }

    @Test
    fun `an explicit SINGLE is legal, and absent reads as SINGLE`() {
        failuresFor("p" to Parameter(LogicalType.STRING, cardinality = ParameterCardinality.SINGLE)).shouldBeEmpty()
        Parameter(LogicalType.STRING).declaration.cardinality shouldBe ParameterCardinality.SINGLE
    }

    @Test
    fun `a value that is not a cardinality is refused by the pre-scan with the same code, not a Jackson exception`() {
        listOf("\"TRIPLE\"", "\"single\"", "2").forEach { wire ->
            withClue(wire) {
                prescan("""{"p": {"type": "STRING", "cardinality": $wire}}""").map { it.code } shouldContainExactly
                    listOf(Validation.CARDINALITY_UNSUPPORTED)
            }
        }
    }

    // ---------------------------------------------------------------- constraints: shape (pre-scan)

    @Test
    fun `a constraints block whose shape has no typed reading is constraint_invalid, every defect at once`() {
        val failures =
            prescan(
                """{"a": {"type": "STRING", "constraints": "min 0"},
                    "b": {"type": "STRING", "constraints": {"minimum": 0, "max_length": "3", "pattern": 5}}}""",
            )

        failures.map { it.code }.toSet() shouldBe setOf(Validation.CONSTRAINT_INVALID)
        failures.map { it.path to it.details["reason"] } shouldContainExactlyInAnyOrder
            listOf(
                "parameters.a.constraints" to "not_an_object",
                "parameters.b.constraints.minimum" to "unknown_key",
                "parameters.b.constraints.max_length" to "length_not_an_integer",
                "parameters.b.constraints.pattern" to "pattern_not_a_string",
            )
    }

    @Test
    fun `a JSON null constraint is absent, and a well-shaped block binds`() {
        val tree = Fixtures.mapper.valueToTree<ObjectNode>(Fixtures.pipeline())
        tree.set<JsonNode>(
            "parameters",
            Fixtures.json("""{"p": {"type": "STRING", "constraints": {"min_length": 1, "max_length": null, "pattern": "[a-z]+"}}}"""),
        )
        val parsed = deserializer.fromTree(tree)

        val constraints =
            parsed
                .shouldBeInstanceOf<DeserializationOutcome.Parsed>()
                .pipeline.parameters
                .getValue("p")
                .constraints
        constraints?.minLength shouldBe 1
        constraints?.maxLength shouldBe null
        constraints?.pattern shouldBe "[a-z]+"
    }

    // ---------------------------------------------------------------- constraints: meaning

    @Test
    fun `a constraint on a type it does not apply to is constraint_not_applicable`() {
        val failures =
            failuresFor(
                "an_int" to Parameter(LogicalType.INTEGER, constraints = ParameterConstraints(pattern = "[0-9]+")),
                "a_string" to Parameter(LogicalType.STRING, constraints = ParameterConstraints(min = Fixtures.json("\"a\""))),
                "a_date" to Parameter(LogicalType.DATE, constraints = ParameterConstraints(maxLength = 10)),
                "a_bool" to Parameter(LogicalType.BOOLEAN, constraints = ParameterConstraints(max = Fixtures.json("true"))),
            )

        failures.map { it.code }.toSet() shouldBe setOf(Validation.CONSTRAINT_NOT_APPLICABLE)
        failures.map { it.path } shouldContainExactlyInAnyOrder
            listOf(
                "parameters.an_int.constraints.pattern",
                "parameters.a_string.constraints.min",
                "parameters.a_date.constraints.max_length",
                "parameters.a_bool.constraints.max",
            )
    }

    @Test
    fun `malformed constraint values are constraint_invalid with the reason`() {
        val failures =
            failuresFor(
                "bound_in_wrong_form" to
                    Parameter(LogicalType.BIGDECIMAL, scale = 2, constraints = ParameterConstraints(min = Fixtures.json("0"))),
                "inverted" to Parameter(LogicalType.INTEGER, constraints = ParameterConstraints(Fixtures.json("9"), Fixtures.json("1"))),
                "negative" to Parameter(LogicalType.STRING, constraints = ParameterConstraints(minLength = -1)),
                "crossed" to Parameter(LogicalType.STRING, constraints = ParameterConstraints(minLength = 5, maxLength = 2)),
            )

        failures.map { it.code }.toSet() shouldBe setOf(Validation.CONSTRAINT_INVALID)
        failures.map { it.details["reason"] } shouldContainExactlyInAnyOrder
            listOf("bound_type", "min_greater_than_max", "negative_length", "min_length_greater_than_max_length")
    }

    @Test
    fun `an unsafe, uncompilable or overlong pattern is pattern_invalid`() {
        val failures =
            failuresFor(
                "lookahead" to Parameter(LogicalType.STRING, constraints = ParameterConstraints(pattern = "a(?=b)")),
                "broken" to Parameter(LogicalType.STRING, constraints = ParameterConstraints(pattern = "(")),
                "long" to Parameter(LogicalType.STRING, constraints = ParameterConstraints(pattern = "a".repeat(257))),
            )

        failures.map { it.code }.toSet() shouldBe setOf(Validation.PATTERN_INVALID)
        failures.map { it.details["reason"] } shouldContainExactlyInAnyOrder listOf("unsafe_construct", "syntax", "too_long")
    }

    @Test
    fun `sound constraints with a default that keeps them save cleanly`() {
        failuresFor(
            "amount" to
                Parameter(
                    LogicalType.DECIMAL,
                    precision = 12,
                    scale = 2,
                    default = Fixtures.json("0"),
                    constraints = ParameterConstraints(min = Fixtures.json("0"), max = Fixtures.json("1000000")),
                ),
            "code" to
                Parameter(
                    LogicalType.STRING,
                    default = Fixtures.json("\"ab\""),
                    constraints = ParameterConstraints(minLength = 2, maxLength = 2, pattern = "[a-z]+"),
                ),
        ).shouldBeEmpty()
    }

    // ---------------------------------------------------------------- the default

    @Test
    fun `a default that breaks the declaration's own rules is default_invalid`() {
        val failures =
            failuresFor(
                "below_min" to
                    Parameter(
                        LogicalType.INTEGER,
                        default = Fixtures.json("-1"),
                        constraints = ParameterConstraints(min = Fixtures.json("0")),
                    ),
                "over_scale" to Parameter(LogicalType.DECIMAL, precision = 12, scale = 2, default = Fixtures.json("1.234")),
                "no_match" to
                    Parameter(
                        LogicalType.STRING,
                        default = Fixtures.json("\"AB\""),
                        constraints = ParameterConstraints(pattern = "[a-z]+"),
                    ),
            )

        failures.map { it.code }.toSet() shouldBe setOf(Validation.DEFAULT_INVALID)
        failures.associate { it.path to it.details["reason"] } shouldBe
            mapOf(
                "parameters.below_min.default" to "min",
                "parameters.over_scale.default" to "scale",
                "parameters.no_match.default" to "pattern",
            )
    }

    @Test
    fun `a default in the wrong form stays default_type_mismatch, constraints or not`() {
        codesFor(
            "p" to
                Parameter(
                    LogicalType.INTEGER,
                    default = Fixtures.json("\"7\""),
                    constraints = ParameterConstraints(min = Fixtures.json("0")),
                ),
        ) shouldContainExactly listOf(Validation.DEFAULT_TYPE_MISMATCH)
    }

    @Test
    fun `when the constraints are refused the default is only type-checked - one defect is not reported twice`() {
        codesFor(
            "p" to Parameter(LogicalType.STRING, default = Fixtures.json("\"x\""), constraints = ParameterConstraints(pattern = "(")),
        ) shouldContainExactly listOf(Validation.PATTERN_INVALID)
        codesFor(
            "q" to Parameter(LogicalType.INTEGER, default = Fixtures.json("\"x\""), constraints = ParameterConstraints(pattern = "x")),
        ) shouldContainExactlyInAnyOrder listOf(Validation.CONSTRAINT_NOT_APPLICABLE, Validation.DEFAULT_TYPE_MISMATCH)
    }

    // ---------------------------------------------------------------- helpers

    private fun failuresFor(vararg parameters: Pair<String, Parameter>) =
        validator.validate(Fixtures.pipeline(parameters = parameters.toMap()), workspaceId).failures

    private fun codesFor(vararg parameters: Pair<String, Parameter>) =
        validator.validate(Fixtures.pipeline(parameters = parameters.toMap()), workspaceId).codes

    /** The wire-value pre-scan's failures for a `parameters` object, nothing else in the body. */
    private fun prescan(parameters: String): List<ValidationFailure> =
        deserializer
            .read("""{"parameters": $parameters}""")
            .shouldBeInstanceOf<DeserializationOutcome.Rejected>()
            .result.failures
}
