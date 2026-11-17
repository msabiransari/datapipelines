package co.datapipelines.config

import co.datapipelines.config.ProcessMemoryAdmission.Term
import co.datapipelines.config.ProcessMemoryAdmission.Verdict
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.env.MockEnvironment
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

/**
 * §7 / §3.36 (#819) — the process memory admission: `staging + reader + heap + reserve < limit × (1 − headroom)`,
 * strict, exact, with a breakdown line on every outcome; the two keys' domains at every boot; the exact staging
 * count (D10) against an exhaustive oracle; the container limit read from cgroup files only.
 */
class ProcessMemoryAdmissionTest {
    private val shipped: Map<String, String?> by lazy { ConfigSnapshots.lakeDefaults() }

    private fun snapshot(
        lake: Map<String, String?> = emptyMap(),
        heap: Long? = TWO_GIB,
        limit: ContainerMemoryLimit? = finite(SIXTEEN_GIB),
    ) = ConfigSnapshots.valid().copy(lake = shipped + lake, maxHeapBytes = heap, containerMemoryLimit = limit)

    private fun check(snapshot: ConfigSnapshot): Pair<List<String>, List<String>> {
        val violations = mutableListOf<String>()
        val notices = mutableListOf<String>()
        ProcessMemoryAdmission.checkProcessMemoryAdmission(snapshot, violations, notices)
        return violations to notices
    }

    private fun verdict(
        limit: Long,
        headroom: String,
    ) = ProcessMemoryAdmission
        .evaluate(Term(1, 100), Term(2, 50), heapBytes = 300, reserveBytes = 100, headroom = BigDecimal(headroom), finite(limit))
        .result

    @Test
    fun `strict below the boundary - below admitted, equal refused, above refused`() {
        // total = 100 + 2×50 + 300 + 100 = 600; right side = limit × 0.75.
        withClue("limit 801 → right side 600.75 > 600") { verdict(801, "0.25") shouldBe Verdict.ADMITTED }
        withClue("limit 800 → right side exactly 600: an equal sum is refused") { verdict(800, "0.25") shouldBe Verdict.REFUSED }
        withClue("limit 799 → right side 599.25 < 600") { verdict(799, "0.25") shouldBe Verdict.REFUSED }
    }

    @Test
    fun `headroom 0 compares against the whole limit, and an equal sum is still refused`() {
        verdict(601, "0") shouldBe Verdict.ADMITTED
        verdict(600, "0") shouldBe Verdict.REFUSED
        verdict(601, "0.0") shouldBe Verdict.ADMITTED
    }

    @ParameterizedTest
    @ValueSource(strings = ["staging", "reader", "heap", "reserve"])
    fun `each consumer term is in the sum - without it the case would be admitted`(term: String) {
        // Bytes a decade apart; the right side sits half of the named term below the total, so dropping the
        // named term (or a larger one) admits — this case is red for its own term's removal.
        val bytes = mapOf("staging" to 8000L, "reader" to 800L, "heap" to 80L, "reserve" to 8L)
        val total = bytes.values.sum()
        val right = BigDecimal(total) - BigDecimal(bytes.getValue(term)) / BigDecimal(2)
        // limit × (1 − 0.5) = right ⇒ limit = 2 × right, a whole number here.
        val limit = (right * BigDecimal(2)).longValueExact()
        val breakdown =
            ProcessMemoryAdmission.evaluate(
                Term(1, bytes.getValue("staging")),
                Term(2, bytes.getValue("reader") / 2),
                heapBytes = bytes.getValue("heap"),
                reserveBytes = bytes.getValue("reserve"),
                headroom = BigDecimal("0.5"),
                limit = finite(limit),
            )
        withClue("the $term term must be counted (total $total vs right side $right)") {
            breakdown.result shouldBe Verdict.REFUSED
            breakdown.line shouldContain "total=${total}B"
        }
    }

