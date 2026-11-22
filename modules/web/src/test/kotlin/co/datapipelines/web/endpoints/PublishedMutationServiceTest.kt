package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointMethod
import co.datapipelines.application.endpoints.EndpointRequestValidator
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedMutationDigests
import co.datapipelines.application.endpoints.PublishedMutationReservation
import co.datapipelines.application.endpoints.PublishedMutationReservationRepository
import co.datapipelines.application.endpoints.ReadOnlyPipelineRule
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.KeyRole
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ResultConfig
import co.datapipelines.executor.ResultStore
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.typesystem.body.StructuredBodyDecoder
import co.datapipelines.typesystem.body.StructuredBodyLimits
import co.datapipelines.web.config.EndpointsProperties
import co.datapipelines.web.endpoints.PublishedEndpointServeService.Outcome
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * #1039 — the admission order of a published mutation (published-mutation-apis §3–§7), each branch on its own: what is
 * reserved, what runs, and what is answered and counted. The end-to-end proof is `PublishedMutationServeIntegrationTest`;
 * this suite pins the branches the HTTP surface cannot steer — the operator flag, store outages, a lost reservation race
 * and the start-claim takeover.
 *
 * The audit sink and the meter registry are REAL in-memory recorders: their contract is "this was written", which a
 * strict mock could only show by failing on an extra call, never on a missing one.
 */
class PublishedMutationServiceTest {
    private val workspaceId = UUID.randomUUID()
    private val now = Instant.parse("2026-11-21T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val reservations = mockk<PublishedMutationReservationRepository>()
    private val executions = mockk<ExecutionRepository>()
    private val launcher = mockk<PublishedMutationLauncher>()
    private val readOnlyRule = mockk<ReadOnlyPipelineRule>()
    private val audit = RecordingAudit()
    private val meters = SimpleMeterRegistry()
    private val digests = PublishedMutationDigests(ByteArray(32) { it.toByte() })
    private val urls = PublishedMutationUrls(null)

    private val endpoint =
        PublishedEndpoint.of(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            pathPattern = "/x/v1/orders",
            isEnabled = true,
            createdBy = UUID.randomUUID(),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            method = EndpointMethod.POST,
        )
    private val pipelineId = UUID.randomUUID()
    private val principal =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "dpk_mutation01@keys.invalid",
            displayName = "mutation",
            authMethod = AuthMethod.API_KEY,
            keyId = KEY_ID,
            workspaceName = "w",
            workspace = WorkspaceContext(workspaceId, "w", WorkspaceRole.VIEWER),
            keyKind = ApiKeyKind.ENDPOINT,
            keyRole = KeyRole.API_CALLER,
        )

    /** The pins: v1 is current unless a case says otherwise; v1 is also every reservation's original. */
    private var current: () -> MutationPin? = { pin(1) }
    private var original: () -> MutationPin? = { pin(1) }
    private val pins =
        object : MutationPins {
            override fun current(endpoint: PublishedEndpoint): MutationPin? = current()

            override fun original(
                endpoint: PublishedEndpoint,
                reservation: PublishedMutationReservation,
            ): MutationPin? = original()
        }

    private fun service(mutationsEnabled: Boolean = true): PublishedMutationService {
        every { readOnlyRule.check(any(), any()) } returns ValidationResult(emptyList())
        return PublishedMutationService(
            reservations = reservations,
            digests = digests,
            executions = executions,
            codec = PublishedRequestBodyCodec(StructuredBodyDecoder(StructuredBodyLimits(maxBytes = 4096, maxDepth = 8))),
            answers = PublishedMutationAnswers(mockk<ResultStore>(), urls),
            launcher = launcher,
            readOnlyRule = readOnlyRule,
            endpointsProperties = EndpointsProperties(mutationsEnabled = mutationsEnabled),
            requests = MutationRequestRules(ResultConfig()),
            windows = PublishedMutationService.Windows(replay = Duration.ofHours(24), start = Duration.ofSeconds(60)),
            audit = audit,
            meters = meters,
            clock = clock,
        )
    }

    // ------------------------------------------------------------------------- fresh requests

