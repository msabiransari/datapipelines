package co.datapipelines.web.ui

import co.datapipelines.application.dependencies.PlannedClosure
import co.datapipelines.application.dependencies.ReleasePlanningWalk
import co.datapipelines.pipeline.release.CheckOverride
import co.datapipelines.pipeline.release.DependencySource
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseClosure
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseGraph
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleasePin
import co.datapipelines.pipeline.release.ReleasePlan
import co.datapipelines.pipeline.release.ReleasePrerequisite
import co.datapipelines.pipeline.release.ResolvedNode
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.ui.ReleaseDialogModel.Kind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.util.LinkedMultiValueMap
import java.util.UUID

/**
 * #876 §F.2 — the shared release dialog's model over REAL closures ([ReleaseGraph] over in-memory sources) and the
 * session plan the GET builds, never hand-built rows. The worked example is the brief's: the board, its two draft
 * pipelines sharing one draft `sales/clean`, the released parameter set, and the released template whose datasource is
 * the one prerequisite. The Gates rows "Shared draft once", "Plan, not tree" (no checkbox for a prerequisite) and
 * "Prerequisite blocks" (submittable carries the prerequisite term; an unavailable `readonly` is unknown) are pinned here.
 */
class ReleaseDialogModelTest {
    private val ws: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val actor: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")

    // ------------------------------------------------------------------ the worked example

    @Test
    fun `the worked example - seven rows, three checked drafts, two chips, one prerequisite text row, six edges`() {
        val model = ReleaseDialogModel.of(preview(boardWorld(), board, listOf(WAREHOUSE)))

        model.family shouldBe "dashboard"
        model.rows.first().label shouldBe "sales/board@3"
        // Plan order is the walk's discovery order; the set of rows and their kinds is the contract.
        model.rows.map { it.label to it.kind } shouldContainExactlyInAnyOrder
            listOf(
                "sales/board@3" to Kind.ROOT,
                "sales/daily@5" to Kind.DRAFT,
                "sales/weekly@2" to Kind.DRAFT,
                "sales/filters@1" to Kind.RELEASED,
                "sales/clean@4" to Kind.DRAFT,
                "sales/query@1" to Kind.RELEASED,
            )
        model.prerequisites shouldContainExactly listOf(ReleaseDialogModel.PrerequisiteRow("warehouse", available = true, readonly = true))
        model.prerequisites.single().text shouldBe "Datasource warehouse — available, read-only"
        // Six edges, by node id: board→daily, board→weekly, board→filters, daily→clean, weekly→clean, clean→query.
        val label = model.rows.associate { it.nodeId to it.name }
        model.edges.map { label.getValue(it.from) to label.getValue(it.to) } shouldContainExactlyInAnyOrder
            listOf(
                "sales/board" to "sales/daily",
                "sales/board" to "sales/weekly",
                "sales/board" to "sales/filters",
                "sales/daily" to "sales/clean",
                "sales/weekly" to "sales/clean",
                "sales/clean" to "sales/query",
            )
        model.treeHash.shouldNotBeNull() shouldStartWith "v1:"
        model.complete shouldBe true
        model.submittable shouldBe true
    }

    @Test
    fun `shared draft once - a diamond's shared draft is exactly ONE row and ONE checkbox`() {
        val model = ReleaseDialogModel.of(preview(boardWorld(), board, listOf(WAREHOUSE)))

        model.rows.count { it.name == "sales/clean" } shouldBe 1
        model.drafts.count { it.name == "sales/clean" } shouldBe 1
        // Two edges still reach it: the shape is in the edges, never in duplicated rows.
        val clean = model.rows.single { it.name == "sales/clean" }.nodeId
        model.edges.count { it.to == clean } shouldBe 2
    }

    @Test
    fun `plan, not tree - a prerequisite is never a row and never a checkbox, a released node never a checkbox`() {
        val model = ReleaseDialogModel.of(preview(boardWorld(), board, listOf(WAREHOUSE)))

        model.rows.none { it.name == "warehouse" } shouldBe true
        model.drafts.map { it.name } shouldContainExactlyInAnyOrder listOf("sales/daily", "sales/weekly", "sales/clean")
        model.drafts.none { it.status != "DRAFT" } shouldBe true
    }

