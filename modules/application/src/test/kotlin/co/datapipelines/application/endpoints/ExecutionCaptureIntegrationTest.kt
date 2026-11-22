package co.datapipelines.application.endpoints

import co.datapipelines.application.SharedPostgres
import co.datapipelines.executor.CaptureSeal
import co.datapipelines.executor.CapturedValue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * #1043 — runtime capture's storage against a real PostgreSQL with the shipped migrations (V68): the one-open-window
 * index, the idempotent session-identified stop, the admission decision taken in the reserving transaction (exact and
 * unfiltered correlation, the half-open deadline, route identity, workspace scope), the database's ordering of a stop
 * against an admission (each side held open in turn), and the private store's own refusals, expiry-bounded reads and
 * batched purge. Each is a property of SQL that a mocked template could never falsify.
 *
 * The write-wait cases (r3) hold a table lock so the reserving INSERT, or a value or seal INSERT that already passed
 * its reservation lock and first deadline check, waits while the clock crosses a boundary (and a purge completes);
 * they need this class's shipped-schema fixture and lock-wait probe, so the class is kept whole rather than split
 * into files that would each re-seed the same schema.
 */
@Suppress("LargeClass") // one storage contract over one shipped schema; see the KDoc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExecutionCaptureIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var sessions: ExecutionCaptureSessionRepository
    private lateinit var reservations: PublishedMutationReservationRepository
    private lateinit var store: ExecutionCaptureStore

    private lateinit var workspaceId: UUID
    private lateinit var otherWorkspaceId: UUID
    private lateinit var userId: UUID
    private lateinit var pipelineId: UUID
    private lateinit var keyId: String
    private lateinit var endpointId: UUID
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private val window = Duration.ofSeconds(900)
    private val retention = Duration.ofHours(24)
    private val replay = Duration.ofHours(24)

    /** The store's write-time clock: what "now" is when a diagnostic write reaches storage. */
    private val storeClock = MutableClock(now)

    @BeforeAll
    fun connect() {
        val ds = SharedPostgres.dataSource()
        jdbc = NamedParameterJdbcTemplate(ds)
        sessions = ExecutionCaptureSessionRepository(jdbc, TransactionTemplate(DataSourceTransactionManager(ds)))
        reservations = PublishedMutationReservationRepository(jdbc, TransactionTemplate(DataSourceTransactionManager(ds)))
        store = ExecutionCaptureStore(jdbc, TransactionTemplate(DataSourceTransactionManager(ds)), storeClock)
    }

    @BeforeEach
    fun seed() {
        storeClock.set(now)
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        userId = UUID.randomUUID()
        workspaceId = UUID.randomUUID()
        otherWorkspaceId = UUID.randomUUID()
        pipelineId = UUID.randomUUID()
        endpointId = UUID.randomUUID()
        keyId = "dpk_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to userId, "email" to "u$userId@example.com", "sub" to "sub-$userId"),
        )
        for (ws in listOf(workspaceId, otherWorkspaceId)) {
            jdbc.update(
                "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (:id, :name, 'W', :owner)",
                mapOf("id" to ws, "name" to "w_${ws.toString().replace("-", "")}", "owner" to userId),
            )
        }
        jdbc.update(
            "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id) " +
                "VALUES (:id, :name, 'P', :owner, 1, :ws)",
            mapOf("id" to pipelineId, "name" to "p_${pipelineId.toString().replace("-", "")}", "owner" to userId, "ws" to workspaceId),
        )
        for (ws in listOf(workspaceId, otherWorkspaceId)) {
            jdbc.update(
                "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role) " +
                    "VALUES (:id, :owner, :owner, 'endpoint key', 'x', :ws, 'endpoint', 'api_caller')",
                mapOf("id" to keyFor(ws), "owner" to userId, "ws" to ws),
            )
        }
    }

    private fun keyFor(ws: UUID) = if (ws == workspaceId) keyId else "${keyId}_o"

    private fun session(
        correlation: UUID? = null,
        startedAt: Instant = now,
        ws: UUID = workspaceId,
        endpoint: UUID = endpointId,
        method: EndpointMethod = EndpointMethod.POST,
    ) = CaptureSession(
        id = UUID.randomUUID(),
        workspaceId = ws,
        endpointId = endpoint,
        method = method,
        pathPattern = "/orders/{id}",
        correlationId = correlation,
        startedBy = userId,
        startedAt = startedAt,
        deadlineAt = startedAt.plus(window),
    )

    private fun candidate(
        createdAt: Instant = now.plusSeconds(1),
        ws: UUID = workspaceId,
        endpoint: UUID = endpointId,
        method: EndpointMethod = EndpointMethod.POST,
    ): PublishedMutationReservation {
        val scope = "scope-${UUID.randomUUID()}"
        return PublishedMutationReservation(
            executionId = UUID.randomUUID(),
            workspaceId = ws,
            apiKeyId = keyFor(ws),
            endpointId = endpoint,
            apiVersion = 1,
            pipelineId = pipelineId,
            pipelineVersion = 1,
            hasCaller = true,
            method = method,
            businessPath = "/x/v1/orders/42",
            scopeDigest = scope,
            requestFingerprint = "fp-$scope",
            bodyBytes = 17,
            ownerToken = UUID.randomUUID(),
            createdAt = createdAt,
            expiresAt = createdAt.plusSeconds(86_400),
        )
    }

    /** The decision a reserving transaction stored, as the tests read it. */
    private data class Decision(
        val sessionId: UUID?,
        val expiresAt: Instant?,
    ) {
        val captured: Boolean get() = sessionId != null
    }

    private fun stamp(at: Instant) = AdmissionStamp(Clock.fixed(at, ZoneOffset.UTC), replay, retention)

    private fun at(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)

    /** Admits [c] with its own createdAt as the admission instant; the stamped reservation as stored, and its decision. */
    private fun admit(
        c: PublishedMutationReservation = candidate(),
        correlation: UUID? = null,
    ): Pair<PublishedMutationReservation, Decision> {
        val stored = reservations.reserveDeciding(c, correlation, stamp(c.createdAt)).shouldNotBeNull()
        return stored to Decision(stored.captureSessionId, stored.captureExpiresAt)
    }

    @Test
    fun `one open window per route and method - a second start is refused naming the first, an expired one is closed`() {
        val first = session()
        sessions.start(first).shouldBeInstanceOf<CaptureStart.Opened>()
        sessions
            .start(session())
            .shouldBeInstanceOf<CaptureStart.AlreadyActive>()
            .active.id shouldBe first.id
        // Another method or another workspace is another key.
        sessions.start(session(method = EndpointMethod.PUT)).shouldBeInstanceOf<CaptureStart.Opened>()
        sessions.start(session(ws = otherWorkspaceId)).shouldBeInstanceOf<CaptureStart.Opened>()
        // Past the first window's deadline, a new start closes it as EXPIRED at its deadline.
        val later = session(startedAt = now.plus(window))
        sessions.start(later).shouldBeInstanceOf<CaptureStart.Opened>()
        sessions.find(workspaceId, endpointId, first.id).shouldNotBeNull().let {
            it.endReason shouldBe CaptureSession.EndReason.EXPIRED
            it.stoppedAt shouldBe first.deadlineAt
        }
    }

    @Test
    fun `stop names its window - idempotent, never closes a newer one, absent in another workspace`() {
        val first = session()
        sessions.start(first)
        val stopped = sessions.stop(workspaceId, endpointId, first.id, userId, at(now.plusSeconds(5)))
        stopped.shouldBeInstanceOf<CaptureStop.Stopped>().session.let {
            it.endReason shouldBe CaptureSession.EndReason.STOPPED
            it.stoppedAt shouldBe now.plusSeconds(5)
            it.stoppedBy shouldBe userId
        }
        val second = session(startedAt = now.plusSeconds(10))
        sessions.start(second).shouldBeInstanceOf<CaptureStart.Opened>()
        // The delayed repeat of the first stop answers the first window's state and leaves the second open.
        sessions.stop(workspaceId, endpointId, first.id, userId, at(now.plusSeconds(20))).shouldBeInstanceOf<CaptureStop.AlreadyEnded>()
        sessions.open(workspaceId, endpointId, EndpointMethod.POST).shouldNotBeNull().id shouldBe second.id
        sessions.stop(otherWorkspaceId, endpointId, second.id, userId, at(now)) shouldBe CaptureStop.NotFound
        sessions.stop(workspaceId, UUID.randomUUID(), second.id, userId, at(now)) shouldBe CaptureStop.NotFound
        // A stop at or after the deadline records the expiry at the deadline.
        sessions
            .stop(
                workspaceId,
                endpointId,
                second.id,
                userId,
                at(second.deadlineAt.plusSeconds(60)),
            ).shouldBeInstanceOf<CaptureStop.Stopped>()
            .session
            .let {
                it.endReason shouldBe CaptureSession.EndReason.EXPIRED
                it.stoppedAt shouldBe second.deadlineAt
            }
    }

    @Test
    fun `default off - no window, no capture decision`() {
        val (c, decision) = admit()
        decision.captured shouldBe false
        reservations
            .findLive(c.scopeDigest!!)
            .shouldNotBeNull()
            .captureSessionId
            .shouldBeNull()
    }

    @Test
    fun `unfiltered window captures any correlation, an exact filter only its own`() {
        val unfiltered = session()
        sessions.start(unfiltered)
        admit(correlation = null).second.sessionId shouldBe unfiltered.id
        admit(correlation = UUID.randomUUID()).second.sessionId shouldBe unfiltered.id
        sessions.stop(workspaceId, endpointId, unfiltered.id, userId, at(now.plusSeconds(2)))

        val wanted = UUID.randomUUID()
        val exact = session(correlation = wanted, startedAt = now.plusSeconds(3))
        sessions.start(exact)
        val (c, decision) = admit(candidate(createdAt = now.plusSeconds(4)), correlation = wanted)
        decision.sessionId shouldBe exact.id
        decision.expiresAt shouldBe c.createdAt.plus(retention)
        admit(candidate(createdAt = now.plusSeconds(4)), correlation = UUID.randomUUID()).second.captured shouldBe false
        admit(candidate(createdAt = now.plusSeconds(4)), correlation = null).second.captured shouldBe false
    }

    @Test
    fun `the window is half-open at its deadline, and a stopped window admits nothing`() {
        val s = session()
        sessions.start(s)
        admit(candidate(createdAt = s.deadlineAt.minus(1, ChronoUnit.MICROS))).second.captured shouldBe true
        admit(candidate(createdAt = s.deadlineAt)).second.captured shouldBe false
        sessions.stop(workspaceId, endpointId, s.id, userId, at(now.plusSeconds(2)))
        admit(candidate(createdAt = now.plusSeconds(3))).second.captured shouldBe false
    }

    @Test
    fun `before and exactly at the start, before and exactly at the deadline - the stored instant, decision and expiries agree`() {
        val s = session(startedAt = now.plusSeconds(10))
        sessions.start(s)
        admit(candidate(createdAt = s.startedAt.minus(1, ChronoUnit.MICROS))).second.captured shouldBe false
        val (atStart, decision) = admit(candidate(createdAt = s.startedAt))
        decision.sessionId shouldBe s.id
        stored(atStart).let {
            it.createdAt shouldBe s.startedAt
            it.expiresAt shouldBe s.startedAt.plus(replay)
            it.captureExpiresAt shouldBe s.startedAt.plus(retention)
        }
        admit(candidate(createdAt = s.deadlineAt.minus(1, ChronoUnit.MICROS))).second.captured shouldBe true
        val (atDeadline, late) = admit(candidate(createdAt = s.deadlineAt))
        late.captured shouldBe false
        stored(atDeadline).let {
            it.createdAt shouldBe s.deadlineAt
            it.captureSessionId.shouldBeNull()
            it.captureExpiresAt.shouldBeNull()
        }
    }

    @Test
    fun `a stale preflight timestamp is never the admission instant - the clock read at durable admission decides`() {
        val s = session(startedAt = now.plusSeconds(10))
        sessions.start(s)
        // Validated before the window started; durable admission happened after the start.
        val crossedStart = candidate(createdAt = now.plusSeconds(9))
        val admittedAt = now.plusSeconds(11)
        reservations.reserveDeciding(crossedStart, null, stamp(admittedAt)).shouldNotBeNull().captureSessionId shouldBe s.id
        stored(crossedStart).let {
            it.createdAt shouldBe admittedAt
            it.expiresAt shouldBe admittedAt.plus(replay)
            it.captureSessionId shouldBe s.id
            it.captureExpiresAt shouldBe admittedAt.plus(retention)
        }
        // Validated just before the natural expiry; durable admission happened after it.
        val crossedDeadline = candidate(createdAt = s.deadlineAt.minusSeconds(1))
        val afterDeadline = s.deadlineAt.plusSeconds(1)
        reservations
            .reserveDeciding(crossedDeadline, null, stamp(afterDeadline))
            .shouldNotBeNull()
            .captureSessionId
            .shouldBeNull()
        stored(crossedDeadline).let {
            it.createdAt shouldBe afterDeadline
            it.captureExpiresAt.shouldBeNull()
        }
    }

    @Test
    fun `a database wait crossing the start or the natural expiry is decided at the instant after the wait`() {
        val s = session(startedAt = now.plusSeconds(10))
        sessions.start(s)
        val clock = MutableClock(s.startedAt.minusSeconds(1))
        val crossedStart = candidate(createdAt = s.startedAt.minusSeconds(1))
        heldWindowAdmission(s, crossedStart, clock, advanceTo = s.startedAt).let {
            it.createdAt shouldBe s.startedAt
            it.captureSessionId shouldBe s.id
            it.captureExpiresAt shouldBe s.startedAt.plus(retention)
        }
        clock.set(s.deadlineAt.minusSeconds(1))
        val crossedDeadline = candidate(createdAt = s.deadlineAt.minusSeconds(1))
        heldWindowAdmission(s, crossedDeadline, clock, advanceTo = s.deadlineAt).let {
            it.createdAt shouldBe s.deadlineAt
            it.captureSessionId.shouldBeNull()
        }
        stored(crossedStart).createdAt shouldBe s.startedAt
        stored(crossedDeadline).createdAt shouldBe s.deadlineAt
    }

    @Test
    fun `a stop whose clock lags a captured admission still ends the window after that admission`() {
        val s = session()
        sessions.start(s)
        val (c, decision) = admit(candidate(createdAt = now.plusSeconds(30)))
        decision.captured shouldBe true
        sessions
            .stop(workspaceId, endpointId, s.id, userId, at(now.plusSeconds(20)))
            .shouldBeInstanceOf<CaptureStop.Stopped>()
            .session.stoppedAt shouldBe c.createdAt.plus(1, ChronoUnit.MICROS)
    }

    @Test
    fun `a recreated route, another method or another workspace with the same text inherits nothing`() {
        sessions.start(session())
        admit(candidate(endpoint = UUID.randomUUID())).second.captured shouldBe false
        admit(candidate(method = EndpointMethod.PUT)).second.captured shouldBe false
        admit(candidate(ws = otherWorkspaceId)).second.captured shouldBe false
    }

    @Test
    fun `the winning reservation owns the decision - a losing retry writes nothing and reads the winner's`() {
        val s = session()
        sessions.start(s)
        val (c, decision) = admit()
        decision.sessionId shouldBe s.id
        sessions.stop(workspaceId, endpointId, s.id, userId, at(now.plusSeconds(2)))
        // The retry under the same scope after the stop: no row, and the live row still carries the original decision.
        reservations.reserveDeciding(c.copy(executionId = UUID.randomUUID()), null, stamp(now.plusSeconds(3))).shouldBeNull()
        reservations.findLive(c.scopeDigest!!).shouldNotBeNull().captureSessionId shouldBe s.id
    }

    @Test
    fun `a stop held open blocks admission, which then sees the window closed`() {
        val s = session()
        sessions.start(s)
        withHeldConnection { held ->
            held.update(
                "UPDATE execution_capture_sessions SET stopped_at = :t, end_reason = 'STOPPED', stopped_by = :u WHERE id = :id",
                mapOf("t" to java.sql.Timestamp.from(now.plusSeconds(1)), "u" to userId, "id" to s.id),
            )
            val admission = CompletableFuture.supplyAsync { admit().second }
            awaitLockWait()
            admission.isDone shouldBe false
            held.jdbcTemplate.execute("COMMIT")
            admission.get(30, TimeUnit.SECONDS).captured shouldBe false
        }
    }

    @Test
    fun `an admission held open blocks the stop, and is captured`() {
        val s = session()
        sessions.start(s)
        val c = candidate()
        withHeldConnection { held ->
            val stored =
                PublishedMutationReservationRepository(held, TransactionOperations.withoutTransaction())
                    .reserveDeciding(c, null, stamp(c.createdAt))
                    .shouldNotBeNull()
            stored.captureSessionId shouldBe s.id
            val stop = CompletableFuture.supplyAsync { sessions.stop(workspaceId, endpointId, s.id, userId, at(now.plusSeconds(2))) }
            awaitLockWait()
            stop.isDone shouldBe false
            held.jdbcTemplate.execute("COMMIT")
            stop
                .get(30, TimeUnit.SECONDS)
                .shouldBeInstanceOf<CaptureStop.Stopped>()
                .session.stoppedAt!!
                .isAfter(c.createdAt) shouldBe true
        }
        reservations.findLive(c.scopeDigest!!).shouldNotBeNull().captureSessionId shouldBe s.id
    }

    @Test
    fun `the store refuses an uncaptured, foreign or expired write, and keeps the reservation's expiry`() {
        val (plain, _) = admit()
        store.write(value(plain.executionId, workspaceId, recordedAt = now.plusSeconds(2))) shouldBe false
        sessions.start(session())
        val (captured, decision) = admit()
        val expiresAt = decision.expiresAt!!
        store.write(value(captured.executionId, otherWorkspaceId, recordedAt = now.plusSeconds(2))) shouldBe false
        store.write(value(captured.executionId, workspaceId, recordedAt = now.plusSeconds(2), expiresAt = Instant.MAX)) shouldBe true
        store.write(value(captured.executionId, workspaceId, recordedAt = now.plusSeconds(3))) shouldBe false // same node/output
        store.values(captured.executionId, workspaceId, now.plusSeconds(4)).single().expiresAt shouldBe expiresAt
        store.values(captured.executionId, otherWorkspaceId, now.plusSeconds(4)).shouldBeEmpty()
        // At the stored expiry the store's own write-time clock refuses — whatever the value's recorded_at says.
        storeClock.set(expiresAt)
        store.write(value(captured.executionId, workspaceId, node = "late", recordedAt = expiresAt.minusSeconds(10))) shouldBe false
        store.seal(seal(captured.executionId, decision.sessionId!!, sealedAt = expiresAt.minusSeconds(10), expiresAt)) shouldBe false
        count("execution_capture_values") shouldBe 1
        count("execution_capture_runs") shouldBe 0
    }

    @Test
    fun `a value and a seal held before the expiry and released after it and after the purge insert nothing`() {
        sessions.start(session())
        val (c, decision) = admit()
        val expiresAt = decision.expiresAt!!
        storeClock.set(expiresAt.minusSeconds(1))
        // Before expiry: a write reaches storage and is kept.
        store.write(value(c.executionId, workspaceId, node = "early", recordedAt = expiresAt.minusSeconds(2))) shouldBe true
        withHeldConnection { held ->
            // Hold the reservation row: every diagnostic write now waits for it, produced well before the expiry.
            held.queryForList(
                "SELECT 1 FROM published_mutation_reservations WHERE execution_id = :id FOR UPDATE",
                mapOf("id" to c.executionId),
            )
            val produced = expiresAt.minusSeconds(1)
            val write =
                CompletableFuture.supplyAsync { store.write(value(c.executionId, workspaceId, node = "late", recordedAt = produced)) }
            val sealed = CompletableFuture.supplyAsync { store.seal(seal(c.executionId, decision.sessionId!!, produced, expiresAt)) }
            awaitLockWaits(2)
            // The expiry passes and the retention step purges, while both writes are still queued behind the hold.
            storeClock.set(expiresAt)
            store.purgeExpired(expiresAt, 10) shouldBe 1
            held.jdbcTemplate.execute("ROLLBACK")
            write.get(30, TimeUnit.SECONDS) shouldBe false
            sealed.get(30, TimeUnit.SECONDS) shouldBe false
        }
        // Nothing was inserted or recreated after the purge, and reads stay denied.
        count("execution_capture_values") shouldBe 0
        count("execution_capture_runs") shouldBe 0
        store.values(c.executionId, workspaceId, expiresAt).shouldBeEmpty()
        store.run(c.executionId, workspaceId, expiresAt).shouldBeNull()
    }

    @Test
    fun `a value and a seal held and released before the expiry are kept - the successful control`() {
        sessions.start(session())
        val (c, decision) = admit()
        val expiresAt = decision.expiresAt!!
        storeClock.set(expiresAt.minusSeconds(5))
        withHeldConnection { held ->
            held.queryForList(
                "SELECT 1 FROM published_mutation_reservations WHERE execution_id = :id FOR UPDATE",
                mapOf("id" to c.executionId),
            )
            val produced = expiresAt.minusSeconds(6)
            val write = CompletableFuture.supplyAsync { store.write(value(c.executionId, workspaceId, recordedAt = produced)) }
            val sealed = CompletableFuture.supplyAsync { store.seal(seal(c.executionId, decision.sessionId!!, produced, expiresAt)) }
            awaitLockWaits(2)
            storeClock.set(expiresAt.minusSeconds(1))
            store.purgeExpired(expiresAt.minusSeconds(1), 10) shouldBe 0
            held.jdbcTemplate.execute("ROLLBACK")
            write.get(30, TimeUnit.SECONDS) shouldBe true
            sealed.get(30, TimeUnit.SECONDS) shouldBe true
        }
        // The production timestamp is kept as metadata; the deadline is the reservation's.
        store.values(c.executionId, workspaceId, expiresAt.minusSeconds(1)).single().let {
            it.recordedAt shouldBe expiresAt.minusSeconds(6)
            it.expiresAt shouldBe expiresAt
        }
        store.run(c.executionId, workspaceId, expiresAt.minusSeconds(1)).shouldNotBeNull().sealedAt shouldBe expiresAt.minusSeconds(6)
    }

    @Test
    fun `reads are denied at expiry before cleanup, and the purge deletes physically in batches`() {
        sessions.start(session())
        val (c, decision) = admit()
        val expiresAt = decision.expiresAt!!
        listOf("a", "b", "c").forEach { node ->
            store.write(value(c.executionId, workspaceId, node = node, recordedAt = now.plusSeconds(2))) shouldBe
                true
        }
        store.write(
            CapturedValue(
                c.executionId,
                workspaceId,
                "d",
                "out",
                null,
                9_999,
                CapturedValue.OmittedReason.BYTE_BUDGET_EXHAUSTED,
                now.plusSeconds(2),
                expiresAt,
            ),
        ) shouldBe true
        store.seal(CaptureSeal(c.executionId, workspaceId, decision.sessionId!!, 3, 1, 0, 30, now.plusSeconds(3), expiresAt)) shouldBe true

        val justBefore = expiresAt.minus(1, ChronoUnit.MICROS)
        store.values(c.executionId, workspaceId, justBefore).map { it.nodeId } shouldBe listOf("a", "b", "c", "d")
        store.values(c.executionId, workspaceId, justBefore).last().let {
            it.valueJson.shouldBeNull()
            it.omittedReason shouldBe "byte_budget_exhausted"
        }
        store.run(c.executionId, workspaceId, justBefore).shouldNotBeNull().valuesOmitted shouldBe 1
        // At expiry: denied, though the rows are still there.
        store.values(c.executionId, workspaceId, expiresAt).shouldBeEmpty()
        store.run(c.executionId, workspaceId, expiresAt).shouldBeNull()
        count("execution_capture_values") shouldBe 4
        // Before expiry the purge deletes nothing; at expiry it drains in batches of two.
        store.purgeExpired(justBefore, 2) shouldBe 0
        store.purgeExpired(expiresAt, 2) shouldBe 3
        store.purgeExpired(expiresAt, 2) shouldBe 2
        store.purgeExpired(expiresAt, 2) shouldBe 0
        count("execution_capture_values") shouldBe 0
        count("execution_capture_runs") shouldBe 0
        // The reservation keeps its decision and expiry, so a later read still knows it expired.
        reservations.findLive(c.scopeDigest!!).shouldNotBeNull().captureExpiresAt shouldBe expiresAt
    }

    @Test
    fun `an admission INSERT blocked beyond the natural expiry is decided at the durable instant - not captured`() {
        val s = session()
        sessions.start(s)
        val clock = MutableClock(s.deadlineAt.minusSeconds(1))
        val c = candidate(createdAt = s.deadlineAt.minusSeconds(1))
        heldInsertAdmission(c, clock, advanceTo = s.deadlineAt).let {
            it.createdAt shouldBe s.deadlineAt
            it.captureSessionId.shouldBeNull()
        }
        stored(c).let {
            it.createdAt shouldBe s.deadlineAt
            it.expiresAt shouldBe s.deadlineAt.plus(replay)
            it.captureSessionId.shouldBeNull()
            it.captureExpiresAt.shouldBeNull()
        }
    }

    @Test
    fun `an admission INSERT blocked across the start is captured at the durable instant, both deadlines measured from it`() {
        val s = session(startedAt = now.plusSeconds(10))
        sessions.start(s)
        val clock = MutableClock(s.startedAt.minusSeconds(1))
        val c = candidate(createdAt = s.startedAt.minusSeconds(1))
        heldInsertAdmission(c, clock, advanceTo = s.startedAt).captureSessionId shouldBe s.id
        stored(c).let {
            it.createdAt shouldBe s.startedAt
            it.expiresAt shouldBe s.startedAt.plus(replay)
            it.captureSessionId shouldBe s.id
            it.captureExpiresAt shouldBe s.startedAt.plus(retention)
        }
    }

    @Test
    fun `an admission INSERT blocked and released before the natural expiry is captured - the successful control`() {
        val s = session()
        sessions.start(s)
        val clock = MutableClock(s.deadlineAt.minusSeconds(2))
        val c = candidate(createdAt = s.deadlineAt.minusSeconds(2))
        val releasedAt = s.deadlineAt.minus(1, ChronoUnit.MICROS)
        heldInsertAdmission(c, clock, advanceTo = releasedAt).captureSessionId shouldBe s.id
        stored(c).let {
            it.createdAt shouldBe releasedAt
            it.expiresAt shouldBe releasedAt.plus(replay)
            it.captureSessionId shouldBe s.id
            it.captureExpiresAt shouldBe releasedAt.plus(retention)
        }
    }

    @Test
    fun `two replicas blocked at the INSERT on one key - one winner stamped after the wait, every retry reads its decision`() {
        val s = session()
        sessions.start(s)
        val clock = MutableClock(s.deadlineAt.minusSeconds(2))
        val c = candidate(createdAt = s.deadlineAt.minusSeconds(2))
        // Replica B carries the same Idempotency-Key scope under its own execution id and claim.
        val replicaB = c.copy(executionId = UUID.randomUUID(), ownerToken = UUID.randomUUID())
        val releasedAt = s.deadlineAt.minusSeconds(1)
        val results =
            withHeldReservationTable { held ->
                val a = CompletableFuture.supplyAsync { reservations.reserveDeciding(c, null, AdmissionStamp(clock, replay, retention)) }
                val b =
                    CompletableFuture.supplyAsync {
                        reservations.reserveDeciding(replicaB, null, AdmissionStamp(clock, replay, retention))
                    }
                awaitLockWaits(2)
                a.isDone shouldBe false
                b.isDone shouldBe false
                clock.set(releasedAt)
                held.jdbcTemplate.execute("COMMIT")
                listOfNotNull(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS))
            }
        val winner = results.single()
        winner.createdAt shouldBe releasedAt
        winner.captureSessionId shouldBe s.id
        count("published_mutation_reservations") shouldBe 1
        // A retry from either replica after the window's natural expiry and a stop: no row, and the original decision.
        clock.set(s.deadlineAt.plusSeconds(5))
        sessions.stop(workspaceId, endpointId, s.id, userId, clock)
        for (retry in listOf(c, replicaB)) {
            reservations.reserveDeciding(retry, null, AdmissionStamp(clock, replay, retention)).shouldBeNull()
        }
        stored(c).let {
            it.executionId shouldBe winner.executionId
            it.ownerToken shouldBe winner.ownerToken
            it.createdAt shouldBe releasedAt
            it.expiresAt shouldBe releasedAt.plus(replay)
            it.captureSessionId shouldBe s.id
            it.captureExpiresAt shouldBe releasedAt.plus(retention)
        }
    }

    @Test
    fun `a value INSERT blocked after its reservation lock cannot recreate detail after expiry and purge`() {
        blockedDetailInsert(sealing = false)
    }

    @Test
    fun `a seal INSERT blocked after its reservation lock cannot recreate detail after expiry and purge`() {
        blockedDetailInsert(sealing = true)
    }

    @Test
    fun `a value and a seal INSERT blocked and released before the expiry are kept - the successful controls`() {
        blockedDetailInsert(sealing = false, expire = false)
        seed()
        blockedDetailInsert(sealing = true, expire = false)
    }

    @Test
    fun `a late-finishing run's seal blocked at the INSERT before the detail deadline is kept, then denied at it before cleanup`() {
        val s = session()
        sessions.start(s)
        val (c, decision) = admit()
        val expiresAt = decision.expiresAt.shouldNotBeNull()
        // The window ends while the run is still going; the run's details stay governed by its admission's deadline.
        sessions.stop(workspaceId, endpointId, s.id, userId, at(now.plusSeconds(5)))
        storeClock.set(expiresAt.minusSeconds(10))
        val accepted =
            withHeldDetailTables { held ->
                val sealed =
                    CompletableFuture.supplyAsync { store.seal(seal(c.executionId, s.id, expiresAt.minusSeconds(11), expiresAt)) }
                awaitLockWait()
                sealed.isDone shouldBe false
                storeClock.set(expiresAt.minusSeconds(1))
                held.jdbcTemplate.execute("COMMIT")
                sealed.get(30, TimeUnit.SECONDS)
            }
        accepted shouldBe true
        store.run(c.executionId, workspaceId, expiresAt.minus(1, ChronoUnit.MICROS)).shouldNotBeNull().sessionId shouldBe s.id
        // At the deadline the read is denied though the purge has not run yet; the purge then deletes it.
        store.run(c.executionId, workspaceId, expiresAt).shouldBeNull()
        count("execution_capture_runs") shouldBe 1
        store.purgeExpired(expiresAt, 10) shouldBe 1
        count("execution_capture_runs") shouldBe 0
    }

    /**
     * Writes an early value, then holds both detail tables in SHARE mode so the next value ([sealing] false) or seal
     * waits at its INSERT — after its reservation lock and first deadline check passed. With [expire] the clock reaches
     * the deadline and the holder purges the early value before releasing: the blocked write must report false and no
     * private row may remain. Without it, the release happens before the deadline and the write is kept.
     */
    private fun blockedDetailInsert(
        sealing: Boolean,
        expire: Boolean = true,
    ) {
        sessions.start(session())
        val (c, decision) = admit()
        val expiresAt = decision.expiresAt.shouldNotBeNull()
        storeClock.set(expiresAt.minusSeconds(1))
        store.write(value(c.executionId, workspaceId, node = "early", recordedAt = now)) shouldBe true
        val accepted =
            withHeldDetailTables { held ->
                val pending =
                    CompletableFuture.supplyAsync {
                        if (sealing) {
                            store.seal(seal(c.executionId, decision.sessionId.shouldNotBeNull(), now, expiresAt))
                        } else {
                            store.write(value(c.executionId, workspaceId, node = "late", recordedAt = now))
                        }
                    }
                awaitLockWait()
                pending.isDone shouldBe false
                if (expire) {
                    storeClock.set(expiresAt)
                    ExecutionCaptureStore.purgeOnly(held).purgeExpired(expiresAt, 10) shouldBe 1
                } else {
                    ExecutionCaptureStore.purgeOnly(held).purgeExpired(expiresAt.minusSeconds(1), 10) shouldBe 0
                }
                held.jdbcTemplate.execute("COMMIT")
                pending.get(30, TimeUnit.SECONDS)
            }
        accepted shouldBe !expire
        val remaining = count("execution_capture_values") + count("execution_capture_runs")
        remaining shouldBe if (expire) 0 else 2
        if (expire) {
            store.values(c.executionId, workspaceId, expiresAt.minus(1, ChronoUnit.MICROS)).shouldBeEmpty()
            store.run(c.executionId, workspaceId, expiresAt.minus(1, ChronoUnit.MICROS)).shouldBeNull()
        }
    }

    /**
     * Admits [c] while another transaction holds the reservation table in SHARE mode: the admission's INSERT waits
     * (after nothing else held it); meanwhile [clock] moves to [advanceTo]; the hold is then released.
     */
    private fun heldInsertAdmission(
        c: PublishedMutationReservation,
        clock: MutableClock,
        advanceTo: Instant,
    ): PublishedMutationReservation =
        withHeldReservationTable { held ->
            val admission =
                CompletableFuture.supplyAsync { reservations.reserveDeciding(c, null, AdmissionStamp(clock, replay, retention)) }
            awaitLockWait()
            admission.isDone shouldBe false
            clock.set(advanceTo)
            held.jdbcTemplate.execute("COMMIT")
            admission.get(30, TimeUnit.SECONDS)
        }.shouldNotBeNull()

    private fun <T> withHeldReservationTable(block: (NamedParameterJdbcTemplate) -> T): T =
        withHeldLock("LOCK TABLE published_mutation_reservations IN SHARE MODE", block)

    private fun <T> withHeldDetailTables(block: (NamedParameterJdbcTemplate) -> T): T =
        withHeldLock("LOCK TABLE execution_capture_values, execution_capture_runs IN SHARE MODE", block)

    private fun <T> withHeldLock(
        lock: String,
        block: (NamedParameterJdbcTemplate) -> T,
    ): T {
        var result: Result<T>? = null
        withHeldConnection { held ->
            held.jdbcTemplate.execute(lock)
            result = runCatching { block(held) }
        }
        return result.shouldNotBeNull().getOrThrow()
    }

    private fun value(
        executionId: UUID,
        ws: UUID,
        node: String = "shape",
        recordedAt: Instant,
        expiresAt: Instant = recordedAt.plusSeconds(1),
    ) = CapturedValue(executionId, ws, node, "summary", """{"k":"v"}""", 9, null, recordedAt, expiresAt)

    private fun stored(c: PublishedMutationReservation) = reservations.findLive(c.scopeDigest!!).shouldNotBeNull()

    /**
     * Admits [c] while another transaction holds window [s]'s row `FOR UPDATE` (a stop in progress): the admission waits
     * on the window lock; meanwhile [clock] moves to [advanceTo]; the hold is released without changing the window.
     */
    private fun heldWindowAdmission(
        s: CaptureSession,
        c: PublishedMutationReservation,
        clock: MutableClock,
        advanceTo: Instant,
    ): PublishedMutationReservation {
        var admitted: PublishedMutationReservation? = null
        withHeldConnection { held ->
            held.queryForList("SELECT id FROM execution_capture_sessions WHERE id = :id FOR UPDATE", mapOf("id" to s.id))
            val admission =
                CompletableFuture.supplyAsync { reservations.reserveDeciding(c, null, AdmissionStamp(clock, replay, retention)) }
            awaitLockWait()
            admission.isDone shouldBe false
            clock.set(advanceTo)
            held.jdbcTemplate.execute("ROLLBACK")
            admitted = admission.get(30, TimeUnit.SECONDS)
        }
        return admitted.shouldNotBeNull()
    }

    private fun seal(
        executionId: UUID,
        sessionId: UUID,
        sealedAt: Instant,
        expiresAt: Instant,
    ) = CaptureSeal(executionId, workspaceId, sessionId, 1, 0, 0, 9, sealedAt, expiresAt)

    private fun count(table: String): Int = jdbc.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java)!!

    /** Runs [block] with a template over ONE connection whose transaction stays open until the block commits it. */
    private fun withHeldConnection(block: (NamedParameterJdbcTemplate) -> Unit) {
        val connection = SharedPostgres.dataSource().connection
        connection.autoCommit = false
        val single = SingleConnectionDataSource(connection, true)
        try {
            block(NamedParameterJdbcTemplate(single))
        } finally {
            runCatching { connection.rollback() }
            connection.close()
        }
    }

    /** Polls, on its own connection, until some backend waits on a lock — the held side is now blocking the other. */
    private fun awaitLockWait() = awaitLockWaits(1)

    /** Polls, on its own connection, until at least [backends] backends wait on a lock. */
    private fun awaitLockWaits(backends: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            val waiting =
                jdbc.jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
                    Int::class.java,
                )!!
            if (waiting >= backends) return
            Thread.sleep(LOCK_POLL_MILLIS)
        }
        error("fewer than $backends backends began waiting on the held lock within 30 s")
    }

    /** A clock a test moves: what the code under test reads as "now" at the moment it reads it. */
    private class MutableClock(
        @Volatile private var instant: Instant,
    ) : Clock() {
        fun set(to: Instant) {
            instant = to
        }

        override fun instant(): Instant = instant

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }

    private companion object {
        const val LOCK_POLL_MILLIS = 20L
    }
}
