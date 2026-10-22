package co.datapipelines.application.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.ContractColumn
import co.datapipelines.templates.ScriptContract
import co.datapipelines.templates.ScriptInput
import co.datapipelines.templates.ScriptMode
import co.datapipelines.templates.ScriptOutput
import co.datapipelines.templates.ScriptTestInput
import co.datapipelines.templates.ScriptTestRunner
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The ONE evaluation service (script-nodes §9.1) over a mocked repository and the REAL
 * runner / engine / pool: the working-version resolution, the type refusal, the input check,
 * the gated output and the invariant verdicts — the shape the MCP tool and the REST route map
 * verbatim.
 */
class TemplateEvaluateServiceTest {
    private val templates = mockk<TemplateRepository>()
    private val workspaceId = UUID.randomUUID()

    private val contract =
        ScriptContract(
            mode = ScriptMode.ROW,
            inputs =
                mapOf(
                    "orders" to
                        ScriptInput.Table(
                            listOf(
                                ContractColumn("order_id", LogicalType.INTEGER),
                                ContractColumn("amount_cents", LogicalType.INTEGER),
                            ),
                        ),
                ),
            output =
                ScriptOutput.Table(
                    listOf(
                        ContractColumn("order_id", LogicalType.INTEGER),
                        ContractColumn("amount", LogicalType.DECIMAL, precision = 12, scale = 2),
                    ),
                ),
        )

    private val stored =
        Template(
            id = "test/xform.jsonata",
            version = 3,
            engine = Template.NONE_ENGINE,
            type = TemplateType.JSONATA,
            dialect = null,
            displayName = "X",
            description = "d",
            body =
                """
                [ rows.{ "order_id": order_id, "amount": amount_cents / 100 } ]
                """.trimIndent(),
            createdAt = Instant.EPOCH,
            createdBy = UUID.randomUUID(),
            contract = contract,
            invariants =
                listOf(
                    co.datapipelines.templates.ScriptInvariant(
                        "one_to_one",
                        "\$count(rows) = \$count(inputs.orders)",
                        "no row is lost",
                    ),
                ),
            tests = emptyList(),
        )

    private fun service(javascript: co.datapipelines.scripting.ScriptEngine? = null): TemplateEvaluateService {
        val runner =
            ScriptTestRunner(
                engines =
                    buildMap {
                        put(co.datapipelines.scripting.ScriptLanguage.JSONATA, co.datapipelines.scripting.JsonataEngine())
                        if (javascript != null) put(co.datapipelines.scripting.ScriptLanguage.JAVASCRIPT, javascript)
                    },
                pool =
                    co.datapipelines.scripting.ScriptEvaluationPool(
                        2,
                        8,
                        Duration.ofMillis(200),
                        co.datapipelines.scripting.ScriptEvaluationPool.SYSTEM,
                    ),
                evaluateTimeout = Duration.ofSeconds(5),
                suiteTimeout = Duration.ofSeconds(30),
            )
        return TemplateEvaluateService(templates, runner)
    }

    @Test
    fun `javascript reaches the same pool and invariant gate and returns console`() {
        every { templates.findWorking(workspaceId, stored.id) } returns stored.copy(type = TemplateType.JAVASCRIPT)
        val delegate = co.datapipelines.scripting.JsonataEngine()
        val javascript =
            object : co.datapipelines.scripting.ScriptEngine by delegate {
                override val type = co.datapipelines.scripting.ScriptLanguage.JAVASCRIPT

                override fun evaluate(
                    script: co.datapipelines.scripting.CompiledScript,
                    input: Any?,
                    limits: co.datapipelines.scripting.EvaluationLimits,
                ): Any? {
                    limits.console?.accept(co.datapipelines.scripting.ConsoleLine(co.datapipelines.scripting.ConsoleLevel.LOG, "mapped"))
                    return delegate.evaluate(script, input, limits)
                }
            }
        val result =
            service(javascript).evaluate(
                workspaceId,
                stored.id,
                null,
                ScriptTestInput(rows = listOf(mapOf("order_id" to 1, "amount_cents" to 1250)), inputs = emptyMap()),
            )
        result.consolePayload() shouldBe listOf(mapOf("level" to "log", "text" to "mapped"))
        result.invariants.single().passed shouldBe true
        val unavailable =
            shouldThrow<DatapipelinesException> {
                service().evaluate(workspaceId, stored.id, null, ScriptTestInput(rows = emptyList(), inputs = emptyMap()))
            }
        unavailable.code shouldBe PipelineErrorCodes.Script.JS_UNAVAILABLE
        unavailable.message shouldBe co.datapipelines.pipeline.JAVASCRIPT_UNAVAILABLE_MESSAGE
    }