    @Test
    fun `heap and limit come from their own inputs - each used in its own place`() {
        // Lake disabled: 2 GiB staging + 2 GiB heap + 1 GiB reserve = 5 GiB < 16 GiB × 0.9. Swapped (heap 16 GiB,
        // limit 2 GiB) it would be refused, so a swap anywhere in the wiring turns this red.
        val (violations, notices) = check(snapshot(heap = TWO_GIB, limit = finite(SIXTEEN_GIB)))
        violations.shouldBeEmpty()
        notices shouldHaveSize 1
        notices.single() shouldContain "heap=${TWO_GIB}B"
        notices.single() shouldContain "limit=${SIXTEEN_GIB}B"
        notices.single() shouldContain "result=admitted"
    }

    @Test
    fun `snapshotFrom takes the limit from the injected source and the heap from the runtime`() {
        val injected = ContainerMemoryLimit.Finite(SIXTEEN_GIB, "injected")
        val snapshot = ConfigValidator.snapshotFrom(MockEnvironment()) { injected }
        snapshot.containerMemoryLimit shouldBe injected
        snapshot.maxHeapBytes shouldBe Runtime.getRuntime().maxMemory()
    }

    @Test
    fun `the three lines are pinned - admitted, refused and not compared, lake disabled`() {
        val disabledTail =
            "staging=2x1073741824B=2147483648B(datapipelines.lake.admission.staging-memory-limit) reader=absent(lake_disabled) " +
                "heap=2147483648B reserve=1073741824B(datapipelines.lake.admission.process-reserve) " +
                "headroom=0.10(datapipelines.lake.admission.memory-headroom)"
        check(snapshot()).second shouldContainExactly
            listOf(
                "process_memory_admission result=admitted $disabledTail limit=17179869184B limit_source=test " +
                    "total=5368709120B right_side=15461882265.60B",
            )
        val (refused, noNotice) = check(snapshot(limit = finite(4L * GIB)))
        noNotice.shouldBeEmpty()
        refused shouldContainExactly
            listOf(
                "process memory admission refused: staging + reader + heap + reserve must be below the container limit × " +
                    "(1 − datapipelines.lake.admission.memory-headroom) (configuration.md §7, §3.36). " +
                    "process_memory_admission result=refused $disabledTail limit=4294967296B limit_source=test " +
                    "total=5368709120B right_side=3865470566.40B",
            )
        check(snapshot(limit = ContainerMemoryLimit.Unlimited("cgroup_v2:/x/memory.max"))).second shouldContainExactly
            listOf(
                "process_memory_admission result=not_compared $disabledTail limit=unlimited " +
                    "limit_source=cgroup_v2:/x/memory.max total=5368709120B right_side=none",
            )
    }

    @Test
    fun `unavailable is not compared either, and neither is ever a violation`() {
        val unavailable = ContainerMemoryLimit.Unavailable("no_readable:/p")
        val (violations, notices) = check(snapshot(heap = Long.MAX_VALUE / 4, limit = unavailable))
        violations.shouldBeEmpty()
        notices.single() shouldContain "result=not_compared"
        notices.single() shouldContain "limit=unavailable limit_source=no_readable:/p"
    }

    @Test
    fun `lake enabled - the reader term is 1 + K generations and the staging count includes the compaction`() {
        val enabled = mapOf(LakeRules.ENABLED to "true")
        check(snapshot(enabled)).second.single() shouldContain
            "staging=2x1073741824B=2147483648B(datapipelines.lake.admission.staging-memory-limit) " +
            "reader=2x536870912B=1073741824B(1+datapipelines.lake.reads.max-retired-generations) " +
            "heap=2147483648B reserve=1073741824B"
        check(snapshot(enabled + (LakeRules.MAX_RETIRED_GENERATIONS to "2"))).second.single() shouldContain
            "reader=3x536870912B=1610612736B(1+datapipelines.lake.reads.max-retired-generations)"
        // The compaction is the seventh database: without a maintenance slot the same budget opens six.
        ProcessMemoryAdmission.stagingDatabases(20 * GIB, 3 * GIB, 6 * GIB, ingestions = 2, maintenance = 1) shouldBe 7
        ProcessMemoryAdmission.stagingDatabases(20 * GIB, 3 * GIB, 6 * GIB, ingestions = 2, maintenance = 0) shouldBe 6
    }

