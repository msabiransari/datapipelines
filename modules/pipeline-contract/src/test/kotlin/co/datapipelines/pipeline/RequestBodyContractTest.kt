package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import co.datapipelines.pipeline.ScriptRulesFixtures.SCRIPT_TEMPLATE
import co.datapipelines.pipeline.ScriptRulesFixtures.scriptNode
import co.datapipelines.pipeline.ScriptRulesFixtures.stageNode
import co.datapipelines.pipeline.ScriptRulesFixtures.stub
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.body.StructuredBody
import co.datapipelines.typesystem.body.StructuredBodyRefusal
import co.datapipelines.typesystem.body.StructuredBodyRefusalReason
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The structured request body at the pipeline layer (pipeline-contract §3.4; #1038): the `request_body` declaration's
 * wire form, the `@request_body` binding rules (§12.13), the caller-overridable key set and [RequestBodyAdmission].
 */
class RequestBodyContractTest {
    private val workspaceId = UUID.randomUUID()

    private val bodyContract =
        ScriptContractView(
            mode = ScriptContractView.Mode.TABLE,
            inputs = mapOf("order" to ScriptContractView.Input.Body, "channel" to ScriptContractView.Input.Value(LogicalType.STRING)),
            output =
                ScriptContractView.Output.Table(
                    listOf(
                        ScriptContractView.Column("sku", LogicalType.STRING, nullable = false),
                        ScriptContractView.Column("qty", LogicalType.INTEGER, nullable = false),
                    ),
                ),
            rejects = false,
        )

    private val valueBodyContract =
        ScriptContractView(
            mode = ScriptContractView.Mode.VALUE,
            inputs = mapOf("order" to ScriptContractView.Input.Body),
            output = ScriptContractView.Output.Value(LogicalType.STRING),
            rejects = false,
        )

    private val channel = mapOf("channel" to Parameter(type = LogicalType.STRING, required = false, description = "Channel."))

    private fun reader(
        inputs: Map<String, String> = mapOf("order" to RequestBodyDeclaration.REFERENCE, "channel" to "\$channel"),
        contextKey: String? = null,
        id: String = "shape_order",
    ): Node =
        scriptNode(
            id = id,
            inputs = inputs.mapValues { (_, v) -> Fixtures.json("\"$v\"") },
            output = if (contextKey == null) NodeOutput.Tempdb("order_lines") else null,
            dependsOn = emptyList(),
            contextKey = contextKey,
        )

    private fun pipeline(
        nodes: List<Node>,
        declaration: RequestBodyDeclaration? = RequestBodyDeclaration(required = true),
    ): Pipeline = Fixtures.pipeline(nodes = nodes, parameters = channel).copy(requestBody = declaration)

    private fun validate(
        pipeline: Pipeline,
        contract: ScriptContractView = bodyContract,
    ): ValidationResult = Fixtures.validator(templates = stub(contract = contract)).validate(pipeline, workspaceId)

    @Test
    fun `a declared body bound by @request_body to a body input saves clean`() {
        validate(pipeline(listOf(reader()))).failures.map { "${it.code} ${it.path} ${it.message}" }.shouldBeEmpty()
    }

    @Test
    fun `a value-mode reader writing a context key saves clean`() {
        validate(
            pipeline(listOf(reader(inputs = mapOf("order" to RequestBodyDeclaration.REFERENCE), contextKey = "order_total"))),
            valueBodyContract,
        ).failures
            .map { "${it.code} ${it.path} ${it.message}" }
            .shouldBeEmpty()
    }

    @Test
    fun `@request_body on a pipeline without a request_body declaration is script_input_contract`() {
        val failure = validate(pipeline(listOf(reader()), declaration = null)).failures.single()
        failure.code shouldBe Validation.SCRIPT_INPUT_CONTRACT
        failure.message shouldContain "request_body"
    }

    @Test
    fun `a body input bound to anything but @request_body is script_input_contract`() {
        val result =
            validate(
                pipeline(
                    listOf(
                        stageNode(),
                        reader(inputs = mapOf("order" to "stg_orders", "channel" to "\$channel")).copy(dependsOn = listOf("stage_orders")),
                    ),
                ),
            )
        result.codes shouldContainExactly listOf(Validation.SCRIPT_INPUT_CONTRACT)
    }

    @Test
    fun `@request_body bound to a value input is script_input_contract`() {
        val result =
            validate(
                pipeline(
                    listOf(
                        reader(
                            inputs =
                                mapOf(
                                    "order" to RequestBodyDeclaration.REFERENCE,
                                    "channel" to RequestBodyDeclaration.REFERENCE,
                                ),
                        ),
                    ),
                ),
            )
        result.codes shouldContainExactly listOf(Validation.SCRIPT_INPUT_CONTRACT)
    }

    @Test
    fun `a pipeline without a declaration serializes no request_body, so no stored body hash moves`() {
        val plain = Fixtures.pipeline()
        PipelineSerializer().write(plain) shouldNotContain "request_body"
        val declared = plain.copy(requestBody = RequestBodyDeclaration(required = true))
        val json = PipelineSerializer().write(declared)
        json shouldContain """"request_body":{"required":true}"""
        val read = PipelineDeserializer().read(json).shouldBeInstanceOf<DeserializationOutcome.Parsed>().pipeline
        read.requestBody shouldBe RequestBodyDeclaration(required = true)
        PipelineDeserializer()
            .read(json.replace(""""request_body":{"required":true}""", """"request_body":{"required":false,"later":1}"""))
            .shouldBeInstanceOf<DeserializationOutcome.Parsed>()
            .pipeline.requestBody shouldBe RequestBodyDeclaration(required = false)
    }

    @Test
    fun `a body reader's context key is not caller-overridable, any other SCRIPT key still is`() {
        val other = scriptNode(id = "other", template = SCRIPT_TEMPLATE, output = null, dependsOn = emptyList(), contextKey = "plain_key")
        val p = pipeline(listOf(reader(contextKey = "order_total"), other))
        p.scriptOutputKeys() shouldBe setOf("order_total", "plain_key")
        p.callerScriptKeys() shouldBe setOf("plain_key")
        p.requestBodyReaders().map { it.id } shouldContainExactly listOf("shape_order")
    }

    @Test
    fun `admission refuses a missing required body, a body nobody declared, and an override of a reader's key`() {
        val required = pipeline(listOf(reader(contextKey = "order_total")))
        val body = StructuredBody.ObjectRoot(mapOf("sku" to "A"))

        fun reason(block: () -> Unit): Map<String, Any?> {
            val refused = shouldThrow<DatapipelinesException>(block)
            refused.code shouldBe PipelineErrorCodes.Execution.REQUEST_BODY_INVALID
            return refused.details
        }

        reason { RequestBodyAdmission.check(required, StructuredBody.Absent) }["reason"] shouldBe RequestBodyAdmission.REQUIRED
        reason { RequestBodyAdmission.check(required.copy(requestBody = null), body) }["reason"] shouldBe RequestBodyAdmission.NOT_ACCEPTED
        val override = reason { RequestBodyAdmission.check(required, body, setOf("order_total", "channel")) }
        override["reason"] shouldBe RequestBodyAdmission.OVERRIDE_REFUSED
        override["keys"] shouldBe listOf("order_total")
        withClue("an optional body may be absent; a declared body may be present; other keys pass") {
            shouldNotThrowAny {
                RequestBodyAdmission.check(required.copy(requestBody = RequestBodyDeclaration(required = false)), StructuredBody.Absent)
                RequestBodyAdmission.check(required, StructuredBody.ArrayRoot(emptyList()), setOf("channel"))
                RequestBodyAdmission.check(Fixtures.pipeline(), StructuredBody.Absent, setOf("anything"))
            }
        }
    }

    @Test
    fun `a decoder refusal becomes the execution code with its reason and position, never a value`() {
        val refused = RequestBodyAdmission.decodeRefused(StructuredBodyRefusal(StructuredBodyRefusalReason.DUPLICATE_MEMBER, offset = 17))
        refused.code shouldBe PipelineErrorCodes.Execution.REQUEST_BODY_INVALID
        refused.details shouldBe mapOf("reason" to "duplicate_member", "offset" to 17L)
        RequestBodyAdmission
            .decodeRefused(StructuredBodyRefusal(StructuredBodyRefusalReason.INVALID_ROOT, index = 2))
            .details shouldBe mapOf("reason" to "invalid_root", "index" to 2)
    }
}
