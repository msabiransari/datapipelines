package co.datapipelines.executor

import co.datapipelines.events.NodeFailed
import co.datapipelines.events.PipelineFailed
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.RequestBodyDeclaration
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.scripting.JsonataEngine
import co.datapipelines.scripting.ScriptEvaluationPool
import co.datapipelines.scripting.ScriptLanguage
import co.datapipelines.templates.ContractColumn
import co.datapipelines.templates.ScriptContract
import co.datapipelines.templates.ScriptInput
import co.datapipelines.templates.ScriptInvariant
import co.datapipelines.templates.ScriptMode
import co.datapipelines.templates.ScriptOutput
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.body.StructuredBodyDecoder
import co.datapipelines.typesystem.body.StructuredBodyDecoding
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The default diagnostics policy of a body execution (#1038, review R3): whatever a transformer, its
 * invariants or the strict check say about the request body, a value taken from it or the transformed
 * rows stays out of every failure the executor discloses — the node and pipeline events, the thrown
 * failure and its cause chain — while the code and the node metadata remain. The twin over persistence,
 * the stream and replay is `StructuredPublishedInputIntegrationTest`.
 *
 * The same failures on an execution WITHOUT a body keep their text: the policy is not widened.
 */
class ScriptBodyDiagnosticsRunTest {
    private val engine = JsonataEngine()

    private val columns = listOf(ContractColumn("sku", LogicalType.STRING), ContractColumn("qty", LogicalType.INTEGER))

    private val bodyText = """{"customer":"$CANARY_BODY","lines":[{"sku":"A","qty":1}]}"""

    private fun contract(
        inputs: Map<String, ScriptInput> = mapOf("order" to ScriptInput.Body()),
        rejects: Boolean = false,
    ) = ScriptContract(ScriptMode.TABLE, inputs, ScriptOutput.Table(columns), rejects)

    private fun support(
        contract: ScriptContract,
        program: String,
        invariants: List<ScriptInvariant> = emptyList(),
    ) = ScriptSupport(
        resolver =
            ScriptVersionResolver { _, _ ->
                ResolvedScript(
                    co.datapipelines.pipeline.TemplateType.JSONATA,
                    co.datapipelines.pipeline.PipelineVersionStatus.DRAFT,
                    program,
                    contract,
                    invariants,
                )
            },
        pool = ScriptEvaluationPool(2, 8, Duration.ofSeconds(2), ScriptEvaluationPool.SYSTEM),
        engines = mapOf(ScriptLanguage.JSONATA to engine),
    )

    private fun reader(
        input: com.fasterxml.jackson.databind.JsonNode,
        output: NodeOutput,
        strict: Boolean? = null,
    ) = Node(
        id = "shape_order",
        description = "reads the request body",
        type = NodeType.SCRIPT,
        source = "",
        template = TemplateRef("acme/shape/order.jsonata", 1),
        output = output,
        dependsOn = emptyList(),
        inputs = mapOf("order" to input),
        strict = strict,
    )

    private val bodyInput = JsonNodeFactory.instance.textNode(RequestBodyDeclaration.REFERENCE)

    /** Runs one failing execution and returns everything it disclosed, as text. */
    private fun disclosed(
        support: ScriptSupport,
        node: Node,
        withBody: Boolean,
        code: String,
    ): String =
        ExecutorHarness(Fixtures.templateEngine("SELECT 1").first, scripts = support).use { h ->
            val base = Fixtures.pipeline(listOf(node))
            val pipeline = if (withBody) base.copy(requestBody = RequestBodyDeclaration(required = true)) else base
            val request =
                Fixtures.request(pipeline).let {
                    if (withBody) {
                        it.copy(
                            requestBody =
                                StructuredBodyDecoder()
                                    .decode(
                                        bodyText.toByteArray(),
                                    ).shouldBeInstanceOf<StructuredBodyDecoding.Decoded>()
                                    .body,
                        )
                    } else {
                        it
                    }
                }
            val thrown = shouldThrow<PipelineExecutionFailed> { runBlocking { h.executor.execute(request) } }
            thrown.errorCode shouldBe code
            val events = h.emitter.events.filter { it is NodeFailed || it is PipelineFailed }
            events.size shouldBe 2
            val chain = generateSequence<Throwable>(thrown) { it.cause }.joinToString("\n") { "$it ${it.message}" }
            events.joinToString("\n") + "\n" + chain
        }

    private val strictProgram =
        "{\"rows\": [], \"rejects\": [{\"row\": {\"sku\": \"A\", \"qty\": 1}, \"reason\": \"rejected for \" & inputs.order.customer}]}"

    @Test
    fun `a strict rejection of a body execution discloses the code and the count, not the reasons`() {
        val text =
            disclosed(
                support(contract(rejects = true), strictProgram),
                reader(bodyInput, NodeOutput.Tempdb("lines", rejects = "lines_rejected"), strict = true),
                withBody = true,
                code = PipelineErrorCodes.Script.REJECTS_STRICT,
            )
        withClue(text) {
            text shouldNotContain CANARY_BODY
            text shouldContain "shape_order"
        }
    }

    @Test
    fun `an invariant failure of a body execution discloses the code, not the author's text`() {
        val text =
            disclosed(
                support(contract(), "[{\"sku\": \"A\", \"qty\": 1}]", listOf(ScriptInvariant("never", "1 = 2", "text $CANARY_AUTHOR"))),
                reader(bodyInput, NodeOutput.Caller),
                withBody = true,
                code = PipelineErrorCodes.Script.INVARIANT_FAILED,
            )
        withClue(text) {
            text shouldNotContain CANARY_AUTHOR
            text shouldContain "shape_order"
        }
    }

    @Test
    fun `an engine failure that quotes a value from the body discloses the code, not the engine text`() {
        val text =
            disclosed(
                support(contract(), "\$error(\"boom \" & inputs.order.customer)"),
                reader(bodyInput, NodeOutput.Caller),
                withBody = true,
                code = PipelineErrorCodes.Script.EVALUATION_FAILED,
            )
        withClue(text) { text shouldNotContain CANARY_BODY }
    }

    @Test
    fun `the same failures without a body keep their text, the policy is not widened`() {
        val noInputs = emptyMap<String, ScriptInput>()
        val none = JsonNodeFactory.instance.objectNode()
        val engineText =
            disclosed(
                support(contract(noInputs), "\$error(\"boom $CANARY_BODY\")"),
                reader(none, NodeOutput.Caller).copy(inputs = emptyMap()),
                withBody = false,
                code = PipelineErrorCodes.Script.EVALUATION_FAILED,
            )
        engineText shouldContain CANARY_BODY
        val strictText =
            disclosed(
                support(
                    contract(noInputs, rejects = true),
                    "{\"rows\": [], \"rejects\": [{\"row\": {\"sku\": \"A\", \"qty\": 1}, \"reason\": \"rejected $CANARY_BODY\"}]}",
                ),
                reader(none, NodeOutput.Tempdb("lines", rejects = "lines_rejected"), strict = true).copy(inputs = emptyMap()),
                withBody = false,
                code = PipelineErrorCodes.Script.REJECTS_STRICT,
            )
        strictText shouldContain CANARY_BODY
    }

    private companion object {
        const val CANARY_BODY = "CANARY-body-7f3a91"
        const val CANARY_AUTHOR = "CANARY-author-52c0de"
    }
}
