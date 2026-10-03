package co.datapipelines.web.parameters

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.parameters.EvaluateResponse
import co.datapipelines.parameters.EvaluationCaller
import co.datapipelines.parameters.OrgEcho
import co.datapipelines.parameters.ParameterEvaluationRepository
import co.datapipelines.parameters.ParameterEvaluator
import co.datapipelines.parameters.ParameterSetBody
import co.datapipelines.parameters.ParameterSetJson
import co.datapipelines.parameters.ParameterSetRecord
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.ParameterSetVersion
import co.datapipelines.parameters.ParameterSetVersionDetail
import co.datapipelines.parameters.ParametersConfig
import co.datapipelines.parameters.SelectorPool
import co.datapipelines.parameters.SelectorTasks
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.EVERYTHING_LENS
import co.datapipelines.web.api.ApiErrorCatalog
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.config.SseProperties
import co.datapipelines.web.config.WebSurfaceConfiguration.ExecutionCoroutineScope
import co.datapipelines.web.parameters.stream.ParameterEvaluationStream
import co.datapipelines.web.parameters.stream.ParameterEvaluationStreamAuthority
import co.datapipelines.web.parameters.stream.ParameterEvaluationStreamRegistry
import co.datapipelines.web.sse.SseJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger

/**
 * The observed evaluation's route (rest-api §21.5; spec §4.1) over mocked collaborators — the `ParameterSetsControllerTest`
 * mould. Every refusal is decided BEFORE a stream opens, in the spec's order (body shape → the lensed set + explicit
 * version → unknown keys → reuse → the cap), and each one is asserted to have opened nothing and started nothing. The
 * 404 and the unknown-key 400 are the ordinary evaluate's own (parity against `ParameterSetsController` on one fixture).
 */
class ParameterEvaluationStreamControllerTest {
    private val sets = mockk<ParameterSetService>()
    private val evaluator = mockk<ParameterEvaluator>()
    private val authority = mockk<ParameterEvaluationStreamAuthority>(relaxed = true)
    private val others = AtomicInteger(0)
    private val registry =
        ParameterEvaluationStreamRegistry(
            SseProperties(maxStreamsPerUser = 2),
            otherStreams = { others.get() },
            mapper = SseJson.mapper,
            scheduler = mockk<ScheduledExecutorService>(relaxed = true),
        )
    private val scope = ExecutionCoroutineScope()

    // The history, as a fake keyed by (workspace, id) — a wrong workspace passed by the controller reads as unused, as in the table.
    private val recorded = mutableSetOf<Pair<UUID, UUID>>()
    private val history =
        mockk<ParameterEvaluationRepository> {
            every { exists(any(), any()) } answers { (firstArg<UUID>() to secondArg<UUID>()) in recorded }
        }
    private val controller = ParameterEvaluationStreamController(sets, EVERYTHING_LENS, history, evaluator, registry, authority, scope)

    private val userId = UUID.randomUUID()
    private val setId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val evaluationId = "1b4e28ba-2fa1-41d2-883f-0016d3cca427"
    private val instanceId = "6f9619ff-8b86-4011-b42d-00cf4fc964ff"
    private val set =
        ParameterSetVersion(
            ParameterSetRecord(
                setId,
                workspaceId,
                "acme/sales/region_filters",
                "Region filters",
                "",
                4,
                Instant.parse("2026-09-28T00:00:00Z"),
                Instant.parse("2026-09-28T00:00:00Z"),
                userId,
            ),
            ParameterSetVersionDetail(setId, 4, PipelineVersionStatus.RELEASED, "hash", Instant.parse("2026-09-28T00:00:00Z"), userId),
            ParameterSetJson.mapper.treeToValue(
                ParameterSetJson.mapper.readTree(
                    """
                    { "display_name": "Region filters", "parameters": [
                      { "name": "country", "label": "Country", "type": "STRING", "kind": "SELECT", "cardinality": "SINGLE",
                        "source": { "constants": [ { "value": "USA", "display_value": "United States", "is_default": true } ] } } ] }
                    """.trimIndent(),
                ),
                ParameterSetBody::class.java,
            ),
        )

    @AfterEach
    fun clear() {
        SecurityContextHolder.clearContext()
        scope.close()
    }

