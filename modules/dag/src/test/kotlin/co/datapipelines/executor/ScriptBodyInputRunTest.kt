package co.datapipelines.executor

import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.RequestBodyAdmission
import co.datapipelines.pipeline.RequestBodyDeclaration
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.scripting.JsonataEngine
import co.datapipelines.scripting.ScriptEvaluationPool
import co.datapipelines.scripting.ScriptLanguage
import co.datapipelines.templates.ContractColumn
import co.datapipelines.templates.ScriptContract
import co.datapipelines.templates.ScriptInput
import co.datapipelines.templates.ScriptMode
import co.datapipelines.templates.ScriptOutput
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.body.StructuredBody
import co.datapipelines.typesystem.body.StructuredBodyDecoder
import co.datapipelines.typesystem.body.StructuredBodyDecoding
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The `body` SCRIPT input through the real executor (#1038; pipeline-contract §3.4): the request's decoded body
 * reaches the transformer unchanged, the transformer's declared output is all that leaves it, an absent optional
 * body is undefined to the transformer, the transformer's own value cap still applies, and admission refuses
 * before any node runs. The twin over the real JavaScript isolate is `StructuredBodyEngineTest` and the
 * integration suite's `StructuredPublishedInputIntegrationTest`.
 */
class ScriptBodyInputRunTest {
    private val engine = JsonataEngine()

    private val lineColumns =
        listOf(
            ContractColumn("sku", LogicalType.STRING),
            ContractColumn("qty", LogicalType.INTEGER),
            ContractColumn("id", LogicalType.STRING, nullable = true),
        )

    private val tableContract =
        ScriptContract(
            mode = ScriptMode.TABLE,
            inputs = mapOf("order" to ScriptInput.Body()),
            output = ScriptOutput.Table(lineColumns),
        )

    /** Lines from an order object, or every element of a root array that carries a sku. */
    private val linesProgram =
        """
        (${'$'}o := inputs.order;
         ${'$'}items := ${'$'}type(${'$'}o) = "array" ? ${'$'}o[${'$'}exists(sku)] : ${'$'}o.lines;
         [${'$'}items.{"sku": sku, "qty": qty, "id": ${'$'}exists(id) ? id : null}])
        """.trimIndent()

    private fun support(
        contract: ScriptContract,
        program: String,
        maxValueBytes: Long = 1_048_576,
    ) = ScriptSupport(
        resolver =
            ScriptVersionResolver { _, _ ->
                ResolvedScript(
                    co.datapipelines.pipeline.TemplateType.JSONATA,
                    co.datapipelines.pipeline.PipelineVersionStatus.DRAFT,
                    program,
                    contract,
                    emptyList(),
                )
            },
        pool = ScriptEvaluationPool(2, 8, Duration.ofSeconds(2), ScriptEvaluationPool.SYSTEM),
        engines = mapOf(ScriptLanguage.JSONATA to engine),
        maxValueBytes = maxValueBytes,
    )

    private fun reader(output: NodeOutput? = NodeOutput.Caller) =
        Node(
            id = "shape_order",
            description = "reads the request body",
            type = NodeType.SCRIPT,
            source = "",
            template = TemplateRef("acme/shape/order.jsonata", 1),
            output = output,
            dependsOn = emptyList(),
            inputs = mapOf("order" to JsonNodeFactory.instance.textNode(RequestBodyDeclaration.REFERENCE)),
        )

    private fun body(json: String): StructuredBody =
        StructuredBodyDecoder().decode(json.toByteArray()).shouldBeInstanceOf<StructuredBodyDecoding.Decoded>().body

    private fun harness(support: ScriptSupport) = ExecutorHarness(Fixtures.templateEngine("SELECT 1").first, scripts = support)

    private fun execute(
        harness: ExecutorHarness,
        body: StructuredBody,
        declaration: RequestBodyDeclaration? = RequestBodyDeclaration(required = true),
    ): List<List<Any?>> =
        runBlocking {
            val pipeline = Fixtures.pipeline(listOf(reader())).copy(requestBody = declaration)
            val result = harness.executor.execute(Fixtures.request(pipeline).copy(requestBody = body))
            result.status shouldBe ExecutionStatus.SUCCESS
            harness.resultStore.describe(result.resultRef!!)!!.firstPage
        }

    @Test
    fun `an order object and a heterogeneous root array become the declared rows, ids exact`() {
        harness(support(tableContract, linesProgram)).use { h ->
            execute(
                h,
                body("""{"customer":{"name":"Zoë"},"lines":[{"sku":"A","qty":2,"id":"9007199254740993"},{"sku":"B","qty":1}]}"""),
            ) shouldBe
                listOf(listOf("A", 2, "9007199254740993"), listOf("B", 1, null))
            execute(h, body("""[{"sku":"S","qty":3},{"kind":"comment"},{"sku":"T","qty":4,"unknown":{"x":[1]}}]""")) shouldBe
                listOf(listOf("S", 3, null), listOf("T", 4, null))
            execute(h, body("[]")) shouldBe emptyList()
            execute(h, body("{}")) shouldBe emptyList()
        }
    }

    @Test
    fun `an absent optional body reaches the transformer as undefined, distinct from an empty container`() {
        val probe =
            ScriptContract(
                ScriptMode.TABLE,
                mapOf("order" to ScriptInput.Body()),
                ScriptOutput.Table(listOf(ContractColumn("kind", LogicalType.STRING))),
            )
        harness(support(probe, "[{\"kind\": \$exists(inputs.order) ? \$type(inputs.order) : \"absent\"}]")).use { h ->
            val optional = RequestBodyDeclaration(required = false)
            execute(h, StructuredBody.Absent, optional) shouldBe listOf(listOf("absent"))
            execute(h, body("[]"), optional) shouldBe listOf(listOf("array"))
            execute(h, body("{}"), optional) shouldBe listOf(listOf("object"))
        }
    }

    @Test
    fun `an admitted body past the transformer's value cap is input_too_large, naming no value`() {
        harness(support(tableContract, linesProgram, maxValueBytes = 64)).use { h ->
            val pipeline = Fixtures.pipeline(listOf(reader())).copy(requestBody = RequestBodyDeclaration(required = true))
            val request =
                Fixtures
                    .request(
                        pipeline,
                    ).copy(requestBody = body("""{"lines":[{"sku":"secret-sku-${"x".repeat(80)}","qty":1}]}"""))
            val aborted = shouldThrow<PipelineExecutionFailed> { runBlocking { h.executor.execute(request) } }
            aborted.message shouldNotContain "secret-sku"
            val failed =
                h.emitter.events
                    .filterIsInstance<co.datapipelines.events.NodeFailed>()
                    .single()
            failed.error.code shouldBe PipelineErrorCodes.Script.INPUT_TOO_LARGE
            failed.error.message shouldNotContain "secret-sku"
        }
    }

    @Test
    fun `the executor refuses a missing required body, an undeclared body and a reader override before any node runs`() {
        harness(support(tableContract, linesProgram)).use { h ->
            val declared =
                Fixtures
                    .pipeline(
                        listOf(reader(output = null).copy(contextKey = "total")),
                    ).copy(requestBody = RequestBodyDeclaration(required = true))
            val cases =
                mapOf(
                    RequestBodyAdmission.REQUIRED to Fixtures.request(declared),
                    RequestBodyAdmission.NOT_ACCEPTED to Fixtures.request(declared.copy(requestBody = null)).copy(requestBody = body("{}")),
                    RequestBodyAdmission.OVERRIDE_REFUSED to
                        Fixtures
                            .request(declared)
                            .copy(requestBody = body("{}"), parameters = mapOf("total" to JsonNodeFactory.instance.textNode("x"))),
                )
            cases.forEach { (reason, request) ->
                withClue(reason) {
                    val refused = shouldThrow<DatapipelinesException> { runBlocking { h.executor.execute(request) } }
                    refused.code shouldBe PipelineErrorCodes.Execution.REQUEST_BODY_INVALID
                    refused.details["reason"] shouldBe reason
                }
            }
            h.emitter.events.size shouldBe 0
        }
    }
}