    @Test
    fun `a family's note rides its node's row and no other`() {
        val model =
            ReleaseDialogModel.of(preview(boardWorld(), board, listOf(WAREHOUSE))) { node ->
                if (node.name == "sales/clean") ", shared" else null
            }

        model.rows.filter { it.note != null }.map { it.name } shouldContainExactly listOf("sales/clean")
    }

    // ------------------------------------------------------------------ prerequisites block

    @Test
    fun `prerequisite blocks - a complete hashed closure with ONE unavailable prerequisite is not submittable`() {
        val model =
            ReleaseDialogModel.of(
                preview(opsWorld(), opsLoad, listOf(ReleasePrerequisite("gone", available = false, readonly = null))),
            )

        model.complete shouldBe true
        model.treeHash.shouldNotBeNull()
        model.blocking shouldBe emptyList()
        model.unavailable.map { it.name } shouldContainExactly listOf("gone")
        model.submittable shouldBe false
        model.prerequisites.single().text shouldBe "Datasource gone is not available"
        // Unknown, never "writable": the outcome type itself refuses a readonly value on an unavailable datasource.
        model.prerequisites
            .single()
            .readonly
            .shouldBeNull()
    }

    @Test
    fun `prerequisite blocks - an available prerequisite whose access mode is unknown is rendered as neither word`() {
        val row = ReleaseDialogModel.PrerequisiteRow("warehouse", available = true, readonly = null)

        row.text shouldBe "Datasource warehouse — available"
        ReleaseDialogModel.PrerequisiteRow("warehouse", available = true, readonly = false).text shouldBe
            "Datasource warehouse — available, writable"
    }

    @Test
    fun `an absent datasource and one granted only to another workspace are byte-identical rows`() {
        // The planner answers both as the same outcome (G3's scoped provider); the model must not tell them apart.
        val absent = ReleaseDialogModel.of(preview(opsWorld(), opsLoad, listOf(ReleasePrerequisite("gone", false, null))))
        val foreign = ReleaseDialogModel.of(preview(opsWorld(), opsLoad, listOf(ReleasePrerequisite("gone", false, null))))

        absent shouldBe foreign
    }

    // ------------------------------------------------------------------ blocking nodes

    @Test
    fun `a MISSING dependency is a blocking row without an input, and the plan is not submittable`() {
        val world = opsWorld(templateResolves = false)
        val model = ReleaseDialogModel.of(preview(world, opsLoad, emptyList()))

        model.rows.map { it.label to it.kind } shouldContainExactly listOf("ops/load@2" to Kind.ROOT, "ops/q@1" to Kind.BLOCKING)
        model.rows.last().status shouldBe "MISSING"
        model.drafts shouldBe emptyList()
        model.submittable shouldBe false
    }

    @Test
    fun `a DISCARDED dependency is blocking`() {
        val world = opsWorld(templateStatus = ReleaseNodeStatus.DISCARDED)
        val model = ReleaseDialogModel.of(preview(world, opsLoad, emptyList()))

        model.rows.last().kind shouldBe Kind.BLOCKING
        model.rows.last().status shouldBe "DISCARDED"
        model.submittable shouldBe false
    }

    @Test
    fun `a refused plan carries the message, no rows and no hash, and is not submittable`() {
        val model = ReleaseDialogModel.refused(ReleaseFamily.PIPELINE, "A dependency cycle.")

        model.refusal shouldBe "A dependency cycle."
        model.rows shouldBe emptyList()
        model.treeHash.shouldBeNull()
        model.submittable shouldBe false
    }

    // ------------------------------------------------------------------ the producer

