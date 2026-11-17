package co.datapipelines.config

import org.springframework.util.unit.DataSize
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * §7 / §3.36 (#819; dp-lake design record §6 and its 2026-11-15 amendment) — the process memory admission: every
 * memory budget of the native engines the product owns, plus the JVM's maximum heap and a process reserve, must be
 * **strictly less** than the container's memory limit less a headroom share:
 *
 *     staging + reader + heap + reserve < limit × (1 − headroom)
 *
 * - **staging** = the most DuckDB staging databases the one scratch budget admits at once × `staging-memory-limit`
 *   ([stagingDatabases]; 2 at the shipped defaults). Counted with the lake disabled too: standalone staging runs
 *   on every deployment.
 * - **reader** = `(1 + reads.max-retired-generations) × reads.memory-limit`: the live or building managed reader
 *   generation plus at most K retired ones still holding an engine (the pool manager's cap, owner ruling
 *   2026-11-15). Absent with the lake disabled.
 * - **heap** = `Runtime.maxMemory()` ([ConfigSnapshot.maxHeapBytes]); **limit** = a separate input, the container's
 *   cgroup limit ([ContainerMemoryLimitSource]). Neither is ever derived from the other, and host RAM is never read.
 *
 * Not counted (owner ruling 2026-11-15, #1006): user-registered embedded DuckDB datasources and LAKE datasources
 * that are not the managed one.
 *
 * Both keys' domains are judged at every boot, lake on or off, limit or none. The sum is compared only when both are
 * inside their domains, every term's input is valid (a malformed one is named by its own rule) and the limit is
 * finite. The arithmetic is exact: `Long` with overflow refused naming the term, and the headroom a [BigDecimal].
 * The breakdown line ([Breakdown.line]) is produced on every outcome: `admitted` and `not compared` are logged at
 * INFO by `ConfigValidator.validateOnStartup`; `refused` is carried by the violation.
 */
internal object ProcessMemoryAdmission {
    /** The words of the line's `result` field (lane 997 reads them). */
    enum class Verdict(
        val word: String,
    ) {
        ADMITTED("admitted"),
        REFUSED("refused"),
        NOT_COMPARED("not_compared"),
    }

    /** A consumer term: [count] instances of [bytesEach] each. */
    data class Term(
        val count: Long,
        val bytesEach: Long,
    ) {
        val bytes: Long get() = Math.multiplyExact(count, bytesEach)
    }

    /** One evaluated admission; [line] is the same fields on every outcome. */
    data class Breakdown(
        val result: Verdict,
        val line: String,
    )

    /** A term or the total does not fit a 64-bit byte count; [term] names it. */
    class TermOverflow(
        val term: String,
        cause: ArithmeticException,
    ) : RuntimeException("the $term term does not fit a 64-bit byte count", cause)

    /** The consumer terms; [reader] is null with the lake disabled. */
    data class Terms(
        val staging: Term,
        val reader: Term?,
    )

    /**
     * The §7 rule: the two keys' domains always, then the sum when it can be compared. `admitted` and `not compared`
     * lines go to [notices], a `refused` line into [violations].
     */
    fun checkProcessMemoryAdmission(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
        notices: MutableList<String>,
    ) {
        val reserve = reserve(snapshot, violations)
        val headroom = headroom(snapshot, violations)
        if (reserve == null || headroom == null) return
        // Null limit or heap = not supplied: a snapshot-driven unit test that is not about this rule (snapshotFrom
        // supplies both). Null terms = a malformed input, already named by its own rule.
        val limit = snapshot.containerMemoryLimit ?: return
        val heap = snapshot.maxHeapBytes ?: return
        val breakdown =
            try {
                terms(snapshot)?.let { evaluate(it.staging, it.reader, heap, reserve, headroom, limit) }
            } catch (overflow: TermOverflow) {
                violations +=
                    "process memory admission: ${overflow.message}; lower ${keysOf(overflow.term)} (configuration.md §7, §3.36)."
                null
            }
        report(breakdown ?: return, violations, notices)
    }

    private fun report(
        breakdown: Breakdown,
        violations: MutableList<String>,
        notices: MutableList<String>,
    ) {
        when (breakdown.result) {
            Verdict.REFUSED -> {
                violations +=
                    "process memory admission refused: staging + reader + heap + reserve must be below the container limit × " +
                    "(1 − ${LakeRules.MEMORY_HEADROOM}) (configuration.md §7, §3.36). ${breakdown.line}"
            }

            Verdict.ADMITTED, Verdict.NOT_COMPARED -> {
                notices += breakdown.line
            }
        }
    }

    /** The comparison and its line. Pure: every input is a parameter. */
    fun evaluate(
        staging: Term,
        reader: Term?,
        heapBytes: Long,
        reserveBytes: Long,
        headroom: BigDecimal,
        limit: ContainerMemoryLimit,
    ): Breakdown {
        val stagingBytes = exact("staging") { staging.bytes }
        val readerBytes = if (reader == null) 0L else exact("reader") { reader.bytes }
        val total = exact("total") { Math.addExact(Math.addExact(Math.addExact(stagingBytes, readerBytes), heapBytes), reserveBytes) }
        val rightSide = (limit as? ContainerMemoryLimit.Finite)?.let { BigDecimal.valueOf(it.bytes) * (BigDecimal.ONE - headroom) }
        val result =
            when {
                rightSide == null -> Verdict.NOT_COMPARED
                BigDecimal.valueOf(total) < rightSide -> Verdict.ADMITTED
                else -> Verdict.REFUSED
            }
        val readerField =
            if (reader == null) {
                "reader=absent(lake_disabled)"
            } else {
                "reader=${reader.count}x${reader.bytesEach}B=${readerBytes}B(1+${LakeRules.MAX_RETIRED_GENERATIONS})"
            }
        val limitField =
            when (limit) {
                is ContainerMemoryLimit.Finite -> "limit=${limit.bytes}B"
                is ContainerMemoryLimit.Unlimited -> "limit=unlimited"
                is ContainerMemoryLimit.Unavailable -> "limit=unavailable"
            }
        val line =
            "process_memory_admission result=${result.word} " +
                "staging=${staging.count}x${staging.bytesEach}B=${stagingBytes}B(${LakeRules.STAGING_MEMORY_LIMIT}) " +
                "$readerField heap=${heapBytes}B reserve=${reserveBytes}B(${LakeRules.PROCESS_RESERVE}) " +
                "headroom=${headroom.toPlainString()}(${LakeRules.MEMORY_HEADROOM}) $limitField limit_source=${limit.source} " +
                "total=${total}B right_side=${rightSide?.let { "${it.toPlainString()}B" } ?: "none"}"
        return Breakdown(result, line)
    }

    /**
     * D10 (owner ruling 2026-11-15; inventory T1+T2+T3) — the exact maximum of staging databases open at once. Every
     * one reserves from the scratch budget [scratch]: a delivery exactly [delivery] bytes (`max-delivery-bytes ×
     * staging-expansion-factor`, `DeliveryAdmission`), at most [ingestions] of them; a compaction scratch database at
     * least 1 byte (its demand is positive), at most [maintenance]; a standalone database exactly [reservation]
     * (`staging-memory-limit × staging-expansion-factor`), with no slot. So the count is the maximum over d ≤ I and
     * m ≤ M with d·D + m ≤ S of `d + m + floor((S − d·D − m) / R)`.
     *
     * Closed form, no loop over the slots. For a fixed d the best m is `min(M, S − d·D)`: one more compaction adds one
     * database and takes one byte, which costs at most one standalone. With that m, while `S − d·D ≥ M` the count is
     * `d + M + floor((S − M − d·D)/R)`, monotone in d (non-decreasing when D < R, non-increasing when D ≥ R); past it,
     * `S − d·(D − 1)`, non-increasing. The maximum is therefore at d = 0, at the last d of the first region, or at the
     * first d of the second: three candidates, plus the largest d for good measure. `ProcessMemoryAdmissionTest`
     * compares this with the exhaustive search over small configurations.
     *
     * With the lake disabled no delivery and no maintenance run exists (their beans are lake-only): I = M = 0, and the
     * count is `floor(S / R)`.
     */
    fun stagingDatabases(
        scratch: Long,
        reservation: Long,
        delivery: Long,
        ingestions: Long,
        maintenance: Long,
    ): Long {
        require(scratch > 0 && reservation > 0 && ingestions >= 0 && maintenance >= 0) { "staging inputs out of domain" }
        require(ingestions == 0L || delivery > 0) { "a delivery reservation must be positive" }
        val maxDeliveries = if (ingestions == 0L) 0L else minOf(ingestions, scratch / delivery)

        fun count(deliveries: Long): Long {
            val left = scratch - deliveries * delivery // deliveries ≤ scratch / delivery: no overflow, left ≥ 0
            val compactions = minOf(maintenance, left)
            return deliveries + compactions + (left - compactions) / reservation
        }
        val edge = if (ingestions == 0L || scratch < maintenance) 0L else minOf(maxDeliveries, (scratch - maintenance) / delivery)
        return listOf(0L, edge, minOf(maxDeliveries, edge + 1), maxDeliveries).maxOf(::count)
    }

    private fun terms(snapshot: ConfigSnapshot): Terms? {
        val staging = stagingTerm(snapshot) ?: return null
        if (!lakeEnabled(snapshot)) return Terms(staging, reader = null)
        return readerTerm(snapshot)?.let { Terms(staging, it) }
    }

    private fun stagingTerm(snapshot: ConfigSnapshot): Term? {
        val memory = size(snapshot, LakeRules.STAGING_MEMORY_LIMIT)
        val factor = whole(snapshot, LakeRules.STAGING_EXPANSION_FACTOR)?.takeIf { it <= Int.MAX_VALUE }
        val scratch = size(snapshot, LakeRules.SCRATCH_BYTES)
        if (memory == null || factor == null || scratch == null) return null
        val reservation = exact("staging") { Math.multiplyExact(memory, factor) }
        if (!lakeEnabled(snapshot)) return Term(stagingDatabases(scratch, reservation, 0L, 0L, 0L), memory)
        val ingestions = whole(snapshot, LakeRules.MAX_CONCURRENT_INGESTIONS)
        val maintenance = whole(snapshot, LakeRules.MAX_CONCURRENT_MAINTENANCE)
        val deliveryBytes = size(snapshot, LakeRules.MAX_DELIVERY_BYTES)
        if (ingestions == null || maintenance == null || deliveryBytes == null) return null
        val delivery = exact("staging") { Math.multiplyExact(deliveryBytes, factor) }
        return Term(stagingDatabases(scratch, reservation, delivery, ingestions, maintenance), memory)
    }

    private fun readerTerm(snapshot: ConfigSnapshot): Term? {
        val memory = size(snapshot, LakeRules.READS_MEMORY_LIMIT) ?: return null
        val cap = whole(snapshot, LakeRules.MAX_RETIRED_GENERATIONS)?.takeIf { it <= Int.MAX_VALUE } ?: return null
        return Term(1 + cap, memory)
    }

    private fun reserve(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ): Long? {
        val parsed = size(snapshot, LakeRules.PROCESS_RESERVE)
        if (parsed == null) violations += "${LakeRules.PROCESS_RESERVE} must be a positive size such as 1GB or 512MB (§3.36)."
        return parsed
    }

    /** `0 ≤ h < 1`, parsed as a decimal: `NaN`, `Infinity` and words do not parse; never a `Double`. */
    private fun headroom(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ): BigDecimal? {
        val parsed = value(snapshot, LakeRules.MEMORY_HEADROOM).toBigDecimalOrNull()?.takeIf { it.signum() >= 0 && it < BigDecimal.ONE }
        if (parsed == null) {
            violations += "${LakeRules.MEMORY_HEADROOM} must be a decimal number at least 0 and below 1, such as 0.10 (§3.36)."
        }
        return parsed
    }

    private fun keysOf(term: String): String =
        when (term) {
            "staging" -> "${LakeRules.STAGING_MEMORY_LIMIT} or ${LakeRules.SCRATCH_BYTES}"
            "reader" -> "${LakeRules.READS_MEMORY_LIMIT} or ${LakeRules.MAX_RETIRED_GENERATIONS}"
            else -> "the largest term (staging, reader, the heap or ${LakeRules.PROCESS_RESERVE})"
        }

    private inline fun <T> exact(
        term: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (overflow: ArithmeticException) {
            throw TermOverflow(term, overflow)
        }

    private fun lakeEnabled(snapshot: ConfigSnapshot): Boolean = value(snapshot, LakeRules.ENABLED).equals("true", ignoreCase = true)

    private fun value(
        snapshot: ConfigSnapshot,
        key: String,
    ): String = snapshot.lake[key]?.trim().orEmpty()

    private fun size(
        snapshot: ConfigSnapshot,
        key: String,
    ): Long? = runCatching { DataSize.parse(value(snapshot, key)).toBytes() }.getOrNull()?.takeIf { it > 0 }

    private fun whole(
        snapshot: ConfigSnapshot,
        key: String,
    ): Long? = value(snapshot, key).toLongOrNull()?.takeIf { it > 0 }
}

/** The container's memory limit as the process sees it; [source] says where it was read (a path, never a value). */
sealed interface ContainerMemoryLimit {
    val source: String

    data class Finite(
        val bytes: Long,
        override val source: String,
    ) : ContainerMemoryLimit

    data class Unlimited(
        override val source: String,
    ) : ContainerMemoryLimit

    data class Unavailable(
        override val source: String,
    ) : ContainerMemoryLimit
}

/** The injectable input of the container limit; separate from the heap (`Runtime.maxMemory()`) by construction. */
fun interface ContainerMemoryLimitSource {
    fun read(): ContainerMemoryLimit
}

/**
 * The EFFECTIVE cgroup memory limit of this process, from files only (no command, no host RAM, no heap): a limit on any
 * ancestor binds the process too, so the limit is the smallest finite one over the process's own cgroup and every
 * ancestor up to the mount root (#819 round 3). The own cgroup is `<mount>/<path>` from `/proc/self/cgroup` when that
 * directory exists inside the mount, else the mount root (a container sees its cgroup there); the walk never leaves
 * the mount.
 * - **v2** (the unified hierarchy: `<root>/cgroup.controllers` exists, `/proc/self/cgroup` has a `0::<path>` line):
 *   each level's `memory.max`; `max` is no limit at that level, and a level without the file (the memory controller
 *   not enabled there) adds none.
 * - **v1** (a `<n>:…memory…:<path>` line, mount `<root>/memory`): each level's `memory.limit_in_bytes`; a value at or
 *   above [V1_UNLIMITED_FROM] (the kernel's page-rounded `LONG_MAX`) is no limit at that level. The parent's limit is
 *   taken as binding whatever `memory.use_hierarchy` says: an over-counted constraint refuses a boot, an ignored one
 *   lets the kernel kill the process.
 * - No finite limit at any level is unlimited. No limit file at any level, a file that exists and cannot be read, a
 *   value that does not parse, or a read that fails is unavailable — never unlimited — with the reason in
 *   [ContainerMemoryLimit.source].
 */
class CgroupMemoryLimitSource(
    private val cgroupRoot: Path = Path.of("/sys/fs/cgroup"),
    private val selfCgroup: Path = Path.of("/proc/self/cgroup"),
) : ContainerMemoryLimitSource {
    override fun read(): ContainerMemoryLimit =
        try {
            resolve()
        } catch (e: java.io.IOException) {
            ContainerMemoryLimit.Unavailable("cgroup_read_failed:${e.javaClass.simpleName}")
        } catch (e: SecurityException) {
            ContainerMemoryLimit.Unavailable("cgroup_read_failed:${e.javaClass.simpleName}")
        }

    private fun resolve(): ContainerMemoryLimit {
        if (!Files.isReadable(selfCgroup)) return ContainerMemoryLimit.Unavailable("no_readable:$selfCgroup")
        val lines = Files.readAllLines(selfCgroup)
        val unified = lines.firstOrNull { it.startsWith("0::") }?.removePrefix("0::")
        if (unified != null && Files.exists(cgroupRoot.resolve("cgroup.controllers"))) return v2(unified)
        // A v1 line is `<id>:<controllers>:<path>`.
        val memory = lines.map { it.split(':', limit = V1_FIELDS) }.firstOrNull { it.size == V1_FIELDS && "memory" in it[1].split(',') }
        if (memory != null) return v1(memory.last())
        return ContainerMemoryLimit.Unavailable("no_memory_controller:$selfCgroup")
    }

    private fun v2(relative: String): ContainerMemoryLimit =
        effective(
            cgroupRoot.normalize(),
            relative,
            fileName = "memory.max",
            label = "cgroup_v2",
            missing = "cgroup_v2_no_memory.max",
        ) { raw -> if (raw == "max") Long.MAX_VALUE else raw.toLongOrNull() }

    private fun v1(relative: String): ContainerMemoryLimit =
        effective(
            cgroupRoot.resolve("memory").normalize(),
            relative,
            fileName = "memory.limit_in_bytes",
            label = "cgroup_v1",
            missing = "cgroup_v1_no_limit_file",
        ) { raw -> raw.toLongOrNull()?.let { if (it >= V1_UNLIMITED_FROM) Long.MAX_VALUE else it } }

    /**
     * The smallest finite [fileName] value over the own cgroup ([relative] under [mount]) and every ancestor up to
     * [mount] (class KDoc); [label] prefixes the source, [missing] is the reason when no level has the file. [parse]
     * turns a file's trimmed text into bytes, `Long.MAX_VALUE` for "no limit at this level", or null when it does not
     * parse.
     */
    private fun effective(
        mount: Path,
        relative: String,
        fileName: String,
        label: String,
        missing: String,
        parse: (String) -> Long?,
    ): ContainerMemoryLimit {
        val levels =
            generateSequence(inside(mount, relative) ?: mount) { dir -> if (dir == mount) null else dir.parent }
                .takeWhile { it.startsWith(mount) }
                .map { it.resolve(fileName) }
                .filter { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
                .toList()
        val unreadable = levels.firstOrNull { !Files.isReadable(it) }
        val parsed = if (unreadable == null) levels.map { it to parse(Files.readString(it).trim()) } else emptyList()
        val unparsable = parsed.firstOrNull { (_, bytes) -> bytes == null }?.first
        val smallest =
            parsed
                .mapNotNull { (file, bytes) -> bytes?.takeIf { it != Long.MAX_VALUE }?.let { file to it } }
                .minByOrNull { (_, bytes) -> bytes }
        return when {
            levels.isEmpty() -> ContainerMemoryLimit.Unavailable("$missing:$mount")
            unreadable != null -> ContainerMemoryLimit.Unavailable("unreadable:$unreadable")
            unparsable != null -> ContainerMemoryLimit.Unavailable("unparsable:$unparsable")
            smallest == null -> ContainerMemoryLimit.Unlimited("$label:${levels.first()}")
            else -> ContainerMemoryLimit.Finite(smallest.second, "$label:${smallest.first}")
        }
    }

    /** [relative] (a kernel-written cgroup path) under [root], or null when it would leave it. */
    private fun inside(
        root: Path,
        relative: String,
    ): Path? = root.resolve(relative.trimStart('/')).normalize().takeIf { it.startsWith(root) && Files.isDirectory(it) }

    companion object {
        /** v1 reports "no limit" as `LONG_MAX` rounded down to a page; any real limit is far below 4 EiB. */
        const val V1_UNLIMITED_FROM: Long = 1L shl 62

        /** The fields of a cgroup v1 line in `/proc/self/cgroup`. */
        private const val V1_FIELDS = 3
    }
}
