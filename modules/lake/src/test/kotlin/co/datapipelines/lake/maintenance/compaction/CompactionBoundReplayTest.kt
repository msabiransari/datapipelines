package co.datapipelines.lake.maintenance.compaction

import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * G6 (M4's bound, replayed; brief 969 §A): over generated file sets with writes stopped, planning and applying groups
 * until nothing is planned takes at most 2W + S groups (W files not stamped with the table's order, S small files in
 * order, none capacity-blocked), never plans a file written at target size and in order, and never applies a group of
 * one small in-order file. (The last is asserted directly: a lone rewrite adds a group without removing a small file,
 * but the bound's slack absorbed it in every generated set when it was planted, so the bound alone cannot see it.) A
 * group's outputs are the writer's: one stream cut at the target, so whole target-size files and at most one remainder,
 * all stamped in order.
 */
class CompactionBoundReplayTest {
    private val profile = CompactionGroupPlannerTest.profile()
    private val rule = CompactionCandidateRule(profile, TABLE_ORDER)
    private val smallBelow = (profile.compactSmallFileRatio * profile.targetFileBytes).toLong()

    private data class SimFile(
        val key: String,
        val partition: String,
        val size: Long,
        val sortOrderId: Int,
        val seq: Long,
    )

    @Test
    fun `G6 at most 2W + S groups drain the backlog, no full-size in-order file is planned, no small one is rewritten alone`() {
        val random = Random(SEED)
        var nonVacuous = 0
        repeat(SETS) { set ->
            val files = generate(random, set)
            val w = files.count { rule.unsorted(it.sortOrderId) }
            val s = files.count { !rule.unsorted(it.sortOrderId) && rule.small(it.size) }
            if (w > 0 && s > 1) nonVacuous++
            val groups = drain(files, bound = 2 * w + s)
            (groups <= 2 * w + s) shouldBe true
        }
        nonVacuous shouldBeGreaterThanOrEqual NON_VACUITY_FLOOR
    }

    /** Plans and applies groups until none is planned; answers how many were applied. Fails past [bound]. */
    private fun drain(
        initial: List<SimFile>,
        bound: Int,
    ): Int {
        var files = initial
        var seq = files.maxOfOrNull { it.seq } ?: 0
        var applied = 0
        while (true) {
            val candidates = rule.candidates(files, SimFile::partition, SimFile::size) { it.sortOrderId }
            val plan =
                CompactionGroupPlanner.plan(
                    candidates.map { CompactionCandidate(it.key, it.partition, it.size, it.seq, rule.unsorted(it.sortOrderId)) },
                    PlanLimits(profile.maxBytesRewrittenPerRun, AMPLE, 1, AMPLE, AMPLE),
                )
            if (plan.groups.isEmpty()) return applied
            plan.groups.forEach { group ->
                // never applied: one small file already in order, alone (the record's "worth rewriting", call 34)
                (group.size == 1 && !group.single().needsSort) shouldBe false
                val keys = group.map { it.key }.toSet()
                val inputs = files.filter { it.key in keys }
                inputs.forEach { input ->
                    // never planned: a file written at target size (not small) and stamped in order
                    (!rule.unsorted(input.sortOrderId) && input.size >= smallBelow) shouldBe false
                }
                val total = inputs.sumOf { it.size }
                val outputs =
                    (0 until total / profile.targetFileBytes).map { profile.targetFileBytes } +
                        listOfNotNull((total % profile.targetFileBytes).takeIf { it > 0 })
                files = files.filter { it.key !in keys } +
                    outputs.map { size -> SimFile("out-${++seq}", group.first().partition, size, TABLE_ORDER, seq) }
                applied++
                check(applied <= bound) { "more than 2W + S = $bound groups for one file set" }
            }
        }
    }

    private fun generate(
        random: Random,
        set: Int,
    ): List<SimFile> {
        var seq = 0L
        return (1..random.nextInt(1, MAX_PARTITIONS + 1)).flatMap { p ->
            (1..random.nextInt(0, MAX_FILES + 1)).map { f ->
                val unsorted = random.nextDouble() < UNSORTED_SHARE
                val size =
                    when {
                        unsorted -> random.nextLong(1, profile.maxBytesRewrittenPerRun + 1)
                        random.nextDouble() < SMALL_SHARE -> random.nextLong(1, smallBelow)
                        else -> random.nextLong(smallBelow, profile.targetFileBytes + 1)
                    }
                SimFile("s$set-p$p-f$f", "0/p=$p", size, if (unsorted) 0 else TABLE_ORDER, ++seq)
            }
        }
    }

    private companion object {
        const val SEED = 969L
        const val SETS = 250
        const val NON_VACUITY_FLOOR = 60
        const val TABLE_ORDER = 1
        const val MAX_PARTITIONS = 3
        const val MAX_FILES = 14
        const val UNSORTED_SHARE = 0.25
        const val SMALL_SHARE = 0.7
        const val AMPLE = 1L shl 40
    }
}
