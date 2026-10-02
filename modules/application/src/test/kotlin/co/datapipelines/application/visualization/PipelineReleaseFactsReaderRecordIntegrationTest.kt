package co.datapipelines.application.visualization

import co.datapipelines.application.SharedPostgres
import co.datapipelines.application.endpoints.ReadOnlyPipelineRule
import co.datapipelines.pipeline.NewPipeline
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ResolvedPipeline
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.OutputColumn
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * #328 E — the acceptance's "answered from the release": a release's recorded caller columns
 * outlive the datasource they were observed from.
 *
 * A REAL table is created in the shared Postgres (the repo's integration container), a one-node
 * DQL pipeline over it is released after one SUCCESS execution (the flip's D1 record), and
 * `releaseOf` answers the columns. Then the TABLE is altered underneath — the column's type is
 * widened — and `releaseOf` answers UNCHANGED: the record is a property of the release, not of
 * the live schema. (The runtime (L2) would now fail on the real columns; that contrast is the
 * POINT of the record and is not asserted here — it is the runtime's test.)
 *
 * The execution row is inserted directly (the D1 inputs are the subject); that every execution
 * RECORDS its schema is `ExecutionRepositoriesIntegrationTest`'s proof (dag). The test-local
 * resolver mirrors `repositoryPipelineResolver` (web) — application cannot see web, and the
 * three reads (entity, detail, body) are the port's whole contract.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PipelineReleaseFactsReaderRecordIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: PipelineRepository
    private lateinit var reader: PipelineReleaseFactsReader
    private lateinit var owner: UUID

    private val deserializer = PipelineDeserializer()

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
        repository = PipelineRepository(jdbc)
        val resolver =
            PipelineResolver { workspaceId, name, version ->
                val record = repository.findByNameAnyStatus(workspaceId, name) ?: return@PipelineResolver null
                val detail = repository.findVersionDetail(workspaceId, record.id, version) ?: return@PipelineResolver null
                val body = repository.findVersionBody(workspaceId, record.id, version) ?: return@PipelineResolver null
                ResolvedPipeline(
                    pipeline = deserializer.readOrThrow(body),
                    entityDiscarded = !repository.hasLiveVersion(workspaceId, record.id),
                    versionStatus = detail.status,
                    callerOutputJson = detail.callerOutputJson,
                )
            }
        reader = PipelineReleaseFactsReader(resolver, ReadOnlyPipelineRule(resolver, MAX_DEPTH), { _, _ -> null })
    }

    @BeforeEach
    fun setUp() {
        // The discipline every suite on this shared database follows: clean what it touches,
        // re-seed the V4-seeded default workspace sibling specs truncate away.
        jdbc.jdbcTemplate.execute("TRUNCATE pipelines, users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name) " +
                "VALUES ('defa0000-0000-0000-0000-000000000001', 'default', 'Default') ON CONFLICT (id) DO NOTHING",
        )
        owner = insertUser()
        jdbc.jdbcTemplate.execute("DROP TABLE IF EXISTS e328_source")
        // The REAL datasource table: the release's record is read from it once, then the table
        // changes underneath.
        jdbc.jdbcTemplate.execute("CREATE TABLE e328_source (month DATE NOT NULL, amount NUMERIC(12,2))")
    }

    /** The one-node DQL pipeline body over `e328_source`, whose single node is the caller. */
    private fun body(name: String): String =
        """{"schema_version":1,"name":"$name","display_name":"E328","description":"d",""" +
            """"parameters":{},"settings":{"tempdb":{"engine":"H2"}},""" +
            """"nodes":[{"id":"read","type":"DQL","source":"pg","template":{"id":"test/e328.sql","version":1},"depends_on":[]}]}"""

    @Test
    fun `the release answers its recorded columns, unchanged after the source table alters`() {
        val pipeline = deserializer.readOrThrow(body("e328/released"))
        val record =
            repository.create(
                WORKSPACE,
                NewPipeline.from(pipeline, ownerId = owner),
                body("e328/released"),
                owner,
                CreateLifecycle.RELEASED,
                WriteSurface.SESSION,
            )
        // The draft differs from the released body (an identical write is the no-op answer).
        val draft =
            checkNotNull(
                repository.createDraft(
                    WORKSPACE,
                    record.id,
                    body("e328/released").replace(""""description":"d"""", """"description":"draft""""),
                    storedHash(record.id),
                    owner,
                    WriteSurface.SESSION,
                ),
            )
        // The run of THIS body, started after the draft write — the D1 candidate.
        jdbc.update(
            """
            INSERT INTO pipeline_executions
                (pipeline_id, pipeline_version, status, executed_by, triggered_via, root_execution_id,
                 started_at, result_schema_json)
            SELECT :pipelineId, :version, 'SUCCESS', :actor, 'REST', gen_random_uuid(), NOW(),
                   CAST(:schema AS jsonb)
            """.trimIndent(),
            mapOf(
                "pipelineId" to record.id,
                "version" to draft.version,
                "actor" to owner,
                "schema" to """[{"name":"month","type":"DATE","nullable":false},{"name":"amount","type":"DECIMAL","nullable":true}]""",
            ),
        )
        val released =
            checkNotNull(
                repository.releaseDraft(WORKSPACE, record.id, "e328/released", "E328", "draft", draft.bodyHash, owner),
            )
        released.version.status shouldBe PipelineVersionStatus.RELEASED

        val fact = checkNotNull(reader.releaseOf(WORKSPACE, ArtifactRef("e328/released", released.version.version)))
        fact.outputColumns shouldBe
            listOf(
                OutputColumn("month", LogicalType.DATE, nullable = false),
                OutputColumn("amount", LogicalType.DECIMAL, nullable = true),
            )

        // The datasource changes underneath — the column's type widens. The RELEASE's answer
        // must not move: it is read from the record, never from the live schema.
        jdbc.jdbcTemplate.execute("ALTER TABLE e328_source ALTER COLUMN amount TYPE NUMERIC(12,4)")
        val after = checkNotNull(reader.releaseOf(WORKSPACE, ArtifactRef("e328/released", released.version.version)))
        after.outputColumns shouldBe fact.outputColumns
    }

    /** The stored v1 hash — the draft-write precondition token. */
    private fun storedHash(pipelineId: UUID): String =
        jdbc
            .query(
                "SELECT body_hash FROM pipeline_versions WHERE pipeline_id = :id AND version = 1",
                mapOf("id" to pipelineId),
            ) { rs, _ -> rs.getString("body_hash") }
            .single()

    private fun insertUser(): UUID =
        checkNotNull(
            jdbc.queryForObject(
                """
                INSERT INTO users (email, display_name, provider, provider_subject)
                VALUES (:email, 'Owner', 'google', :subject) RETURNING id
                """.trimIndent(),
                mapOf("email" to "e328-${UUID.randomUUID()}@example.com", "subject" to "sub-${UUID.randomUUID()}"),
                UUID::class.java,
            ),
        )

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        const val MAX_DEPTH = 5
    }
}
