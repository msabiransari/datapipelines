package co.datapipelines.lake.maintenance.compaction

import co.datapipelines.lake.profiles.LakeProfile
import co.datapipelines.lake.profiles.LakeProfileDocument
import co.datapipelines.lake.profiles.LakeProfileFixtures
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * G1–G5, G7, G8 (brief 969 §A): the pure group planner and the shared candidate rule, no I/O. Sizes in MiB; [ample] is a
 * limit no fixture reaches. G6 (the bound, replayed) is [CompactionBoundReplayTest].
 */
class CompactionGroupPlannerTest {
    private fun unsorted(
        key: String,
        mib: Long,
        seq: Long,
        partition: String = P1,
    ) = CompactionCandidate(key, partition, mib * MIB, seq, needsSort = true)

    private fun small(
        key: String,
        mib: Long,
        seq: Long,
        partition: String = P1,
    ) = CompactionCandidate(key, partition, mib * MIB, seq, needsSort = false)

    private fun limits(
        cap: Long = 128,
        grant: Long = AMPLE,
        factor: Int = 1,
        wholeRun: Long = AMPLE,
        now: Long = wholeRun,
    ) = PlanLimits(cap * MIB, grant * MIB, factor, wholeRun * MIB, now * MIB)

    private fun GroupPlan.keys() = groups.map { group -> group.map { it.key } }

    @Test
    fun `G1 one partition per group, oldest first, within the limit, the oldest candidate's group first`() {
        val files =
            listOf(
                small("b3", 10, seq = 30, partition = P2),
                small("a2", 10, seq = 20),
                small("b1", 10, seq = 5, partition = P2),
                small("a1", 10, seq = 10),
                small("a3", 10, seq = 40),
                small("b2", 10, seq = 15, partition = P2),
            )
        val plan = CompactionGroupPlanner.plan(files, limits(cap = 25))
        // limit 25 MiB: two 10 MiB files per group; the third of each partition is left alone and waits
        plan.keys() shouldContainExactly listOf(listOf("b1", "b2"), listOf("a1", "a2"))
        plan.waiting.map { it.key } shouldContainExactlyInAnyOrder listOf("a3", "b3")
        plan.groups.forEach { group -> group.map { it.partition }.toSet() shouldHaveSize 1 }
        plan.groups.forEach { group -> group.sumOf { it.sizeBytes } shouldBeLessThanOrEqualLong 25 * MIB }
    }

    @Test
    fun `G1 a group holding one small in-order file alone is not worth rewriting`() {
        val plan = CompactionGroupPlanner.plan(listOf(small("a", 10, 1), small("b", 30, 2)), limits(cap = 35))
        // a + b = 40 > 35: the smallest merge of each is above every run's limit, so both are blocked, neither grouped
        plan.groups.shouldBeEmpty()
        plan.blocked.map { it.candidate.key } shouldContainExactlyInAnyOrder listOf("a", "b")
        CompactionGroupPlanner.worthRewriting(listOf(small("a", 10, 1))) shouldBe false
        CompactionGroupPlanner.worthRewriting(listOf(unsorted("a", 10, 1))) shouldBe true
        CompactionGroupPlanner.worthRewriting(listOf(small("a", 10, 1), small("b", 10, 2))) shouldBe true
    }

    @Test
    fun `G2 files of two specs in what looks like one partition form two groups`() {
        val files =
            listOf(
                small("old1", 10, 1, "0/day=1"),
                small("old2", 10, 2, "0/day=1"),
                small("new1", 10, 3, "1/day=1"),
                small("new2", 10, 4, "1/day=1"),
            )
        CompactionGroupPlanner.plan(files, limits()).keys() shouldContainExactly listOf(listOf("old1", "old2"), listOf("new1", "new2"))
    }

    @Test
    fun `G3 capacity by cap - a 64 MiB unsorted file under a 32 MiB cap is blocked, a fitting group beside it is planned`() {
        val alone = CompactionGroupPlanner.plan(listOf(unsorted("big", 64, 1)), limits(cap = 32))
        alone.groups.shouldBeEmpty()
        alone.blocked shouldContainExactly listOf(BlockedFile(unsorted("big", 64, 1), 64 * MIB))
        alone.limit shouldBe GroupLimit(32 * MIB, LimitSource.CAP)

        val beside = CompactionGroupPlanner.plan(listOf(unsorted("big", 64, 1), unsorted("fits", 20, 2, P2)), limits(cap = 32))
        beside.keys() shouldContainExactly listOf(listOf("fits"))
        beside.blocked.map { it.candidate.key } shouldContainExactly listOf("big")
    }

