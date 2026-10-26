package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.NewPipeline
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineNames
import co.datapipelines.pipeline.PipelineNodeRef
import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineSerializer
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.PipelineValidationException
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.SharedPostgres
import co.datapipelines.web.pipelineServiceOver
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * #723 — can a registered child pipeline hold a live version while its version 1 is gone, and what
 * does a parent save that pins a MISSING version of such a child answer?
 *
 * The §12.9 rule tells `pipeline_version_not_found` ("has no version N") from `pipeline_not_found`
 * ("not in the registry"). Each path that could leave an entity without version 1 is driven through
 * the shipped verbs on the real registry ([SharedPostgres], app's migrations): the service's
 * `purgeVersion`, and [PipelineImportService]'s preserved-number import (versioning §9.2). The parent
 * save then runs [PipelineService.create] through the PRODUCTION resolver ([repositoryPipelineResolver])
 * and the real [PipelineValidator]; nothing in the composition rules is stubbed.
 *
 * Two doubles, both outside the question: the CHILD lifecycle service's validator answers VALID (a
 * child's own body is not under test, and its DQL node would need a datasource and a template here),
 * and the parent validator's renderer FAILS IF TOUCHED (a parent of one PIPELINE node renders nothing).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChildVersionProbeIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: PipelineRepository
    private lateinit var children: PipelineService
    private lateinit var parents: PipelineService
    private lateinit var imports: PipelineImportService

    private val actor = UUID.randomUUID()
    private val serializer = PipelineSerializer()

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
    }

    @BeforeEach
    fun setUp() {
        repository = PipelineRepository(jdbc)
        // The CASCADE reaches workspaces (created_by) and pipelines, so both workspaces are re-seeded.
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        for ((id, name) in listOf(WORKSPACE to "default", OTHER_WORKSPACE to "other")) {
            jdbc.jdbcTemplate.execute(
                "INSERT INTO workspaces (id, name, display_name) VALUES ('$id', '$name', '$name') ON CONFLICT DO NOTHING",
            )
        }
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to actor, "email" to "u$actor@example.com", "sub" to "sub-$actor"),
        )
        val childBodiesValid = mockk<PipelineValidator>()
        every { childBodiesValid.validate(any(), any(), any()) } returns ValidationResult.VALID
        every { childBodiesValid.validateOrThrow(any(), any(), any()) } answers { firstArg() }
        children = pipelineServiceOver(repository, validator = childBodiesValid)
        imports = PipelineImportService(repository, childBodiesValid)
        parents =
            pipelineServiceOver(
                repository,
                validator =
                    PipelineValidator(
                        datasources = { _, _ -> null },
                        templates = ChildVersionProbeTemplates,
                        pipelines = repositoryPipelineResolver(repository),
                        // The production bean's answer (DomainConfiguration.pipelineValidator).
                        pipelineNames = PipelineNames.of(repository),
                        maxCompositionDepth = MAX_DEPTH,
                    ),
            )
    }

    // ---------------------------------------------------------------- A.1 reachability

    @Test
    fun `(i) purging the sole draft v1 purges the ENTITY - no v1-less entity is left`() {
        val created = createChild(CHILD, CreateLifecycle.DRAFT)

        children.purgeVersion(WORKSPACE, created.id, 1).shouldBeInstanceOf<PipelineReleaseService.Purged.Entity>()

        repository.findByIdAnyStatus(WORKSPACE, created.id) shouldBe null
        versionsOf(created.id) shouldContainExactly emptyList()
    }

    @Test
    fun `(ii) a RELEASED v1 under a draft v2 is never purged - last_release`() {
        val created = createChild(CHILD, CreateLifecycle.RELEASED)
        val v1Hash = checkNotNull(repository.findVersionDetail(WORKSPACE, created.id, 1)).bodyHash
        children.update(WORKSPACE, created.id, body(child(CHILD, "v2 draft")), v1Hash, actor, WriteSurface.SESSION)
        versionsOf(created.id) shouldContainExactly listOf(1 to "RELEASED", 2 to "DRAFT")

        val refused = shouldThrow<DatapipelinesException> { children.purgeVersion(WORKSPACE, created.id, 1) }

        refused.code shouldBe PipelineErrorCodes.Versioning.LAST_RELEASE
        versionsOf(created.id) shouldContainExactly listOf(1 to "RELEASED", 2 to "DRAFT")
    }

    @Test
    fun `(iii) a preserved-number import onto an absent entity lands version 3 alone`() {
        val id = importChild(CHILD, version = 3)

        versionsOf(id) shouldContainExactly listOf(3 to "RELEASED")
        checkNotNull(repository.findByIdAnyStatus(WORKSPACE, id)).currentVersion shouldBe 3
    }

    @Test
    fun `(iv) an import above a never-released draft v1, then the draft purge, leaves version 2 alone`() {
        val created = createChild(CHILD, CreateLifecycle.DRAFT)
        importChild(CHILD, version = 2, id = created.id)
        versionsOf(created.id) shouldContainExactly listOf(1 to "DRAFT", 2 to "RELEASED")

        children.purgeVersion(WORKSPACE, created.id, 1).shouldBeInstanceOf<PipelineReleaseService.Purged.Version>()

        versionsOf(created.id) shouldContainExactly listOf(2 to "RELEASED")
    }

    // ---------------------------------------------------------------- A.2 the code a parent save answers

    @Test
    fun `a parent pinning a missing version of a v1-less child is pipeline_version_not_found`() {
        importChild(CHILD, version = 3)

        saveParentPinning(CHILD, 2) shouldContainExactly listOf(PipelineErrorCodes.Validation.PIPELINE_VERSION_NOT_FOUND)
    }

    @Test
    fun `the same answer for the child the purge path left without v1`() {
        val created = createChild(CHILD, CreateLifecycle.DRAFT)
        importChild(CHILD, version = 2, id = created.id)
        children.purgeVersion(WORKSPACE, created.id, 1)

        saveParentPinning(CHILD, 5) shouldContainExactly listOf(PipelineErrorCodes.Validation.PIPELINE_VERSION_NOT_FOUND)
    }

    @Test
    fun `control - a child that is not registered is pipeline_not_found`() {
        saveParentPinning(CHILD, 2) shouldContainExactly listOf(PipelineErrorCodes.Validation.PIPELINE_NOT_FOUND)
    }

    @Test
    fun `control - pinning the version the v1-less child does hold saves`() {
        importChild(CHILD, version = 3)

        saveParentPinning(CHILD, 3) shouldContainExactly emptyList()
    }

    @Test
    fun `control - the same name registered in ANOTHER workspace is still pipeline_not_found here`() {
        // v1 RELEASED there: an unscoped probe — at v1 or at the entity — would call the name known.
        createChild(CHILD, CreateLifecycle.RELEASED, workspace = OTHER_WORKSPACE)

        withClue("another workspace's registry must not make the name 'known' (workspaces design §3)") {
            saveParentPinning(CHILD, 2) shouldContainExactly listOf(PipelineErrorCodes.Validation.PIPELINE_NOT_FOUND)
        }
    }

    // ---------------------------------------------------------------- fixture

    /** Saves a parent whose one PIPELINE node pins `name@version`; the failure codes, empty when it saved. */
    private fun saveParentPinning(
        name: String,
        version: Int,
    ): List<String> {
        val parent = pipeline(PARENT, listOf(pipelineNode(PipelineNodeRef(name, version))))
        return try {
            parents.create(WORKSPACE, body(parent), actor, WriteSurface.SESSION)
            emptyList()
        } catch (refused: PipelineValidationException) {
            withClue("a refused save writes nothing") { repository.findByNameAnyStatus(WORKSPACE, PARENT) shouldBe null }
            refused.result.failures.map { it.code }
        }
    }

    private fun createChild(
        name: String,
        lifecycle: CreateLifecycle,
        workspace: UUID = WORKSPACE,
    ) = repository.create(
        workspace,
        NewPipeline.from(child(name), ownerId = actor),
        body(child(name)),
        actor,
        lifecycle,
        WriteSurface.SESSION,
    )

    /** The §9.2 preserved-number import of [name]'s child body at [version]; returns the entity id. */
    private fun importChild(
        name: String,
        version: Int,
        id: UUID? = null,
        workspace: UUID = WORKSPACE,
    ): UUID {
        val pipeline = child(name, "imported v$version")
        val tree = MAPPER.readTree(body(pipeline)) as ObjectNode
        id?.let { tree.put("id", it.toString()) }
        tree.put("version", version)
        tree.put("body_hash", repository.computeBodyHash(body(pipeline)))
        return imports.import(MAPPER.writeValueAsString(tree), workspace, actor).record.id
    }

    /** `(version, status)` rows the registry holds for [pipelineId], read straight from the table. */
    private fun versionsOf(pipelineId: UUID): List<Pair<Int, String>> =
        jdbc.query(
            "SELECT version, status FROM pipeline_versions WHERE pipeline_id = :id ORDER BY version",
            mapOf("id" to pipelineId),
        ) { rs, _ -> rs.getInt("version") to rs.getString("status") }

    private fun body(pipeline: Pipeline) = serializer.write(pipeline)

    private fun child(
        name: String,
        description: String = "",
    ) = pipeline(
        name,
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
        description,
    )

    private fun pipelineNode(ref: PipelineNodeRef) =
        Node(
            id = "run_child",
            description = "Runs the child pipeline.",
            type = NodeType.PIPELINE,
            source = "",
            template = TemplateRef(),
            output = null,
            dependsOn = emptyList(),
            pipeline = ref,
        )

    private fun pipeline(
        name: String,
        nodes: List<Node>,
        description: String = "",
    ) = Pipeline(
        schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
        name = name,
        displayName = name,
        description = description,
        settings = PipelineSettings(),
        parameters = emptyMap(),
        nodes = nodes,
    )

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        val OTHER_WORKSPACE: UUID = UUID.fromString("0723aaaa-0000-0000-0000-000000000002")
        const val CHILD = "test/probe_child"
        const val PARENT = "test/probe_parent"
        const val MAX_DEPTH = 5
        val MAPPER = ObjectMapper()
    }
}
