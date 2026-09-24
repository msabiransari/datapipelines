package co.datapipelines.application.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.ContractColumn
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TransformContract
import co.datapipelines.templates.TransformInput
import co.datapipelines.templates.TransformMode
import co.datapipelines.templates.TransformOutput
import co.datapipelines.templates.TransformTestInput
import co.datapipelines.templates.TransformTestRunner
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
 * The ONE evaluation service (transform-nodes §9.1) over a mocked repository and the REAL
 * runner / engine / pool: the working-version resolution, the type refusal, the input check,
 * the gated output and the invariant verdicts — the shape the MCP tool and the REST route map
 * verbatim.
 */
class TemplateEvaluateServiceTest {
    private val templates = mockk<TemplateRepository>()
    private val workspaceId = UUID.randomUUID()

    private val contract =
        TransformContract(
            mode = TransformMode.ROW,
            inputs =
                mapOf(
                    "orders" to
                        TransformInput.Table(
                            listOf(
                                ContractColumn("order_id", LogicalType.INTEGER),
                                ContractColumn("amount_cents", LogicalType.INTEGER),
                            ),
                        ),
                ),
            output =
                TransformOutput.Table(
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
                    co.datapipelines.templates.TransformInvariant(
                        "one_to_one",
                        "\$count(rows) = \$count(inputs.orders)",
                        "no row is lost",
                    ),
                ),
            tests = emptyList(),
        )

    private fun service(): TemplateEvaluateService {
        val runner =
            TransformTestRunner(
                engines =
                    mapOf(
                        co.datapipelines.scripting.ScriptLanguage.JSONATA to
                            co.datapipelines.scripting.JsonataEngine(),
                    ),
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
    fun `the working version resolves, evaluates, gates and runs invariants`() {
        every { templates.findWorking(workspaceId, "test/xform.jsonata") } returns stored

        val result =
            service().evaluate(
                workspaceId,
                "test/xform.jsonata",
                version = null,
                input =
                    TransformTestInput(
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
    fun `a sql template is refused as not-a-transform`() {
        every { templates.findWorking(workspaceId, "test/plain.sql") } returns
            stored.copy(id = "test/plain.sql", type = TemplateType.SQL, contract = null, invariants = null, tests = null)

        val thrown =
            shouldThrow<DatapipelinesException> {
                service().evaluate(workspaceId, "test/plain.sql", null, TransformTestInput(rows = emptyList()))
            }
        thrown.code shouldBe PipelineErrorCodes.Template.CONTRACT_INVALID
        thrown.details["rule"] shouldBe "type_not_transform"
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
                    TransformTestInput(rows = listOf(mapOf("order_id" to "x")), inputs = emptyMap()),
                )
            }
        // The §13.18 constant is 7c's (declared privately in templates until then).
        thrown.code shouldBe "pipeline.transform.input_contract_violation"
    }

    @Test
    fun `an unknown template is template_not_found`() {
        every { templates.findWorking(workspaceId, "test/missing.jsonata") } returns null

        val thrown =
            shouldThrow<DatapipelinesException> {
                service().evaluate(workspaceId, "test/missing.jsonata", null, TransformTestInput(rows = emptyList()))
            }
        thrown.code shouldBe PipelineErrorCodes.Template.NOT_FOUND
    }
}
