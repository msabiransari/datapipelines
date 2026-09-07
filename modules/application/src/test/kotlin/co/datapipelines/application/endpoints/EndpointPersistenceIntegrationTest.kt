package co.datapipelines.application.endpoints

import co.datapipelines.application.SharedPostgres
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * The three JDBC classes this module owns, against a real Postgres (083 §D).
 *
 * ## Why they are tested HERE and not only through `web`
 *
 * [PublishedEndpointRepository], [EndpointKeyBindingRepository] and [EndpointServeAudit] live in
 * `:modules:application` and were exercised only by the *web* module's integration suite, so this
 * module earned no coverage for its own code and sat at 84.9 % against an 84 floor — thin enough
 * that the next class added here would have tripped someone else's gate. The 074 handback named
 * both the cause and the fix; this is the fix.
 *
 * ## Why a real database and not a mocked template
 *
 * Every property below is a statement *about Postgres*: a transaction-scoped advisory lock, an
 * `ON CONFLICT DO NOTHING`, a `UNIQUE` violation translated into a catalog code, an `IN (:list)`
 * expansion, and a JSONB `->>` comparison. A mocked `NamedParameterJdbcTemplate` would assert
 * that this module passes strings to Spring, which nobody doubts, and would keep passing if
 * every one of those statements were wrong.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndpointPersistenceIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var transactions: TransactionTemplate
    private lateinit var endpoints: PublishedEndpointRepository
    private lateinit var bindings: EndpointKeyBindingRepository
    private lateinit var serveAudit: EndpointServeAudit

    private lateinit var workspaceId: UUID
    private lateinit var userId: UUID
    private lateinit var pipelineId: UUID
    private lateinit var keyId: String

    @BeforeAll
    fun connect() {
        val dataSource = SharedPostgres.dataSource()
        jdbc = NamedParameterJdbcTemplate(dataSource)
        // `insert` takes a TRANSACTION-scoped advisory lock, so it must be called inside one —
        // without a transaction the lock is released the instant the SELECT returns, which is
        // exactly when it is needed. The service layer's @Transactional supplies it in
        // production; this template is that supply here.
        transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
        endpoints = PublishedEndpointRepository(jdbc)
        bindings = EndpointKeyBindingRepository(jdbc)
        serveAudit = EndpointServeAudit(jdbc)
    }

    @BeforeEach
    fun seed() {
        // Clean what we touch (DEVELOPMENT.md §9.1). The CASCADE from `users` reaches
        // workspaces, pipelines, api_keys, published_endpoints, bindings and the audit rows.
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        jdbc.jdbcTemplate.execute("TRUNCATE audit_log CASCADE")

        userId = UUID.randomUUID()
        workspaceId = UUID.randomUUID()
        pipelineId = UUID.randomUUID()
        keyId = "dpk_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject)" +
                " VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to userId, "email" to "u$userId@example.com", "sub" to "sub-$userId"),
        )
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (:id, :name, 'W', :owner)",
            mapOf("id" to workspaceId, "name" to "w_${workspaceId.toString().replace("-", "")}", "owner" to userId),
        )
        jdbc.update(
            """
            INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id)
            VALUES (:id, :name, 'P', :owner, 1, :ws)
            """.trimIndent(),
            mapOf(
                "id" to pipelineId,
                "name" to "p_${pipelineId.toString().replace("-", "")}",
                "owner" to userId,
                "ws" to workspaceId,
            ),
        )
        jdbc.update(
            """
            INSERT INTO api_keys (id, user_id, name, key_hash, workspace_id, kind)
            VALUES (:id, :owner, 'endpoint key', 'x', :ws, 'endpoint')
            """.trimIndent(),
            mapOf("id" to keyId, "owner" to userId, "ws" to workspaceId),
        )
    }

    // ---------------------------------------------------------------- published_endpoints

    @Test
    fun `an endpoint round-trips through every read the registry and the screens use`() {
        publish("/lending/{borough}/home")

        val byPath = endpoints.findByPath("/lending/{borough}/home")
        byPath.shouldNotBeNull()
        byPath.pipelineId shouldBe pipelineId
        byPath.isEnabled shouldBe true
        // The parse travels with the row, so the matcher and the screens share ONE parse.
        byPath.pathVariables shouldContainExactly listOf("borough")

        endpoints.findAll().map { it.pathPattern } shouldContainExactly listOf("/lending/{borough}/home")
        endpoints.findAllEnabled().map { it.pathPattern } shouldContainExactly listOf("/lending/{borough}/home")
        endpoints.findByWorkspace(workspaceId).map { it.pathPattern } shouldContainExactly listOf("/lending/{borough}/home")
        endpoints.findByPipeline(pipelineId).map { it.pathPattern } shouldContainExactly listOf("/lending/{borough}/home")

        // The negative reads matter as much: a pipeline delete asks this question and must not
        // be blocked by an endpoint that does not exist.
        endpoints.findByPath("/lending/nothing").shouldBeNull()
        endpoints.findByPipeline(UUID.randomUUID()).shouldBeEmpty()
        endpoints.findByWorkspace(UUID.randomUUID()).shouldBeEmpty()
    }

    @Test
    fun `a disabled endpoint leaves findAllEnabled but stays in findAll`() {
        publish("/lending/home")

        endpoints.setEnabled("/lending/home", enabled = false) shouldBe true

        // §5.6: a disabled endpoint answers 404 exactly like an unknown path, so the registry
        // cache must not see it — while the tree screen still shows it, flagged.
        endpoints.findAllEnabled().shouldBeEmpty()
        endpoints.findAll().single().isEnabled shouldBe false
        // Updating a path nobody published changes nothing and says so.
        endpoints.setEnabled("/lending/absent", enabled = false) shouldBe false
    }

    @Test
    fun `deleteByPath reports whether a row actually went`() {
        publish("/lending/home")

        endpoints.deleteByPath("/lending/home") shouldBe true
        endpoints.deleteByPath("/lending/home") shouldBe false
        endpoints.findAll().shouldBeEmpty()
    }

    @Test
    fun `an overlapping pattern is refused with endpoint path_conflict naming the other path`() {
        publish("/lending/{borough}")

        // §4.1: "/lending/home" and "/lending/{borough}" could match the same URL. The database
        // cannot express that — UNIQUE(path_pattern) catches only exact duplicates — so the
        // refusal is a read-then-write under the advisory lock, which is why this assertion
        // needs a real transaction and a real Postgres.
        val refused = shouldThrow<DatapipelinesException> { publish("/lending/home") }
        refused.code shouldBe PipelineErrorCodes.Endpoint.PATH_CONFLICT
        refused.details["conflicting_path"] shouldBe "/lending/{borough}"
        refused.message.shouldNotBeNull() shouldContain "/lending/home"

        endpoints.findAll().map { it.pathPattern } shouldContainExactly listOf("/lending/{borough}")
    }

    @Test
    fun `an exact duplicate is refused with the same code, whichever line catches it`() {
        publish("/lending/home")

        // Reached through the overlap check with the lock held, and through the UNIQUE
        // constraint without it. A client must not be able to tell the two paths apart, so the
        // code is the same either way — this asserts the pair, not the route.
        val refused = shouldThrow<DatapipelinesException> { publish("/lending/home") }
        refused.code shouldBe PipelineErrorCodes.Endpoint.PATH_CONFLICT
    }

    // ------------------------------------------------------------- endpoint_key_bindings

    @Test
    fun `binding is idempotent and the whole ancestor chain comes back in one query`() {
        bindings.insert(binding("/lending")) shouldBe true
        // ON CONFLICT DO NOTHING: binding a key twice at one node is the same state, and a
        // promotion batch that re-pushes an unchanged binding must not fail on it.
        bindings.insert(binding("/lending")) shouldBe false
        bindings.insert(binding("/lending/home")) shouldBe true

        // §5.2's ancestor walk asks for the whole chain at once — one round trip, not one per
        // path segment, because "a query per level before the request is even authorised" is
        // the cheapest thing a hostile caller could ask the database to do.
        bindings.findByPrefixes(listOf("/lending/home", "/lending", "/")).map { it.pathPrefix } shouldContainExactlyInAnyOrder
            listOf("/lending", "/lending/home")
        bindings.findByPrefixes(emptyList()).shouldBeEmpty()
        bindings.findByPrefixes(listOf("/nothing")).shouldBeEmpty()

        bindings.findByKey(keyId).map { it.pathPrefix } shouldContainExactlyInAnyOrder listOf("/lending", "/lending/home")
        bindings.findByKey("dpk_absent").shouldBeEmpty()
        bindings.findAll() shouldHaveSize 2

        bindings.delete("/lending", keyId) shouldBe true
        bindings.delete("/lending", keyId) shouldBe false
        bindings.findByKey(keyId).map { it.pathPrefix } shouldContainExactly listOf("/lending/home")
    }

    @Test
    fun `revoking the key takes its bindings with it`() {
        bindings.insert(binding("/lending")) shouldBe true

        // ON DELETE CASCADE (V11): a key that no longer exists cannot authorise anything, and
        // leaving its bindings behind would make the tree screen show a binding to nothing.
        jdbc.update("DELETE FROM api_keys WHERE id = :id", mapOf("id" to keyId))

        bindings.findAll().shouldBeEmpty()
    }

    // -------------------------------------------------------------------- serve audit

    @Test
    fun `servedByKey answers only for the key that actually served the execution`() {
        val executionId = UUID.randomUUID()
        val otherKeyId = "dpk_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        jdbc.update(
            """
            INSERT INTO api_keys (id, user_id, name, key_hash, workspace_id, kind)
            VALUES (:id, :owner, 'other key', 'x', :ws, 'endpoint')
            """.trimIndent(),
            mapOf("id" to otherKeyId, "owner" to userId, "ws" to workspaceId),
        )
        auditServe(keyId, executionId)

        serveAudit.servedByKey(executionId, keyId) shouldBe true

        // The whole reason this question is asked of the AUDIT row and not the execution row:
        // `pipeline_executions.triggered_by` is the key's OWNER, so two endpoint keys owned by
        // one person are indistinguishable through it — a key bound at /lending could read the
        // results of a key bound at /payroll. These two keys share an owner on purpose.
        serveAudit.servedByKey(executionId, otherKeyId) shouldBe false
        serveAudit.servedByKey(UUID.randomUUID(), keyId) shouldBe false
    }

    @Test
    fun `an audit row for a different event is not a serve`() {
        val executionId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO audit_log (event, user_id, key_id, details_json)
            VALUES ('auth.login.success', :user, :key, CAST(:details AS jsonb))
            """.trimIndent(),
            mapOf("user" to userId, "key" to keyId, "details" to """{"execution_id":"$executionId"}"""),
        )

        // The event name is half the predicate. Without it any audited action mentioning an
        // execution id would authorise a cursor read.
        serveAudit.servedByKey(executionId, keyId) shouldBe false
    }

    // ------------------------------------------------------------------------- helpers

    /** Publishes [path] inside a transaction, as the service layer does. */
    private fun publish(path: String): PublishedEndpoint =
        transactions.execute {
            endpoints.insert(
                PublishedEndpoint.of(
                    id = UUID.randomUUID(),
                    workspaceId = workspaceId,
                    pathPattern = path,
                    pipelineId = pipelineId,
                    timeoutSeconds = 60,
                    description = "",
                    isEnabled = true,
                    createdBy = userId,
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
            )
        }!!

    private fun binding(prefix: String) =
        EndpointKeyBinding(
            pathPrefix = prefix,
            apiKeyId = keyId,
            workspaceId = workspaceId,
            createdBy = userId,
            createdAt = Instant.EPOCH,
        )

    private fun auditServe(
        key: String,
        executionId: UUID,
    ) = jdbc.update(
        """
        INSERT INTO audit_log (event, user_id, key_id, details_json)
        VALUES (:event, :user, :key, CAST(:details AS jsonb))
        """.trimIndent(),
        mapOf(
            "event" to EndpointServeAudit.SERVE_EVENT,
            "user" to userId,
            "key" to key,
            "details" to """{"execution_id":"$executionId"}""",
        ),
    )
}