    @Test
    fun `lake disabled - standalone staging only, floor of scratch over one reservation`() {
        ProcessMemoryAdmission.stagingDatabases(20 * GIB, 3 * GIB, 0, ingestions = 0, maintenance = 0) shouldBe 6
        check(snapshot()).second.single() shouldContain "staging=2x"
        check(snapshot(mapOf(LakeRules.SCRATCH_BYTES to "30GB"))).second.single() shouldContain "staging=3x"
    }

    @Test
    fun `D10 - the closed form equals the exhaustive search over every small configuration`() {
        var compared = 0
        // One index decoded into S 1..40, R 1..9, D 1..12, I 0..4, M 0..4 (mixed radix, M fastest).
        for (index in 0L until SWEEP) {
            val m = index % 5
            val i = index / 5 % 5
            val d = index / 25 % 12 + 1
            val r = index / 300 % 9 + 1
            val s = index / 2700 + 1
            val oracle = exhaustive(s, r, d, i, m)
            withClue("S=$s R=$r D=$d I=$i M=$m") { ProcessMemoryAdmission.stagingDatabases(s, r, d, i, m) shouldBe oracle }
            compared++
        }
        // The non-vacuity floor: the sweep really ran its 40 × 9 × 12 × 5 × 5 configurations.
        compared shouldBe 108_000
    }

    @Test
    fun `the two keys are judged at every boot, refused by key name, lake on or off`() {
        for (lake in listOf("false", "true")) {
            for (bad in listOf("0", "-1GB", "", "abc")) {
                val (violations, _) = check(snapshot(mapOf(LakeRules.ENABLED to lake, LakeRules.PROCESS_RESERVE to bad)))
                withClue("lake=$lake process-reserve='$bad'") {
                    violations shouldContainExactly
                        listOf("datapipelines.lake.admission.process-reserve must be a positive size such as 1GB or 512MB (§3.36).")
                }
            }
            for (bad in listOf("1", "1.5", "-0.1", "NaN", "Infinity", "", "abc")) {
                val (violations, _) = check(snapshot(mapOf(LakeRules.ENABLED to lake, LakeRules.MEMORY_HEADROOM to bad)))
                withClue("lake=$lake memory-headroom='$bad'") {
                    violations shouldContainExactly
                        listOf(
                            "datapipelines.lake.admission.memory-headroom must be a decimal number at least 0 and below 1, " +
                                "such as 0.10 (§3.36).",
                        )
                }
            }
            val goods = listOf(LakeRules.PROCESS_RESERVE to "512MB", LakeRules.MEMORY_HEADROOM to "0", LakeRules.MEMORY_HEADROOM to "0.99")
            for ((key, good) in goods) {
                // Inside the domain; the sum itself is not this case (0.99 leaves too little of a 16 GiB limit).
                val inDomain = snapshot(mapOf(LakeRules.ENABLED to lake, key to good), limit = ContainerMemoryLimit.Unlimited("u"))
                withClue("lake=$lake $key='$good'") { check(inDomain).first.shouldBeEmpty() }
            }
        }
        // Judged with no limit to compare against too: a domain error is never hidden by not_compared.
        check(snapshot(mapOf(LakeRules.PROCESS_RESERVE to "abc"), heap = null, limit = null)).first shouldHaveSize 1
    }

    @Test
    fun `an oversized multiplication is refused naming its term, with or without a limit`() {
        val staging =
            mapOf(
                LakeRules.ENABLED to "true",
                LakeRules.STAGING_MEMORY_LIMIT to "4194304TB",
                LakeRules.STAGING_EXPANSION_FACTOR to "1",
                LakeRules.SCRATCH_BYTES to "4194304TB",
                LakeRules.MAX_DELIVERY_BYTES to "1B",
                LakeRules.MAX_CONCURRENT_INGESTIONS to "3",
            )
        for (limit in listOf(finite(SIXTEEN_GIB), ContainerMemoryLimit.Unlimited("u"))) {
            check(snapshot(staging, limit = limit)).first.single() shouldStartWith
                "process memory admission: the staging term does not fit a 64-bit byte count; lower " +
                "datapipelines.lake.admission.staging-memory-limit or datapipelines.lake.admission.scratch-bytes"
        }
        check(snapshot(mapOf(LakeRules.ENABLED to "true", LakeRules.READS_MEMORY_LIMIT to "4194304TB"))).first.single() shouldStartWith
            "process memory admission: the reader term does not fit a 64-bit byte count; lower " +
            "datapipelines.lake.reads.memory-limit or datapipelines.lake.reads.max-retired-generations"
        check(snapshot(heap = Long.MAX_VALUE)).first.single() shouldStartWith
            "process memory admission: the total term does not fit a 64-bit byte count"
    }

