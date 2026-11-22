package co.datapipelines.application.endpoints

import co.datapipelines.application.SharedPostgres
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
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
 * #1040 (published-mutation-apis §7) — the receiver's EXACT-pin import of a method-bearing route, against real
 * PostgreSQL. The pin is the SENDER's (never this deployment's current), the version must be here, RELEASED and carry
 * the sender's content hash; the route's version numbers are this deployment's own. Anything that cannot be honoured
 * exactly refuses and writes nothing, and the receiver's one transaction rolls every write back on a later refusal.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndpointPromotionImportExactIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var receiverTransaction: TransactionTemplate
    private lateinit var endpoints: PublishedEndpointRepository
    private lateinit var versions: PublishedEndpointVersionRepository
    private lateinit var pipelineRepository: PipelineRepository
    private val pipelines = mockk<PipelineService>()
    private var userId: UUID = UUID.randomUUID()
    private var workspaceId: UUID = UUID.randomUUID()

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
            val record = thirdArg<PipelineRecord>()
            PipelineService.ExecutablePipeline(record, arg(3), "{}", body(record.name))
        }
        // v1 RELEASED, v2 RELEASED, v3 RELEASED (current), v4 DRAFT — hashes seed-0 … seed-3.
        pipeline(
            current = 3,
            PipelineVersionStatus.RELEASED,
            PipelineVersionStatus.RELEASED,
            PipelineVersionStatus.RELEASED,
            PipelineVersionStatus.DRAFT,
        )
    }

    @Test
    fun `an absent POST route is created on the SENDER's pin - not this deployment's current - beside its GET sibling`() {
        val get = receiverTransaction.execute { importer().importPromoted(workspaceId, userId, PATH, PIPELINE, 30, "") }.shouldNotBeNull()

        val outcome = receiverTransaction.execute { importer().importExact(workspaceId, userId, PATH, EndpointMethod.POST, pin(2)) }

        outcome shouldBe EndpointPromotionImport.ExactOutcome.CREATED
        val post = endpoints.findByPath(PATH, EndpointMethod.POST).shouldNotBeNull()
        val v1 = versions.find(post.id, 1).shouldNotBeNull()
        withClue("pinned exactly to v2 although this deployment's current is v3") { v1.body.pipelineVersion shouldBe 2 }
        v1.status shouldBe PipelineVersionStatus.RELEASED
        pointer(post.id) shouldBe 1
        withClue("the GET sibling at the same path is its own route, untouched") {
            versions
                .find(get.id, 1)
                .shouldNotBeNull()
                .body.pipelineVersion shouldBe 3
            versionCount() shouldBe 2
        }
    }

    @Test
    fun `the same exact pin again is UNCHANGED, and a moved pin appends a new RELEASED current version`() {
        receiverTransaction.execute { importer().importExact(workspaceId, userId, PATH, EndpointMethod.PUT, pin(2)) }

        receiverTransaction.execute { importer().importExact(workspaceId, userId, PATH, EndpointMethod.PUT, pin(2)) } shouldBe
            EndpointPromotionImport.ExactOutcome.UNCHANGED
        versionCount() shouldBe 1

        receiverTransaction.execute { importer().importExact(workspaceId, userId, PATH, EndpointMethod.PUT, pin(3)) } shouldBe
            EndpointPromotionImport.ExactOutcome.NEW_VERSION
        val put = endpoints.findByPath(PATH, EndpointMethod.PUT).shouldNotBeNull()
        pointer(put.id) shouldBe 2
        versions.find(put.id, 2).shouldNotBeNull().let {
            it.body.pipelineVersion shouldBe 3
            it.status shouldBe PipelineVersionStatus.RELEASED
        }
        withClue("the earlier version stays as history") {
            versions
                .find(put.id, 1)
                .shouldNotBeNull()
                .body.pipelineVersion shouldBe 2
        }
    }

    @Test
    fun `a pin that cannot be honoured exactly refuses and writes nothing`() {
        val cases =
            listOf(
                "same number, other content" to pin(2).copy(pipelineBodyHash = "sender-other") to
                    PipelineErrorCodes.Import.VERSION_CONFLICT,
                "the version is absent here" to pin(9).copy(pipelineBodyHash = "seed-8") to
                    PipelineErrorCodes.Endpoint.PIPELINE_VERSION_NOT_FOUND,
                "the version is a DRAFT here" to pin(4) to PipelineErrorCodes.Endpoint.PIPELINE_VERSION_NOT_ELIGIBLE,
                "the pipeline is absent here" to pin(2).copy(pipelineName = "acme/rides/absent") to PipelineErrorCodes.Execution.NOT_FOUND,
            )
        cases.forEach { (labelled, code) ->
            val (label, pin) = labelled
            withClue(label) {
                shouldThrow<DatapipelinesException> {
                    receiverTransaction.execute { importer().importExact(workspaceId, userId, PATH, EndpointMethod.DELETE, pin) }
                }.code shouldBe code
                entityCount() shouldBe 0
            }
        }
    }

    @Test
    fun `a moved pin is rolled back with the receiver's transaction when a later entry fails`() {
        receiverTransaction.execute { importer().importExact(workspaceId, userId, PATH, EndpointMethod.POST, pin(2)) }
        shouldThrow<IllegalStateException> {
            receiverTransaction.execute {
                importer().importExact(workspaceId, userId, PATH, EndpointMethod.POST, pin(3))
                withClue("non-vacuity: inside the transaction the new version exists") { versionCount() shouldBe 2 }
                error("injected: the next entry of the promoted batch failed")
            }
        }
        val post = endpoints.findByPath(PATH, EndpointMethod.POST).shouldNotBeNull()
        pointer(post.id) shouldBe 1
        versionCount() shouldBe 1
    }

    // ------------------------------------------------------------------------- helpers

    private fun pin(version: Int) = EndpointPromotionImport.ExactPin(PIPELINE, version, "seed-${version - 1}", 30, "")

    private fun importer() =
        EndpointPromotionImport(
            endpoints = endpoints,
            versions = versions,
            pipelines = pipelineRepository,
            pins =
                EndpointPinValidator(
                    pipelineRepository = pipelineRepository,
                    pipelines = pipelines,
                    readOnlyRule = ReadOnlyPipelineRule(PipelineResolver { _, _, _ -> null }, MAX_DEPTH),
                    authoring = AuthoringGuard(false),
                ),
            registry = EndpointRegistry(endpoints),
            timeouts = EndpointPublishService.TimeoutBounds(defaultSeconds = 30, minSeconds = 1, maxSeconds = 300),
        )

    private fun pipeline(
        current: Int,
        vararg statuses: PipelineVersionStatus,
    ) {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id)" +
                " VALUES (:id, :name, 'P', :owner, :current, :ws)",
            mapOf("id" to id, "name" to PIPELINE, "owner" to userId, "current" to current, "ws" to workspaceId),
        )
        statuses.forEachIndexed { index, status ->
            jdbc.update(
                "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at)" +
                    " VALUES (:id, :version, CAST('{}' AS jsonb), :hash, :status, :owner," +
                    " CASE WHEN :status <> 'DRAFT' THEN CAST(:owner AS UUID) END, CASE WHEN :status <> 'DRAFT' THEN NOW() END)",
                mapOf("id" to id, "version" to index + 1, "hash" to "seed-$index", "status" to status.name, "owner" to userId),
            )
        }
    }

    private fun body(name: String) =
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
                        id = "apply",
                        description = "",
                        type = NodeType.DQL,
                        source = "pg",
                        template = TemplateRef("test/apply.sql", 1),
                        output = NodeOutput.Caller,
                        dependsOn = emptyList(),
                    ),
                ),
        )

    private fun pointer(id: UUID): Int? =
        jdbc.queryForObject("SELECT current_version FROM published_endpoints WHERE id = :id", mapOf("id" to id), Int::class.javaObjectType)

    private fun count(sql: String): Int = jdbc.queryForObject(sql, mapOf("ws" to workspaceId), Int::class.java).shouldNotBeNull()

    private fun entityCount() = count("SELECT COUNT(*) FROM published_endpoints WHERE workspace_id = :ws")

    private fun versionCount() =
        count(
            "SELECT COUNT(*) FROM published_endpoint_versions v JOIN published_endpoints e ON e.id = v.endpoint_id " +
                "WHERE e.workspace_id = :ws",
        )

    private companion object {
        const val MAX_DEPTH = 5
        const val PIPELINE = "acme/rides/apply"
        const val PATH = "/acme/v1/applications"
    }
}
