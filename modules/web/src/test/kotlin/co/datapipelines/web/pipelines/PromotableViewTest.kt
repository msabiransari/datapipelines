package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.CurrentPipelineVersion
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.templates.CurrentTemplateVersion
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [PromotableView] — versioning §10.2's four-line rule, per kind, over the one-join reads
 * (178). The pipeline arm was `PromotionService.plan`'s inline rule before 178; the template
 * arm is stated here for the first time (the push closure only implied it), and each of its
 * four clauses gets its own row so a template that regresses on one is named by that clause.
 */
class PromotableViewTest {
    @Test
    fun `a pipeline is promotable iff the target lacks it, or the content differs AND the version is greater`() {
        val local =
            listOf(
                pipeline("absent_there", 1, "h-absent"),
                pipeline("newer", 3, "h-new"),
                pipeline("same_hash_higher_number", 4, "h-shared"),
                pipeline("behind", 1, "h-behind"),
                pipeline("equal", 2, "h-equal-ours"),
            )
        val inventory =
            inventory(
                pipelines =
                    listOf(
                        entry("newer", 2, "h-old"),
                        entry("same_hash_higher_number", 3, "h-shared"),
                        entry("behind", 5, "h-theirs"),
                        entry("equal", 2, "h-equal-theirs"),
                    ),
            )

        val view = PromotableView.of(local, emptyList(), inventory)

        view.pipelines.map { it.name } shouldContainExactly listOf("absent_there", "newer")
        view.pipelines.single { it.name == "absent_there" }.targetVersion shouldBe 0
        view.pipelines.single { it.name == "newer" }.targetVersion shouldBe 2
        view.examinedPipelines shouldBe 5
        withClue("the lens is exactly the listing's names") {
            view.pipelineLens.admits("newer") shouldBe true
            view.pipelineLens.admits("absent_there") shouldBe true
            listOf("same_hash_higher_number", "behind", "equal", "never_heard_of").forEach { view.pipelineLens.admits(it) shouldBe false }
        }
        view.pipeline("newer")?.localVersion shouldBe 3
        view.pipeline("behind") shouldBe null
    }

    @Test
    fun `the template arm is the same four lines with the template's id for its name`() {
        val local =
            listOf(
                template("finance/absent.sql", 1, "t-absent"),
                template("finance/newer.sql", 2, "t-new"),
                template("finance/same_hash.sql", 3, "t-shared"),
                template("finance/behind.sql", 1, "t-behind"),
            )
        val inventory =
            inventory(
                templates =
                    listOf(
                        entry("finance/newer.sql", 1, "t-old"),
                        entry("finance/same_hash.sql", 2, "t-shared"),
                        entry("finance/behind.sql", 4, "t-theirs"),
                    ),
            )

        val view = PromotableView.of(emptyList(), local, inventory)

        view.templates.map { it.name } shouldContainExactly listOf("finance/absent.sql", "finance/newer.sql")
        view.templateLens.admits("finance/newer.sql") shouldBe true
        view.templateLens.admits("finance/same_hash.sql") shouldBe false
        view.templateLens.admits("finance/behind.sql") shouldBe false
        view.examinedTemplates shouldBe 4
        withClue("the two arms are independent: a template's entry never governs a pipeline of the same name") {
            val crossed = PromotableView.of(listOf(pipeline("finance/newer.sql", 2, "p")), local, inventory)
            crossed.pipelineLens.admits("finance/newer.sql") shouldBe true
        }
    }

    @Test
    fun `an empty workspace or an empty target are both legal inputs`() {
        PromotableView.of(emptyList(), emptyList(), inventory()).pipelines shouldBe emptyList()
        val all = PromotableView.of(listOf(pipeline("a", 1, "h")), listOf(template("t/x.sql", 1, "h")), inventory())
        all.pipelines.map { it.name } shouldContainExactly listOf("a")
        all.templates.map { it.name } shouldContainExactly listOf("t/x.sql")
    }

    // ---- #563: a pointer can name the DRAFT under development posture (D60); the RELEASED half of
    // rule 1 is applied HERE, so a draft-pointer object is invisible to a promoter. Per arm.

    @Test
    fun `a pipeline whose pointer names the DRAFT is not promotable and is in no lens, but is still examined`() {
        val view =
            PromotableView.of(
                listOf(
                    pipeline("released_one", 1, "h-one"),
                    pipeline("draft_pointer", 2, "h-two", PipelineVersionStatus.DRAFT),
                    pipeline("discarded_pointer", 3, "h-three", PipelineVersionStatus.DISCARDED),
                ),
                emptyList(),
                inventory(),
            )

        view.pipelines.map { it.name } shouldContainExactly listOf("released_one")
        view.pipelineLens.admits("released_one") shouldBe true
        view.pipelineLens.admits("draft_pointer") shouldBe false
        view.pipelineLens.admits("discarded_pointer") shouldBe false
        view.pipeline("draft_pointer") shouldBe null
        // The count is over POINTER rows, before the status filter: the object exists, it is
        // just not promotable — an empty listing must read as "in sync", not "broken".
        view.examinedPipelines shouldBe 3
    }

