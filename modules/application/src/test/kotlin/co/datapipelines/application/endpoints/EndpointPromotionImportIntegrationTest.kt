package co.datapipelines.application.endpoints

import co.datapipelines.application.SharedPostgres
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * #786 §F.2 — the receiver-only import of a promoted API, against real PostgreSQL. A promotion receiver is
 * authoring-DISABLED, so the native creation refuses there ([EndpointPublishService.create] →
 * `endpoint.authoring_disabled`); [EndpointPromotionImport] is the one path that still writes the API, in the
 * RECEIVER's transaction: the entity, its v1 pinned to the TARGET pipeline's CURRENT (the import takes no version and no
 * hash — none travels on the wire, #879 owns the exact-pin promotion), `current_version = 1` and the shared hash; v1's
 * status follows E.1's rule. No CURRENT refuses with the receiver's existing `endpoint.pipeline_not_released` and writes
 * nothing; a failure later in the receiver's transaction rolls the entity and v1 back with it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndpointPromotionImportIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var receiverTransaction: TransactionTemplate
    private lateinit var endpoints: PublishedEndpointRepository
    private lateinit var versions: PublishedEndpointVersionRepository
    private lateinit var pipelineRepository: PipelineRepository
    private val pipelines = mockk<PipelineService>()
    private var userId: UUID = UUID.randomUUID()
    private var workspaceId: UUID = UUID.randomUUID()

    /** The receiver's deployment: authoring is OFF, by construction of a promotion receiver. */
    private val authoring = AuthoringGuard(false)

    @BeforeAll
    fun wire() {
        val dataSource = SharedPostgres.dataSource()
        jdbc = NamedParameterJdbcTemplate(dataSource)
        receiverTransaction = TransactionTemplate(DataSourceTransactionManager(dataSource))
        endpoints = PublishedEndpointRepository(jdbc)
        versions = PublishedEndpointVersionRepository(jdbc)
        pipelineRepository = PipelineRepository(jdbc)
    }

    @BeforeEach
    fun seed() {
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        userId = UUID.randomUUID()
        workspaceId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to userId, "email" to "u$userId@example.com", "sub" to "sub-$userId"),
        )
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (:id, :name, 'W', :owner)",
            mapOf("id" to workspaceId, "name" to "w_${workspaceId.toString().replace("-", "")}", "owner" to userId),
        )
        every { pipelines.findExecutable(any(), any(), any(), any()) } answers {
            val record = thirdArg<co.datapipelines.pipeline.PipelineRecord>()
            PipelineService.ExecutablePipeline(record, arg(3), "{}", readOnlyBody(record.name))
        }
    }

    @Test
    fun `on an authoring-disabled target the native create refuses while the import writes the API and its v1`() {
        pipeline(
            "acme/rides/daily",
            current = 3,
            PipelineVersionStatus.RELEASED,
            PipelineVersionStatus.RELEASED,
            PipelineVersionStatus.RELEASED,
        )
        withClue("the native creation path (REST and MCP share it) is an authoring operation: refused here") {
            shouldThrow<DatapipelinesException> {
                publishing().create(principal(), "/acme/v1/rides", "acme/rides/daily", 3, 45, "Daily rides")
            }.code shouldBe PipelineErrorCodes.Endpoint.AUTHORING_DISABLED
            entityCount() shouldBe 0
        }

        val imported =
            receiverTransaction
                .execute {
                    importer().importPromoted(workspaceId, userId, "/acme/v1/rides", "acme/rides/daily", 45, "Daily rides")
                }.shouldNotBeNull()

        val v1 = versions.find(imported.id, 1).shouldNotBeNull()
        withClue("the pin is the TARGET pipeline's CURRENT at receive time; nothing on the wire chose it") {
            v1.body shouldBe EndpointVersionBody("acme/rides/daily", 3, 45, "Daily rides")
        }
        v1.status shouldBe PipelineVersionStatus.RELEASED
        v1.bodyHash shouldBe sharedHash(v1.body)
        pointer(imported.id) shouldBe 1
        withClue("E.2: no path leaves a version-less entity behind") { versionLess() shouldBe 0 }
    }

    @Test
    fun `a DRAFT current imports as v1 DRAFT - the API serves at the target iff it would have before`() {
        pipeline("acme/rides/drafty", current = 2, PipelineVersionStatus.RELEASED, PipelineVersionStatus.DRAFT)
        val imported =
            receiverTransaction
                .execute {
                    importer().importPromoted(workspaceId, userId, "/acme/v1/drafty", "acme/rides/drafty", null, "")
                }.shouldNotBeNull()
        val v1 = versions.find(imported.id, 1).shouldNotBeNull()
        v1.status shouldBe PipelineVersionStatus.DRAFT
        v1.body.pipelineVersion shouldBe 2
        pointer(imported.id) shouldBe 1
    }

    @Test
    fun `a target pipeline with no CURRENT refuses with the receiver's existing shape and writes nothing`() {
        pipeline("acme/rides/none", current = null, PipelineVersionStatus.DRAFT)
        val refused =
            shouldThrow<DatapipelinesException> {
                receiverTransaction.execute {
                    importer().importPromoted(workspaceId, userId, "/acme/v1/none", "acme/rides/none", 30, "")
                }
            }
        refused.code shouldBe PipelineErrorCodes.Endpoint.PIPELINE_NOT_RELEASED
        entityCount() shouldBe 0
        versionCount() shouldBe 0
    }

    @Test
    fun `a failure later in the receiver's transaction rolls the entity and v1 back`() {
        pipeline("acme/rides/rollback", current = 1, PipelineVersionStatus.RELEASED)
        val injected =
            shouldThrow<IllegalStateException> {
                receiverTransaction.execute {
                    importer().importPromoted(workspaceId, userId, "/acme/v1/rollback", "acme/rides/rollback", 30, "")
                    withClue("non-vacuity: inside the transaction the rows exist") {
                        entityCount() shouldBe 1
                        versionCount() shouldBe 1
                    }
                    error("injected: the next entry of the promoted batch failed")
                }
            }
        injected.message shouldBe "injected: the next entry of the promoted batch failed"
        entityCount() shouldBe 0
        versionCount() shouldBe 0
    }

    // ------------------------------------------------------------------------- helpers

    private fun pins() =
        EndpointPinValidator(
            pipelineRepository = pipelineRepository,
            pipelines = pipelines,
            readOnlyRule = ReadOnlyPipelineRule(PipelineResolver { _, _, _ -> null }, MAX_DEPTH),
            authoring = authoring,
        )

    private fun importer() =
        EndpointPromotionImport(
            endpoints = endpoints,
            versions = versions,
            pipelines = pipelineRepository,
            pins = pins(),
            registry = EndpointRegistry(endpoints),
            timeouts = TIMEOUTS,
        )

    private fun publishing() =
        EndpointPublishService(
            endpoints = endpoints,
            versions = versions,
            pins = pins(),
            registry = EndpointRegistry(endpoints),
            audit = mockk<AuditEventSink>(relaxed = true),
            timeouts = TIMEOUTS,
            authoring = authoring,
        )

    private fun pipeline(
        name: String,
        current: Int?,
        vararg statuses: PipelineVersionStatus,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id)" +
                " VALUES (:id, :name, 'P', :owner, :current, :ws)",
            mapOf("id" to id, "name" to name, "owner" to userId, "current" to current, "ws" to workspaceId),
        )
        statuses.forEachIndexed { index, status ->
            jdbc.update(
                "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at)" +
                    " VALUES (:id, :version, CAST('{}' AS jsonb), :hash, :status, :owner," +
                    " CASE WHEN :status <> 'DRAFT' THEN CAST(:owner AS UUID) END, CASE WHEN :status <> 'DRAFT' THEN NOW() END)",
                mapOf("id" to id, "version" to index + 1, "hash" to "seed-$index", "status" to status.name, "owner" to userId),
            )
        }
        return id
    }

    private fun readOnlyBody(name: String) =
        Pipeline(
            schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
            name = name,
            displayName = "P",
            description = "",
            settings = PipelineSettings(),
            parameters = emptyMap(),
            nodes =
                listOf(
                    Node(
                        id = "rides",
                        description = "",
                        type = NodeType.DQL,
                        source = "pg",
                        template = TemplateRef("test/rides.sql", 1),
                        output = NodeOutput.Caller,
                        dependsOn = emptyList(),
                    ),
                ),
        )

    private fun principal() =
        AuthenticatedPrincipal(
            userId = userId,
            email = "a@b.c",
            displayName = "A",
            authMethod = AuthMethod.OIDC,
            workspaceName = "w",
            workspace = WorkspaceContext(workspaceId, "w"),
        )

    private fun sharedHash(body: EndpointVersionBody): String =
        jdbc
            .queryForObject(
                "SELECT ${PublishedEndpointVersionRepository.API_BODY_HASH_EXPR}",
                PublishedEndpointVersionRepository.bodyParams(body),
                String::class.java,
            ).shouldNotBeNull()

    private fun pointer(id: UUID): Int? =
        jdbc.queryForObject("SELECT current_version FROM published_endpoints WHERE id = :id", mapOf("id" to id), Int::class.javaObjectType)

    private fun count(sql: String): Int = jdbc.queryForObject(sql, mapOf("ws" to workspaceId), Int::class.java).shouldNotBeNull()

    private fun entityCount() = count("SELECT COUNT(*) FROM published_endpoints WHERE workspace_id = :ws")

    private fun versionCount() =
        count(
            "SELECT COUNT(*) FROM published_endpoint_versions v JOIN published_endpoints e ON e.id = v.endpoint_id " +
                "WHERE e.workspace_id = :ws",
        )

    private fun versionLess() =
        count(
            "SELECT COUNT(*) FROM published_endpoints e WHERE e.workspace_id = :ws" +
                " AND NOT EXISTS (SELECT 1 FROM published_endpoint_versions v WHERE v.endpoint_id = e.id)",
        )

    private companion object {
        const val MAX_DEPTH = 5
        val TIMEOUTS = EndpointPublishService.TimeoutBounds(defaultSeconds = 30, minSeconds = 1, maxSeconds = 300)
    }
}