    @Test
    fun `a malformed term input is left to its own rule - no breakdown, no second violation`() {
        check(snapshot(mapOf(LakeRules.STAGING_MEMORY_LIMIT to "abc"))) shouldBe (emptyList<String>() to emptyList())
        check(snapshot(mapOf(LakeRules.ENABLED to "true", LakeRules.MAX_RETIRED_GENERATIONS to "0"))) shouldBe
            (emptyList<String>() to emptyList())
    }

    @Test
    fun `the validator carries the refusal as a violation and the admitted line as a notice`() {
        val refused = ConfigValidator.validate(snapshot(limit = finite(4L * GIB)))
        refused.violations.filter { it.startsWith("process memory admission refused") } shouldHaveSize 1
        val admitted = ConfigValidator.validate(snapshot())
        admitted.violations.shouldBeEmpty()
        admitted.notices.single() shouldStartWith "process_memory_admission result=admitted "
    }

    @Test
    fun `cgroup v2 - the smallest memory max from the own group up to the root`(
        @TempDir root: Path,
    ) {
        Files.writeString(root.resolve("cgroup.controllers"), "memory\n")
        val own = Files.createDirectories(root.resolve("a/b"))
        Files.writeString(root.resolve("a/memory.max"), "4294967296\n")
        Files.writeString(own.resolve("memory.max"), "max\n")
        val self = Files.writeString(root.resolve("self-cgroup"), "0::/a/b\n")
        val limit = CgroupMemoryLimitSource(root, self).read()
        limit shouldBe ContainerMemoryLimit.Finite(4_294_967_296, "cgroup_v2:${root.resolve("a/memory.max")}")

        Files.writeString(root.resolve("a/memory.max"), "max\n")
        CgroupMemoryLimitSource(root, self).read().shouldBeInstanceOf<ContainerMemoryLimit.Unlimited>()
    }

    @Test
    fun `cgroup v2 - no memory max anywhere and an escaping path are unavailable or bounded to the root`(
        @TempDir root: Path,
    ) {
        Files.writeString(root.resolve("cgroup.controllers"), "")
        val self = Files.writeString(root.resolve("self-cgroup"), "0::/../../etc\n")
        CgroupMemoryLimitSource(root, self).read().shouldBeInstanceOf<ContainerMemoryLimit.Unavailable>()
    }

    @Test
    fun `cgroup v1 - the memory controller's limit, LONG_MAX page-rounded is unlimited`(
        @TempDir root: Path,
    ) {
        val group = Files.createDirectories(root.resolve("memory/docker/abc"))
        val self = Files.writeString(root.resolve("self-cgroup"), "4:cpu,cpuacct:/docker/abc\n3:memory:/docker/abc\n")
        Files.writeString(group.resolve("memory.limit_in_bytes"), "2147483648\n")
        CgroupMemoryLimitSource(root, self).read() shouldBe
            ContainerMemoryLimit.Finite(TWO_GIB, "cgroup_v1:${group.resolve("memory.limit_in_bytes")}")
        Files.writeString(group.resolve("memory.limit_in_bytes"), "9223372036854771712\n")
        CgroupMemoryLimitSource(root, self).read().shouldBeInstanceOf<ContainerMemoryLimit.Unlimited>()
    }

