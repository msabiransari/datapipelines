package co.datapipelines.lake.maintenance

import java.time.Instant
import java.util.UUID

/**
 * What a maintenance part answered (#957 D6; the P6 lane cut §3's outcome words). The [wire] value is what the run row's
 * `parts_json` and the audit event carry: the cut's words in snake case (`nothing to do` is `nothing_to_do`, `paused
 * until <instant>` is `paused_until` with the instant in the part's `until`).
 */
enum class MaintenanceOutcome(
    val wire: String,
) {
    DONE("done"),
    NOTHING_TO_DO("nothing_to_do"),

    /** The table lease was refused (another operation holds the table): the RUN ends here (D6). */
    BUSY("busy"),

    /** The run's deadline passed before or during the part: the RUN ends here (D5, D6). */
    DEADLINE("deadline"),

    /** The part needs scratch the run does not hold (D3 step 7); it comes again with the table's next task. */
    CAPACITY("capacity"),
    OUTSIDE_WINDOW("outside_window"),
    PAUSED_UNTIL("paused_until"),

    /** Recorded only: the part threw, the run ends `failed` (D6). Never answered by a part. */
    FAILED("failed"),
}

/** What one part did, in the figures the run row sums (D4). */
data class PartFigures(
    val files: Long = 0,
    val bytes: Long = 0,
    val deferredDeletions: Long = 0,
) {
    init {
        require(files >= 0 && bytes >= 0 && deferredDeletions >= 0) { "part figures are never negative: $this" }
    }

    operator fun plus(other: PartFigures) =
        PartFigures(
            files + other.files,
            bytes + other.bytes,
            deferredDeletions + other.deferredDeletions,
        )

    companion object {
        val NONE = PartFigures()
    }
}

/**
 * A part's answer: its outcome, the figures of what it did, and the instant of a `paused_until`. [details] are the
 * part's own fields, written at the top level of its `parts_json` entry beside the common ones (#969: compaction's
 * `committed` and `left_for_fresh_run`, which 959's plan reads, and its blocked files and group figures); a key may
 * not repeat a common one ([PartRecord.COMMON_KEYS]).
 */
data class PartResult(
    val outcome: MaintenanceOutcome,
    val figures: PartFigures = PartFigures.NONE,
    val until: Instant? = null,
    val details: Map<String, Any?> = emptyMap(),
) {
    init {
        require(outcome != MaintenanceOutcome.FAILED) { "a part never answers failed: it throws" }
        require((outcome == MaintenanceOutcome.PAUSED_UNTIL) == (until != null)) { "only paused_until carries an instant: $this" }
        require(details.keys.none { it in PartRecord.COMMON_KEYS }) { "a part's details never repeat a common key: ${details.keys}" }
    }

    companion object {
        val NOTHING_TO_DO = PartResult(MaintenanceOutcome.NOTHING_TO_DO)
    }
}

/**
 * The table as the metadata database's rows describe it (D3 step 3): an id, a namespace, a name and a lifecycle status.
 * It can read nothing: a part's declaration is handed this and no Iceberg handle.
 */
data class MaintenanceTable(
    val id: UUID,
    val namespace: String,
    val name: String,
    val status: String,
) {
    /** The table's writer lease name (`<namespace>.<table>`, design §8a). */
    val leaseName: String
        get() = "$namespace.$name"

    val purged: Boolean
        get() = status == STATUS_PURGED

    companion object {
        const val STATUS_PURGED = "purged"
    }
}

/** A part's declaration before admission (D3 step 3), from database rows only. */
data class MaintenanceDeclaration(
    /** Whether the part expects to be due for this table. */
    val expectsDue: Boolean,
    /** The scratch bytes it would need; zero for a part that needs none (L4). */
    val scratchBytes: Long,
) {
    init {
        require(scratchBytes >= 0) { "a declared scratch demand is never negative: $scratchBytes" }
    }

    companion object {
        val NONE = MaintenanceDeclaration(expectsDue = false, scratchBytes = 0)
    }
}

/** `lake_maintenance_runs.kind` (V60). */
enum class MaintenanceRunKind(
    val wire: String,
) {
    MAINTENANCE("maintenance"),
    PURGE("purge"),
}

/** `lake_maintenance_runs.state` (V60). */
enum class MaintenanceRunState(
    val wire: String,
) {
    RUNNING("running"),
    FINISHED("finished"),
    FAILED("failed"),
    ABANDONED("abandoned"),
}

/** One entry of the run row's `parts_json`. */
data class PartRecord(
    val name: String,
    val outcome: MaintenanceOutcome,
    /** False for a part recorded without being started: held by the gate, after `busy`/`deadline`, or `capacity`. */
    val attempted: Boolean,
    val figures: PartFigures = PartFigures.NONE,
    val until: Instant? = null,
    /** The gate's reason for a held part. */
    val reason: String? = null,
    /** A failed part's error code. */
    val errorCode: String? = null,
    /** The part's own fields ([PartResult.details]), at the top level of its entry. */
    val details: Map<String, Any?> = emptyMap(),
) {
    companion object {
        /** The keys every `parts_json` entry carries; a part's [details] never repeat one. */
        val COMMON_KEYS: Set<String> =
            setOf("name", "outcome", "attempted", "files", "bytes", "deferred_deletions", "until", "reason", "error_code")
    }
}

/** The settlement step's figures in the run row (D6a). */
data class SettlementRecord(
    /** Deliveries the page moved to a terminal state. */
    val settled: Int,
    /** Deliveries the page visited. */
    val visited: Int,
    /** True when the table holds unsettled deliveries past the page (they wait for a later pass or the sweep). */
    val leftUnsettled: Boolean,
    /** True when the page stopped at a failing row or a failure outside the rows. */
    val deferred: Boolean,
    /** The failure's code when [deferred]; never a message or a stack. */
    val errorCode: String? = null,
)
