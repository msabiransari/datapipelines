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

    /**
     * #868 R1: the same sweep with sources that FAIL on a seeded fifth of the versions. The old walk's outcome — closure,
     * refusal (code, message, details) or the infrastructure failure itself — is the new path's, so no read the neutral
     * walk issues ahead of the old order can overtake a refusal, and no failure the old walk reaches is masked.
     */
    @Test
    fun `generated graphs with failing sources refuse or fail exactly as the old release graph does`() {
        val seeds = Random(FAILING_SEED).let { random -> List(SWEEP) { random.nextLong() } }
        val tally = IntArray(4) // failed, refused, closure, overtaken-and-still-equal
        seeds.forEachIndexed { index, seed ->
            val old = Failing(draw(Random(seed), "failing-$index"), seed)
            val new = Failing(draw(Random(seed), "failing-$index"), seed)
            val oldOutcome = old.outcome { root -> ReleaseGraph(old.case.sources, old.case.bounds).walk(root) }
            val newOutcome = new.outcome { root -> neutral(new.case).plan(root, ACTOR) }
            withClue("failing case $index") { newOutcome shouldBe oldOutcome }
            when {
                oldOutcome.startsWith(Failing.FAILED) -> tally[0]++
                "\"outcome\" : \"refused\"" in oldOutcome -> tally[1]++
                else -> tally[2]++
            }
            if (new.failures > old.failures) tally[3]++
        }
        println("failing sweep: failed=${tally[0]} refused=${tally[1]} closure=${tally[2]} new-walk-met-extra-failures=${tally[3]}")
        // Non-vacuity: every outcome occurs, and the new walk often met a failure the old one never read — yet agreed.
        tally.forEachIndexed { i, count -> withClue("outcome class $i of ${tally.toList()}") { count shouldBeGreaterThan SWEEP / 50 } }
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
            ReadIsolation.NONE,
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

    /** [case] with every source failing on a seeded fifth of the versions (a stable function of the seed and the version). */
    private class Failing(
        original: GoldenCase,
        seed: Long,
    ) {
        var failures = 0
            private set

        val case: GoldenCase =
            GoldenCase(
                original.name,
                original.graph,
                original.root,
                original.bounds,
                original.sources.mapValues { (_, source) ->
                    DependencySource { identity ->
                        val key = "${identity.family.wire}/${identity.entityId}@${identity.version}"
                        if (Random(seed xor key.hashCode().toLong()).nextInt(FAILING_ONE_IN) == 0) {
                            failures++
                            error("$FAILED $key")
                        }
                        source.resolve(identity)
                    }
                },
                original.wireBytes,
            )

        fun outcome(walk: (ReleaseNodeIdentity) -> ReleaseClosure?): String =
            try {
                ReleaseGoldens.render(case, walk)
            } catch (failure: IllegalStateException) {
                "${failure.message}"
            }

        companion object {
            const val FAILED = "metadata source unavailable:"
            const val FAILING_ONE_IN = 5
        }
    }

    private companion object {
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-00000000a868")
        const val SEED = 868L
        const val FAILING_SEED = 8681L
        const val SWEEP = 2000
    }
}
