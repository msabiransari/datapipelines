package co.datapipelines.visualization

import java.time.Instant

/**
 * The stored facts of a visualization version's LATEST test run (the spec's §11.4 judges the latest run) — what the
 * release gate's read-side ladder and the dependency graph's `evidence_ready` fact are decided from (#873 R1).
 */
data class LatestRunFacts(
    val status: TestRunStatus,
    val bodyHash: String,
    val expiresAt: Instant,
) {
    /** A RUNNING row past its deadline that no read swept is EXPIRED ([TestRunRow.statusAt]'s rule). */
    fun statusAt(now: Instant): TestRunStatus {
        val expired = status == TestRunStatus.RUNNING && !expiresAt.isAfter(now)
        return if (expired) TestRunStatus.EXPIRED else status
    }
}

/**
 * The read-side classification of a version's stored release evidence (#873 R1; record §11.1 `evidence_ready`, §15.1),
 * SHARED by [VisualizationReleaseEvidence] — whose verdict maps every class but [READY] to its refusal — and by the
 * visualization dependency provider, whose node shows `evidence_ready = (class == READY)`. Stored facts only: the lock
 * on the live draft and the mechanical re-run stay the gate's own steps, so the graph never runs a probe.
 *
 * The ladder, in the gate's order: no run → [NO_RUNS]; EXPIRED → [EXPIRED]; a run for other content → [HASH_MISMATCH];
 * an open session → [OPEN]; RED or INCOMPLETE → [NOT_GREEN]; GREEN for this exact hash → [READY]. The `when` is
 * exhaustive over `TestRunStatus` with no `else`, so a new status cannot fall through to READY.
 */
enum class StoredEvidence {
    READY,
    NO_RUNS,
    EXPIRED,
    HASH_MISMATCH,
    OPEN,
    NOT_GREEN,
    ;

    companion object {
        /** The class of [latest] (null: no run) for the version whose stored body hash is [bodyHash], read at [now]. */
        fun classify(
            latest: LatestRunFacts?,
            bodyHash: String,
            now: Instant,
        ): StoredEvidence {
            if (latest == null) return NO_RUNS
            return when (val status = latest.statusAt(now)) {
                TestRunStatus.EXPIRED -> {
                    EXPIRED
                }

                TestRunStatus.RUNNING, TestRunStatus.GREEN, TestRunStatus.RED, TestRunStatus.INCOMPLETE -> {
                    when {
                        latest.bodyHash != bodyHash -> HASH_MISMATCH
                        status == TestRunStatus.RUNNING -> OPEN
                        status != TestRunStatus.GREEN -> NOT_GREEN
                        else -> READY
                    }
                }
            }
        }
    }
}