    @Test
    fun `ReleasePreviews - the dialog reads the planner's prerequisites, never the closure alone`() {
        val closure = requireNotNull(ReleaseGraph(opsWorld(), ReleaseBounds()).walk(opsLoad))
        val planning = mockk<ReleasePlanningWalk>()
        every { planning.planned(opsLoad, actor) } returns
            PlannedClosure(closure, listOf(ReleasePrerequisite("gone", available = false, readonly = null)))

        val model = ReleasePreviews(planning, ReleaseBounds()).dialog(ReleaseFamily.PIPELINE, ws, "ops/load", 2, actor)

        model.unavailable.map { it.name } shouldContainExactly listOf("gone")
        model.submittable shouldBe false
    }

    @Test
    fun `ReleasePreviews - a root that no longer resolves is the family's plan_conflict, rendered as a refused model`() {
        val planning = mockk<ReleasePlanningWalk>()
        every { planning.planned(any(), any()) } returns null
        val previews = ReleasePreviews(planning, ReleaseBounds())

        shouldThrow<DatapipelinesException> { previews.preview(ReleaseFamily.PIPELINE, ws, "ops/load", 2, actor) }.code shouldBe
            "pipeline.release.plan_conflict"
        val model = previews.dialog(ReleaseFamily.PIPELINE, ws, "ops/load", 2, actor)
        model.refusal.shouldNotBeNull()
        model.submittable shouldBe false
    }

    @Test
    fun `ReleaseDialogPost - a POST without a tree_hash is plan_required, with one it is the posted selection`() {
        shouldThrow<DatapipelinesException> { ReleaseDialogPost.plan(ReleaseFamily.DASHBOARD, null, listOf("n1")) }.code shouldBe
            "dashboard.release.plan_required"
        shouldThrow<DatapipelinesException> { ReleaseDialogPost.plan(ReleaseFamily.PARAMETER_SET, " ", null) }.code shouldBe
            "parameter.release.plan_required"
        val plan = ReleaseDialogPost.plan(ReleaseFamily.PIPELINE, "v1:abc", null)
        plan.treeHash shouldBe "v1:abc"
        plan.includeDependencies shouldBe emptyList()
    }

    @Test
    fun `876 R3 - ReleaseDialogPost pairs each check_override_node with its check_override_reason by index`() {
        val form =
            LinkedMultiValueMap<String, String>().apply {
                add(ReleaseDialogPost.OVERRIDE_NODE, "n-daily")
                add(ReleaseDialogPost.OVERRIDE_REASON, "Feed late, checked by hand, safe")
                add(ReleaseDialogPost.OVERRIDE_NODE, "n-weekly")
                add(ReleaseDialogPost.OVERRIDE_REASON, "")
                add(ReleaseDialogPost.OVERRIDE_NODE, "n-clean")
                add(ReleaseDialogPost.OVERRIDE_REASON, "  \t ")
                // The root's own reason is a different field: it is never one of the plan's overrides.
                add("overrideChecksReason", "the root's reason, never a child's")
            }

        val plan = ReleaseDialogPost.plan(ReleaseFamily.DASHBOARD, "v1:abc", listOf("n-daily", "n-weekly"), form)

        // A blank-after-trim reason is no override; the kept reason is the posted text, untrimmed, for the coordinator.
        plan.checkOverrides shouldBe listOf(CheckOverride("n-daily", "Feed late, checked by hand, safe"))
        plan.includeDependencies shouldBe listOf("n-daily", "n-weekly")
    }

    @Test
    fun `876 R3 - no pair, or only blank reasons, is a null checkOverrides - the plan the REST route builds without one`() {
        ReleaseDialogPost.plan(ReleaseFamily.PIPELINE, "v1:abc", null).checkOverrides.shouldBeNull()
        ReleaseDialogPost.checkOverrides(ReleaseFamily.PIPELINE, listOf("n1"), listOf(" ")).shouldBeNull()
        ReleaseDialogPost.checkOverrides(ReleaseFamily.PIPELINE, null, null).shouldBeNull()
    }

    @Test
    fun `876 R3 - mismatched counts are plan_invalid at check_overrides, under the root's family, echoing no value`() {
        val mismatched: List<Pair<List<String>?, List<String>>> =
            listOf(
                listOf("n1", "n2") to listOf("long enough reason"),
                listOf("n1") to emptyList(),
                null to listOf("x"),
            )
        for ((nodes, reasons) in mismatched) {
            val thrown =
                shouldThrow<DatapipelinesException> { ReleaseDialogPost.checkOverrides(ReleaseFamily.DASHBOARD, nodes, reasons) }
            thrown.code shouldBe "dashboard.release.plan_invalid"
            thrown.details["field"] shouldBe "check_overrides"
            thrown.message.orEmpty().contains("long enough reason") shouldBe false
        }
    }

