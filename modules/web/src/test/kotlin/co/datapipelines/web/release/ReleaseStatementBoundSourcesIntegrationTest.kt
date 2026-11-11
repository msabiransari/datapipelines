package co.datapipelines.web.release

import co.datapipelines.application.dependencies.PostgresStatementCeiling
import co.datapipelines.application.dependencies.ReleasePlanningDeadline
import co.datapipelines.application.dependencies.ReleasePlanningLimits
import co.datapipelines.application.dependencies.ReleasePlanningWalk
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineSerializer
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.web.SharedPostgres
import co.datapipelines.web.config.EndpointsConfiguration
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * #786 R3, landing review r4 (record §25.4), through the PRODUCTION descriptor bean methods of all three release families
 * on a real PostgreSQL: the API source (one statement), the pipeline source (three) and the template provider (one batch
 * read since #873) each
 * run every statement under `min(statement limit, remaining deadline)` measured when THAT statement starts. The descriptors
 * are built by `ReleaseConfiguration` / `EndpointsConfiguration` exactly as the context builds them, so a bean whose
 * source read through any template but the ceiling's would show a stale bound here.
 *
 * [ObservedStatements] records the `statement_timeout` each real statement executes under and advances the walk's clock
 * one second after each one.
 */
class ReleaseStatementBoundSourcesIntegrationTest {
    private val clock = StepClock()
    private val statements = ObservedStatements(SharedPostgres.dataSource()) { clock.now = clock.now.plusSeconds(1) }
    private val jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
    private val manager = DataSourceTransactionManager(statements.dataSource)
    private val ceiling = PostgresStatementCeiling(statements.dataSource)
    private var userId: UUID = UUID.randomUUID()
    private var workspaceId: UUID = UUID.randomUUID()

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
        jdbc.update(
            "INSERT INTO templates (name, display_name, description, current_version, workspace_id, created_by)" +
                " VALUES (:name, 'T', '', 1, :ws, :owner)",
            mapOf("name" to TEMPLATE, "ws" to workspaceId, "owner" to userId),
        )
        jdbc.update(
            "INSERT INTO template_versions (template_id, version, engine, dialect, is_library, imports_json, body, status, body_hash," +
                " created_by, released_by, released_at)" +
                " SELECT t.id, 1, 'freemarker', 'POSTGRES', FALSE, '[]'::jsonb, 'SELECT 1', 'RELEASED', 'ht1', :owner, :owner, NOW()" +
                " FROM templates t WHERE t.name = :name AND t.workspace_id = :ws",
            mapOf("name" to TEMPLATE, "ws" to workspaceId, "owner" to userId),
        )
        val pipelineId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id)" +
                " VALUES (:id, :name, 'P', :owner, NULL, :ws)",
            mapOf("id" to pipelineId, "name" to PIPELINE, "owner" to userId, "ws" to workspaceId),
        )
        jdbc.update(
            "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by)" +
                " VALUES (:id, 1, CAST(:body AS jsonb), 'hp1', 'DRAFT', :owner)",
            mapOf("id" to pipelineId, "body" to PipelineSerializer().write(body()), "owner" to userId),
        )
        val endpointId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO published_endpoints (id, workspace_id, path_pattern, pipeline_id, timeout_seconds, created_by)" +
                " VALUES (:id, :ws, :path, :pipeline, 30, :owner)",
            mapOf("id" to endpointId, "ws" to workspaceId, "path" to PATH, "pipeline" to pipelineId, "owner" to userId),
        )
        jdbc.update(
            "INSERT INTO published_endpoint_versions (endpoint_id, version, status, pipeline_name, pipeline_version, timeout_seconds," +
                " body_hash, created_by) VALUES (:id, 1, 'DRAFT', :pipeline, 1, 30, 'h1', :owner)",
            mapOf("id" to endpointId, "pipeline" to PIPELINE, "owner" to userId),
        )
        statements.observed.clear()
    }

    @Test
    fun `every statement of the production API, pipeline and template sources reads the budget left at its own start`() {
        val closure = checkNotNull(walk().plan(ReleaseNodeIdentity(workspaceId, ReleaseFamily.ENDPOINT, PATH, 1), userId))

        closure.nodes.map { it.identity.family } shouldContainExactly
            listOf(ReleaseFamily.ENDPOINT, ReleaseFamily.PIPELINE, ReleaseFamily.TEMPLATE)
        withClue("API 1, pipeline 3, template 1 (#873: one batch read); one second after each, from 10 s:\n${statements.observed}") {
            statements.timeouts() shouldContainExactly listOf("10s", "9s", "8s", "7s", "6s")
        }
    }

    /** The planning walk over the three production descriptors, built by their bean methods over the observed ceiling. */
    private fun walk(): ReleasePlanningWalk {
        val release = ReleaseConfiguration()
        val registry =
            FamilyRegistry.production(
                listOf(
                    release.pipelineDependencyFamily(ceiling),
                    release.templateDependencyFamily(ceiling),
                    EndpointsConfiguration().endpointDependencyFamily(ceiling),
                ),
            )
        return ReleasePlanningWalk(
            registry,
            ReleaseBounds(),
            ReleasePlanningWalk.planningTransaction(manager),
            ReleasePlanningWalk.speculativeReads(manager),
            ReleasePlanningDeadline.after(Duration.ofSeconds(BUDGET_SECONDS), clock),
            clock = clock,
            planning = ReleasePlanningLimits(statementTimeout = Duration.ofSeconds(LIMIT_SECONDS)),
        )
    }

    private fun body() =
        Pipeline(
            schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
            name = PIPELINE,
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
                        template = TemplateRef(TEMPLATE, 1),
                        output = NodeOutput.Caller,
                        dependsOn = emptyList(),
                    ),
                ),
        )

    private class StepClock : Clock() {
        @Volatile var now: Instant = Instant.parse("2026-11-11T00:00:00Z")

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }

    private companion object {
        const val PIPELINE = "acme/rides/daily"
        const val PATH = "/acme/v1/rides"
        const val TEMPLATE = "acme/rides/rides.sql"
        const val BUDGET_SECONDS = 10L
        const val LIMIT_SECONDS = 30L
    }
}