    @Test
    fun `no readable self cgroup, no memory controller and an unparsable file are unavailable, never host RAM`(
        @TempDir root: Path,
    ) {
        CgroupMemoryLimitSource(root, root.resolve("missing")).read().shouldBeInstanceOf<ContainerMemoryLimit.Unavailable>()
        val noMemory = Files.writeString(root.resolve("self-a"), "4:cpu:/x\n")
        CgroupMemoryLimitSource(root, noMemory).read().shouldBeInstanceOf<ContainerMemoryLimit.Unavailable>()
        Files.writeString(Files.createDirectories(root.resolve("memory")).resolve("memory.limit_in_bytes"), "lots\n")
        val v1 = Files.writeString(root.resolve("self-b"), "3:memory:/\n")
        CgroupMemoryLimitSource(root, v1).read() shouldBe
            ContainerMemoryLimit.Unavailable("unparsable:${root.resolve("memory/memory.limit_in_bytes")}")
    }

    @Test
    fun `cgroup v1 - a finite parent binds an unlimited child - the effective limit is the parent's`(
        @TempDir root: Path,
    ) {
        val (parent, self) = v1Hierarchy(root, parentLimit = "4294967296", childLimit = "9223372036854771712")
        CgroupMemoryLimitSource(root, self).read() shouldBe
            ContainerMemoryLimit.Finite(FOUR_GIB, "cgroup_v1:${parent.resolve("memory.limit_in_bytes")}")
    }

    @Test
    fun `cgroup v1 - a tighter parent wins over a larger finite child`(
        @TempDir root: Path,
    ) {
        val (parent, self) = v1Hierarchy(root, parentLimit = "4294967296", childLimit = "$SIXTEEN_GIB")
        CgroupMemoryLimitSource(root, self).read() shouldBe
            ContainerMemoryLimit.Finite(FOUR_GIB, "cgroup_v1:${parent.resolve("memory.limit_in_bytes")}")
    }

    @Test
    fun `cgroup v1 - a tighter child wins, an unlimited hierarchy stays unlimited, and the walk stays in the mount`(
        @TempDir root: Path,
    ) {
        val (_, self) = v1Hierarchy(root, parentLimit = "$SIXTEEN_GIB", childLimit = "$TWO_GIB")
        val child = root.resolve("memory/parent/child/memory.limit_in_bytes")
        CgroupMemoryLimitSource(root, self).read() shouldBe ContainerMemoryLimit.Finite(TWO_GIB, "cgroup_v1:$child")

        Files.writeString(root.resolve("memory/parent/memory.limit_in_bytes"), "9223372036854771712\n")
        Files.writeString(child, "9223372036854771712\n")
        Files.writeString(root.resolve("memory/memory.limit_in_bytes"), "9223372036854771712\n")
        CgroupMemoryLimitSource(root, self).read() shouldBe ContainerMemoryLimit.Unlimited("cgroup_v1:$child")

        // An escaping path falls back to the mount root; a limit file beside the mount is never read.
        Files.writeString(Files.createDirectories(root.resolve("outside")).resolve("memory.limit_in_bytes"), "1\n")
        val escaping = Files.writeString(root.resolve("self-escaping"), "3:memory:/../outside\n")
        CgroupMemoryLimitSource(root, escaping).read() shouldBe
            ContainerMemoryLimit.Unlimited("cgroup_v1:${root.resolve("memory/memory.limit_in_bytes")}")
    }

    @Test
    fun `cgroup v2 - a tighter parent wins over a larger finite child`(
        @TempDir root: Path,
    ) {
        Files.writeString(root.resolve("cgroup.controllers"), "memory\n")
        val child = Files.createDirectories(root.resolve("parent/child"))
        Files.writeString(root.resolve("parent/memory.max"), "4294967296\n")
        Files.writeString(child.resolve("memory.max"), "$SIXTEEN_GIB\n")
        val self = Files.writeString(root.resolve("self-cgroup"), "0::/parent/child\n")
        CgroupMemoryLimitSource(root, self).read() shouldBe
            ContainerMemoryLimit.Finite(FOUR_GIB, "cgroup_v2:${root.resolve("parent/memory.max")}")
    }