    @Test
    fun `G4 five 40 MiB in-order files under a 128 MiB cap merge in groups of at least two, no file alone`() {
        val plan = CompactionGroupPlanner.plan(fiveForty(), limits(cap = 128))
        plan.keys() shouldContainExactly listOf(listOf("f1", "f2", "f3"), listOf("f4", "f5"))
        plan.groups.forEach { it.size shouldBeGreaterThanOrEqual 2 }
        plan.waiting.shouldBeEmpty()
        plan.blocked.shouldBeEmpty()
    }

    @Test
    fun `G5 the smallest of cap, scratch and time always wins, named in that order on a tie`() {
        val file = listOf(unsorted("u", 60, 1))
        CompactionGroupPlanner.plan(file, limits(cap = 128, grant = 100, factor = 2)).limit shouldBe
            GroupLimit(50 * MIB, LimitSource.SCRATCH)
        CompactionGroupPlanner
            .plan(file, limits(cap = 128, grant = 100, factor = 2))
            .blocked
            .single()
            .candidate.key shouldBe "u"
        CompactionGroupPlanner.plan(file, limits(cap = 128, wholeRun = 40)).limit shouldBe GroupLimit(40 * MIB, LimitSource.TIME)
        CompactionGroupPlanner.plan(file, limits(cap = 40, wholeRun = 40)).limit shouldBe GroupLimit(40 * MIB, LimitSource.CAP)
        CompactionGroupPlanner.plan(file, limits(cap = 128, grant = 40, wholeRun = 40)).limit shouldBe
            GroupLimit(40 * MIB, LimitSource.SCRATCH)
        CompactionGroupPlanner.plan(file, limits(cap = 32, grant = 100, factor = 2, wholeRun = 40)).limit shouldBe
            GroupLimit(32 * MIB, LimitSource.CAP)
    }

    @Test
    fun `G5 time's two cases from one fixture - beyond a whole run is blocked, beyond the time left is left for a fresh run`() {
        val files = listOf(unsorted("huge", 100, 1), unsorted("fits-a-run", 60, 2, P2))
        val plan = CompactionGroupPlanner.plan(files, limits(cap = 128, wholeRun = 80, now = 30))
        plan.groups.shouldBeEmpty()
        plan.blocked shouldContainExactly listOf(BlockedFile(unsorted("huge", 100, 1), 100 * MIB))
        plan.limit shouldBe GroupLimit(80 * MIB, LimitSource.TIME)
        plan.leftForFreshRun.map { it.key } shouldContainExactly listOf("fits-a-run")
    }

    @Test
    fun `G5 (a) a whole run writes 64 MiB - five blocked by time with limit 64 and largest need 80, none left`() {
        val plan = CompactionGroupPlanner.plan(fiveForty(), limits(cap = 128, wholeRun = 64))
        plan.groups.shouldBeEmpty()
        plan.blocked shouldHaveSize 5
        plan.limit shouldBe GroupLimit(64 * MIB, LimitSource.TIME)
        plan.largestBlockedNeed shouldBe 80 * MIB
        plan.leftForFreshRun.shouldBeEmpty()
    }

    @Test
    fun `G5 (b) a whole run writes 96 MiB and 64 MiB fit the time left - no group, five left for a fresh run`() {
        val plan = CompactionGroupPlanner.plan(fiveForty(), limits(cap = 128, wholeRun = 96, now = 64))
        plan.groups.shouldBeEmpty()
        plan.blocked.shouldBeEmpty()
        plan.leftForFreshRun shouldHaveSize 5
    }

    @Test
    fun `G5 (c) all of a 96 MiB run is left - two pairs planned, the fifth in none, then (c1) and (c2)`() {
        val plan = CompactionGroupPlanner.plan(fiveForty(), limits(cap = 128, wholeRun = 96, now = 96))
        plan.keys() shouldContainExactly listOf(listOf("f1", "f2"), listOf("f3", "f4"))
        plan.waiting.map { it.key } shouldContainExactly listOf("f5")
        plan.blocked.shouldBeEmpty()
        plan.leftForFreshRun.shouldBeEmpty()

        // planned again after the first group (f1, f2) committed, 48 MiB of the cap unused
        val rest = fiveForty().drop(2)
        val c1 = CompactionGroupPlanner.plan(rest, limits(cap = 128, wholeRun = 96, now = 16))
        c1.groups.shouldBeEmpty()
        c1.blocked.shouldBeEmpty()
        c1.leftForFreshRun shouldHaveSize 3

        val c2 = CompactionGroupPlanner.plan(rest, limits(cap = 128, wholeRun = 96, now = 96))
        c2.keys() shouldContainExactly listOf(listOf("f3", "f4"))
        c2.blocked.shouldBeEmpty()
        c2.leftForFreshRun.shouldBeEmpty()
        // the part does not start it: 80 MiB would cross the 48 MiB of the cap this run has not used
        CompactionRunBudget(capBytes = 128 * MIB).apply { spend(80 * MIB) }.fits(c2.groups.first()) shouldBe false
    }

