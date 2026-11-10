package co.datapipelines.application.dependencies

import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseGraph
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionOperations
import java.util.UUID
import kotlin.random.Random

/**
 * Section D's check: today's release behavior survives behind the neutral contract. Every [ReleaseGoldenCases] case is
 * rendered through the release adapters, the neutral walk and the closure adapter ([ReleasePlanningWalk]) and must equal
 * the committed golden byte for byte — node ids, statuses, edges and paths, depth, dependency-first order, the
 * `TreeHash` canonical string and digest, and every refusal's code, message and details.
 *
 * A seeded differential sweep then walks generated graphs (diamonds, cycles, missing, discarded and unregistered
 * targets, tight bounds) through BOTH the old [ReleaseGraph] and the new path and compares the two renderings.
 */
class ReleaseGoldenParityTest {
    @Test
    fun `every golden case walked through the neutral path equals its committed golden`() {
        val cases = ReleaseGoldenCases.ALL.map { it() }
        cases.size shouldBeGreaterThan 0
        cases.forEach { case ->
            val rendered = ReleaseGoldens.render(case) { root -> neutral(case).plan(root, ACTOR) }
            withClue("golden ${case.name} through the neutral walk") { rendered shouldBe ReleaseGoldens.file(case.name).readText() }
        }
    }

    @Test
    fun `the guarded in-transaction walk answers the same closure as the planning walk`() {
        ReleaseGoldenCases.ALL.map { it() }.forEach { case ->
            val planned = ReleaseGoldens.render(case) { root -> neutral(case).plan(root, ACTOR) }
            val guarded = ReleaseGoldens.render(case) { root -> neutral(case).walkInCurrentTransaction(root, ACTOR) }
            withClue(case.name) { guarded shouldBe planned }
        }
    }

    @Test
    fun `generated graphs walk identically through the old release graph and the neutral path`() {
        val seeds = Random(SEED).let { random -> List(SWEEP) { random.nextLong() } }
        var refused = 0
        var closures = 0
        seeds.forEachIndexed { index, seed ->
            // Two graphs drawn from one seed: the old walk and the new one never share a source or its read counts.
            val case = draw(Random(seed), "generated-$index")
            val fresh = draw(Random(seed), "generated-$index")
            val old = ReleaseGoldens.render(case) { root -> ReleaseGraph(case.sources, case.bounds).walk(root) }
            val new = ReleaseGoldens.render(fresh) { root -> neutral(fresh).plan(root, ACTOR) }
            withClue("generated case $index") { new shouldBe old }
            if ("\"outcome\" : \"refused\"" in old) refused++ else closures++
        }
        // Non-vacuity: the sweep exercised both outcomes, not only one of them.
        refused shouldBeGreaterThan SWEEP / 10
        closures shouldBeGreaterThan SWEEP / 10
    }

    @Test
    fun `a diagnostics overflow is not a release refusal - many missing pins under max_blockers 1`() {
        fun case(): GoldenCase {
            val g = GoldenGraph()
            val pins = (1..5).map { g.id("absent$it") to "nodes[$it].template" }
            val root = g.node(g.id("orders", 1, ReleaseFamily.PIPELINE), ReleaseNodeStatus.DRAFT, *pins.toTypedArray())
            return GoldenCase("overflow", g, root, ReleaseBounds(maxBlockers = 1))
        }
        val old = case().let { c -> ReleaseGoldens.render(c) { ReleaseGraph(c.sources, c.bounds).walk(it) } }
        val new = case().let { c -> ReleaseGoldens.render(c) { neutral(c).plan(it, ACTOR) } }
        new shouldBe old
        ("\"outcome\" : \"closure\"" in new) shouldBe true
    }

    private fun neutral(case: GoldenCase) =
        ReleasePlanningWalk(
            ReleaseDependencyFamilies.registry(case.sources),
            case.bounds,
            TransactionOperations.withoutTransaction(),
            ReleasePlanningDeadline.NONE,
        )

    /**
     * One generated case: one to five pipeline and up to four template versions with random statuses and pins (a pipeline
     * pins templates and pipelines, a template pins nothing — the two §6 rows the release sources produce), some targets
     * absent, sometimes the template family unregistered, and bounds drawn small enough to trip.
     */
    @Suppress("MagicNumber") // the sweep's shape, one place
    private fun draw(
        random: Random,
        name: String,
    ): GoldenCase {
        val g = GoldenGraph()
        val pipelines = (0 until random.nextInt(1, 6)).map { g.id("p$it", random.nextInt(1, 3), ReleaseFamily.PIPELINE) }
        val templates = (0 until random.nextInt(0, 5)).map { g.id("t$it", random.nextInt(1, 3)) }
        val absent = listOf(g.id("gone", 1), g.id("gone", 1, ReleaseFamily.PIPELINE))
        val statuses = listOf(ReleaseNodeStatus.DRAFT, ReleaseNodeStatus.RELEASED, ReleaseNodeStatus.DISCARDED)
        templates.forEach { g.node(it, statuses[random.nextInt(statuses.size)]) }
        pipelines.forEach { p ->
            val targets = pipelines + templates + absent
            val pins: List<Pair<ReleaseNodeIdentity, String>> =
                (0 until random.nextInt(0, 4)).map { i ->
                    val target = targets[random.nextInt(targets.size)]
                    target to (if (target.family == ReleaseFamily.PIPELINE) "nodes[$i].pipeline" else "nodes[$i].template")
                }
            g.node(
                p,
                statuses[random.nextInt(statuses.size)],
                *pins.toTypedArray(),
                currentVersion = random.nextInt(0, 3).takeIf { it > 0 },
            )
        }
        val bounds =
            ReleaseBounds(
                maxDepth = random.nextInt(1, 6),
                maxVersions = random.nextInt(2, 12),
                maxEdges = random.nextInt(2, 16),
                maxBlockers = random.nextInt(1, 4),
            )
        val sources = if (random.nextInt(5) == 0) mapOf(ReleaseFamily.PIPELINE to g.source()) else g.allFamilies()
        return GoldenCase(name, g, pipelines.first(), bounds, sources)
    }

    private companion object {
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-00000000a868")
        const val SEED = 868L
        const val SWEEP = 2000
    }
}
