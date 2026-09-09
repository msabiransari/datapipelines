package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.NewPipeline
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineSerializer
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.web.SharedPostgres
import co.datapipelines.web.TestRepoFiles
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

/**
 * [repositoryPipelineResolver] against the real registry (a Postgres container running app's
 * shipped migrations): pinned-version resolution by name, the unknown-name/unknown-version nulls,
 * and the D7 rule — a soft-deleted pipeline still resolves for EXISTING references, flagged
 * `deleted`, so save-time validation can block only the new ones.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepositoryPipelineResolverTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var pipelines: PipelineRepository

    private val userId = UUID.randomUUID()

    /** Binds the JDBC template to the module's shared, already-migrated container. */
    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
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
                        template = TemplateRef("test/tq", 1),
                        output = NodeOutput.Caller,
                        dependsOn = emptyList(),
                    ),
                ),
        )

    private fun save(pipeline: Pipeline) =
        // The fixtures resolve RELEASED versions by name, so they create released content —
        // the import path's lifecycle, named explicitly (D55).
        pipelines.create(
            DEFAULT_WORKSPACE_ID,
            NewPipeline.from(pipeline, ownerId = userId),
            PipelineSerializer().write(pipeline),
            userId,
            co.datapipelines.pipeline.CreateLifecycle.RELEASED,
            WriteSurface.SESSION,
        )

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
        v1?.entityDiscarded shouldBe false
        resolvedV2?.pipeline?.description shouldBe "v2"
    }

    @Test
    fun `unknown name and unknown version both resolve to null`() {
        val record = save(childPipeline("resolver_versions"))
        val resolver = repositoryPipelineResolver(pipelines)

        resolver.resolve(DEFAULT_WORKSPACE_ID, "no_such_pipeline", 1) shouldBe null
        resolver.resolve(DEFAULT_WORKSPACE_ID, "resolver_versions", checkNotNull(record.currentVersion) + 9) shouldBe null
    }

    @Test
    fun `a discarded pipeline still resolves, flagged entityDiscarded (D7)`() {
        val record = save(childPipeline("resolver_deleted"))
        // 101: the derived entity status — discard the only release through the real verb.
        checkNotNull(
            pipelines.discardVersion(
                DEFAULT_WORKSPACE_ID,
                record.id,
                record.name,
                checkNotNull(record.currentVersion),
                userId,
                draftEligible = true,
            ),
        )

        val resolved = repositoryPipelineResolver(pipelines).resolve(DEFAULT_WORKSPACE_ID, "resolver_deleted", 1)

        resolved shouldNotBe null
        resolved?.entityDiscarded shouldBe true
        resolved?.pipeline?.name shouldBe "resolver_deleted"
        withClue("D58: the pinned version reads as DISCARDED, so a NEW pin is refused at save") {
            resolved?.versionStatus shouldBe PipelineVersionStatus.DISCARDED
        }
    }

    private companion object {
        /** The V4-seeded `default` workspace every repository call in this suite is scoped to. */
        val DEFAULT_WORKSPACE_ID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
    }
}