    // ------------------------------------------------------------------ fixtures

    private val board = id(ReleaseFamily.DASHBOARD, "sales/board", 3)
    private val daily = id(ReleaseFamily.PIPELINE, "sales/daily", 5)
    private val weekly = id(ReleaseFamily.PIPELINE, "sales/weekly", 2)
    private val filters = id(ReleaseFamily.PARAMETER_SET, "sales/filters", 1)
    private val clean = id(ReleaseFamily.PIPELINE, "sales/clean", 4)
    private val query = id(ReleaseFamily.TEMPLATE, "sales/query", 1)
    private val opsLoad = id(ReleaseFamily.PIPELINE, "ops/load", 2)
    private val opsQ = id(ReleaseFamily.TEMPLATE, "ops/q", 1)

    private fun id(
        family: ReleaseFamily,
        name: String,
        version: Int,
    ) = ReleaseNodeIdentity(ws, family, name, version)

    private fun boardWorld(): Map<ReleaseFamily, DependencySource> =
        world(
            node(
                board,
                ReleaseNodeStatus.DRAFT,
                pin(daily, "sources/0/pipeline"),
                pin(weekly, "sources/1/pipeline"),
                pin(filters, "parameter_set"),
            ),
            node(daily, ReleaseNodeStatus.DRAFT, pin(clean, "nodes/0/pipeline")),
            node(weekly, ReleaseNodeStatus.DRAFT, pin(clean, "nodes/0/pipeline")),
            node(filters, ReleaseNodeStatus.RELEASED),
            node(clean, ReleaseNodeStatus.DRAFT, pin(query, "nodes/0/template")),
            node(query, ReleaseNodeStatus.RELEASED),
        )

    private fun opsWorld(
        templateResolves: Boolean = true,
        templateStatus: ReleaseNodeStatus = ReleaseNodeStatus.RELEASED,
    ): Map<ReleaseFamily, DependencySource> =
        world(
            *listOfNotNull(
                node(opsLoad, ReleaseNodeStatus.DRAFT, pin(opsQ, "nodes/0/template")),
                if (templateResolves) node(opsQ, templateStatus) else null,
            ).toTypedArray(),
        )

    private fun pin(
        target: ReleaseNodeIdentity,
        path: String,
    ) = ReleasePin(target, target.entityId, path)

    private fun node(
        identity: ReleaseNodeIdentity,
        status: ReleaseNodeStatus,
        vararg pins: ReleasePin,
    ) = ResolvedNode(
        identity = identity,
        name = identity.entityId,
        status = status,
        bodyHash = "h-${identity.entityId}-${identity.version}",
        currentVersion = if (status == ReleaseNodeStatus.RELEASED) identity.version else null,
        pins = pins.toList(),
        entityUuid = UUID.nameUUIDFromBytes(identity.entityId.toByteArray()),
    )

    private fun world(vararg nodes: ResolvedNode): Map<ReleaseFamily, DependencySource> {
        val byIdentity = nodes.associateBy { it.identity }
        val source = DependencySource { byIdentity[it] }
        return ReleaseFamily.entries.associateWith { source }
    }

    private fun preview(
        sources: Map<ReleaseFamily, DependencySource>,
        root: ReleaseNodeIdentity,
        prerequisites: List<ReleasePrerequisite>,
    ): ReleasePreview {
        val closure: ReleaseClosure = requireNotNull(ReleaseGraph(sources, ReleaseBounds()).walk(root))
        return ReleasePreview(ReleasePlan.session(closure, ReleaseBounds().maxWireBytes, prerequisites), prerequisites)
    }

    private companion object {
        val WAREHOUSE = ReleasePrerequisite("warehouse", available = true, readonly = true)
    }
}
