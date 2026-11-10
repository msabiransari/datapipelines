package co.datapipelines.application.dependencies

import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.pipeline.release.DependencySource
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseClosure
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseGraph
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionOperations
import java.util.UUID

/**
 * #868 R1: the release walk refuses exactly where `ReleaseGraph` refused — the first cycle, depth, edge or version
 * refusal with its code, message and details — even when a source FAILS on a version the old walk never read, and a
 * failure the old walk did reach still propagates unchanged. Each case is walked by the old [ReleaseGraph] and through
 * BOTH release entry points of [ReleasePlanningWalk]: the planning read and the guarded in-transaction re-walk.
 */
class ReleaseRefusalPrecedenceTest {
    @Test
    fun `a root that pins itself refuses dependency_cycle before a failing sibling is read`() {
        val outcome =
            differential(ReleaseBounds()) { g ->
                val root = g.id("root", 1, ReleaseFamily.PIPELINE)
                g.node(root, ReleaseNodeStatus.DRAFT, root to "nodes[0].pipeline", BROKEN to "nodes[1].template")
                root
            }
        outcome shouldStartWith "{"
        ("pipeline.release.dependency_cycle" in outcome) shouldBe true
    }

    @Test
    fun `a target beyond the depth bound refuses dependency_limit depth before it is read`() {
        val outcome =
            differential(ReleaseBounds(maxDepth = 1)) { g ->
                val child = g.node(g.id("child", 1, ReleaseFamily.PIPELINE), ReleaseNodeStatus.DRAFT, BROKEN to "nodes[0].template")
                g.node(g.id("root", 1, ReleaseFamily.PIPELINE), ReleaseNodeStatus.DRAFT, child to "nodes[0].pipeline")
            }
        ("\"limit\" : \"depth\"" in outcome) shouldBe true
    }

    @Test
    fun `a failing speculative sibling never overtakes the cycle the walk reaches first - same family and another family`() {
        listOf(BROKEN_PIPELINE to "nodes[1].pipeline", BROKEN to "nodes[1].template").forEach { (broken, path) ->
            val run =
                run(ReleaseBounds()) { g ->
                    val root = g.id("root", 1, ReleaseFamily.PIPELINE)
                    val child = g.node(g.id("child", 1, ReleaseFamily.PIPELINE), ReleaseNodeStatus.DRAFT, root to "nodes[0].pipeline")
                    g.node(root, ReleaseNodeStatus.DRAFT, child to "nodes[0].pipeline", broken to path)
                }
            withClue(broken) {
                run.new shouldBe run.old
                ("pipeline.release.dependency_cycle" in run.old) shouldBe true
                // Non-vacuity: the new walk really read the failing version speculatively; the old walk never did.
                run.newReads shouldContain broken
                run.oldReads shouldNotContain broken
            }
        }
    }

    @Test
    fun `a failing speculative sibling never overtakes the edges bound`() {
        val run =
            run(ReleaseBounds(maxEdges = 1)) { g ->
                val a = g.node(g.id("a", 1), ReleaseNodeStatus.RELEASED)
                g.node(ROOT, ReleaseNodeStatus.DRAFT, a to "nodes[0].template", BROKEN to "nodes[1].template")
            }
        run.new shouldBe run.old
        ("\"limit\" : \"edges\"" in run.old) shouldBe true
        run.newReads shouldContain BROKEN
    }

    @Test
    fun `control - a failure the old walk reaches propagates unchanged, directly and after a failed speculation`() {
        val direct =
            run(ReleaseBounds()) { g ->
                g.node(g.id("root", 1, ReleaseFamily.PIPELINE), ReleaseNodeStatus.DRAFT, BROKEN to "nodes[0].template")
            }
        direct.new shouldBe direct.old
        direct.old shouldBe FAILURE
        val later =
            run(ReleaseBounds()) { g ->
                val a = g.node(g.id("a", 1), ReleaseNodeStatus.RELEASED)
                g.node(ROOT, ReleaseNodeStatus.DRAFT, a to "nodes[0].template", BROKEN to "nodes[1].template")
            }
        later.new shouldBe later.old
        later.old shouldBe FAILURE
        later.oldReads.map { it.entityId } shouldContainExactly listOf("root", "a", "broken")
    }