    @Test
    fun `a template whose pointer names the DRAFT is not promotable and is in no lens, but is still examined`() {
        val view =
            PromotableView.of(
                emptyList(),
                listOf(
                    template("finance/live.sql", 1, "h-live"),
                    template("finance/draft.sql", 2, "h-draft", PipelineVersionStatus.DRAFT),
                ),
                inventory(),
            )

        view.templates.map { it.name } shouldContainExactly listOf("finance/live.sql")
        view.templateLens.admits("finance/draft.sql") shouldBe false
        view.examinedTemplates shouldBe 2
    }

    @Test
    fun `a set whose pointer names the DRAFT is dropped even though its reader already filters - the arm restates rule 1`() {
        val local =
            listOf(
                co.datapipelines.parameters.CurrentParameterSetVersion(
                    UUID.randomUUID(),
                    "ops/sets/released",
                    "Released",
                    1,
                    "h-set",
                    PipelineVersionStatus.RELEASED,
                ),
                co.datapipelines.parameters.CurrentParameterSetVersion(
                    UUID.randomUUID(),
                    "ops/sets/draft",
                    "Draft",
                    2,
                    "h-set-draft",
                    PipelineVersionStatus.DRAFT,
                ),
            )

        val view = PromotableView.of(emptyList(), emptyList(), inventory(), localParameterSets = local)

        view.parameterSets.map { it.name } shouldContainExactly listOf("ops/sets/released")
        view.parameterSetLens.admits("ops/sets/draft") shouldBe false
        view.parameterSet("ops/sets/draft") shouldBe null
    }

    @Test
    fun `a draft visualization's pin is not a lens name even when an admitted dashboard pins it`() {
        val local =
            listOf(
                visualization("ops/visualizations/released_pin", PipelineVersionStatus.RELEASED),
                visualization("ops/visualizations/draft_pin", PipelineVersionStatus.DRAFT),
            )
        val dashboard =
            projection(
                "ops/dashboards/board",
                PipelineVersionStatus.RELEASED,
                listOf("ops/visualizations/released_pin", "ops/visualizations/draft_pin"),
            )
        val draftDashboard =
            projection(
                "ops/dashboards/draft_board",
                PipelineVersionStatus.DRAFT,
                listOf("ops/visualizations/released_pin"),
            )

        val view =
            PromotableView.of(
                listOf(pipeline("p", 1, "h")),
                emptyList(),
                inventory(),
                localVisualizations = local,
                currentDashboards = listOf(dashboard, draftDashboard),
            )

        // Only the RELEASED dashboard is admitted, and only its RELEASED pin is a lens name.
        view.dashboards.map { it.name } shouldContainExactly listOf("ops/dashboards/board")
        view.visualizations.map { it.name } shouldContainExactly listOf("ops/visualizations/released_pin")
        view.visualizationsLens.admits("ops/visualizations/released_pin") shouldBe true
        view.visualizationsLens.admits("ops/visualizations/draft_pin") shouldBe false
        view.dashboardsLens.admits("ops/dashboards/draft_board") shouldBe false
    }

    private fun visualization(
        name: String,
        status: PipelineVersionStatus,
    ) = co.datapipelines.visualization.CurrentArtifactVersion(UUID.randomUUID(), name, name.substringAfterLast('/'), 1, "h-$name", status)

    private fun projection(
        name: String,
        status: PipelineVersionStatus,
        pins: List<String>,
    ) = co.datapipelines.visualization.DashboardCurrentPins(
        UUID.randomUUID(),
        name,
        "Board",
        1,
        "h-board",
        status,
        listOf("p"),
        pins,
    )

    private fun pipeline(
        name: String,
        version: Int,
        hash: String,
        status: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
    ) = CurrentPipelineVersion(UUID.randomUUID(), name, name, version, hash, status)

    private fun template(
        id: String,
        version: Int,
        hash: String,
        status: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
    ) = CurrentTemplateVersion(id, id, version, hash, status)

    private fun entry(
        name: String,
        version: Int,
        hash: String,
    ) = PromotionWire.Entry(name, version, hash)

    private fun inventory(
        pipelines: List<PromotionWire.Entry> = emptyList(),
        templates: List<PromotionWire.Entry> = emptyList(),
    ) = PromotionWire.Inventory(
        deployment = "uat",
        authoringEnabled = false,
        workspace = "analytics",
        pipelines = pipelines,
        templates = templates,
    )
}