    @Test
    fun `a failed javascript evaluation retains console and resource kind for both adapters`() {
        every { templates.findWorking(workspaceId, stored.id) } returns stored.copy(type = TemplateType.JAVASCRIPT)
        val delegate = co.datapipelines.scripting.JsonataEngine()
        val javascript =
            object : co.datapipelines.scripting.ScriptEngine by delegate {
                override val type = co.datapipelines.scripting.ScriptLanguage.JAVASCRIPT

                override fun evaluate(
                    script: co.datapipelines.scripting.CompiledScript,
                    input: Any?,
                    limits: co.datapipelines.scripting.EvaluationLimits,
                ): Any? {
                    limits.console?.accept(
                        co.datapipelines.scripting.ConsoleLine(co.datapipelines.scripting.ConsoleLevel.ERROR, "before failure"),
                    )
                    throw co.datapipelines.scripting.ScriptResourceLimitException(
                        co.datapipelines.scripting.ScriptResourceLimitException.Kind.OUTPUT,
                        "witness",
                    )
                }
            }
        val failure =
            shouldThrow<DatapipelinesException> {
                service(javascript).evaluate(workspaceId, stored.id, null, ScriptTestInput(rows = emptyList(), inputs = emptyMap()))
            }
        failure.code shouldBe PipelineErrorCodes.Script.RESOURCE_LIMIT
        failure.details["kind"] shouldBe "OUTPUT"
        failure.details["console"] shouldBe listOf(mapOf("level" to "error", "text" to "before failure"))
    }

    @Test
    fun `the working version resolves, evaluates, gates and runs invariants`() {
        every { templates.findWorking(workspaceId, "test/xform.jsonata") } returns stored

        val result =
            service().evaluate(
                workspaceId,
                "test/xform.jsonata",
                version = null,
                input =
                    ScriptTestInput(
                        rows = listOf(mapOf("order_id" to 1, "amount_cents" to 1250)),
                        inputs = emptyMap(),
                    ),
            )

        assertSoftly {
            result.output shouldBe listOf(mapOf("order_id" to 1, "amount" to java.math.BigDecimal("12.50")))
            result.rejects shouldBe emptyList()
            result.invariants.single().name shouldBe "one_to_one"
            result.invariants.single().passed shouldBe true
        }
    }

    @Test
    fun `a sql template is refused as not-a-script`() {
        every { templates.findWorking(workspaceId, "test/plain.sql") } returns
            stored.copy(id = "test/plain.sql", type = TemplateType.SQL, contract = null, invariants = null, tests = null)

        val thrown =
            shouldThrow<DatapipelinesException> {
                service().evaluate(workspaceId, "test/plain.sql", null, ScriptTestInput(rows = emptyList()))
            }
        thrown.code shouldBe PipelineErrorCodes.Template.CONTRACT_INVALID
        thrown.details["rule"] shouldBe "type_not_script"
    }

    @Test
    fun `an input violating the contract is the input-check refusal`() {
        every { templates.findWorking(workspaceId, "test/xform.jsonata") } returns stored

        val thrown =
            shouldThrow<DatapipelinesException> {
                service().evaluate(
                    workspaceId,
                    "test/xform.jsonata",
                    null,
                    ScriptTestInput(rows = listOf(mapOf("order_id" to "x")), inputs = emptyMap()),
                )
            }
        // The §13.18 constant is 7c's (declared privately in templates until then).
        thrown.code shouldBe "pipeline.script.input_contract_violation"
    }

    @Test
    fun `an unknown template is template_not_found`() {
        every { templates.findWorking(workspaceId, "test/missing.jsonata") } returns null

        val thrown =
            shouldThrow<DatapipelinesException> {
                service().evaluate(workspaceId, "test/missing.jsonata", null, ScriptTestInput(rows = emptyList()))
            }
        thrown.code shouldBe PipelineErrorCodes.Template.NOT_FOUND
    }
}
