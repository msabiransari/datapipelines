package co.datapipelines.application.endpoints

import co.datapipelines.application.SharedPostgres
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * #786 A44 and the one-draft invariant, against real PostgreSQL (record §5.3, §24).
 *
 * The API body hash is computed by the DATABASE ([PublishedEndpointVersionRepository.API_BODY_HASH_EXPR]) over exactly
 * four inputs: the pipeline name, the exact pipeline version, the timeout and the description. Two deployments that hold
 * the same body must agree on its hash (promotion compares it), so everything deployment-local is NOT an input — this
 * suite enumerates those non-inputs one by one and pins the value against a golden computed here, independently of
 * PostgreSQL, so feeding any other column into the expression goes red even if both sides drift together.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndpointVersionBodyHashIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var transactions: TransactionTemplate
    private lateinit var endpoints: PublishedEndpointRepository
    private lateinit var versions: PublishedEndpointVersionRepository
    private lateinit var userId: UUID

    @BeforeAll
    fun connect() {
        val dataSource = SharedPostgres.dataSource()
        jdbc = NamedParameterJdbcTemplate(dataSource)
        transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
        endpoints = PublishedEndpointRepository(jdbc)
        versions = PublishedEndpointVersionRepository(jdbc)
    }

    @BeforeEach
    fun seed() {
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        jdbc.jdbcTemplate.execute("TRUNCATE audit_log CASCADE")
        userId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to userId, "email" to "u$userId@example.com", "sub" to "sub-$userId"),
        )
    }

    /**
     * The deployment-local facts an API carries, each deliberately DIFFERENT between the two deployments below: none of
     * them is a body-hash input (A44). The test asserts the hash is equal despite every one of them differing.
     */
    private data class Deployment(
        val path: String,
        val enabled: Boolean,
        val boundKeyPrefix: String?,
    )

    @Test
    fun `equal bodies hash equally across deployments that differ in every non-input`() {
        val body = EndpointVersionBody("finance/revenue", 2, 30, "Revenue by region.")
        val a = deploy(Deployment("/finance/v1/revenue", enabled = true, boundKeyPrefix = "/finance"), body)
        val b = deploy(Deployment("/reports/v2/rev", enabled = false, boundKeyPrefix = null), body)
        withClue("the non-inputs really differ: entity UUID, workspace, pipeline UUID, path, enabled, bindings") {
            a.endpointId shouldNotBe b.endpointId
            a.workspaceId shouldNotBe b.workspaceId
            a.pipelineId shouldNotBe b.pipelineId
            bindingCount(a.workspaceId) shouldBe 1
            bindingCount(b.workspaceId) shouldBe 0
        }
        val hashA = storedHash(a.endpointId, 1)
        hashA shouldBe storedHash(b.endpointId, 1)
        withClue("the golden is computed outside PostgreSQL from the four inputs alone (jsonb's canonical key order)") {
            hashA shouldBe golden(body)
        }
    }

    @Test
    fun `a pin-only change and a description-only or timeout-only change each change the hash`() {
        val base = EndpointVersionBody("finance/revenue", 2, 30, "d")
        val variants =
            listOf(
                base.copy(pipelineVersion = 3),
                base.copy(pipelineName = "finance/revenue_eu"),
                base.copy(timeoutSeconds = 31),
                base.copy(description = "e"),
            )
        val hashes = (listOf(base) + variants).map { sharedHash(it) }
        withClue("every one of the four inputs moves the hash; no two bodies collide") {
            hashes.toSet().size shouldBe hashes.size
        }
        hashes.zip(listOf(base) + variants).forEach { (hash, body) -> hash shouldBe golden(body) }
    }

    @Test
    fun `a draft edit recomputes the hash at the same number and a stale hash is a CAS miss`() {
        val body = EndpointVersionBody("finance/revenue", 1, 30, "")
        val d = deploy(Deployment("/finance/v1/cas", enabled = true, boundKeyPrefix = null), body)
        val draft = transactions.execute { versions.insertDraft(d.endpointId, body.copy(pipelineVersion = 2), userId) }.shouldNotBeNull()
        withClue("a new draft takes max(existing) + 1") { draft.version shouldBe 2 }
        draft.bodyHash shouldBe golden(body.copy(pipelineVersion = 2))
        val edited = versions.updateDraft(d.endpointId, 2, body.copy(pipelineVersion = 3), draft.bodyHash, userId).shouldNotBeNull()
        edited.version shouldBe 2
        edited.bodyHash shouldBe golden(body.copy(pipelineVersion = 3))
        versions.updateDraft(d.endpointId, 2, body.copy(pipelineVersion = 4), draft.bodyHash, userId).shouldBeNull()
        withClue("a RELEASED version is never edited by the draft CAS, even with its own hash") {
            versions.updateDraft(d.endpointId, 1, body.copy(timeoutSeconds = 9), storedHash(d.endpointId, 1), userId).shouldBeNull()
        }
    }

    @Test
    fun `an API holds at most one working draft - the second is endpoint version draft_exists`() {
        val body = EndpointVersionBody("finance/revenue", 1, 30, "")
        val d = deploy(Deployment("/finance/v1/one", enabled = true, boundKeyPrefix = null), body)
        transactions.execute { versions.insertDraft(d.endpointId, body.copy(pipelineVersion = 2), userId) }.shouldNotBeNull()
        val refused =
            shouldThrow<DatapipelinesException> {
                transactions.execute { versions.insertDraft(d.endpointId, body.copy(pipelineVersion = 3), userId) }
            }
        refused.code shouldBe PipelineErrorCodes.Endpoint.VERSION_DRAFT_EXISTS
        withClue("the refused draft left no row") { versions.list(d.endpointId).size shouldBe 2 }
        withClue("a draft on ANOTHER API is not blocked by this one's") {
            val other = deploy(Deployment("/finance/v1/other", enabled = true, boundKeyPrefix = null), body)
            transactions.execute { versions.insertDraft(other.endpointId, body.copy(pipelineVersion = 2), userId) }.shouldNotBeNull()
        }
    }

    // ------------------------------------------------------------------------- helpers

    private data class Deployed(
        val endpointId: UUID,
        val workspaceId: UUID,
        val pipelineId: UUID,
    )

    /** A fresh workspace with its own pipeline UUID holding [body]'s pipeline name, and an API at [d] whose v1 is [body]. */
    private fun deploy(
        d: Deployment,
        body: EndpointVersionBody,
    ): Deployed {
        val workspaceId = UUID.randomUUID()
        val pipelineId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (:id, :name, 'W', :owner)",
            mapOf("id" to workspaceId, "name" to "w_${workspaceId.toString().replace("-", "")}", "owner" to userId),
        )
        jdbc.update(
            "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id) " +
                "VALUES (:id, :name, 'P', :owner, 1, :ws)",
            mapOf("id" to pipelineId, "name" to body.pipelineName, "owner" to userId, "ws" to workspaceId),
        )
        val endpoint =
            transactions
                .execute {
                    endpoints
                        .insert(
                            PublishedEndpoint.of(UUID.randomUUID(), workspaceId, d.path, d.enabled, userId, Instant.EPOCH, Instant.EPOCH),
                            LegacyEndpointColumns(pipelineId, body.timeoutSeconds, body.description),
                        ).also {
                            versions.insert(it.id, 1, PipelineVersionStatus.RELEASED, body, userId)
                            versions.setCurrent(it.id, 1)
                        }
                }.shouldNotBeNull()
        d.boundKeyPrefix?.let { prefix ->
            val keyId = "k_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            jdbc.update(
                """
                INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)
                VALUES (:id, :owner, :owner, 'endpoint key', 'x', :ws, 'endpoint', 'api_caller')
                """.trimIndent(),
                mapOf("id" to keyId, "owner" to userId, "ws" to workspaceId),
            )
            jdbc.update(
                "INSERT INTO endpoint_key_bindings (path_prefix, api_key_id, workspace_id, created_by) VALUES (:prefix, :key, :ws, :owner)",
                mapOf("key" to keyId, "ws" to workspaceId, "prefix" to prefix, "owner" to userId),
            )
        }
        return Deployed(endpoint.id, workspaceId, pipelineId)
    }

    private fun bindingCount(workspaceId: UUID): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM endpoint_key_bindings WHERE workspace_id = :ws",
            mapOf("ws" to workspaceId),
            Int::class.java,
        )
            ?: 0

    private fun storedHash(
        endpointId: UUID,
        version: Int,
    ): String = versions.find(endpointId, version).shouldNotBeNull().bodyHash

    private fun sharedHash(body: EndpointVersionBody): String =
        jdbc
            .queryForObject(
                "SELECT ${PublishedEndpointVersionRepository.API_BODY_HASH_EXPR}",
                PublishedEndpointVersionRepository.bodyParams(body),
                String::class.java,
            ).shouldNotBeNull()

    /**
     * The A44 golden, computed in Kotlin: SHA-256 over jsonb's canonical text of the four inputs. jsonb orders keys by
     * length, then bytes — pipeline (8), description (11), timeout_seconds (15), pipeline_version (16) — and prints
     * `"key": value` pairs joined by `", "`. The test bodies hold no character jsonb would escape.
     */
    private fun golden(body: EndpointVersionBody): String {
        val text =
            """{"pipeline": "${body.pipelineName}", "description": "${body.description}", """ +
                """"timeout_seconds": ${body.timeoutSeconds}, "pipeline_version": ${body.pipelineVersion}}"""
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
