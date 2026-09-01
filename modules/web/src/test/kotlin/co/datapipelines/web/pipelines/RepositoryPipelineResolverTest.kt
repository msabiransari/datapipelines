package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.NewPipeline
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineSerializer
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.web.TestRepoFiles
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * [repositoryPipelineResolver] against the real registry (a Postgres container running app's
 * shipped migrations): pinned-version resolution by name, the unknown-name/unknown-version nulls,
 * and the D7 rule — a soft-deleted pipeline still resolves for EXISTING references, flagged
 * `deleted`, so save-time validation can block only the new ones.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepositoryPipelineResolverTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var pipelines: PipelineRepository

    private val userId = UUID.randomUUID()

    @BeforeAll
    fun createSchema() {
        jdbc =
            NamedParameterJdbcTemplate(
                DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password).apply {
                    setDriverClassName(postgres.driverClassName)
                },
            )
        TestRepoFiles.migrationPaths().forEach { path -> jdbc.jdbcTemplate.execute(TestRepoFiles.read(path)) }
    }

    @BeforeEach
    fun setUp() {
        pipelines = PipelineRepository(jdbc)
        // The CASCADE also reaches workspaces (created_by), so the V4-seeded `default`
        // workspace the repository pins is re-seeded after every truncate.
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name)" +
                " VALUES ('$DEFAULT_WORKSPACE_ID', 'default', 'Default')",
        )
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to userId, "email" to "u$userId@example.com", "sub" to "sub-$userId"),
        )
    }

    private fun childPipeline(name: String) =
        Pipeline(
            schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
            name = name,
            displayName = name,
            description = "",
            settings = PipelineSettings(),
            parameters = emptyMap(),
            nodes =
                listOf(
                    Node(
                        id = "q",
                        description = "q",
                        type = NodeType.DQL,
                        source = "tempdb",
                        template = TemplateRef("tq", 1),
                        output = NodeOutput.Caller,
                        dependsOn = emptyList(),
                    ),
                ),
        )

    private fun save(pipeline: Pipeline) =
        pipelines.create(DEFAULT_WORKSPACE_ID, NewPipeline.from(pipeline, ownerId = userId), PipelineSerializer().write(pipeline), userId)

    @Test
    fun `a pinned reference resolves to the parsed body of exactly that version`() {
        val record = save(childPipeline("resolver_child"))
        // A second version with an observably different body: the pin must keep reading v1.
        val v2 = childPipeline("resolver_child").copy(description = "v2")
        pipelines.appendReleasedVersion(DEFAULT_WORKSPACE_ID, record.id, v2, PipelineSerializer().write(v2), userId)

        val v1 = repositoryPipelineResolver(pipelines).resolve(DEFAULT_WORKSPACE_ID, "resolver_child", 1)
        val resolvedV2 = repositoryPipelineResolver(pipelines).resolve(DEFAULT_WORKSPACE_ID, "resolver_child", 2)

        v1 shouldNotBe null
        v1?.pipeline?.description shouldBe ""
        v1?.deleted shouldBe false
        resolvedV2?.pipeline?.description shouldBe "v2"
    }

    @Test
    fun `unknown name and unknown version both resolve to null`() {
        val record = save(childPipeline("resolver_versions"))
        val resolver = repositoryPipelineResolver(pipelines)

        resolver.resolve(DEFAULT_WORKSPACE_ID, "no_such_pipeline", 1) shouldBe null
        resolver.resolve(DEFAULT_WORKSPACE_ID, "resolver_versions", record.currentVersion + 9) shouldBe null
    }

    @Test
    fun `a soft-deleted pipeline still resolves, flagged deleted (D7)`() {
        val record = save(childPipeline("resolver_deleted"))
        pipelines.softDelete(DEFAULT_WORKSPACE_ID, record.id) shouldBe true

        val resolved = repositoryPipelineResolver(pipelines).resolve(DEFAULT_WORKSPACE_ID, "resolver_deleted", 1)

        resolved shouldNotBe null
        resolved?.deleted shouldBe true
        resolved?.pipeline?.name shouldBe "resolver_deleted"
    }

    private companion object {
        /** The V4-seeded `default` workspace every repository call in this suite is scoped to. */
        val DEFAULT_WORKSPACE_ID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("dp")
                .withPassword("dp")
    }
}
