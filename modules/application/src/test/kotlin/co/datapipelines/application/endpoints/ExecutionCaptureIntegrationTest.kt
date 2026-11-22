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
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * #1043 — runtime capture's storage against a real PostgreSQL with the shipped migrations (V68): the one-open-window
 * index, the idempotent session-identified stop, the admission decision taken in the reserving statement (exact and
 * unfiltered correlation, the half-open deadline, route identity, workspace scope), the database's ordering of a stop
 * against an admission (each side held open in turn), and the private store's own refusals, expiry-bounded reads and
 * batched purge. Each is a property of SQL that a mocked template could never falsify.
 */
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

    @BeforeAll
    fun connect() {
        val ds = SharedPostgres.dataSource()
        jdbc = NamedParameterJdbcTemplate(ds)
        sessions = ExecutionCaptureSessionRepository(jdbc, TransactionTemplate(DataSourceTransactionManager(ds)))
        reservations = PublishedMutationReservationRepository(jdbc)
        store = ExecutionCaptureStore(jdbc)
    }

    @BeforeEach
    fun seed() {
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

    private fun admit(
        c: PublishedMutationReservation = candidate(),
        correlation: UUID? = null,
    ): Pair<PublishedMutationReservation, CaptureDecision> =
        c to reservations.reserveDeciding(c, correlation, c.createdAt.plus(retention)).shouldNotBeNull()

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
        val stopped = sessions.stop(workspaceId, endpointId, first.id, userId, now.plusSeconds(5))
        stopped.shouldBeInstanceOf<CaptureStop.Stopped>().session.let {
            it.endReason shouldBe CaptureSession.EndReason.STOPPED
            it.stoppedAt shouldBe now.plusSeconds(5)
            it.stoppedBy shouldBe userId
        }
        val second = session(startedAt = now.plusSeconds(10))
        sessions.start(second).shouldBeInstanceOf<CaptureStart.Opened>()
        // The delayed repeat of the first stop answers the first window's state and leaves the second open.
        sessions.stop(workspaceId, endpointId, first.id, userId, now.plusSeconds(20)).shouldBeInstanceOf<CaptureStop.AlreadyEnded>()
        sessions.open(workspaceId, endpointId, EndpointMethod.POST).shouldNotBeNull().id shouldBe second.id
        sessions.stop(otherWorkspaceId, endpointId, second.id, userId, now) shouldBe CaptureStop.NotFound
        sessions.stop(workspaceId, UUID.randomUUID(), second.id, userId, now) shouldBe CaptureStop.NotFound
        // A stop at or after the deadline records the expiry at the deadline.
        sessions
            .stop(
                workspaceId,
                endpointId,
                second.id,
                userId,
                second.deadlineAt.plusSeconds(60),
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
        sessions.stop(workspaceId, endpointId, unfiltered.id, userId, now.plusSeconds(2))

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
        sessions.stop(workspaceId, endpointId, s.id, userId, now.plusSeconds(2))
        admit(candidate(createdAt = now.plusSeconds(3))).second.captured shouldBe false
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
        sessions.stop(workspaceId, endpointId, s.id, userId, now.plusSeconds(2))
        // The retry under the same scope after the stop: no row, and the live row still carries the original decision.
        reservations.reserveDeciding(c.copy(executionId = UUID.randomUUID()), null, now.plus(retention)).shouldBeNull()
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
            val decision =
                PublishedMutationReservationRepository(held).reserveDeciding(c, null, c.createdAt.plus(retention)).shouldNotBeNull()
            decision.captured shouldBe true
            val stop = CompletableFuture.supplyAsync { sessions.stop(workspaceId, endpointId, s.id, userId, now.plusSeconds(2)) }
            awaitLockWait()
            stop.isDone shouldBe false
            held.jdbcTemplate.execute("COMMIT")
            stop.get(30, TimeUnit.SECONDS).shouldBeInstanceOf<CaptureStop.Stopped>()
        }
        reservations.findLive(c.scopeDigest!!).shouldNotBeNull().captureSessionId shouldBe s.id
    }

    @Test
    fun `the store refuses an uncaptured, foreign or expired write, and keeps the reservation's expiry`() {
        val (plain, _) = admit()
        store.write(value(plain.executionId, workspaceId, recordedAt = now.plusSeconds(2))) shouldBe false
        sessions.start(session())
        val (captured, decision) = admit()
        store.write(value(captured.executionId, otherWorkspaceId, recordedAt = now.plusSeconds(2))) shouldBe false
        store.write(value(captured.executionId, workspaceId, recordedAt = decision.expiresAt!!)) shouldBe false
        store.write(value(captured.executionId, workspaceId, recordedAt = now.plusSeconds(2), expiresAt = Instant.MAX)) shouldBe true
        store.write(value(captured.executionId, workspaceId, recordedAt = now.plusSeconds(3))) shouldBe false // same node/output
        store.values(captured.executionId, workspaceId, now.plusSeconds(4)).single().expiresAt shouldBe decision.expiresAt
        store.values(captured.executionId, otherWorkspaceId, now.plusSeconds(4)).shouldBeEmpty()
        count("execution_capture_values") shouldBe 1
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

    private fun value(
        executionId: UUID,
        ws: UUID,
        node: String = "shape",
        recordedAt: Instant,
        expiresAt: Instant = recordedAt.plusSeconds(1),
    ) = CapturedValue(executionId, ws, node, "summary", """{"k":"v"}""", 9, null, recordedAt, expiresAt)

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
    private fun awaitLockWait() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            val waiting =
                jdbc.jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
                    Int::class.java,
                )!!
            if (waiting > 0) return
            Thread.sleep(LOCK_POLL_MILLIS)
        }
        error("no backend began waiting on the held lock within 30 s")
    }

    private companion object {
        const val LOCK_POLL_MILLIS = 20L
    }
}