    private fun authenticate() {
        val principal = AuthenticatedPrincipal(userId, "a@b.c", "A", AuthMethod.OIDC, workspace = WorkspaceContext(workspaceId, "acme"))
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun body(
        version: String = "4",
        selections: String = """{"country":"USA"}""",
        evaluation: String = "\"$evaluationId\"",
    ) = """{"version":$version,"selections":$selections,"evaluation_id":$evaluation,"instance_id":"$instanceId"}"""

    /** Nothing opened, nothing started — the assertion every refusal below ends with. */
    private fun nothingStarted() {
        registry.activeStreams shouldBe 0
        coVerify(exactly = 0) { evaluator.evaluate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a body over the stated bound is the platform 413 before the JSON is parsed or the set is read`() {
        authenticate()
        val oversized = """{"selections":{"x":"${"y".repeat(ParameterSetsControllerTestBound.BYTES)}"}}"""

        val error = shouldThrow<ApiException> { controller.evaluations(setId, oversized) }

        error.code shouldBe "request.body_too_large"
        ApiErrorCatalog.statusFor(error.code) shouldBe HttpStatus.PAYLOAD_TOO_LARGE
        verify(exactly = 0) { sets.findVersion(any(), any(), any(), any()) }
        nothingStarted()
    }

    @Test
    fun `the body's shape is judged before the set - whatever the id, a malformed body is the 400`() {
        authenticate()

        listOf(
            """{"selections":{},"evaluation_id":"$evaluationId","instance_id":"$instanceId"}""" to ("version" to "missing"),
            body(version = "\"4\"") to ("version" to "wrong_type"),
            body(selections = "[]") to ("selections" to "wrong_type"),
            body(evaluation = "\"c232ab00-9414-11ec-b3c8-9f6bdeced846\"") to ("evaluation_id" to "malformed"),
        ).forEach { (request, expected) ->
            val error = shouldThrow<ApiException> { controller.evaluations(UUID.randomUUID(), request) }
            error.code shouldBe "parameter.validation.body_invalid"
            (error.details["path"] to error.details["reason"]) shouldBe expected
        }
        verify(exactly = 0) { sets.findVersion(any(), any(), any(), any()) }
        nothingStarted()
    }

    @Test
    fun `a hidden set or a missing version is the ordinary evaluate's IDENTICAL 404`() {
        authenticate()
        every { sets.findVersion(workspaceId, any(), setId, 4) } returns null
        val ordinary =
            ParameterSetsController(sets, mockk<ParameterSetRepository>(), evaluator, mockk(), ParametersConfig(), EVERYTHING_LENS, mockk())

        val observed = shouldThrow<ApiException> { controller.evaluations(setId, body()) }
        val plain = shouldThrow<ApiException> { ordinary.evaluate(setId, """{"version":4,"selections":{"country":"USA"}}""") }

        observed.code shouldBe "parameter.not_found"
        (observed.code to observed.message to observed.details) shouldBe (plain.code to plain.message to plain.details)
        nothingStarted()
    }

    @Test
    fun `an unknown selections key is the ordinary evaluate's unknown_parameter 400, code, message and details - before any stream`() {
        authenticate()
        every { sets.findVersion(workspaceId, any(), setId, 4) } returns set
        val real = ParameterEvaluator(mockk<SelectorTasks>(), SelectorPool(1, 0))
        val ordinary =
            ParameterSetsController(sets, mockk<ParameterSetRepository>(), real, mockk(), ParametersConfig(), EVERYTHING_LENS, mockk())

        val observed =
            shouldThrow<DatapipelinesException> { controller.evaluations(setId, body(selections = """{"country":"USA","nope":1}""")) }
        val plain =
            shouldThrow<DatapipelinesException> { ordinary.evaluate(setId, """{"version":4,"selections":{"country":"USA","nope":1}}""") }

        observed.code shouldBe "parameter.evaluate.unknown_parameter"
        ApiErrorCatalog.statusFor(observed.code) shouldBe HttpStatus.BAD_REQUEST
        (observed.code to observed.message to observed.details) shouldBe (plain.code to plain.message to plain.details)
        nothingStarted()
    }

    @Test
    fun `an evaluation id already open on this instance is refused reused - no second stream, no evaluation`() {
        authenticate()
        every { sets.findVersion(workspaceId, any(), setId, 4) } returns set
        registry.open(UUID.fromString(evaluationId), setId, 4, principal(), authority)

        val error = shouldThrow<ApiException> { controller.evaluations(setId, body()) }

        error.code shouldBe "parameter.validation.body_invalid"
        error.details shouldBe mapOf("path" to "evaluation_id", "reason" to "reused")
        registry.activeStreams shouldBe 1
        coVerify(exactly = 0) { evaluator.evaluate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an id the workspace's history already holds is refused reused - its stream long closed, no stream, no evaluation (417)`() {
        authenticate()
        every { sets.findVersion(workspaceId, any(), setId, 4) } returns set
        recorded += workspaceId to UUID.fromString(evaluationId)

        val error = shouldThrow<ApiException> { controller.evaluations(setId, body()) }

        error.code shouldBe "parameter.validation.body_invalid"
        ApiErrorCatalog.statusFor(error.code) shouldBe HttpStatus.BAD_REQUEST
        error.details shouldBe mapOf("path" to "evaluation_id", "reason" to "reused")
        verify(exactly = 1) { history.exists(workspaceId, UUID.fromString(evaluationId)) }
        registry.find(UUID.fromString(evaluationId)) shouldBe null
        nothingStarted()
    }

    @Test
    fun `reuse is judged BEFORE the cap - a full cap and a recorded id answer reused, never rate_limit exceeded (417)`() {
        authenticate()
        every { sets.findVersion(workspaceId, any(), setId, 4) } returns set
        others.set(2)
        recorded += workspaceId to UUID.fromString(evaluationId)

        val error = shouldThrow<ApiException> { controller.evaluations(setId, body()) }

        error.code shouldBe "parameter.validation.body_invalid"
        error.details shouldBe mapOf("path" to "evaluation_id", "reason" to "reused")
        nothingStarted()
    }

    @Test
    fun `an id recorded in ANOTHER workspace is not refused by the read - the evaluation opens its stream and runs (417)`() {
        authenticate()
        every { sets.findVersion(workspaceId, any(), setId, 4) } returns set
        recorded += UUID.randomUUID() to UUID.fromString(evaluationId)
        val ran = kotlinx.coroutines.CompletableDeferred<Any>()
        coEvery { evaluator.evaluate(workspaceId, set, any(), any(), any()) } coAnswers {
            ran.complete(arg<Any>(4))
            EvaluateResponse(setId, set.record.name, 4, OrgEcho(null, null), emptyList())
        }

        val emitter = controller.evaluations(setId, body())

        verify(exactly = 1) { history.exists(workspaceId, UUID.fromString(evaluationId)) }
        emitter shouldBeSameInstanceAs (registry.find(UUID.fromString(evaluationId)) as ParameterEvaluationStream).emitter
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(5_000) { ran.await() } }
    }

    @Test
    fun `the one per-user cap is the 429 rate_limit exceeded - no stream opened, no evaluation started`() {
        authenticate()
        every { sets.findVersion(workspaceId, any(), setId, 4) } returns set
        others.set(2)

        val error = shouldThrow<ApiException> { controller.evaluations(setId, body()) }

        error.code shouldBe "rate_limit.exceeded"
        ApiErrorCatalog.statusFor(error.code) shouldBe HttpStatus.TOO_MANY_REQUESTS
        nothingStarted()
    }

    @Test
    fun `the happy path opens the stream, runs the evaluation on the scope with the stream as its observer, and answers the emitter`() {
        authenticate()
        every { sets.findVersion(workspaceId, any(), setId, 4) } returns set
        val ran = kotlinx.coroutines.CompletableDeferred<Any>()
        // #376: the page is the PAGE caller, and the client-minted evaluation id is the history record's key.
        val minted = UUID.fromString(evaluationId)
        coEvery {
            evaluator.evaluate(workspaceId, set, any(), match { it.caller == EvaluationCaller.PAGE && it.evaluationId == minted }, any())
        } coAnswers {
            ran.complete(arg<Any>(4))
            EvaluateResponse(setId, set.record.name, 4, OrgEcho(null, null), emptyList())
        }

        val emitter = controller.evaluations(setId, body())

        val stream = registry.find(UUID.fromString(evaluationId)) as ParameterEvaluationStream
        emitter shouldBeSameInstanceAs stream.emitter
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(5_000) { ran.await() } } shouldBeSameInstanceAs stream
        stream.parameterSetId shouldBe setId
        stream.version shouldBe 4
    }

    private fun principal() =
        AuthenticatedPrincipal(userId, "a@b.c", "A", AuthMethod.OIDC, workspace = WorkspaceContext(workspaceId, "acme"))

    /** The ordinary evaluate's bound, as `ParameterSetsControllerTest` spells it. */
    private object ParameterSetsControllerTestBound {
        const val BYTES = 1_048_576
    }
}
