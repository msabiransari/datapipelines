package co.datapipelines.pipeline

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * [PipelineRepository.findLiveVersionBodiesByName] (#873 R1) against the shipped schema: the bulk read the release's
 * apply-time prerequisite check feeds to its datasource extractor. It returns the stored body of every DRAFT or RELEASED
 * version a `(name, version)` key names in the caller's workspace — the same text [PipelineRepository.findVersionBody]
 * returns — and nothing for a DISCARDED version, an unknown key, or a pipeline of another workspace (one outcome).
 * The one-statement-per-batch property is counted on PostgreSQL by the application module's
 * `ReleasePrerequisitesPostgresTest`, which observes every statement the check issues.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PipelineVersionBodiesIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: PipelineRepository
    private lateinit var owner: UUID

    private val serializer = PipelineSerializer()

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.pooledDataSource())
    }

    @BeforeEach
    fun setUp() {
        repository = PipelineRepository(jdbc)
        jdbc.jdbcTemplate.execute("TRUNCATE pipelines, users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', 'default', 'Default')",
        )
        owner =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO users (email, display_name, provider, provider_subject)" +
                        " VALUES ('owner@example.com', 'Owner', 'google', 'sub-1') RETURNING id",
                    emptyMap<String, Any>(),
                    UUID::class.java,
                ),
            )
    }

    @Test
    fun `DRAFT and RELEASED versions come back with their stored bodies, keyed by name and version`() {
        val released = create("test/released", CreateLifecycle.RELEASED)
        val drafted = create("test/drafted", CreateLifecycle.DRAFT)
        val draftV2 =
            checkNotNull(
                repository.createDraft(
                    WORKSPACE_ID,
                    released.id,
                    serializer.write(Fixtures.pipeline(name = "test/released", nodes = listOf(Fixtures.node(id = "v2")))),
                    checkNotNull(repository.findVersionDetail(WORKSPACE_ID, released.id, 1)).bodyHash,
                    owner,
                    WriteSurface.SESSION,
                ),
            )

        val keys = listOf("test/released" to 1, "test/released" to 2, "test/drafted" to 1)
        val rows = repository.findLiveVersionBodiesByName(WORKSPACE_ID, keys)

        rows shouldContainExactlyInAnyOrder
            listOf(
                PipelineVersionBody("test/released", 1, body(released.id, 1)),
                PipelineVersionBody("test/released", draftV2.version, body(released.id, draftV2.version)),
                PipelineVersionBody("test/drafted", 1, body(drafted.id, 1)),
            )
        draftV2.version shouldBe 2
    }

    @Test
    fun `a DISCARDED version, an unknown name and an unknown version are absent`() {
        val record = create("test/discarded", CreateLifecycle.RELEASED)
        checkNotNull(repository.discardVersion(WORKSPACE_ID, record.id, record.name, 1, owner, draftEligible = true))
        create("test/kept", CreateLifecycle.RELEASED)

        val rows =
            repository.findLiveVersionBodiesByName(
                WORKSPACE_ID,
                listOf("test/discarded" to 1, "test/nobody" to 1, "test/kept" to 2, "test/kept" to 1),
            )

        rows.map { it.name to it.version } shouldBe listOf("test/kept" to 1)
    }

    @Test
    fun `another workspace's pipeline is absent - the same name in the caller's workspace returns only the caller's body`() {
        val other = UUID.randomUUID()
        jdbc.jdbcTemplate.execute("INSERT INTO workspaces (id, name, display_name) VALUES ('$other', 'other', 'Other')")
        val theirs = create("test/theirs", CreateLifecycle.RELEASED, workspaceId = other)
        create("test/shared", CreateLifecycle.RELEASED, workspaceId = other, nodeId = "theirs")
        val mine = create("test/shared", CreateLifecycle.RELEASED, nodeId = "mine")

        val rows = repository.findLiveVersionBodiesByName(WORKSPACE_ID, listOf("test/theirs" to 1, "test/shared" to 1))

        rows shouldBe listOf(PipelineVersionBody("test/shared", 1, body(mine.id, 1)))
        repository.findLiveVersionBodiesByName(other, listOf("test/theirs" to 1)).map { it.name } shouldBe listOf(theirs.name)
    }

    @Test
    fun `no key reads nothing - and a full batch of 256 keys answers every one in the one call`() {
        repository.findLiveVersionBodiesByName(WORKSPACE_ID, emptyList()).shouldBeEmpty()
        val names = (0 until FULL_BATCH).map { "test/p_$it" }
        names.forEach { create(it, CreateLifecycle.RELEASED) }

        val rows = repository.findLiveVersionBodiesByName(WORKSPACE_ID, names.map { it to 1 })

        rows.map { it.name } shouldContainExactlyInAnyOrder names
    }

    private fun create(
        name: String,
        lifecycle: CreateLifecycle,
        workspaceId: UUID = WORKSPACE_ID,
        nodeId: String = "n1",
    ): PipelineRecord {
        val body = Fixtures.pipeline(name = name, nodes = listOf(Fixtures.node(id = nodeId)))
        return repository.create(workspaceId, NewPipeline.from(body, owner), serializer.write(body), owner, lifecycle, WriteSurface.SESSION)
    }

    private fun body(
        pipelineId: UUID,
        version: Int,
    ): String = checkNotNull(repository.findVersionBody(WORKSPACE_ID, pipelineId, version))

    private companion object {
        /** The V4-seeded `default` workspace, re-inserted after every truncate. */
        val WORKSPACE_ID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")

        /** `GraphLimits.MAX_RESOLVE_BATCH_SIZE`, the largest batch a graph read sends (this module does not depend on it). */
        const val FULL_BATCH = 256
    }
}
