package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * #517 round three / #719 — a pipeline RESTORE rechecks the restored body's outbound pins under the workspace writer guard
 * (release-dependencies record §12.1 "Restore DISCARDED", RULED 2026-10-25). A restore lands the version RELEASED without a
 * human's consent, and a DISCARDED version protects nothing: the template versions and child pipeline versions it pins may
 * have been discarded or purged since. A missing or DISCARDED pin refuses with the family's validation code (400, the D7
 * precedent); a present-but-DRAFT pin refuses `pipeline.release.dependency_not_released` (409, the import-landing
 * precedent) in the shared blockers shape with reason `not_released`. Only dependency failures block.
 *
 * Real rows over the shipped schema ([SharedPostgres]); every pipeline is seeded through the repository, which does not
 * validate, so a parent can pin whatever a case needs. The service's validator renders through a renderer that FAILS IF
 * TOUCHED: the recheck is metadata only — no render, no check gate, no customer connection under the guard.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PipelineRestorePinsIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: PipelineRepository
    private lateinit var owner: UUID
    private val serializer = PipelineSerializer()
    private val authoring = AuthoringGuard(true)

    /** The template registry's answer per `id@version`; absent means the template or that version does not exist. */
    private val templateStatuses = mutableMapOf<Pair<String, Int>, PipelineVersionStatus>()
    private val statusesPort = TemplateVersionStatuses { _, id, version -> templateStatuses[id to version] }

    /** The entity row: a template is registered when the registry holds ANY version of it, version 1 or not (#733). */
    private val idsPort = TemplateIds { _, id -> templateStatuses.keys.any { it.first == id } }

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
    }

    @BeforeEach
    fun setUp() {
        repository = PipelineRepository(jdbc)
        jdbc.jdbcTemplate.execute("TRUNCATE pipelines, users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE', 'default', 'Default') ON CONFLICT DO NOTHING",
        )
        owner =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO users (email, display_name, provider, provider_subject) " +
                        "VALUES ('owner@example.com', 'Owner', 'google', 'sub-1') RETURNING id",
                    emptyMap<String, Any>(),
                    UUID::class.java,
                ),
            )
        templateStatuses.clear()
        templateStatuses[ORDERS to 1] = PipelineVersionStatus.RELEASED
    }

    private fun service(
        maxBlockers: Int = 100,
        validator: PipelineValidator = Fixtures.validator(templates = UntouchableTemplates),
    ): PipelineService =
        PipelineService(
            pipelines = repository,
            validator = validator,
            drafts = PipelineDraftService(repository, authoring),
            releases = PipelineReleaseService(repository, statusesPort, validator, authoring, dashboards = NoDashboards),
            authoring = authoring,
            draftTemplates = NoDraftTemplates,
            dashboards = NoDashboards,
            templateStatuses = statusesPort,
            templateIds = idsPort,
            maxBlockers = maxBlockers,
        )

    private fun seed(
        pipeline: Pipeline,
        lifecycle: CreateLifecycle = CreateLifecycle.RELEASED,
    ): PipelineRecord =
        repository.create(WORKSPACE, NewPipeline.from(pipeline, owner), serializer.write(pipeline), owner, lifecycle, WriteSurface.SESSION)

    private fun discard(record: PipelineRecord) {
        checkNotNull(repository.discardVersion(WORKSPACE, record.id, record.name, 1, owner, authoring.developmentPosture)) {
            "discard of ${record.name} v1 refused"
        }
    }

    private fun childNode(
        name: String,
        version: Int = 1,
        id: String = "run_child",
    ): Node = Fixtures.node(id = id, type = NodeType.PIPELINE, template = TemplateRef()).copy(pipeline = PipelineNodeRef(name, version))

    private fun templateNode(
        id: String,
        template: TemplateRef,
    ): Node = Fixtures.node(id = id, template = template)

    /** A RELEASED parent pinning [nodes], then DISCARDED — the version a restore brings back. */
    private fun discardedParent(vararg nodes: Node): PipelineRecord =
        seed(Fixtures.pipeline(name = PARENT, nodes = nodes.toList())).also(::discard)

    private data class Stored(
        val status: String,
        val current: Int?,
        val body: String,
        val discardedAt: Any?,
    )

    private fun stored(id: UUID): Stored {
        val row =
            jdbc.queryForMap(
                "SELECT v.status, p.current_version, v.body_json::text AS body, v.discarded_at FROM pipeline_versions v " +
                    "JOIN pipelines p ON p.id = v.pipeline_id WHERE p.id = :id AND v.version = 1",
                mapOf("id" to id),
            )
        return Stored(
            row.getValue("status").toString(),
            row["current_version"] as Int?,
            row.getValue("body").toString(),
            row["discarded_at"],
        )
    }

    /** The refusal, with the proof that nothing moved: status, pointer, body and discard stamp all as before. */
    private fun refusedWithoutWrite(
        parent: PipelineRecord,
        service: PipelineService = service(),
    ): DatapipelinesException {
        val before = stored(parent.id)
        before.status shouldBe "DISCARDED"
        val error = shouldThrow<DatapipelinesException> { service.restoreVersion(WORKSPACE, parent.id, 1) }
        withClue("a refused restore writes nothing") { stored(parent.id) shouldBe before }
        return error
    }

    private fun DatapipelinesException.failures(): List<ValidationFailure> {
        shouldBeInstanceOf<PipelineValidationException>()
        return result.failures
    }

    @Test
    fun `control - a discarded parent whose template and child are RELEASED restores and the pointer comes back`() {
        seed(Fixtures.pipeline(name = CHILD))
        val parent = discardedParent(templateNode("fetch_orders", TemplateRef(ORDERS, 1)), childNode(CHILD))

        val restored = service().restoreVersion(WORKSPACE, parent.id, 1)

        restored.record.currentVersion shouldBe 1
        stored(parent.id).status shouldBe "RELEASED"
    }

    @Test
    fun `a template pin discarded meanwhile refuses template_version_not_found with reason discarded`() {
        val parent = discardedParent(templateNode("fetch_orders", TemplateRef(ORDERS, 1)))
        templateStatuses[ORDERS to 1] = PipelineVersionStatus.DISCARDED

        val error = refusedWithoutWrite(parent)

        error.code shouldBe Validation.TEMPLATE_VERSION_NOT_FOUND
        val failure = error.failures().single()
        failure.path shouldBe "nodes[0].template"
        failure.details shouldBe mapOf("template" to ORDERS, "version" to 1, "reason" to "discarded")
    }

    @Test
    fun `a purged template version and a vanished template refuse with the save rules' two codes`() {
        templateStatuses[ORDERS to 2] = PipelineVersionStatus.RELEASED
        val versionGone = discardedParent(templateNode("fetch_orders", TemplateRef(ORDERS, 3)))

        val missingVersion = refusedWithoutWrite(versionGone)
        missingVersion.code shouldBe Validation.TEMPLATE_VERSION_NOT_FOUND
        missingVersion.failures().single().details shouldBe mapOf("template" to ORDERS, "version" to 3)

        templateStatuses.clear()
        val missingTemplate = refusedWithoutWrite(versionGone)
        missingTemplate.code shouldBe Validation.TEMPLATE_NOT_FOUND
        missingTemplate.failures().single().details shouldBe mapOf("template" to ORDERS)
    }

    @Test
    fun `a template that holds no version 1 is still told from a vanished one - the entity row, not a version-1 probe`() {
        // A preserved-number import or a draft purge leaves a registered template at version 3 alone (versioning §9.2, §5.4).
        templateStatuses.clear()
        templateStatuses[ORDERS to 3] = PipelineVersionStatus.RELEASED
        val parent = discardedParent(templateNode("fetch_orders", TemplateRef(ORDERS, 2)))

        val error = refusedWithoutWrite(parent)

        error.code shouldBe Validation.TEMPLATE_VERSION_NOT_FOUND
        error.failures().single().details shouldBe mapOf("template" to ORDERS, "version" to 2)
    }

    @Test
    fun `a child pipeline version discarded meanwhile refuses pipeline_reference_not_released carrying its status`() {
        val child = seed(Fixtures.pipeline(name = CHILD))
        val parent = discardedParent(childNode(CHILD))
        discard(child)

        val error = refusedWithoutWrite(parent)

        error.code shouldBe Validation.PIPELINE_REFERENCE_NOT_RELEASED
        val failure = error.failures().single()
        failure.path shouldBe "nodes[0].pipeline.version"
        failure.details shouldBe mapOf("node" to "run_child", "pipeline" to CHILD, "pipeline_version" to 1, "status" to "DISCARDED")
    }

    @Test
    fun `a missing child pipeline and a missing child version refuse with the composition rules' two codes`() {
        seed(Fixtures.pipeline(name = CHILD))
        val parent = discardedParent(childNode(CHILD, version = 4), childNode(GONE, id = "run_gone"))

        val error = refusedWithoutWrite(parent)

        error.failures().map { it.code to it.path } shouldContainExactly
            listOf(
                Validation.PIPELINE_VERSION_NOT_FOUND to "nodes[0].pipeline",
                Validation.PIPELINE_NOT_FOUND to "nodes[1].pipeline",
            )
    }

    @Test
    fun `repair is bottom-up - restore the child first and the parent's restore then passes`() {
        val child = seed(Fixtures.pipeline(name = CHILD))
        val parent = discardedParent(childNode(CHILD))
        discard(child)
        refusedWithoutWrite(parent)

        service().restoreVersion(WORKSPACE, child.id, 1)
        service().restoreVersion(WORKSPACE, parent.id, 1).record.currentVersion shouldBe 1
    }

    @Test
    fun `a rule that tightened since is not a dangling pin - a datasource that no longer resolves does not block`() {
        val body = Fixtures.pipeline(name = PARENT, nodes = listOf(templateNode("fetch_orders", TemplateRef(ORDERS, 1))))
        val parent = seed(body).also(::discard)
        // The datasource the node writes through no longer resolves: the save rules refuse the body today, but every pin
        // in it is alive — only dependency failures block a restore.
        val strict = Fixtures.validator(datasources = StubDatasources(emptyMap()))
        withClue("the save rules would refuse this body today") {
            strict.validate(body, WORKSPACE).codes shouldContain Validation.UNKNOWN_DATASOURCE
        }

        service(validator = strict).restoreVersion(WORKSPACE, parent.id, 1).record.currentVersion shouldBe 1
    }

    @Test
    fun `a version that is not discarded keeps the lifecycle's own refusal - the recheck does not answer for it`() {
        val parent = seed(Fixtures.pipeline(name = PARENT, nodes = listOf(templateNode("fetch_orders", TemplateRef(ORDERS, 1)))))
        templateStatuses[ORDERS to 1] = PipelineVersionStatus.DISCARDED

        shouldThrow<DatapipelinesException> { service().restoreVersion(WORKSPACE, parent.id, 1) }
            .code shouldBe PipelineErrorCodes.Versioning.NOT_DISCARDED
    }

    @Test
    fun `defensive - pins present but DRAFT (planted past the save rules) refuse dependency_not_released in the shared shape`() {
        // Unreachable for a once-released version today: the repository write below skips every save rule, planting a
        // RELEASED-then-DISCARDED parent over a DRAFT template version and a DRAFT child — the state this branch defends.
        templateStatuses[DRAFT_TEMPLATE to 2] = PipelineVersionStatus.DRAFT
        seed(Fixtures.pipeline(name = CHILD), CreateLifecycle.DRAFT)
        val parent =
            discardedParent(
                templateNode("first", TemplateRef(DRAFT_TEMPLATE, 2)),
                childNode(CHILD),
                templateNode("second", TemplateRef(DRAFT_TEMPLATE, 2)),
                templateNode("fetch_orders", TemplateRef(ORDERS, 1)),
            )

        val error = refusedWithoutWrite(parent)

        error.code shouldBe "pipeline.release.dependency_not_released"
        error.details shouldBe
            mapOf(
                "root" to mapOf("node_id" to "n0", "family" to "pipeline", "name" to PARENT, "version" to 1),
                "blockers" to
                    listOf(
                        mapOf(
                            "node_id" to "n1",
                            "family" to "template",
                            "name" to DRAFT_TEMPLATE,
                            "version" to 2,
                            "status" to "DRAFT",
                            "reason" to "not_released",
                            "paths" to listOf("nodes[0].template", "nodes[2].template"),
                        ),
                        mapOf(
                            "node_id" to "n2",
                            "family" to "pipeline",
                            "name" to CHILD,
                            "version" to 1,
                            "status" to "DRAFT",
                            "reason" to "not_released",
                            "paths" to listOf("nodes[1].pipeline"),
                        ),
                    ),
                "blockers_truncated" to false,
            )
    }

    @Test
    fun `a dangling pin answers before a draft one - the 400 wins over the 409`() {
        templateStatuses[DRAFT_TEMPLATE to 2] = PipelineVersionStatus.DRAFT
        val parent = discardedParent(templateNode("first", TemplateRef(DRAFT_TEMPLATE, 2)), templateNode("gone", TemplateRef(GONE, 1)))

        refusedWithoutWrite(parent).code shouldBe Validation.TEMPLATE_NOT_FOUND
    }

    @Test
    fun `the draft blockers truncate at max-blockers`() {
        templateStatuses[DRAFT_TEMPLATE to 2] = PipelineVersionStatus.DRAFT
        seed(Fixtures.pipeline(name = CHILD), CreateLifecycle.DRAFT)
        val parent = discardedParent(templateNode("first", TemplateRef(DRAFT_TEMPLATE, 2)), childNode(CHILD))

        val error = refusedWithoutWrite(parent, service(maxBlockers = 1))

        (error.details["blockers"] as List<*>).map { (it as Map<*, *>)["name"] } shouldContainExactly listOf(DRAFT_TEMPLATE)
        error.details["blockers_truncated"] shouldBe true
    }

    /** The recheck never renders: any port call here is a render or a registry read the restore must not make. */
    private object UntouchableTemplates : TemplateDryRenderer {
        override fun lookup(
            workspaceId: UUID,
            ref: TemplateRef,
        ): TemplateLookup = error("a restore must not consult the renderer (lookup ${ref.id})")

        override fun dryRender(
            workspaceId: UUID,
            ref: TemplateRef,
            context: Map<String, Any?>,
        ): DryRenderOutcome = error("a restore must not render (${ref.id})")

        override fun interpolatedParameters(
            workspaceId: UUID,
            ref: TemplateRef,
            declared: Set<String>,
            guarded: Set<String>,
        ): List<String> = error("a restore must not scan template bodies (${ref.id})")

        override fun boundParameters(
            workspaceId: UUID,
            ref: TemplateRef,
        ): List<String> = error("a restore must not scan template bodies (${ref.id})")
    }

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

    private object NoDraftTemplates : ExclusiveDraftTemplates {
        override fun exclusiveIds(
            workspaceId: UUID,
            pipelineId: UUID,
        ) = emptyList<String>()

        override fun purge(
            workspaceId: UUID,
            templateId: String,
        ) = Unit
    }

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        const val PARENT = "test/restore/parent"
        const val CHILD = "test/restore/child"
        const val GONE = "test/restore/gone"
        const val ORDERS = "test/fetch_orders.sql"
        const val DRAFT_TEMPLATE = "test/restore/draft.sql"
    }
}
