package co.datapipelines.web.ui

import co.datapipelines.application.dependencies.PipelineReleaseFamily
import co.datapipelines.application.dependencies.PostgresStatementCeiling
import co.datapipelines.application.dependencies.ReleasePlanningDeadline
import co.datapipelines.application.dependencies.ReleasePlanningWalk
import co.datapipelines.application.dependencies.datasource.DatasourceDependencyFamily
import co.datapipelines.application.dependencies.template.TemplateReleaseProvider
import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleasePrerequisite
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.web.SharedPostgres
import co.datapipelines.web.release.ReleaseArtifactSeed
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import java.time.Duration
import java.util.UUID

/**
 * #876 §F.2 "Unavailable prerequisite" on a real PostgreSQL, through the PRODUCTION planner the dialog GET uses
 * ([ReleasePreviews] over [ReleasePlanningWalk.planned] and the production descriptors: the pipeline arm, the template
 * provider, G3's datasource family). Root pipeline `ops/load` v2 DRAFT whose only artifact dependency is template
 * `ops/q` v1 RELEASED and whose DQL node names datasource `gone`, run twice: (1) no datasource named `gone` exists;
 * (2) `gone` exists and is granted only to ANOTHER workspace. Both runs: the plan is complete with a tree hash, the
 * preview's prerequisite is `available: false, readonly: null`, the two dialog rows are identical (no oracle between
 * absent and foreign), and the model is not submittable. The control: `gone` granted to this workspace read-only is
 * available, read-only, and submittable — so the refusal is the prerequisite term, not a broken fixture.
 *
 * The preview must come from the planner's ONE planning read, never from the closure alone: a preview built from
 * `ReleasePlan.session(closure)` without the prerequisites answers `complete` and submittable here and goes red.
 */
class ReleasePreviewPrerequisiteIntegrationTest {
    /** ONE DataSource for the seed, the planning transaction and the ceiling: the ceiling's SET LOCAL binds to it. */
    private val dataSource = SharedPostgres.dataSource()
    private val jdbc = NamedParameterJdbcTemplate(dataSource)
    private val seed = ReleaseArtifactSeed(jdbc)
    private val manager = DataSourceTransactionManager(dataSource)
    private val ceiling = PostgresStatementCeiling(dataSource)
    private var otherWorkspaceId: UUID = UUID.randomUUID()

    private val registry =
        FamilyRegistry.production(
            listOf(
                PipelineReleaseFamily.descriptor(PipelineRepository(ceiling.jdbc), ceiling),
                TemplateReleaseProvider(TemplateRepository(ceiling.jdbc), ceiling).descriptor(),
                DatasourceDependencyFamily.descriptor(DatasourceRepository(ceiling.jdbc), ceiling),
            ),
        )
    private val previews =
        ReleasePreviews(
            ReleasePlanningWalk(
                registry,
                ReleaseBounds(),
                ReleasePlanningWalk.planningTransaction(manager),
                ReleasePlanningWalk.speculativeReads(manager),
                ReleasePlanningDeadline.after(Duration.ofSeconds(BUDGET_SECONDS)),
            ),
            ReleaseBounds(),
        )

    @BeforeEach
    fun reset() {
        seed.reset()
        otherWorkspaceId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (:id, :name, 'Other', :owner)",
            mapOf("id" to otherWorkspaceId, "name" to "w_${otherWorkspaceId.toString().replace("-", "")}", "owner" to seed.userId),
        )
        seed.template(TEMPLATE, 1, PipelineVersionStatus.RELEASED)
        seed.pipeline(ROOT, 2, PipelineVersionStatus.DRAFT, templates = listOf(TEMPLATE to 1), source = GONE)
    }

    @Test
    fun `an absent datasource and one granted only to another workspace are the same unavailable row, never submittable`() {
        val absent = previewOfRoot()
        datasource(GONE, grantedTo = otherWorkspaceId, readonly = true)
        val foreign = previewOfRoot()

        listOf("absent" to absent, "foreign" to foreign).forEach { (run, preview) ->
            withClue(run) {
                preview.plan.complete shouldBe true
                preview.plan.treeHash.shouldNotBeNull()
                preview.prerequisites shouldBe listOf(ReleasePrerequisite(GONE, available = false, readonly = null))
                val model = ReleaseDialogModel.of(preview)
                model.submittable shouldBe false
                model.prerequisites.single().text shouldBe "Datasource $GONE is not available"
            }
        }
        // No oracle: the two runs' rows, their wire rows and their tree hashes are equal.
        ReleaseDialogModel.of(absent).prerequisites shouldBe ReleaseDialogModel.of(foreign).prerequisites
        absent.prerequisites.map { it.toWire() } shouldBe foreign.prerequisites.map { it.toWire() }
        absent.plan.treeHash shouldBe foreign.plan.treeHash
    }

    @Test
    fun `the control - granted to this workspace read-only, the same root is available, read-only and submittable`() {
        datasource(GONE, grantedTo = seed.workspaceId, readonly = true)

        val preview = previewOfRoot()

        preview.prerequisites shouldBe listOf(ReleasePrerequisite(GONE, available = true, readonly = true))
        val model = ReleaseDialogModel.of(preview)
        model.submittable shouldBe true
        model.prerequisites.single().text shouldBe "Datasource $GONE — available, read-only"
        withClue("the prerequisite is a row, never a node") {
            model.rows.map { it.family }.toSet() shouldBe setOf("pipeline", "template")
        }
    }

    @Test
    fun `the dialog model the GET renders is the same one - the planner's prerequisites reach it`() {
        val model = previews.dialog(ReleaseFamily.PIPELINE, seed.workspaceId, ROOT, 2, seed.userId)

        model.refusal shouldBe null
        model.submittable shouldBe false
        model.unavailable.map { it.name } shouldBe listOf(GONE)
    }

    private fun previewOfRoot(): ReleasePreview = previews.preview(ReleaseFamily.PIPELINE, seed.workspaceId, ROOT, 2, seed.userId)

    /** A live datasource [name] granted to [grantedTo] — the application seed's SQL. */
    private fun datasource(
        name: String,
        grantedTo: UUID,
        readonly: Boolean,
    ) {
        jdbc.update(
            "INSERT INTO datasources (name, display_name, dialect, jdbc_url, credential_kind, created_by, is_readonly, is_deleted) " +
                "VALUES (:name, :display, 'H2', 'jdbc:h2:mem:x', 'none', :by, :readonly, FALSE)",
            mapOf("name" to name, "display" to "DS $name", "by" to seed.userId, "readonly" to readonly),
        )
        jdbc.update(
            "INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by) VALUES (:name, :ws, :by)",
            mapOf("name" to name, "ws" to grantedTo, "by" to seed.userId),
        )
    }

    private companion object {
        const val ROOT = "ops/load"
        const val TEMPLATE = "ops/q"
        const val GONE = "gone"
        const val BUDGET_SECONDS = 30L
    }
}