    /** Both directions agree and both entry points agree; the (common) rendering. */
    private fun differential(
        bounds: ReleaseBounds,
        build: (GoldenGraph) -> ReleaseNodeIdentity,
    ): String {
        val run = run(bounds, build)
        run.new shouldBe run.old
        run.newReads shouldContainExactly run.oldReads // no speculation possible here: the reads are the old walk's own
        return run.old
    }

    private class Run(
        val old: String,
        val oldReads: List<ReleaseNodeIdentity>,
        val new: String,
        val newReads: List<ReleaseNodeIdentity>,
    )

    /** One case walked three times on fresh graphs: the old walk, the planning read and the guarded re-walk. */
    private fun run(
        bounds: ReleaseBounds,
        build: (GoldenGraph) -> ReleaseNodeIdentity,
    ): Run {
        val old = Failing(bounds, build)
        val oldOutcome = old.outcome { root -> ReleaseGraph(old.sources, bounds).walk(root) }
        val planned = Failing(bounds, build)
        val plannedOutcome = planned.outcome { root -> planned.walk().plan(root, ACTOR) }
        val guarded = Failing(bounds, build)
        val guardedOutcome = guarded.outcome { root -> guarded.walk().walkInCurrentTransaction(root, ACTOR) }
        withClue("the guarded re-walk answers what the planning read answered") {
            guardedOutcome shouldBe plannedOutcome
            guarded.reads shouldContainExactly planned.reads
        }
        return Run(oldOutcome, old.reads, plannedOutcome, planned.reads)
    }

    /** A fresh graph whose sources fail on [BROKEN] and [BROKEN_PIPELINE], recording every read in order. */
    private class Failing(
        private val bounds: ReleaseBounds,
        build: (GoldenGraph) -> ReleaseNodeIdentity,
    ) {
        private val graph = GoldenGraph()
        private val root = build(graph)
        val reads = ArrayList<ReleaseNodeIdentity>()
        private val source = graph.source()
        val sources: Map<ReleaseFamily, DependencySource> =
            ReleaseFamily.entries.associateWith {
                DependencySource { identity ->
                    reads += identity
                    check(identity.entityId != BROKEN.entityId) { "metadata source unavailable: ${identity.family.wire}/broken" }
                    source.resolve(identity)
                }
            }

        fun walk() =
            ReleasePlanningWalk(
                ReleaseDependencyFamilies.registry(sources),
                bounds,
                TransactionOperations.withoutTransaction(),
                ReadIsolation.NONE,
                ReleasePlanningDeadline.NONE,
            )

        fun outcome(walk: (ReleaseNodeIdentity) -> ReleaseClosure?): String =
            try {
                ReleaseGoldens.render(GoldenCase("precedence", graph, root, bounds, sources), walk)
            } catch (failure: IllegalStateException) {
                "${failure.javaClass.simpleName}: ${failure.message}"
            }
    }

    private companion object {
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-00000000a868")
        private val WS: UUID = UUID.fromString("00000000-0000-0000-0000-000000000868")
        val ROOT = ReleaseNodeIdentity(WS, ReleaseFamily.PIPELINE, "root", 1)
        val BROKEN = ReleaseNodeIdentity(WS, ReleaseFamily.TEMPLATE, "broken", 1)
        val BROKEN_PIPELINE = ReleaseNodeIdentity(WS, ReleaseFamily.PIPELINE, "broken", 1)
        const val FAILURE = "IllegalStateException: metadata source unavailable: template/broken"
    }
}
