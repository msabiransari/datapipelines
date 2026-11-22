package co.datapipelines.application.endpoints

import co.datapipelines.application.SharedPostgres
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * #1039 — the durable reservation of a published mutation (V67; published-mutation-apis §3, §6), against a real
 * PostgreSQL with the shipped migrations. Every property is a statement about the database — a unique scope under
 * `ON CONFLICT DO NOTHING`, compare-and-set updates, a `NOT EXISTS` fence on the execution row, and a CHECK that keeps
 * a retired row free of digests — which a mocked template could never falsify.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublishedMutationReservationIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var reservations: PublishedMutationReservationRepository

    private lateinit var workspaceId: UUID
    private lateinit var otherWorkspaceId: UUID
    private lateinit var userId: UUID
    private lateinit var pipelineId: UUID
    private lateinit var keyId: String
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
        reservations = PublishedMutationReservationRepository(jdbc)
    }

    @BeforeEach
    fun seed() {
        // The CASCADE from `users` reaches workspaces, api_keys, pipelines and (through them) the reservations.
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        userId = UUID.randomUUID()
        workspaceId = UUID.randomUUID()
        otherWorkspaceId = UUID.randomUUID()
        pipelineId = UUID.randomUUID()
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
        // The released version an execution row's foreign key names.
        jdbc.update(
            "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at) " +
                "VALUES (:id, 1, CAST('{}' AS jsonb), 'seed', 'RELEASED', :owner, :owner, NOW())",
            mapOf("id" to pipelineId, "owner" to userId),
        )
        jdbc.update(
            "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role) " +
                "VALUES (:id, :owner, :owner, 'endpoint key', 'x', :ws, 'endpoint', 'api_caller')",
            mapOf("id" to keyId, "owner" to userId, "ws" to workspaceId),
        )
    }

    @Test
    fun `a reservation round-trips, and a second one under the same scope is refused without an error`() {
        val first = candidate(scope = "scope-a")
        reservations.reserve(first) shouldBe true

        reservations.findLive("scope-a") shouldBe first
        reservations.reserve(candidate(scope = "scope-a")) shouldBe false
        reservations.findLive("scope-a")?.executionId shouldBe first.executionId
        reservations.findLive("scope-b").shouldBeNull()
    }

    @Test
    fun `an origin is found only in its own workspace`() {
        val row = candidate(scope = "scope-a")
        reservations.reserve(row)

        reservations.findOrigin(row.executionId, workspaceId).shouldNotBeNull()
        reservations.findOrigin(row.executionId, otherWorkspaceId).shouldBeNull()
    }

    @Test
    fun `retire frees the scope for a new request, keeps the origin, and wins once`() {
        val old = candidate(scope = "scope-a")
        reservations.reserve(old)

        reservations.retire(old.executionId, "scope-a", now) shouldBe true
        reservations.retire(old.executionId, "scope-a", now) shouldBe false

        reservations.findLive("scope-a").shouldBeNull()
        val origin = reservations.findOrigin(old.executionId, workspaceId).shouldNotBeNull()
        origin.retiredAt shouldBe now
        origin.scopeDigest.shouldBeNull()
        origin.requestFingerprint.shouldBeNull()
        origin.businessPath shouldBe old.businessPath

        val fresh = candidate(scope = "scope-a")
        reservations.reserve(fresh) shouldBe true
        reservations.findLive("scope-a")?.executionId shouldBe fresh.executionId
    }

    @Test
    fun `a takeover wins exactly once and never while an execution row exists`() {
        val stranded = candidate(scope = "scope-a")
        reservations.reserve(stranded)
        val mine = UUID.randomUUID()

        reservations.takeOver(stranded.executionId, stranded.ownerToken, mine) shouldBe true
        // A second contender still holding the old token loses.
        reservations.takeOver(stranded.executionId, stranded.ownerToken, UUID.randomUUID()) shouldBe false
        reservations.findLive("scope-a")?.ownerToken shouldBe mine

        val started = candidate(scope = "scope-b")
        reservations.reserve(started)
        recordExecution(started.executionId)
        reservations.takeOver(started.executionId, started.ownerToken, UUID.randomUUID()) shouldBe false
    }

    @Test
    fun `release deletes only an unstarted reservation that is still the caller's claim`() {
        val refused = candidate(scope = "scope-a")
        reservations.reserve(refused)
        reservations.releaseUnstarted(refused.executionId, UUID.randomUUID()) shouldBe false
        reservations.releaseUnstarted(refused.executionId, refused.ownerToken) shouldBe true
        reservations.findLive("scope-a").shouldBeNull()

        val admitted = candidate(scope = "scope-b")
        reservations.reserve(admitted)
        recordExecution(admitted.executionId)
        reservations.releaseUnstarted(admitted.executionId, admitted.ownerToken) shouldBe false
        reservations.findLive("scope-b").shouldNotBeNull()
    }

    @Test
    fun `the schema refuses a live row without digests and an unknown method`() {
        val row = candidate(scope = "scope-a")
        reservations.reserve(row)
        try {
            jdbc.update(
                "UPDATE published_mutation_reservations SET request_fingerprint = NULL WHERE execution_id = :id",
                mapOf("id" to row.executionId),
            )
            error("a live row without a fingerprint was accepted")
        } catch (expected: DataIntegrityViolationException) {
            expected.message.orEmpty().contains("chk_published_mutation_reservations_live") shouldBe true
        }
        try {
            jdbc.update(
                "UPDATE published_mutation_reservations SET method = 'GET' WHERE execution_id = :id",
                mapOf("id" to row.executionId),
            )
            error("a GET reservation was accepted")
        } catch (expected: DataIntegrityViolationException) {
            expected.message.orEmpty().contains("chk_published_mutation_reservations_method") shouldBe true
        }
    }

    @Test
    fun `deleting the key removes its reservations`() {
        val row = candidate(scope = "scope-a")
        reservations.reserve(row)
        jdbc.update("DELETE FROM api_keys WHERE id = :id", mapOf("id" to keyId))
        reservations.findOrigin(row.executionId, workspaceId).shouldBeNull()
    }

    private fun candidate(scope: String): PublishedMutationReservation =
        PublishedMutationReservation(
            executionId = UUID.randomUUID(),
            workspaceId = workspaceId,
            apiKeyId = keyId,
            endpointId = UUID.randomUUID(),
            apiVersion = 3,
            pipelineId = pipelineId,
            pipelineVersion = 2,
            hasCaller = true,
            method = EndpointMethod.PUT,
            businessPath = "/x/v1/orders/42",
            scopeDigest = scope,
            requestFingerprint = "fp-$scope",
            bodyBytes = 17,
            ownerToken = UUID.randomUUID(),
            createdAt = now,
            expiresAt = now.plusSeconds(86_400),
        )

    /** The run's own row under the reservation's id — what the start fence tests for. */
    private fun recordExecution(executionId: UUID) {
        jdbc.update(
            "INSERT INTO pipeline_executions (execution_id, pipeline_id, pipeline_version, status, executed_by, triggered_via, " +
                "root_execution_id, started_at) VALUES (:id, :pipelineId, 1, 'RUNNING', :actor, 'ENDPOINT', :id, NOW())",
            mapOf("id" to executionId, "pipelineId" to pipelineId, "actor" to userId),
        )
    }
}
