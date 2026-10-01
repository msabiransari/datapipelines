package co.datapipelines.visualization

import java.time.Instant
import java.util.UUID

/**
 * The production release gate (the spec's §11.4, D56): the [ReleaseEvidence] half of a visualization
 * release, REPLACING `NOT_INSTALLED`. It runs INSIDE the release's one transaction, before the cascade
 * and the flip, and judges:
 *
 * 1. **The exact DRAFT, locked** — the candidate's version row is read `FOR SHARE`, so a concurrent draft
 *    write blocks until this transaction ends and a candidate whose hash no longer matches the live draft
 *    is refused `tests_stale` before anything cascades. The service forms its candidate before entering
 *    the transaction; THIS re-read is what makes that safe.
 * 2. **The latest run for that version** (the spec's §11.4 judges THE LATEST run): none → `tests_missing`;
 *    EXPIRED or a hash that is not the candidate's → `tests_stale`; RED or INCOMPLETE → `tests_red`.
 * 3. **The mechanical test, re-run now** against the current pins — a check that stopped passing since
 *    the GREEN submission refuses the release `mechanical_failed`.
 *
 * A PASS lets the service cascade and flip; a refusal leaves the version DRAFT, the templates
 * untouched and the qualifying run retained.
 */
class VisualizationReleaseEvidence(
    private val runs: TestRunRepository,
    private val mechanical: VisualizationMechanicalCheck,
    private val now: () -> Instant = Instant::now,
) : ReleaseEvidence {
    @Suppress("LongMethod", "ReturnCount") // the verdict ladder: each refusal is its own named exit (the ReadOnlyPipelineRule mould)
    override fun verdict(
        workspaceId: UUID,
        candidate: ReleaseCandidate,
    ): EvidenceVerdict {
        val draftHash =
            runs.lockCandidateDraft(candidate.visualizationId, candidate.version)
                ?: return refused(
                    VisualizationErrorCodes.RELEASE_TESTS_STALE,
                    "Visualization '${candidate.name.safeEcho()}' version ${candidate.version} has no live DRAFT to release; " +
                        "the candidate moved on after the release began.",
                    mapOf("reason" to "draft_missing", "version" to candidate.version),
                )
        if (draftHash != candidate.bodyHash) {
            return refused(
                VisualizationErrorCodes.RELEASE_TESTS_STALE,
                "The DRAFT's content changed since the candidate was formed; test evidence is pinned to the exact body hash.",
                mapOf("reason" to "draft_changed", "version" to candidate.version),
            )
        }
        val latest =
            runs.latestRun(candidate.visualizationId, candidate.version)
                ?: return refused(
                    VisualizationErrorCodes.RELEASE_TESTS_MISSING,
                    "Visualization '${candidate.name.safeEcho()}' version ${candidate.version} has no test run; a release needs " +
                        "the agent's GREEN run for this exact content.",
                    mapOf("reason" to "no_runs", "version" to candidate.version),
                )
        when {
            latest.status == TestRunStatus.EXPIRED -> {
                return refused(
                    VisualizationErrorCodes.RELEASE_TESTS_STALE,
                    "The latest test run expired; start a fresh session for this exact content.",
                    mapOf("reason" to "run_expired", "session_id" to latest.sessionId.toString()),
                )
            }

            latest.bodyHash != candidate.bodyHash -> {
                return refused(
                    VisualizationErrorCodes.RELEASE_TESTS_STALE,
                    "The latest test run is for other content; a release qualifies only for the exact body hash it tested.",
                    mapOf("reason" to "run_hash_mismatch", "session_id" to latest.sessionId.toString()),
                )
            }

            latest.status == TestRunStatus.RED || latest.status == TestRunStatus.INCOMPLETE -> {
                return refused(
                    VisualizationErrorCodes.RELEASE_TESTS_RED,
                    "The latest test run is ${latest.status.name}; a release needs its run GREEN.",
                    mapOf("reason" to latest.status.name.lowercase(), "session_id" to latest.sessionId.toString()),
                )
            }
        }
        val report = mechanical.run(workspaceId, candidate.body, now())
        if (!report.ok) {
            return refused(
                VisualizationErrorCodes.RELEASE_MECHANICAL_FAILED,
                "The mechanical test fails now (${report.failures.size} finding(s)); the release re-runs it against the " +
                    "current pins, whatever an earlier GREEN recorded.",
                mapOf(
                    "reason" to "mechanical",
                    "session_id" to latest.sessionId.toString(),
                    "failures" to report.failures.take(MAX_REPORTED_FAILURES).map { it.toJson().toString() },
                ),
            )
        }
        return EvidenceVerdict.Pass
    }

    private fun refused(
        code: String,
        message: String,
        details: Map<String, Any?>,
    ): EvidenceVerdict.Refused = EvidenceVerdict.Refused(code, message, details)

    private companion object {
        /** The refusal names the first findings, not the whole report — the run row carries it all. */
        const val MAX_REPORTED_FAILURES = 5
    }
}