    @Test
    fun `G5 (d) one small in-order file alone in its partition - no group, not blocked and not left under every limit set`() {
        val lone = listOf(small("lone", 40, 1))
        listOf(limits(cap = 128, wholeRun = 64), limits(cap = 128, wholeRun = 96, now = 64), limits(cap = 128, wholeRun = 96)).forEach {
            val plan = CompactionGroupPlanner.plan(lone, it)
            plan.groups.shouldBeEmpty()
            plan.blocked.shouldBeEmpty()
            plan.leftForFreshRun.shouldBeEmpty()
            plan.waiting.map { file -> file.key } shouldContainExactly listOf("lone")
        }
    }

    @Test
    fun `G7 a partition larger than the cap is finished over several plans while another keeps receiving files`() {
        var big = (1..10).map { unsorted("big$it", 40, seq = it.toLong()) }
        var busy = (1..5).map { small("busy$it", 10, seq = 100L + it, partition = P2) }
        var seq = 200L
        var plans = 0
        while (big.isNotEmpty()) {
            val all = big + busy
            val plan = CompactionGroupPlanner.plan(all, limits(cap = 128))
            val oldest = all.minWith(compareBy({ it.sequenceNumber }, { it.key }))
            plan.groups.first().first() shouldBe oldest
            // one group per run: the run's cap is used up by it
            val done =
                plan.groups
                    .first()
                    .map { it.key }
                    .toSet()
            big = big.filter { it.key !in done }
            busy = busy.filter { it.key !in done } + listOf(small("busy-new-$seq", 10, seq++, P2), small("busy-new-$seq", 10, seq++, P2))
            plans++
            plans shouldBeLessThanOrEqual 10
        }
        plans shouldBeGreaterThanOrEqual 4
    }

    @Test
    fun `G8 a legacy table has only small-file candidates, a full-size file is never one`() {
        val rule = CompactionCandidateRule(profile(), tableSortOrderId = null)
        val files =
            (1..5).map { Fact("small$it", "0/p=1", 10 * MIB, sortOrderId = null) } +
                Fact("full", "0/p=1", 64 * MIB, sortOrderId = null) +
                Fact("lonely-small", "0/p=2", 10 * MIB, sortOrderId = null)
        val candidates = rule.candidates(files, Fact::partition, Fact::size, Fact::sortOrderId).map { it.key }
        candidates shouldContainExactly (1..5).map { "small$it" }

        val sorted = CompactionCandidateRule(profile(), tableSortOrderId = 1)
        val stamped =
            listOf(Fact("full-unsorted", "0/p=1", 64 * MIB, sortOrderId = 0), Fact("full-sorted", "0/p=1", 64 * MIB, sortOrderId = 1))
        sorted.candidates(stamped, Fact::partition, Fact::size, Fact::sortOrderId).map { it.key } shouldContainExactly
            listOf("full-unsorted")
    }

    internal data class Fact(
        val key: String,
        val partition: String,
        val size: Long,
        val sortOrderId: Int?,
    )

    private fun fiveForty() = (1..5).map { small("f$it", 40, seq = it.toLong()) }

    private infix fun Long.shouldBeLessThanOrEqualLong(other: Long) = (this <= other) shouldBe true

    companion object {
        const val MIB = 1L shl 20
        const val AMPLE = 1L shl 30
        const val P1 = "0/p=1"
        const val P2 = "0/p=2"

        /** Target 64 MiB, ratio 0.75 (small below 48 MiB), minimum 5, cap 128 MiB. */
        fun profile(): LakeProfile =
            LakeProfileDocument.parse(
                LakeProfileFixtures.document(
                    "maintenance.target_file_bytes" to "${64 * MIB}",
                    "maintenance.max_bytes_rewritten_per_run" to "${128 * MIB}",
                ),
            )
    }
}
