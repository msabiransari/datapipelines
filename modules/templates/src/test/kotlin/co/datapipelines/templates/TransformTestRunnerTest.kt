package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.scripting.JsonataEngine
import co.datapipelines.scripting.ScriptEvaluationPool
import co.datapipelines.scripting.ScriptLanguage
import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * The §8.1 test suite over the record's own §2.2 example, and each named refusal: a wrong
 * expected output, a wrong refusal code, a false invariant, a case over its wall clock, and a
 * suite over its budget. Every case runs on the real pool through the real engine.
 *
 * Falsified at birth: skipping the invariants on cases turns the false-invariant test red.
 */
class TransformTestRunnerTest {
    private val workspaceId = UUID.randomUUID()

    private fun runner(
        evaluateTimeout: Duration = Duration.ofSeconds(5),
        suiteTimeout: Duration = Duration.ofSeconds(30),
        pool: ScriptEvaluationPool = ScriptEvaluationPool(4, 64, Duration.ofMillis(200), ScriptEvaluationPool.SYSTEM),
    ) = TransformTestRunner(
        engines = mapOf(ScriptLanguage.JSONATA to JsonataEngine()),
        pool = pool,
        evaluateTimeout = evaluateTimeout,
        suiteTimeout = suiteTimeout,
    )

    private fun validatorWith(runner: TransformTestRunner) =
        TemplateValidator(LibraryResolver { _ -> InMemoryTemplateRegistry() }, suiteRunner = runner)

    /** The record §2.2's example contract (row mode, rejects, the two invariants). */
    private val exampleContract =
        TransformContract(
            mode = TransformMode.ROW,
            inputs =
                mapOf(
                    "orders" to
                        TransformInput.Table(
                            listOf(
                                ContractColumn("order_id", LogicalType.INTEGER),
                                ContractColumn("amount_cents", LogicalType.INTEGER),
                                ContractColumn("customer_id", LogicalType.STRING, nullable = true),
                            ),
                        ),
                    "tz" to TransformInput.Value(LogicalType.STRING),
                    "min_total" to TransformInput.Value(LogicalType.DECIMAL, precision = 12, scale = 2),
                ),
            output =
                TransformOutput.Table(
                    listOf(
                        ContractColumn("order_id", LogicalType.INTEGER),
                        ContractColumn("amount", LogicalType.DECIMAL, precision = 12, scale = 2),
                        ContractColumn("customer_id", LogicalType.STRING),
                    ),
                ),
            rejects = true,
        )

    private val exampleInvariants =
        listOf(
            TransformInvariant(
                name = "customer_present",
                expr = "\$count(rows[customer_id = null]) = 0",
                message = "every accepted row carries a customer id",
            ),
            TransformInvariant(
                name = "one_to_one",
                expr = "\$count(rows) + \$count(rejects) = \$count(inputs.orders)",
                message = "every input row is accepted or rejected, never lost",
            ),
        )

    // The array constructors are load-bearing: JSONata flattens a singleton sequence to the
    // value and drops an undefined one, so a bare `rows[pred].{}` is an object (or missing)
    // when the match count is one (or zero), never the array the contract declares.
    private val exampleBody =
        """
        {
          "rows": [ rows[customer_id != null].{
            "order_id": order_id,
            "amount": amount_cents / 100,
            "customer_id": customer_id
          } ],
          "rejects": [ rows[customer_id = null].{
            "row": {
              "order_id": order_id,
              "amount_cents": amount_cents,
              "customer_id": customer_id
            },
            "reason": "customer_id missing"
          } ]
        }
        """.trimIndent()

    private fun exampleCases() =
        listOf(
            TransformTestCase(
                name = "empty input",
                input = TransformTestInput(rows = emptyList(), inputs = mapOf("tz" to "UTC", "min_total" to 0.00)),
                expect = TransformTestExpect(output = TransformBlocks.mapper.readTree("""{"rows": [], "rejects": []}""")),
            ),
            TransformTestCase(
                name = "missing customer is rejected",
                input =
                    TransformTestInput(
                        rows = listOf(mapOf("order_id" to 1, "amount_cents" to 1250, "customer_id" to null)),
                        inputs = mapOf("tz" to "UTC", "min_total" to 0.00),
                    ),
                expect =
                    TransformTestExpect(
                        output =
                            TransformBlocks.mapper.readTree(
                                """{"rows": [], "rejects": [{"row": {"order_id": 1, "amount_cents": 1250, "customer_id": null}, "reason": "customer_id missing"}]}""",
                            ),
                    ),
            ),
            TransformTestCase(
                name = "wrong shape is refused",
                input =
                    TransformTestInput(
                        rows = listOf(mapOf("order_id" to "x")),
                        inputs = mapOf("tz" to "UTC", "min_total" to 0.00),
                    ),
                expect = TransformTestExpect(refusal = TransformCodes.INPUT_CONTRACT_VIOLATION),
            ),
        )