    @Test
    fun `a fresh request reserves its scope and starts the current pin under the reservation's id`() {
        val reserved = slot<PublishedMutationReservation>()
        every { reservations.findLive(any()) } returns null
        every { reservations.reserve(capture(reserved)) } returns true
        every { launcher.start(any(), any(), any(), any(), any(), any()) } answers {
            Outcome.Accepted(thirdArg<PublishedMutationReservation>().executionId, emptyMap())
        }

        val outcome = service().serve(call(), pins)

        val accepted = outcome.shouldBeInstanceOf<Outcome.Accepted>()
        accepted.executionId shouldBe reserved.captured.executionId
        reserved.captured.apiVersion shouldBe 1
        reserved.captured.apiKeyId shouldBe KEY_ID
        reserved.captured.businessPath shouldBe "/x/v1/orders"
        reserved.captured.expiresAt shouldBe now.plus(Duration.ofHours(24))
        verify(exactly = 1) {
            launcher.start(endpoint, principal, any(), match { it.executionId == reserved.captured.executionId }, ORIGINAL_TIMEOUT, any())
        }
    }

    @Test
    fun `with mutations disabled a fresh request is 503 mutations_disabled and nothing is reserved or run`() {
        every { reservations.findLive(any()) } returns null

        val outcome = service(mutationsEnabled = false).serve(call(), pins)

        val refused = outcome.shouldBeInstanceOf<Outcome.Refused>()
        refused.status shouldBe 503
        refused.code shouldBe PipelineErrorCodes.Endpoint.MUTATIONS_DISABLED
        verify(exactly = 0) { reservations.reserve(any()) }
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
        counted("disabled") shouldBe 1.0
    }

    @Test
    fun `with mutations disabled a retry of an admitted request is still answered from its reservation`() {
        val live = reservationFor(call(), createdAt = now)
        every { reservations.findLive(live.scope) } returns live
        every { executions.findById(workspaceId, live.executionId) } returns null

        val outcome = service(mutationsEnabled = false).serve(call(), pins)

        // Inside the start window with no row yet: the owner may still be starting it — 202 with the same id.
        val accepted = outcome.shouldBeInstanceOf<Outcome.Accepted>()
        accepted.executionId shouldBe live.executionId
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
        audit.outcomes(live.executionId) shouldBe listOf("replayed")
    }

    @Test
    fun `an expired reservation is retired and the request is admitted fresh`() {
        val stale = reservationFor(call(), createdAt = now.minus(Duration.ofDays(2)), expiresAt = now.minusSeconds(1))
        every { reservations.findLive(stale.scope) } returns stale
        every { reservations.retire(stale.executionId, stale.scope, now) } returns true
        every { reservations.reserve(any()) } returns true
        every { launcher.start(any(), any(), any(), any(), any(), any()) } answers {
            Outcome.Accepted(thirdArg<PublishedMutationReservation>().executionId, emptyMap())
        }

        val outcome = service().serve(call(), pins)

        // A new request under a new id: the retired reservation's execution is never answered for it.
        val accepted = outcome.shouldBeInstanceOf<Outcome.Accepted>()
        (accepted.executionId == stale.executionId) shouldBe false
        verify(exactly = 1) { reservations.retire(stale.executionId, stale.scope, now) }
    }

    @Test
    fun `a route with no servable pin is 503 pipeline_not_released before anything is reserved`() {
        current = { null }
        every { reservations.findLive(any()) } returns null

        val refused = service().serve(call(), pins).shouldBeInstanceOf<Outcome.Refused>()

        refused.status shouldBe 503
        refused.code shouldBe PipelineErrorCodes.Endpoint.PIPELINE_NOT_RELEASED
        verify(exactly = 0) { reservations.reserve(any()) }
    }

    @Test
    fun `a pin lookup the metadata store cannot answer is 503 dependency_unavailable`() {
        current = { throw DataAccessResourceFailureException("down") }
        every { reservations.findLive(any()) } returns null

        val refused = service().serve(call(), pins).shouldBeInstanceOf<Outcome.Refused>()

        refused.status shouldBe 503
        refused.code shouldBe PipelineErrorCodes.Endpoint.DEPENDENCY_UNAVAILABLE
        verify(exactly = 0) { reservations.reserve(any()) }
    }

    // ------------------------------------------------------------------------- the store

    @Test
    fun `a reservation store that cannot be read is 503 reservation_unavailable and nothing runs`() {
        every { reservations.findLive(any()) } throws DataAccessResourceFailureException("down")

        val refused = service().serve(call(), pins).shouldBeInstanceOf<Outcome.Refused>()

        refused.status shouldBe 503
        refused.code shouldBe PipelineErrorCodes.Limits.IDEMPOTENCY_RESERVATION_UNAVAILABLE
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
        counted("reservation_unavailable") shouldBe 1.0
    }

