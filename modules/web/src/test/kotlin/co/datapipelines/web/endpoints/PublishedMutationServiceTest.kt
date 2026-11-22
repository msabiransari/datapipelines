package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.AdmissionStamp
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

    /** The pipeline's declared parameters (JSON); none unless a case declares some. */
    private var parameterSchema = "{}"

    /**
     * The pins: v1 is current unless a case says otherwise; v1 is also every reservation's original — [original] the
     * servable pin a takeover RUNS, [rules] the admitted pin whose rules JUDGE a retry (servable or not).
     */
    private var current: () -> MutationPin? = { pin(1) }
    private var original: () -> MutationPin? = { pin(1) }
    private var rules: () -> MutationPin? = { pin(1) }
    private val pins =
        object : MutationPins {
            override fun current(endpoint: PublishedEndpoint): MutationPin? = current()

            override fun original(
                endpoint: PublishedEndpoint,
                reservation: PublishedMutationReservation,
            ): MutationPin? = original()

            override fun admittedUnder(
                endpoint: PublishedEndpoint,
                reservation: PublishedMutationReservation,
            ): MutationPin? = rules()
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

    /** What the repository stores for an uncaptured admission: the candidate, stamped by the stamp's clock. */
    private fun io.mockk.MockKAnswerScope<PublishedMutationReservation?, *>.stampedUncaptured(): PublishedMutationReservation {
        val stamp = thirdArg<AdmissionStamp>()
        val at = stamp.clock.instant()
        return firstArg<PublishedMutationReservation>().copy(createdAt = at, expiresAt = at.plus(stamp.replay))
    }

    @Test
    fun `a fresh request reserves its scope and starts the current pin under the reservation's id`() {
        val reserved = slot<PublishedMutationReservation>()
        every { reservations.findLive(any()) } returns null
        every { reservations.reserveDeciding(capture(reserved), any(), any()) } answers { stampedUncaptured() }
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
    fun `the launcher starts the reservation as the repository stamped it at durable admission, never the preflight instant`() {
        val stamps = slot<AdmissionStamp>()
        val durable = now.plusSeconds(30)
        val session = UUID.randomUUID()
        every { reservations.findLive(any()) } returns null
        every { reservations.reserveDeciding(any(), any(), capture(stamps)) } answers {
            firstArg<PublishedMutationReservation>().copy(
                createdAt = durable,
                expiresAt = durable.plus(Duration.ofHours(24)),
                captureSessionId = session,
                captureExpiresAt = durable.plus(Duration.ofHours(24)),
            )
        }
        val started = slot<PublishedMutationReservation>()
        every { launcher.start(any(), any(), capture(started), any(), any(), any()) } answers {
            Outcome.Accepted(thirdArg<PublishedMutationReservation>().executionId, emptyMap())
        }

        service().serve(call(), pins)

        // The stamp carries the service's clock and the two configured durations; the repository reads the clock itself.
        stamps.captured.clock shouldBe clock
        stamps.captured.replay shouldBe Duration.ofHours(24)
        stamps.captured.captureRetention shouldBe Duration.ofHours(EndpointsProperties().captureDetailRetentionHours.toLong())
        started.captured.createdAt shouldBe durable
        started.captured.captureSessionId shouldBe session
        started.captured.captureExpiresAt shouldBe durable.plus(Duration.ofHours(24))
    }

    @Test
    fun `with mutations disabled a fresh request is 503 mutations_disabled and nothing is reserved or run`() {
        every { reservations.findLive(any()) } returns null

        val outcome = service(mutationsEnabled = false).serve(call(), pins)

        val refused = outcome.shouldBeInstanceOf<Outcome.Refused>()
        refused.status shouldBe 503
        refused.code shouldBe PipelineErrorCodes.Endpoint.MUTATIONS_DISABLED
        verify(exactly = 0) { reservations.reserveDeciding(any(), any(), any()) }
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
        every { reservations.reserveDeciding(any(), any(), any()) } answers { stampedUncaptured() }
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
        verify(exactly = 0) { reservations.reserveDeciding(any(), any(), any()) }
    }

    @Test
    fun `a pin lookup the metadata store cannot answer is 503 dependency_unavailable`() {
        current = { throw DataAccessResourceFailureException("down") }
        every { reservations.findLive(any()) } returns null

        val refused = service().serve(call(), pins).shouldBeInstanceOf<Outcome.Refused>()

        refused.status shouldBe 503
        refused.code shouldBe PipelineErrorCodes.Endpoint.DEPENDENCY_UNAVAILABLE
        verify(exactly = 0) { reservations.reserveDeciding(any(), any(), any()) }
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
        every { reservations.reserveDeciding(any(), any(), any()) } throws DataAccessResourceFailureException("down")

        val refused = service().serve(call(), pins).shouldBeInstanceOf<Outcome.Refused>()

        refused.code shouldBe PipelineErrorCodes.Limits.IDEMPOTENCY_RESERVATION_UNAVAILABLE
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a lost reservation race replays the winner instead of running twice`() {
        val winner = reservationFor(call(), createdAt = now)
        every { reservations.findLive(winner.scope) } returnsMany listOf(null, winner)
        every { reservations.reserveDeciding(any(), any(), any()) } returns null
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

    // ------------------------------------------------------------------------- the round-1 review's contract (R1–R4)

    @Test
    fun `review - equal typed query values replay the same execution`() {
        parameterSchema = QUANTITY_INTEGER
        val one = call(query = mapOf("quantity" to listOf("01")))
        val two = call(query = mapOf("quantity" to listOf("1")))
        val validator =
            EndpointRequestValidator(
                pin(1).executable.pipeline.parameters,
                EndpointRequestValidator.Limits(1, 86400, 300, 10000, 1000),
            )
        val firstTyped = validator.validate(one.request).shouldBeInstanceOf<EndpointRequestValidator.Outcome.Valid>()
        val nextTyped = validator.validate(two.request).shouldBeInstanceOf<EndpointRequestValidator.Outcome.Valid>()
        firstTyped.parameters shouldBe nextTyped.parameters
        val live = reservationFor(one, createdAt = now)
        every { reservations.findLive(live.scope) } returns live
        every { executions.findById(workspaceId, live.executionId) } returns null
        service().serve(two, pins).shouldBeInstanceOf<Outcome.Accepted>().executionId shouldBe live.executionId
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `review - a replay still refuses unsupported result negotiation`() {
        val live = reservationFor(call(), createdAt = now)
        every { reservations.findLive(live.scope) } returns live
        every { executions.findById(workspaceId, live.executionId) } returns null
        val answer = service().serve(call(accept = "text/plain"), pins).shouldBeInstanceOf<Outcome.Refused>()
        answer.status shouldBe 406
    }

    @Test
    fun `review - original pin dependency outage has the dependency refusal code`() {
        original = { throw DataAccessResourceFailureException("synthetic outage") }
        val stranded = reservationFor(call(), createdAt = now.minusSeconds(61))
        every { reservations.findLive(stranded.scope) } returns stranded
        every { executions.findById(workspaceId, stranded.executionId) } returns null
        every { reservations.takeOver(stranded.executionId, stranded.ownerToken, any()) } returns true
        every { reservations.releaseUnstarted(stranded.executionId, any()) } returns true
        val answer = service().serve(call(), pins).shouldBeInstanceOf<Outcome.Refused>()
        answer.status shouldBe 503
        answer.code shouldBe PipelineErrorCodes.Endpoint.DEPENDENCY_UNAVAILABLE
    }

    @Test
    fun `review - an unconfirmed takeover release fails closed with the reservation refusal code`() {
        original = { null }
        val stranded = reservationFor(call(), createdAt = now.minusSeconds(61))
        every { reservations.findLive(stranded.scope) } returns stranded
        every { executions.findById(workspaceId, stranded.executionId) } returns null
        every { reservations.takeOver(stranded.executionId, stranded.ownerToken, any()) } returns true
        every { reservations.releaseUnstarted(stranded.executionId, any()) } throws DataAccessResourceFailureException("synthetic outage")
        val answer = service().serve(call(), pins).shouldBeInstanceOf<Outcome.Refused>()
        answer.status shouldBe 503
        answer.code shouldBe PipelineErrorCodes.Limits.IDEMPOTENCY_RESERVATION_UNAVAILABLE
    }

    // ------------------------------------------------------------------------- R1: typed comparison, original pin

    @Test
    fun `a fresh request is fingerprinted over its TYPED parameters, so another spelling of the value matches it`() {
        parameterSchema = QUANTITY_INTEGER
        val reserved = slot<PublishedMutationReservation>()
        every { reservations.findLive(any()) } returns null
        every { reservations.reserveDeciding(capture(reserved), any(), any()) } answers { stampedUncaptured() }
        every { launcher.start(any(), any(), any(), any(), any(), any()) } answers {
            Outcome.Accepted(thirdArg<PublishedMutationReservation>().executionId, emptyMap())
        }

        service().serve(call(query = mapOf("quantity" to listOf("01"))), pins)

        reserved.captured.requestFingerprint shouldBe
            reservationFor(call(query = mapOf("quantity" to listOf("1"))), createdAt = now).requestFingerprint
    }

    @Test
    fun `a genuinely different typed value under the same key is 409 and runs nothing`() {
        parameterSchema = QUANTITY_INTEGER
        val live = reservationFor(call(query = mapOf("quantity" to listOf("1"))), createdAt = now)
        every { reservations.findLive(live.scope) } returns live

        val refused = service().serve(call(query = mapOf("quantity" to listOf("2"))), pins).shouldBeInstanceOf<Outcome.Refused>()

        refused.status shouldBe 409
        refused.code shouldBe PipelineErrorCodes.Limits.IDEMPOTENCY_KEY_REUSED
        verify(exactly = 0) { executions.findById(any(), any()) }
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an absent optional parameter and its declared default bind the same value, so they are the same request`() {
        parameterSchema = QUANTITY_DEFAULT_ONE
        val live = reservationFor(call(), createdAt = now)
        every { reservations.findLive(live.scope) } returns live
        every { executions.findById(workspaceId, live.executionId) } returns null

        service().serve(call(query = mapOf("quantity" to listOf("1"))), pins).shouldBeInstanceOf<Outcome.Accepted>().executionId shouldBe
            live.executionId
        val refused = service().serve(call(query = mapOf("quantity" to listOf("2"))), pins).shouldBeInstanceOf<Outcome.Refused>()
        refused.code shouldBe PipelineErrorCodes.Limits.IDEMPOTENCY_KEY_REUSED
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a newer current pin with different parameter rules never changes how a retry is judged`() {
        parameterSchema = QUANTITY_INTEGER
        // v2 is current and declares quantity a STRING: under it "01" and "1" would differ. The retry is judged by v1.
        current = { pin(2, QUANTITY_STRING) }
        val live = reservationFor(call(query = mapOf("quantity" to listOf("01"))), createdAt = now)
        every { reservations.findLive(live.scope) } returns live
        every { executions.findById(workspaceId, live.executionId) } returns null

        val outcome = service().serve(call(query = mapOf("quantity" to listOf("1"))), pins)

        outcome.shouldBeInstanceOf<Outcome.Accepted>().executionId shouldBe live.executionId
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a retry the original pin refuses is that pin's 400 and runs nothing`() {
        parameterSchema = QUANTITY_INTEGER
        val live = reservationFor(call(query = mapOf("quantity" to listOf("1"))), createdAt = now)
        every { reservations.findLive(live.scope) } returns live

        val refused = service().serve(call(query = mapOf("quantity" to listOf("abc"))), pins).shouldBeInstanceOf<Outcome.Refused>()

        refused.status shouldBe 400
        refused.code shouldBe PipelineErrorCodes.EndpointRequest.INVALID
        refused.details.toString().contains("abc") shouldBe false
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
    }

    // ------------------------------------------------------------------------- R2: result negotiation on every answer

    @Test
    fun `an unacceptable first call is 406 before anything is reserved or run`() {
        every { reservations.findLive(any()) } returns null

        val refused = service().serve(call(accept = "text/plain"), pins).shouldBeInstanceOf<Outcome.Refused>()

        refused.status shouldBe 406
        refused.code shouldBe PipelineErrorCodes.Endpoint.NOT_ACCEPTABLE
        verify(exactly = 0) { reservations.reserveDeciding(any(), any(), any()) }
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an unacceptable retry is 406 whatever the execution's state - the record is not even read`() {
        val live = reservationFor(call(), createdAt = now)
        every { reservations.findLive(live.scope) } returns live

        val refused = service().serve(call(accept = "text/csv"), pins).shouldBeInstanceOf<Outcome.Refused>()

        refused.status shouldBe 406
        refused.code shouldBe PipelineErrorCodes.Endpoint.NOT_ACCEPTABLE
        verify(exactly = 0) { executions.findById(any(), any()) }
        verify(exactly = 0) { reservations.retire(any(), any(), any()) }
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
        audit.outcomes(live.executionId) shouldBe emptyList()
    }

    // ------------------------------------------------------------------------- R3: outage is not absence

    @Test
    fun `a retry whose admitted pin cannot be read is 503 dependency_unavailable and claims nothing`() {
        rules = { throw DataAccessResourceFailureException("synthetic outage") }
        val stranded = reservationFor(call(), createdAt = now.minusSeconds(61))
        every { reservations.findLive(stranded.scope) } returns stranded

        val refused = service().serve(call(), pins).shouldBeInstanceOf<Outcome.Refused>()

        refused.status shouldBe 503
        refused.code shouldBe PipelineErrorCodes.Endpoint.DEPENDENCY_UNAVAILABLE
        verify(exactly = 0) { reservations.takeOver(any(), any(), any()) }
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a retry whose admitted pin no longer exists is 503 pipeline_not_released and claims nothing`() {
        rules = { null }
        val stranded = reservationFor(call(), createdAt = now.minusSeconds(61))
        every { reservations.findLive(stranded.scope) } returns stranded

        val refused = service().serve(call(), pins).shouldBeInstanceOf<Outcome.Refused>()

        refused.code shouldBe PipelineErrorCodes.Endpoint.PIPELINE_NOT_RELEASED
        verify(exactly = 0) { reservations.takeOver(any(), any(), any()) }
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a takeover after a pin outage hands its claim back, and once the store recovers it runs the original once`() {
        original = { throw DataAccessResourceFailureException("synthetic outage") }
        val stranded = reservationFor(call(), createdAt = now.minusSeconds(61))
        val claims = mutableListOf<UUID>()
        every { reservations.findLive(stranded.scope) } returns stranded
        every { executions.findById(workspaceId, stranded.executionId) } returns null
        every { reservations.takeOver(stranded.executionId, stranded.ownerToken, capture(claims)) } returns true
        every { reservations.releaseUnstarted(stranded.executionId, any()) } returns true
        every { launcher.start(any(), any(), any(), any(), any(), any()) } answers {
            Outcome.Accepted(thirdArg<PublishedMutationReservation>().executionId, emptyMap())
        }

        service().serve(call(), pins).shouldBeInstanceOf<Outcome.Refused>().code shouldBe PipelineErrorCodes.Endpoint.DEPENDENCY_UNAVAILABLE
        verify(exactly = 1) { reservations.releaseUnstarted(stranded.executionId, claims.single()) }
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }

        original = { pin(1) }
        current = { pin(2) }
        service().serve(call(), pins).shouldBeInstanceOf<Outcome.Accepted>().executionId shouldBe stranded.executionId
        verify(exactly = 1) {
            launcher.start(
                any(),
                any(),
                match { it.executionId == stranded.executionId && it.apiVersion == 1 },
                any(),
                ORIGINAL_TIMEOUT,
                any(),
            )
        }
    }

    // ------------------------------------------------------------------------- R4: only confirmed state answers

    @Test
    fun `an unconfirmed takeover release keeps the reservation, and a retry after recovery runs the original once`() {
        original = { null }
        val stranded = reservationFor(call(), createdAt = now.minusSeconds(61))
        every { reservations.findLive(stranded.scope) } returns stranded
        every { executions.findById(workspaceId, stranded.executionId) } returns null
        every { reservations.takeOver(stranded.executionId, any(), any()) } returns true
        every { reservations.releaseUnstarted(stranded.executionId, any()) } throws DataAccessResourceFailureException("synthetic outage")
        every { launcher.start(any(), any(), any(), any(), any(), any()) } answers {
            Outcome.Accepted(thirdArg<PublishedMutationReservation>().executionId, emptyMap())
        }

        val refused = service().serve(call(), pins).shouldBeInstanceOf<Outcome.Refused>()

        refused.code shouldBe PipelineErrorCodes.Limits.IDEMPOTENCY_RESERVATION_UNAVAILABLE
        verify(exactly = 0) { reservations.retire(any(), any(), any()) }
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
        counted("reservation_unavailable") shouldBe 1.0

        // The store and the pin are back: the same key, the same reservation, the original pin — started once.
        original = { pin(1) }
        service().serve(call(), pins).shouldBeInstanceOf<Outcome.Accepted>().executionId shouldBe stranded.executionId
        verify(exactly = 1) { launcher.start(any(), any(), match { it.executionId == stranded.executionId }, any(), any(), any()) }
    }

    @Test
    fun `a takeover release the store REFUSED is answered from the persisted state, not from the refusal`() {
        original = { null }
        val stranded = reservationFor(call(), createdAt = now.minusSeconds(61))
        every { reservations.findLive(stranded.scope) } returns stranded
        every { executions.findById(workspaceId, stranded.executionId) } returns null
        every { reservations.takeOver(stranded.executionId, stranded.ownerToken, any()) } returns true
        // Confirmed: the claim is another retry's now. With no row yet, that retry is starting it.
        every { reservations.releaseUnstarted(stranded.executionId, any()) } returns false

        val outcome = service().serve(call(), pins)

        outcome.shouldBeInstanceOf<Outcome.Accepted>().executionId shouldBe stranded.executionId
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a takeover release refused while the record cannot be read is 503 reservation_unavailable`() {
        original = { null }
        val stranded = reservationFor(call(), createdAt = now.minusSeconds(61))
        every { reservations.findLive(stranded.scope) } returns stranded
        every { executions.findById(workspaceId, stranded.executionId) } returns null andThenThrows
            DataAccessResourceFailureException("synthetic outage")
        every { reservations.takeOver(stranded.executionId, stranded.ownerToken, any()) } returns true
        every { reservations.releaseUnstarted(stranded.executionId, any()) } returns false

        val refused = service().serve(call(), pins).shouldBeInstanceOf<Outcome.Refused>()

        refused.code shouldBe PipelineErrorCodes.Limits.IDEMPOTENCY_RESERVATION_UNAVAILABLE
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a retry whose execution record cannot be read is 503 reservation_unavailable, never an assumed 202`() {
        val live = reservationFor(call(), createdAt = now)
        every { reservations.findLive(live.scope) } returns live
        every { executions.findById(workspaceId, live.executionId) } throws DataAccessResourceFailureException("synthetic outage")

        val refused = service().serve(call(), pins).shouldBeInstanceOf<Outcome.Refused>()

        refused.code shouldBe PipelineErrorCodes.Limits.IDEMPOTENCY_RESERVATION_UNAVAILABLE
        verify(exactly = 0) { reservations.retire(any(), any(), any()) }
        verify(exactly = 0) { launcher.start(any(), any(), any(), any(), any(), any()) }
    }

    // ------------------------------------------------------------------------- fixture

    private fun call(
        body: String? = """{"sku":"A"}""",
        key: String? = "k-1",
        query: Map<String, List<String>> = emptyMap(),
        accept: String = "application/json",
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
                    queryParameters = query,
                    accept = accept,
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

    /**
     * The reservation a previous identical [call] left under [admittedPin], digested exactly as the service digests it:
     * the fingerprint is taken over the parameters as that pin TYPES them.
     */
    private fun reservationFor(
        call: MutationCall,
        createdAt: Instant,
        expiresAt: Instant = createdAt.plus(Duration.ofHours(24)),
        admittedPin: MutationPin = pin(1),
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
        val typed =
            MutationRequestRules(ResultConfig())
                .validate(call, admittedPin, body)
                .shouldBeInstanceOf<MutationRequestRules.Validated.Valid>()
                .typed
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
            requestFingerprint = digests.fingerprint(EndpointMethod.POST, call.path, typed, body),
            bodyBytes = null,
            ownerToken = UUID.randomUUID(),
            createdAt = createdAt,
            expiresAt = expiresAt,
        )
    }

    private fun pin(
        apiVersion: Int,
        schema: String = parameterSchema,
    ): MutationPin =
        MutationPin(
            apiVersion = apiVersion,
            timeoutSeconds = if (apiVersion == 1) ORIGINAL_TIMEOUT else 30,
            pipelineId = pipelineId,
            executable = executable(schema),
        )

    private fun executable(schema: String): PipelineService.ExecutablePipeline {
        val body =
            """{"name":"x/orders","display_name":"o","description":"d","parameters":$schema,"request_body":{"required":false},""" +
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
        /** A won reservation no capture session admitted (#1043): the default answer of every case here. */

        const val KEY_ID = "dpk_MUTATION01"
        const val ORIGINAL_TIMEOUT = 7
        const val QUANTITY_INTEGER = """{"quantity":{"type":"INTEGER","required":false}}"""
        const val QUANTITY_DEFAULT_ONE = """{"quantity":{"type":"INTEGER","required":false,"default":1}}"""
        const val QUANTITY_STRING = """{"quantity":{"type":"STRING","required":false}}"""
    }
}