    private fun exampleDraft(
        body: String = exampleBody,
        invariants: List<TransformInvariant> = exampleInvariants,
        tests: List<TransformTestCase> = exampleCases(),
    ) = TemplateFixtures.draft(
        id = "test/xform.jsonata",
        type = TemplateType.JSONATA,
        engine = Template.NONE_ENGINE,
        dialect = null,
        body = body,
        contract = exampleContract,
        invariants = invariants,
        tests = tests,
    )

    @Test
    fun `the record's section-2_2 example saves`() {
        validatorWith(runner()).validate(exampleDraft(), workspaceId).failures.shouldBeEmpty()
    }

    @Test
    fun `a wrong expected output is test_failed with the diff path`() {
        val cases =
            listOf(
                exampleCases()[0],
                exampleCases()[1].copy(
                    expect =
                        TransformTestExpect(
                            output = TransformBlocks.mapper.readTree("""{"rows": [{"order_id": 1}], "rejects": []}"""),
                        ),
                ),
                exampleCases()[2],
            )
        val failures = validatorWith(runner()).validate(exampleDraft(tests = cases), workspaceId).failures
        failures.size shouldBe 1
        failures.single().code shouldBe PipelineErrorCodes.Template.TEST_FAILED
        failures.single().details["case"] shouldBe "missing customer is rejected"
        failures.single().message shouldContain "differs at"
    }

    @Test
    fun `a wrong refusal code is test_failed`() {
        val cases =
            listOf(
                exampleCases()[0],
                exampleCases()[2].copy(expect = TransformTestExpect(refusal = "pipeline.transform.timeout")),
            )
        val failures = validatorWith(runner()).validate(exampleDraft(tests = cases), workspaceId).failures
        failures.size shouldBe 1
        failures.single().code shouldBe PipelineErrorCodes.Template.TEST_FAILED
        failures.single().message shouldContain TransformCodes.INPUT_CONTRACT_VIOLATION
    }

    @Test
    fun `a false invariant is test_failed naming the invariant`() {
        val failures =
            validatorWith(runner())
                .validate(
                    exampleDraft(
                        invariants =
                            listOf(TransformInvariant("always_false", "\$count(rows) > 100", "rows must number a hundred")),
                        tests = listOf(exampleCases()[0]),
                    ),
                    workspaceId,
                ).failures
        failures.size shouldBe 1
        failures.single().code shouldBe PipelineErrorCodes.Template.TEST_FAILED
        failures.single().details["invariant"] shouldBe "always_false"
    }

    @Test
    fun `a case over its wall clock is the engine timeout refusal`() {
        val slow =
            exampleCases()[0].copy(
                name = "slow",
            )
        val runner = runner(evaluateTimeout = Duration.ofMillis(300))
        val failures =
            validatorWith(runner)
                .validate(exampleDraft(body = "\$pad('x', 100000000)", tests = listOf(slow)), workspaceId)
                .failures
        failures.size shouldBe 1
        failures.single().code shouldBe PipelineErrorCodes.Template.TEST_FAILED
        failures.single().message shouldContain "pipeline.transform.timeout"
    }

    @Test
    fun `a suite over its budget is the suite-timeout refusal`() {
        val runner = runner(suiteTimeout = Duration.ZERO)
        val failures =
            validatorWith(runner)
                .validate(exampleDraft(), workspaceId)
                .failures
        failures.size shouldBe exampleCases().size
        failures.forEach { it.code shouldBe PipelineErrorCodes.Template.TEST_FAILED }
        failures.first().details["timeout"] shouldBe "suite"
    }

    @Test
    fun `a case pinning the clock sees it, and the same body without the pin refuses`() {
        val clockBody =
            """
            {
              "rows": [{
                "order_id": 1,
                "amount": 0,
                "customer_id": ${'$'}now()
              }],
              "rejects": []
            }
            """.trimIndent()
        val pinnedCase =
            TransformTestCase(
                name = "clock pinned",
                input =
                    TransformTestInput(
                        rows = emptyList(),
                        inputs = mapOf("tz" to "UTC", "min_total" to 0.00),
                        now = "2026-09-23T12:00:00Z",
                    ),
                expect =
                    TransformTestExpect(
                        output =
                            TransformBlocks.mapper.readTree(
                                """{"rows": [{"order_id": 1, "amount": 0, "customer_id": "2026-09-23T12:00:00.000Z"}], "rejects": []}""",
                            ),
                    ),
            )
        val draft = exampleDraft(body = clockBody, invariants = emptyList(), tests = listOf(pinnedCase))
        validatorWith(runner()).validate(draft, workspaceId).failures.shouldBeEmpty()

        // The same body with no pin: the clock builtins refuse (7a's A.3), so the case fails.
        val unpinned = draft.copy(tests = draft.tests.orEmpty().map { it.copy(input = it.input.copy(now = null)) })
        val failures = validatorWith(runner()).validate(unpinned, workspaceId).failures
        failures.size shouldBe 1
        failures.single().code shouldBe PipelineErrorCodes.Template.TEST_FAILED
    }
}