    @Test
    fun `a reservation that cannot be written is 503 reservation_unavailable and nothing runs`() {
        every { reservations.findLive(any()) } returns null
        every { reservations.reserve(any()) } throws DataAccessResourceFailureException("down")

        val refused = service().serve(call(), pins).shouldBeInstanceOf<Outcome.Refused>()

        refused.code shouldBe PipelineErrorCodes.Limits.IDEMPOTENCY_RESERVATION_UNAVAILABLE
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a lost reservation race replays the winner instead of running twice`() {
        val winner = reservationFor(call(), createdAt = now)
        every { reservations.findLive(winner.scope) } returnsMany listOf(null, winner)
        every { reservations.reserve(any()) } returns false
        every { executions.findById(workspaceId, winner.executionId) } returns null

        val outcome = service().serve(call(), pins)

        outcome.shouldBeInstanceOf<Outcome.Accepted>().executionId shouldBe winner.executionId
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
    }

    // ------------------------------------------------------------------------- replay

    @Test
    fun `the same key with a different input is 409 and names no execution`() {
        val live = reservationFor(call(body = """{"sku":"A"}"""), createdAt = now)
        every { reservations.findLive(live.scope) } returns live

        val refused = service().serve(call(body = """{"sku":"B"}"""), pins).shouldBeInstanceOf<Outcome.Refused>()

        refused.status shouldBe 409
        refused.code shouldBe PipelineErrorCodes.Limits.IDEMPOTENCY_KEY_REUSED
        refused.details shouldBe emptyMap()
        refused.message.contains(live.executionId.toString()) shouldBe false
        counted("key_reused") shouldBe 1.0
    }

    @Test
    fun `a reservation with no execution row past the start window is taken over and runs the ORIGINAL pin`() {
        current = { pin(2) }
        val stranded = reservationFor(call(), createdAt = now.minusSeconds(61))
        val claimed = slot<UUID>()
        every { reservations.findLive(stranded.scope) } returns stranded
        every { executions.findById(workspaceId, stranded.executionId) } returns null
        every { reservations.takeOver(stranded.executionId, stranded.ownerToken, capture(claimed)) } returns true
        every { launcher.start(any(), any(), any(), any(), any(), any()) } answers {
            Outcome.Accepted(thirdArg<PublishedMutationReservation>().executionId, emptyMap())
        }

        val outcome = service().serve(call(), pins)

        outcome.shouldBeInstanceOf<Outcome.Accepted>().executionId shouldBe stranded.executionId
        verify(exactly = 1) {
            launcher.start(
                endpoint,
                principal,
                match { it.executionId == stranded.executionId && it.ownerToken == claimed.captured && it.apiVersion == 1 },
                match { it.executionId == stranded.executionId },
                ORIGINAL_TIMEOUT,
                any(),
            )
        }
    }

    @Test
    fun `a takeover lost to another retry answers 202 with the same id and runs nothing`() {
        val stranded = reservationFor(call(), createdAt = now.minusSeconds(61))
        every { reservations.findLive(stranded.scope) } returns stranded
        every { executions.findById(workspaceId, stranded.executionId) } returns null
        every { reservations.takeOver(stranded.executionId, stranded.ownerToken, any()) } returns false

        val outcome = service().serve(call(), pins)

        outcome.shouldBeInstanceOf<Outcome.Accepted>().executionId shouldBe stranded.executionId
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a takeover the store cannot answer is 503 reservation_unavailable, never a 202`() {
        val stranded = reservationFor(call(), createdAt = now.minusSeconds(61))
        every { reservations.findLive(stranded.scope) } returns stranded
        every { executions.findById(workspaceId, stranded.executionId) } returns null
        every { reservations.takeOver(any(), any(), any()) } throws DataAccessResourceFailureException("down")

        val refused = service().serve(call(), pins).shouldBeInstanceOf<Outcome.Refused>()

        refused.code shouldBe PipelineErrorCodes.Limits.IDEMPOTENCY_RESERVATION_UNAVAILABLE
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a takeover whose original pin is no longer servable releases its claim and runs nothing`() {
        original = { null }
        val stranded = reservationFor(call(), createdAt = now.minusSeconds(61))
        val claimed = slot<UUID>()
        every { reservations.findLive(stranded.scope) } returns stranded
        every { executions.findById(workspaceId, stranded.executionId) } returns null
        every { reservations.takeOver(stranded.executionId, stranded.ownerToken, capture(claimed)) } returns true
        every { reservations.releaseUnstarted(stranded.executionId, any()) } returns true

        val refused = service().serve(call(), pins).shouldBeInstanceOf<Outcome.Refused>()

        refused.code shouldBe PipelineErrorCodes.Endpoint.PIPELINE_NOT_RELEASED
        verify(exactly = 1) { reservations.releaseUnstarted(stranded.executionId, claimed.captured) }
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
    }

    // ------------------------------------------------------------------------- fixture

    private fun call(
        body: String? = """{"sku":"A"}""",
        key: String? = "k-1",
    ): MutationCall =
        MutationCall(
            endpoint = endpoint,
            method = EndpointMethod.POST,
            path = "/x/v1/orders",
            pathVariables = emptyMap(),
            principal = principal,
            request =
                EndpointRequestValidator.Request(
                    pathVariables = emptyMap(),
                    queryParameters = emptyMap(),
                    accept = "application/json",
                    resultTtlSecondsHeader = null,
                    resultPageRowsHeader = null,
                ),
            input =
                MutationInput(
                    idempotencyKey = key,
                    contentType = body?.let { "application/json" },
                    contentEncoding = null,
                    entity = ByteArrayInputStream(body.orEmpty().toByteArray()),
                    declaredLength = body.orEmpty().length.toLong(),
                ),
        )

    /** The reservation a previous identical [call] left, digested exactly as the service digests it. */
    private fun reservationFor(
        call: MutationCall,
        createdAt: Instant,
        expiresAt: Instant = createdAt.plus(Duration.ofHours(24)),
    ): PublishedMutationReservation {
        val body =
            PublishedRequestBodyCodec(StructuredBodyDecoder(StructuredBodyLimits(maxBytes = 4096, maxDepth = 8)))
                .read(
                    call.input.contentType,
                    null,
                    call.input.entity,
                    PublishedRequestBodyCodec.Route(acceptsBody = true, required = false),
                ).shouldBeInstanceOf<PublishedRequestBodyCodec.Outcome.Admitted>()
                .body
        call.input.entity.reset()
        return PublishedMutationReservation(
            executionId = UUID.randomUUID(),
            workspaceId = workspaceId,
            apiKeyId = KEY_ID,
            endpointId = endpoint.id,
            apiVersion = 1,
            pipelineId = pipelineId,
            pipelineVersion = 1,
            hasCaller = true,
            method = EndpointMethod.POST,
            businessPath = call.path,
            scopeDigest =
                digests.scope(
                    workspaceId,
                    KEY_ID,
                    endpoint.id,
                    EndpointMethod.POST,
                    call.path,
                    checkNotNull(call.input.idempotencyKey),
                ),
            requestFingerprint = digests.fingerprint(EndpointMethod.POST, call.path, emptyMap(), body),
            bodyBytes = null,
            ownerToken = UUID.randomUUID(),
            createdAt = createdAt,
            expiresAt = expiresAt,
        )
    }

    private fun pin(apiVersion: Int): MutationPin =
        MutationPin(
            apiVersion = apiVersion,
            timeoutSeconds = if (apiVersion == 1) ORIGINAL_TIMEOUT else 30,
            pipelineId = pipelineId,
            executable = executable(),
        )

    private fun executable(): PipelineService.ExecutablePipeline {
        val body =
            """{"name":"x/orders","display_name":"o","description":"d","parameters":{},"request_body":{"required":false},""" +
                """"nodes":[{"id":"n","type":"DML","source":"s","template":{"id":"t.sql","version":1},""" +
                """"operation":"update","output":{"target":"discard"},"depends_on":[]}]}"""
        val record =
            PipelineRecord(
                id = pipelineId,
                name = "x/orders",
                displayName = "o",
                description = "",
                ownerId = principal.userId,
                currentVersion = 1,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
        return PipelineService.ExecutablePipeline(record, 1, body, PipelineDeserializer().readOrThrow(body))
    }

    private val PublishedMutationReservation.scope: String get() = checkNotNull(scopeDigest)

    private fun counted(outcome: String): Double =
        meters
            .find(PublishedMutationLauncher.METER)
            .tags("method", "POST", "outcome", outcome)
            .counter()
            ?.count() ?: 0.0

    /** In-memory [AuditEventSink] — the recorded effect is the assertion. */
    private class RecordingAudit : AuditEventSink {
        val rows = mutableListOf<Map<String, Any?>>()

        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) {
            rows += details
        }

        fun outcomes(executionId: UUID): List<String> =
            rows.filter { it["execution_id"] == executionId.toString() }.map { it["outcome"] as String }
    }

    private companion object {
        const val KEY_ID = "dpk_MUTATION01"
        const val ORIGINAL_TIMEOUT = 7
    }
}
