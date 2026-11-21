package co.datapipelines.application.compatibility

import co.datapipelines.executor.LocalWritePlanner
import co.datapipelines.executor.LocalWriteVerdict
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.typesystem.DatapipelinesException
import java.util.UUID

/**
 * The offline pre-upgrade compatibility report of the single-database pipeline transaction
 * (pipeline-write-transactions §7, dag-executor.md §16.7): every CURRENT stored pipeline version
 * and every version a live schedule or a published API PINS, run through the SAME [LocalWritePlanner] run admission
 * uses, so an operator sees before deploying which versions the transaction will enroll and which
 * it will refuse — and why — instead of discovering it at the first run.
 *
 * It reads the metadata store only. The planner it is handed resolves registrations through a
 * [RegistrationView], which answers visibility from metadata and refuses every connection, so the
 * report leases no customer connection and runs no business statement. Each line names the
 * workspace, pipeline, version, where the version came from, the verdict and the planner's reason
 * with its node ids and registration names — never a statement, a row, a URL or a credential.
 */
class LocalWriteCompatibilityReport(
    private val planner: LocalWritePlanner,
) {
    /** One verdict per candidate, in the candidates' order. */
    fun inspect(candidates: List<CompatibilityCandidate>): List<CompatibilityEntry> = candidates.map(::inspect)

    private fun inspect(candidate: CompatibilityCandidate): CompatibilityEntry {
        val pipeline = candidate.pipeline ?: return candidate.entry(CompatibilityOutcome.UNREADABLE, candidate.unreadable)
        return try {
            when (val verdict = planner.plan(pipeline, candidate.workspaceId)) {
                LocalWriteVerdict.Ordinary -> {
                    candidate.entry(CompatibilityOutcome.UNCHANGED, null)
                }

                is LocalWriteVerdict.Enroll -> {
                    candidate.entry(
                        CompatibilityOutcome.ENROLLED,
                        null,
                        mapOf("registration" to verdict.registration),
                    )
                }

                is LocalWriteVerdict.Refuse -> {
                    candidate.entry(CompatibilityOutcome.REFUSED, verdict.reason, verdict.details)
                }
            }
        } catch (e: DatapipelinesException) {
            // Run admission's DML shape rules refuse inside the same walk (operation-less DML,
            // an unqualified insert): reported with their catalogued code, never their message.
            candidate.entry(CompatibilityOutcome.REFUSED, e.code, e.details.filterKeys { it in SAFE_DETAIL_KEYS })
        }
    }

    private fun CompatibilityCandidate.entry(
        outcome: CompatibilityOutcome,
        reason: String?,
        details: Map<String, Any?> = emptyMap(),
    ) = CompatibilityEntry(workspace, pipelineName, version, sources, outcome, reason, details)

    companion object {
        /** Admission refusal details that are identifiers only (node ids, paths, registration names, dialects). */
        private val SAFE_DETAIL_KEYS = setOf("node_id", "path", "registration", "dialect", "reason")

        /** The human-readable report: one line per entry, then one summary line. */
        fun render(entries: List<CompatibilityEntry>): List<String> {
            val counts =
                CompatibilityOutcome.entries.joinToString(
                    " ",
                ) { outcome -> "${outcome.wire}=${entries.count { it.outcome == outcome }}" }
            return entries.map(::line) + "SUMMARY versions=${entries.size} $counts"
        }

        private fun line(entry: CompatibilityEntry): String =
            buildString {
                append("workspace=").append(entry.workspace)
                append(" pipeline=").append(entry.pipeline)
                append(" version=").append(entry.version ?: "-")
                append(" source=").append(entry.sources.joinToString(","))
                append(" verdict=").append(entry.outcome.wire)
                entry.reason?.let { append(" reason=").append(it) }
                if (entry.details.isNotEmpty()) append(" details=").append(flatten(entry.details))
            }

        /** `{a=x, b=[{registration=r, node_id=n, via=[]}]}` rendered compactly, identifiers only. */
        private fun flatten(value: Any?): String =
            when (value) {
                is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { (k, v) -> "$k=${flatten(v)}" }
                is Collection<*> -> value.joinToString(", ", "[", "]") { flatten(it) }
                else -> value.toString()
            }
    }
}

/** One stored version to inspect, from the metadata store. */
data class CompatibilityCandidate(
    val workspaceId: UUID,
    val workspace: String,
    val pipelineName: String,
    /** The exact version, or null when a schedule's payload did not name one the store holds. */
    val version: Int?,
    /** `current`, `schedule:<name>` per live schedule and `endpoint:<METHOD>:<path>` per API pinning this version. */
    val sources: List<String>,
    /** The parsed body, or null when it could not be read — then [unreadable] says why. */
    val pipeline: Pipeline?,
    val unreadable: String? = null,
)

/** One line of the report. */
data class CompatibilityEntry(
    val workspace: String,
    val pipeline: String,
    val version: Int?,
    val sources: List<String>,
    val outcome: CompatibilityOutcome,
    /** The planner's refusal reason (or the admission refusal code); for UNREADABLE, why. */
    val reason: String?,
    /** The planner's identifier-only details: registrations, node ids, the trail through children. */
    val details: Map<String, Any?>,
)

/** What the upgraded executor does with the version at its next run. */
enum class CompatibilityOutcome(
    val wire: String,
) {
    /** No eligible durable write: the run is unchanged (ordinary, independent statements). */
    UNCHANGED("unchanged"),

    /** Every eligible write targets one qualified registration: the run commits once at the root. */
    ENROLLED("enrolled"),

    /** Run admission refuses the run before its first business statement. */
    REFUSED("refused"),

    /** The stored body or the schedule's pin could not be read; the version was not inspected. */
    UNREADABLE("unreadable"),
}
