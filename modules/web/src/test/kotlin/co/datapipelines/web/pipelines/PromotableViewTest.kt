package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.CurrentPipelineVersion
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

    private fun pipeline(
        name: String,
        version: Int,
        hash: String,
    ) = CurrentPipelineVersion(UUID.randomUUID(), name, name, version, hash)

    private fun template(
        id: String,
        version: Int,
        hash: String,
    ) = CurrentTemplateVersion(id, id, version, hash)

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