    @Test
    fun `an unreadable ancestor limit is unavailable, never unlimited - v1 and v2`(
        @TempDir root: Path,
    ) {
        val (parent, v1Self) = v1Hierarchy(root.resolve("v1"), parentLimit = "4294967296", childLimit = "9223372036854771712")
        val v1File = parent.resolve("memory.limit_in_bytes")
        val v2Root = Files.createDirectories(root.resolve("v2"))
        Files.writeString(v2Root.resolve("cgroup.controllers"), "memory\n")
        Files.createDirectories(v2Root.resolve("parent/child"))
        val v2File = Files.writeString(v2Root.resolve("parent/memory.max"), "4294967296\n")
        Files.writeString(v2Root.resolve("parent/child/memory.max"), "max\n")
        val v2Self = Files.writeString(v2Root.resolve("self-cgroup"), "0::/parent/child\n")
        listOf(v1File, v2File).forEach { Files.setPosixFilePermissions(it, emptySet()) }
        // As root every file stays readable, and the unreadable case cannot be built.
        assumeFalse(Files.isReadable(v1File) || Files.isReadable(v2File), "the test user can read a mode-000 file")
        CgroupMemoryLimitSource(root.resolve("v1"), v1Self).read() shouldBe ContainerMemoryLimit.Unavailable("unreadable:$v1File")
        CgroupMemoryLimitSource(v2Root, v2Self).read() shouldBe ContainerMemoryLimit.Unavailable("unreadable:$v2File")
    }

    @Test
    fun `the admission compares against, and refuses by, the effective 4 GiB limit under a 16 GiB child`(
        @TempDir root: Path,
    ) {
        val (parent, self) = v1Hierarchy(root, parentLimit = "4294967296", childLimit = "$SIXTEEN_GIB")
        val effective = CgroupMemoryLimitSource(root, self).read()
        // Lake disabled: 5 GiB total. Below 16 GiB × 0.9 (the child alone admits it); above 4 GiB × 0.9.
        check(snapshot(limit = finite(SIXTEEN_GIB))).first.shouldBeEmpty()
        val (violations, notices) = check(snapshot(limit = effective))
        notices.shouldBeEmpty()
        violations.single() shouldContain "result=refused"
        violations.single() shouldContain "limit=${FOUR_GIB}B limit_source=cgroup_v1:${parent.resolve("memory.limit_in_bytes")} "
    }

    /** A v1 memory mount under [root] with `/parent/child`, the process in the child; returns the parent dir and the self file. */
    private fun v1Hierarchy(
        root: Path,
        parentLimit: String,
        childLimit: String,
    ): Pair<Path, Path> {
        val parent = Files.createDirectories(root.resolve("memory/parent"))
        val child = Files.createDirectories(parent.resolve("child"))
        Files.writeString(parent.resolve("memory.use_hierarchy"), "1\n")
        Files.writeString(parent.resolve("memory.limit_in_bytes"), "$parentLimit\n")
        Files.writeString(child.resolve("memory.limit_in_bytes"), "$childLimit\n")
        return parent to Files.writeString(root.resolve("self-cgroup"), "4:cpu,cpuacct:/parent/child\n3:memory:/parent/child\n")
    }

    /** The definition of D10 by brute force: every d ≤ I, m ≤ M with d·D + m ≤ S, standalones fill the rest. */
    private fun exhaustive(
        s: Long,
        r: Long,
        d: Long,
        i: Long,
        m: Long,
    ): Long {
        var best = 0L
        for (deliveries in 0L..i) {
            for (compactions in 0L..m) {
                val left = s - deliveries * d - compactions
                if (left >= 0) best = maxOf(best, deliveries + compactions + left / r)
            }
        }
        return best
    }

    private companion object {
        const val GIB = 1L shl 30
        const val TWO_GIB = 2 * GIB
        const val FOUR_GIB = 4 * GIB
        const val SIXTEEN_GIB = 16 * GIB
        const val SWEEP = 40L * 9 * 12 * 5 * 5

        fun finite(bytes: Long) = ContainerMemoryLimit.Finite(bytes, "test")
    }
}
