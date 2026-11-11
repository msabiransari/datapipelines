package co.datapipelines.application.dependencies

import co.datapipelines.application.SharedPostgres
import co.datapipelines.application.endpoints.EndpointVersionBody
import co.datapipelines.application.endpoints.LegacyEndpointColumns
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.application.endpoints.PublishedEndpointVersionRepository
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.DashboardPin
import co.datapipelines.pipeline.EndpointVersionPin
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionConsumers
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.PipelineVersionStatus.DISCARDED
import co.datapipelines.pipeline.PipelineVersionStatus.DRAFT
import co.datapipelines.pipeline.PipelineVersionStatus.RELEASED
import co.datapipelines.pipeline.SchedulePin
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
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
 * #786 B.4 — per-live-API-version and per-schedule protection (record §14, A39), against a real Postgres: the two
 * consumer adapters over `published_endpoint_versions` and `schedules`, and the REAL [PipelineService] discard and
 * purge refusing on them. Every property here is a statement about the SQL (the status predicate, the CURRENT join,
 * the soft-delete filter), so a mocked template could not hold any of them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiAndScheduleConsumersIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var transactions: TransactionTemplate
    private lateinit var endpoints: PublishedEndpointRepository
    private lateinit var versions: PublishedEndpointVersionRepository
    private lateinit var pipelines: PipelineRepository
    private lateinit var apiConsumers: EndpointVersionPipelineConsumers
    private lateinit var scheduleConsumers: SchedulePipelineConsumers
    private lateinit var service: PipelineService

    private lateinit var workspaceId: UUID
    private lateinit var userId: UUID
    private lateinit var pipelineId: UUID
    private lateinit var pipelineName: String

    @BeforeAll
    fun connect() {
        val dataSource = SharedPostgres.dataSource()
        jdbc = NamedParameterJdbcTemplate(dataSource)
        transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
        endpoints = PublishedEndpointRepository(jdbc)
        versions = PublishedEndpointVersionRepository(jdbc)
        pipelines = PipelineRepository(jdbc)
        apiConsumers = EndpointVersionPipelineConsumers(versions)
        scheduleConsumers = SchedulePipelineConsumers(jdbc, TARGET_PREFIX)
        service =
            PipelineService(
                pipelines = pipelines,
                validator = mockk(),
                drafts = mockk(),
                releases = mockk(),
                authoring = AuthoringGuard(true),
                draftTemplates = mockk(),
                dashboards = NoDashboards,
                endpointVersions = apiConsumers,
                schedules = scheduleConsumers,
            )
    }

    @BeforeEach
    fun seed() {
        // Clean what we touch (DEVELOPMENT.md §9.1): the CASCADE from `users` reaches workspaces, pipelines, their
        // versions, published endpoints and their versions, and schedules.
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        userId = UUID.randomUUID()
        workspaceId = UUID.randomUUID()
        pipelineId = UUID.randomUUID()
        pipelineName = "a39/p_${pipelineId.toString().replace("-", "").take(12)}"
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to userId, "email" to "u$userId@example.com", "sub" to "sub-$userId"),
        )
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (:id, :name, 'W', :owner)",
            mapOf("id" to workspaceId, "name" to "w_${workspaceId.toString().replace("-", "")}", "owner" to userId),
        )
        // v1 and v2 RELEASED, v2 current — a noncurrent release (v1) is the case the API's own pin keeps alive.
        jdbc.update(
            "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id) " +
                "VALUES (:id, :name, 'P', :owner, 2, :ws)",
            mapOf("id" to pipelineId, "name" to pipelineName, "owner" to userId, "ws" to workspaceId),
        )
        pipelineVersion(1, RELEASED)
        pipelineVersion(2, RELEASED)
    }

    // ------------------------------------------------------------------ API versions

    @Test
    fun `a noncurrent RELEASED API version of a DISABLED API refuses the discard of the pipeline version it pins, naming it`() {
        val api = endpoint("/a39/v1/home", enabled = false)
        versions.insert(api, 1, RELEASED, body(pipelineVersion = 1), userId)
        versions.insert(api, 2, RELEASED, body(pipelineVersion = 2), userId)
        versions.setCurrent(api, 2)

        val refused = shouldThrow<DatapipelinesException> { service.discardVersion(workspaceId, pipelineId, 1, userId) }

        refused.code shouldBe PipelineErrorCodes.Versioning.PINNED
        refused.message shouldContain "1 API version(s)"
        refused.details["referencing_api_versions"] shouldBe
            listOf(mapOf("path" to "/a39/v1/home", "version" to 1, "status" to "RELEASED", "pipeline_version" to 1))
        status(1) shouldBe RELEASED
    }

    @Test
    fun `a DRAFT API version protects its exact pin too`() {
        val api = endpoint("/a39/v1/draft", enabled = true)
        versions.insert(api, 1, DRAFT, body(pipelineVersion = 1), userId)

        val refused = shouldThrow<DatapipelinesException> { service.discardVersion(workspaceId, pipelineId, 1, userId) }

        refused.code shouldBe PipelineErrorCodes.Versioning.PINNED
        apiConsumers.liveVersionPins(workspaceId, pipelineName, 1) shouldContainExactly
            listOf(EndpointVersionPin("/a39/v1/draft", 1, DRAFT, 1))
    }

    @Test
    fun `a DISCARDED API version protects nothing - the pipeline version it pinned discards`() {
        val api = endpoint("/a39/v1/history", enabled = true)
        versions.insert(api, 1, RELEASED, body(pipelineVersion = 1), userId)
        versions.insert(api, 2, RELEASED, body(pipelineVersion = 2), userId)
        versions.setCurrent(api, 2)
        versions.discard(api, 1, userId, draftEligible = true)

        apiConsumers.liveVersionPins(workspaceId, pipelineName, 1).shouldBeEmpty()
        apiConsumers.anyVersionPins(workspaceId, pipelineName) shouldContainExactly
            listOf(EndpointVersionPin("/a39/v1/history", 2, RELEASED, 2))

        service.discardVersion(workspaceId, pipelineId, 1, userId).version.status shouldBe DISCARDED
    }

    @Test
    fun `an API version pinning a pipeline DRAFT refuses that draft's purge`() {
        pipelineVersion(3, DRAFT)
        val api = endpoint("/a39/v1/next", enabled = true)
        versions.insert(api, 1, DRAFT, body(pipelineVersion = 3), userId)

        val refused = shouldThrow<DatapipelinesException> { service.purgeVersion(workspaceId, pipelineId, 3) }

        refused.code shouldBe PipelineErrorCodes.Versioning.PINNED
        status(3) shouldBe DRAFT
    }

    @Test
    fun `another workspace's API version pinning the same name protects nothing here`() {
        val otherWorkspace = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (:id, :name, 'W', :owner)",
            mapOf("id" to otherWorkspace, "name" to "w_${otherWorkspace.toString().replace("-", "")}", "owner" to userId),
        )
        val api = endpoint("/a39/v1/elsewhere", enabled = true, workspace = otherWorkspace)
        versions.insert(api, 1, RELEASED, body(pipelineVersion = 1), userId)

        apiConsumers.liveVersionPins(workspaceId, pipelineName, 1).shouldBeEmpty()
        apiConsumers.anyVersionPins(workspaceId, pipelineName).shouldBeEmpty()
    }

    // ------------------------------------------------------------------ schedules

    @Test
    fun `a PAUSED schedule protects the version CURRENT resolves to and nothing else`() {
        schedule("nightly", enabled = false)

        scheduleConsumers.liveVersionPins(workspaceId, pipelineName, 2) shouldContainExactly listOf(SchedulePin("nightly", 2))
        scheduleConsumers.liveVersionPins(workspaceId, pipelineName, 1).shouldBeEmpty()

        val refused = shouldThrow<DatapipelinesException> { service.discardVersion(workspaceId, pipelineId, 2, userId) }
        refused.code shouldBe PipelineErrorCodes.Versioning.PINNED
        refused.details["referencing_schedules"] shouldBe listOf(mapOf("schedule" to "nightly", "pipeline_version" to 2))
        // The noncurrent release is not what the schedule runs: its discard leaves the schedule's version in place.
        service.discardVersion(workspaceId, pipelineId, 1, userId).version.status shouldBe DISCARDED
    }

    @Test
    fun `a BLOCKED schedule protects too, a deleted one does not`() {
        schedule("blocked_one", blocked = true)
        schedule("deleted_one", deleted = true)

        scheduleConsumers.liveVersionPins(workspaceId, pipelineName, 2) shouldContainExactly listOf(SchedulePin("blocked_one", 2))
        scheduleConsumers.anyVersionPins(workspaceId, pipelineName) shouldContainExactly listOf(SchedulePin("blocked_one", 2))
    }

    @Test
    fun `a schedule of a pipeline with no CURRENT runs no version, and the entity scope still counts it`() {
        jdbc.update("UPDATE pipelines SET current_version = NULL WHERE id = :id", mapOf("id" to pipelineId))
        schedule("waiting")

        scheduleConsumers.liveVersionPins(workspaceId, pipelineName, 1).shouldBeEmpty()
        scheduleConsumers.liveVersionPins(workspaceId, pipelineName, 2).shouldBeEmpty()
        scheduleConsumers.anyVersionPins(workspaceId, pipelineName) shouldContainExactly listOf(SchedulePin("waiting", null))
    }

    @Test
    fun `#786 D1 - a schema-2 schedule protects exactly its pinned version, never what CURRENT resolves to`() {
        schedule("pinned_v1", schema = 2, version = "1")

        scheduleConsumers.liveVersionPins(workspaceId, pipelineName, 1) shouldContainExactly listOf(SchedulePin("pinned_v1", 1))
        scheduleConsumers.liveVersionPins(workspaceId, pipelineName, 2).shouldBeEmpty()
        scheduleConsumers.anyVersionPins(workspaceId, pipelineName) shouldContainExactly listOf(SchedulePin("pinned_v1", 1))

        val refused = shouldThrow<DatapipelinesException> { service.discardVersion(workspaceId, pipelineId, 1, userId) }
        refused.code shouldBe PipelineErrorCodes.Versioning.PINNED
        refused.details["referencing_schedules"] shouldBe listOf(mapOf("schedule" to "pinned_v1", "pipeline_version" to 1))
        // CURRENT is not what this schedule runs: moving or clearing the pointer never reaches it.
        jdbc.update("UPDATE pipelines SET current_version = NULL WHERE id = :id", mapOf("id" to pipelineId))
        scheduleConsumers.liveVersionPins(workspaceId, pipelineName, 1) shouldContainExactly listOf(SchedulePin("pinned_v1", 1))
    }

    @Test
    fun `#786 D1 - a schema-2 payload whose version is not a positive integer pins nothing, and the entity scope still counts it`() {
        schedule("planted_text", schema = 2, version = "\"current\"")
        schedule("planted_zero", schema = 2, version = "0")

        scheduleConsumers.liveVersionPins(workspaceId, pipelineName, 1).shouldBeEmpty()
        scheduleConsumers.liveVersionPins(workspaceId, pipelineName, 2).shouldBeEmpty()
        scheduleConsumers.anyVersionPins(workspaceId, pipelineName).map { it.pipelineVersion } shouldContainExactly listOf(null, null)
    }

    @Test
    fun `a schedule targeting another pipeline protects nothing here`() {
        schedule("other", target = TARGET_PREFIX + "a39/someone_else")

        scheduleConsumers.anyVersionPins(workspaceId, pipelineName).shouldBeEmpty()
    }

    // ------------------------------------------------------------------ fixtures

    private fun pipelineVersion(
        version: Int,
        status: PipelineVersionStatus,
    ) = jdbc.update(
        "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at) " +
            "VALUES (:id, :version, CAST('{}' AS jsonb), :hash, :status, :owner, " +
            "CASE WHEN :status = 'RELEASED' THEN CAST(:owner AS UUID) END, CASE WHEN :status = 'RELEASED' THEN NOW() END)",
        mapOf("id" to pipelineId, "version" to version, "hash" to "seed-$version", "status" to status.name, "owner" to userId),
    )

    private fun status(version: Int): PipelineVersionStatus? = pipelines.findVersionDetail(workspaceId, pipelineId, version)?.status

    private fun body(pipelineVersion: Int) = EndpointVersionBody(pipelineName, pipelineVersion, 30, "")

    private fun endpoint(
        path: String,
        enabled: Boolean,
        workspace: UUID = workspaceId,
    ): UUID =
        requireNotNull(
            transactions.execute {
                endpoints
                    .insert(
                        PublishedEndpoint.of(
                            id = UUID.randomUUID(),
                            workspaceId = workspace,
                            pathPattern = path,
                            isEnabled = enabled,
                            createdBy = userId,
                            createdAt = Instant.EPOCH,
                            updatedAt = Instant.EPOCH,
                        ),
                        LegacyEndpointColumns(pipelineId, 30, ""),
                    ).id
            },
        )

    private fun schedule(
        name: String,
        enabled: Boolean = true,
        blocked: Boolean = false,
        deleted: Boolean = false,
        target: String = TARGET_PREFIX + pipelineName,
        schema: Int = 1,
        version: String? = null,
    ) = jdbc.update(
        """
        INSERT INTO schedules (id, workspace_id, name, executor_id, payload_schema_version, payload_json, target_ref, cron, timezone,
                               enabled, blocked_reason, blocked_at, created_by, updated_by, deleted_at, deleted_by)
        VALUES (:id, :ws, :name, 'pipeline', :schema, CAST(:payload AS jsonb), :target, '0 3 * * *', 'UTC', :enabled,
                CASE WHEN :blocked THEN 'pipeline_not_released' END, CASE WHEN :blocked THEN NOW() END, :owner, :owner,
                CASE WHEN :deleted THEN NOW() END, CASE WHEN :deleted THEN CAST(:owner AS UUID) END)
        """.trimIndent(),
        mapOf(
            "id" to UUID.randomUUID(),
            "ws" to workspaceId,
            "name" to name,
            "schema" to schema,
            "payload" to
                if (version == null) {
                    """{"pipeline":"${target.removePrefix(TARGET_PREFIX)}"}"""
                } else {
                    """{"pipeline":"${target.removePrefix(TARGET_PREFIX)}","version":$version}"""
                },
            "target" to target,
            "enabled" to enabled,
            "blocked" to blocked,
            "deleted" to deleted,
            "owner" to userId,
        ),
    )

    private object NoDashboards : PipelineVersionConsumers {
        override fun liveVersionPins(
            workspaceId: UUID,
            pipelineName: String,
            version: Int,
        ): List<DashboardPin> = emptyList()

        override fun anyVersionPins(
            workspaceId: UUID,
            pipelineName: String,
        ): List<DashboardPin> = emptyList()
    }

    private companion object {
        /** The pipeline executor's `target_ref` prefix, as `web` supplies it (`PipelineJobExecutor.TARGET_PREFIX`). */
        const val TARGET_PREFIX = "pipeline:"
    }
}
