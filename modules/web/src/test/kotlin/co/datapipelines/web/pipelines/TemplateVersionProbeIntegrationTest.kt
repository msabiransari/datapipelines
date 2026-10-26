package co.datapipelines.web.pipelines

import co.datapipelines.application.templates.TemplateUsage
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.NewPipeline
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineDraftService
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineSerializer
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.PipelineValidationException
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.pipeline.TemplateIds
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.templates.CitableFacts
import co.datapipelines.templates.ScriptBlocks
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateImplementsRepository
import co.datapipelines.templates.TemplateJson
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.NO_DASHBOARDS
import co.datapipelines.web.NO_EXCLUSIVE_DRAFT_TEMPLATES
import co.datapipelines.web.SharedPostgres
import co.datapipelines.web.templates.TemplateImportService
import co.datapipelines.web.templates.TemplateReleaseService
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * #733 — can a template keep a live version after its version 1 is gone, and what does a pipeline
 * RESTORE whose pinned template version is missing answer on such a template?
 *
 * [co.datapipelines.pipeline.PipelineRestorePins] tells `template_version_not_found` ("has no
 * version N") from `template_not_found` ("not in the registry"). Each path that could leave a
 * template entity without version 1 is driven through the shipped verbs on the real registry
 * ([SharedPostgres], the app's migrations): [TemplateReleaseService.purgeVersion] and
 * [TemplateImportService]'s preserved-number import (versioning §9.2), over the real
 * [TemplateRepository]. The restore then runs [PipelineService.restoreVersion] with the PRODUCTION
 * `TemplateVersionStatuses` (`templates.findVersionStatus`, as `PipelineLifecycleConfiguration` wires
 * it) and the PRODUCTION entity port (`templates.existsId`).
 *
 * Doubles outside the question: the template validator answers its input (a template body is not
 * under test), the usage evidence is empty (no pinners block a purge), and the pipeline validator is
 * STRICT — a restore recheck is metadata only and must never consult it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemplateVersionProbeIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var templates: TemplateRepository
    private lateinit var pipelines: PipelineRepository
    private lateinit var releases: TemplateReleaseService
    private lateinit var imports: TemplateImportService
    private lateinit var restores: PipelineService

    private val actor = UUID.randomUUID()
    private val serializer = PipelineSerializer()
    private val authoring = AuthoringGuard(true)

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
    }

    @BeforeEach
    fun setUp() {
        templates = TemplateRepository(jdbc)
        pipelines = PipelineRepository(jdbc)
        // The CASCADE reaches workspaces (created_by), templates and pipelines, so both workspaces are re-seeded.
        jdbc.jdbcTemplate.execute("TRUNCATE templates, pipelines, users CASCADE")
        for ((id, name) in listOf(WORKSPACE to "default", OTHER_WORKSPACE to "other")) {
            jdbc.jdbcTemplate.execute(
                "INSERT INTO workspaces (id, name, display_name) VALUES ('$id', '$name', '$name') ON CONFLICT DO NOTHING",
            )
        }
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to actor, "email" to "u$actor@example.com", "sub" to "sub-$actor"),
        )
        val validator = mockk<TemplateValidator>()
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }
        val noPins = TemplateUsage.Pins(emptyList(), emptyList(), emptyList())
        val usage = mockk<TemplateUsage>()
        every { usage.liveVersionPins(any(), any(), any()) } returns noPins
        every { usage.everPins(any(), any()) } returns noPins
        releases = TemplateReleaseService(templates, validator, authoring, usage)
        imports = TemplateImportService(templates, validator, CitableFacts.NONE, TemplateImplementsRepository(jdbc))

        val statuses = TemplateVersionStatuses { workspaceId, id, version -> templates.findVersionStatus(workspaceId, id, version) }
        val pipelineValidator = mockk<PipelineValidator>()
        restores =
            PipelineService(
                pipelines = pipelines,
                validator = pipelineValidator,
                drafts = PipelineDraftService(pipelines, authoring),
                releases = PipelineReleaseService(pipelines, statuses, pipelineValidator, authoring, dashboards = NO_DASHBOARDS),
                authoring = authoring,
                draftTemplates = NO_EXCLUSIVE_DRAFT_TEMPLATES,
                dashboards = NO_DASHBOARDS,
                templateStatuses = statuses,
                // The production bean's answer (PipelineLifecycleConfiguration.templateIds).
                templateIds = TemplateIds { workspaceId, id -> templates.existsId(workspaceId, id) },
            )
    }

    // ---------------------------------------------------------------- A.1 reachability

    @Test
    fun `(i) purging the sole draft v1 takes the ENTITY - no v1-less entity is left`() {
        templates.create(WORKSPACE, draft(TEMPLATE), actor, CreateLifecycle.DRAFT, WriteSurface.SESSION)
        versionsOf(WORKSPACE, TEMPLATE) shouldContainExactly listOf(1 to "DRAFT")

        releases.purgeVersion(WORKSPACE, TEMPLATE, 1)

        templates.existsId(WORKSPACE, TEMPLATE) shouldBe false
        versionsOf(WORKSPACE, TEMPLATE) shouldContainExactly emptyList()
    }

    @Test
    fun `(ii) a RELEASED v1 under a draft v2 is never purged - last_release`() {
        val v1 = templates.create(WORKSPACE, draft(TEMPLATE), actor, CreateLifecycle.RELEASED, WriteSurface.SESSION)
        checkNotNull(
            templates.createDraft(WORKSPACE, TEMPLATE, draft(TEMPLATE, body = "SELECT 2"), v1.bodyHash, actor, WriteSurface.SESSION),
        )
        versionsOf(WORKSPACE, TEMPLATE) shouldContainExactly listOf(1 to "RELEASED", 2 to "DRAFT")

        val refused = shouldThrow<DatapipelinesException> { releases.purgeVersion(WORKSPACE, TEMPLATE, 1) }

        refused.code shouldBe PipelineErrorCodes.Template.VERSION_LAST_RELEASE
        versionsOf(WORKSPACE, TEMPLATE) shouldContainExactly listOf(1 to "RELEASED", 2 to "DRAFT")
    }

    @Test
    fun `(iii) a preserved-number import onto an absent entity lands version 3 alone`() {
        importPreserved(TEMPLATE, version = 3)

        versionsOf(WORKSPACE, TEMPLATE) shouldContainExactly listOf(3 to "RELEASED")
        templates.existsId(WORKSPACE, TEMPLATE) shouldBe true
    }

    @Test
    fun `(iv) an import above a never-released draft v1, then the draft purge, leaves version 2 alone`() {
        templates.create(WORKSPACE, draft(TEMPLATE), actor, CreateLifecycle.DRAFT, WriteSurface.SESSION)
        importPreserved(TEMPLATE, version = 2)
        versionsOf(WORKSPACE, TEMPLATE) shouldContainExactly listOf(1 to "DRAFT", 2 to "RELEASED")

        releases.purgeVersion(WORKSPACE, TEMPLATE, 1)

        versionsOf(WORKSPACE, TEMPLATE) shouldContainExactly listOf(2 to "RELEASED")
        templates.existsId(WORKSPACE, TEMPLATE) shouldBe true
    }

    // ---------------------------------------------------------------- A.2 the code the restore answers

    @Test
    fun `restoring a parent that pins a missing version of a v1-less template is template_version_not_found`() {
        importPreserved(TEMPLATE, version = 3)
        val parent = discardedParentPinning(TEMPLATE, 2)

        restoreRefusalCodes(parent) shouldContainExactly listOf(PipelineErrorCodes.Validation.TEMPLATE_VERSION_NOT_FOUND)
    }

    @Test
    fun `the same answer for the template the draft purge left without v1`() {
        templates.create(WORKSPACE, draft(TEMPLATE), actor, CreateLifecycle.DRAFT, WriteSurface.SESSION)
        importPreserved(TEMPLATE, version = 2)
        releases.purgeVersion(WORKSPACE, TEMPLATE, 1)
        val parent = discardedParentPinning(TEMPLATE, 5)

        restoreRefusalCodes(parent) shouldContainExactly listOf(PipelineErrorCodes.Validation.TEMPLATE_VERSION_NOT_FOUND)
    }

    @Test
    fun `control - a template that is not registered is template_not_found`() {
        val parent = discardedParentPinning(TEMPLATE, 2)

        restoreRefusalCodes(parent) shouldContainExactly listOf(PipelineErrorCodes.Validation.TEMPLATE_NOT_FOUND)
    }

    @Test
    fun `control - pinning the version the v1-less template does hold restores`() {
        importPreserved(TEMPLATE, version = 3)
        val parent = discardedParentPinning(TEMPLATE, 3)

        restores.restoreVersion(WORKSPACE, parent.id, 1).record.currentVersion shouldBe 1
    }

    @Test
    fun `control - the same id registered in ANOTHER workspace is still template_not_found here`() {
        // v1 RELEASED there: an unscoped probe — at v1 or at the entity — would call the id known.
        templates.create(OTHER_WORKSPACE, draft(TEMPLATE), actor, CreateLifecycle.RELEASED, WriteSurface.SESSION)
        val parent = discardedParentPinning(TEMPLATE, 2)

        withClue("another workspace's registry must not make the id 'known' (workspaces design §3)") {
            restoreRefusalCodes(parent) shouldContainExactly listOf(PipelineErrorCodes.Validation.TEMPLATE_NOT_FOUND)
        }
    }

    // ---------------------------------------------------------------- fixture

    /** The restore's refusal codes; a refusal writes nothing (the version stays DISCARDED). */
    private fun restoreRefusalCodes(parent: PipelineRecord): List<String> {
        val refused = shouldThrow<PipelineValidationException> { restores.restoreVersion(WORKSPACE, parent.id, 1) }
        withClue("a refused restore writes nothing") { pipelineVersionStatus(parent.id) shouldBe "DISCARDED" }
        return refused.result.failures.map { it.code }
    }

    /** A RELEASED parent pinning `template@version`, then DISCARDED — the version a restore brings back. */
    private fun discardedParentPinning(
        template: String,
        version: Int,
    ): PipelineRecord {
        val parent = pipeline(PARENT, TemplateRef(template, version))
        // The repository does not validate, so the parent may pin a version the registry does not hold.
        val record =
            pipelines.create(
                WORKSPACE,
                NewPipeline.from(parent, ownerId = actor),
                serializer.write(parent),
                actor,
                CreateLifecycle.RELEASED,
                WriteSurface.SESSION,
            )
        checkNotNull(pipelines.discardVersion(WORKSPACE, record.id, record.name, 1, actor, authoring.developmentPosture)) {
            "discard of ${record.name} v1 refused"
        }
        return record
    }

    /** The §9.2 preserved-number import of [id]'s body at [version], through the shipped import verb. */
    private fun importPreserved(
        id: String,
        version: Int,
    ) {
        val draft = draft(id, body = "SELECT $version")
        val entry = TemplateJson.objectMapper().valueToTree<ObjectNode>(draft)
        entry.put("version", version)
        entry.put(
            "body_hash",
            templates.computeBodyHash(
                engine = draft.engine,
                dialect = draft.dialect?.wire,
                isLibrary = draft.isLibrary,
                importsJson = TemplateJson.writeImports(draft.imports),
                body = draft.body,
                contractJson = ScriptBlocks.writeContract(draft.contract),
                invariantsJson = ScriptBlocks.writeInvariants(draft.invariants),
                testsJson = ScriptBlocks.writeTests(draft.tests),
            ),
        )
        val body = TemplateJson.objectMapper().createObjectNode().also { it.putArray("templates").add(entry) }
        imports.import(body.toString(), WORKSPACE, actor)
    }

    /** `(version, status)` rows the registry holds for [id] in [workspace], read straight from the table. */
    private fun versionsOf(
        workspace: UUID,
        id: String,
    ): List<Pair<Int, String>> =
        jdbc.query(
            "SELECT v.version, v.status FROM template_versions v JOIN templates t ON t.id = v.template_id" +
                " WHERE t.name = :name AND t.workspace_id = :workspaceId ORDER BY v.version",
            mapOf("name" to id, "workspaceId" to workspace),
        ) { rs, _ -> rs.getInt("version") to rs.getString("status") }

    private fun pipelineVersionStatus(pipelineId: UUID): String =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT status FROM pipeline_versions WHERE pipeline_id = :id AND version = 1",
                mapOf("id" to pipelineId),
                String::class.java,
            ),
        )

    private fun draft(
        id: String,
        body: String = "SELECT 1",
    ) = TemplateDraft(
        id = id,
        // The wire carries an explicit type: a null one is `type_invalid` on import (creation resolves it, import does not).
        type = TemplateType.SQL,
        dialect = Dialect.POSTGRES,
        displayName = "Probe",
        description = "Template version probe fixture.",
        body = body,
    )

    private fun pipeline(
        name: String,
        template: TemplateRef,
    ) = Pipeline(
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
                    template = template,
                    output = NodeOutput.Caller,
                    dependsOn = emptyList(),
                ),
            ),
    )

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        val OTHER_WORKSPACE: UUID = UUID.fromString("0733aaaa-0000-0000-0000-000000000002")
        const val TEMPLATE = "test/probe_tpl.sql"
        const val PARENT = "test/probe_restore_parent"
    }
}
