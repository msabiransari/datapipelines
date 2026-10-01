package co.datapipelines.visualization

import java.time.Instant
import java.util.UUID

/**
 * The production release gate (the spec's §11.4, D56): the [ReleaseEvidence] half of a visualization
 * release, REPLACING `NOT_INSTALLED`. It runs INSIDE the release's one transaction, before the cascade
 * and the flip, and judges:
 *
 * 1. **The exact DRAFT, locked** — the candidate's version row is read `FOR NO KEY UPDATE`, so a concurrent
 *    draft write — or a SECOND release of the same draft — blocks until this transaction ends, and a candidate
 *    whose hash no longer matches the live draft (or whose draft the first release already flipped) is refused
 *    `tests_stale` before anything cascades (`FOR SHARE` let two releases both hold the row and deadlock on the
 *    flip's UPDATE — the 352 merge's F5). The service forms its candidate before entering
 *    the transaction; THIS re-read is what makes that safe.
 * 2. **The latest run for that version** (the spec's §11.4 judges THE LATEST run): none → `tests_missing`;
 *    EXPIRED — written, or a RUNNING row past its deadline that no read swept — or a hash that is not the
 *    candidate's → `tests_stale`; RUNNING (an open session is not evidence) → `tests_missing` with reason
 *    `run_open`; RED or INCOMPLETE → `tests_red`. The `when` is exhaustive over `TestRunStatus` with no `else`,
 *    so a new status cannot fall through to PASS (the 352 merge's F1 did exactly that for RUNNING).
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
        val refusal =
            when (val status = latest.statusAt(now())) {
                TestRunStatus.EXPIRED -> {
                    refused(
                        VisualizationErrorCodes.RELEASE_TESTS_STALE,
                        "The latest test run expired; start a fresh session for this exact content.",
                        mapOf("reason" to "run_expired", "session_id" to latest.sessionId.toString()),
                    )
                }

                TestRunStatus.RUNNING, TestRunStatus.GREEN, TestRunStatus.RED, TestRunStatus.INCOMPLETE -> {
                    when {
                        latest.bodyHash != candidate.bodyHash -> {
                            refused(
                                VisualizationErrorCodes.RELEASE_TESTS_STALE,
                                "The latest test run is for other content; a release qualifies only for the exact body hash it tested.",
                                mapOf("reason" to "run_hash_mismatch", "session_id" to latest.sessionId.toString()),
                            )
                        }

                        // An OPEN session is not evidence (the 352 merge's F1): without this arm a start that was
                        // never submitted — or a second session opened after a GREEN run — fell through to PASS.
                        status == TestRunStatus.RUNNING -> {
                            refused(
                                VisualizationErrorCodes.RELEASE_TESTS_MISSING,
                                "The latest test run is still open — a session without a verdict is not evidence; submit it, " +
                                    "or let it expire, then release.",
                                mapOf("reason" to "run_open", "session_id" to latest.sessionId.toString()),
                            )
                        }

                        status != TestRunStatus.GREEN -> {
                            refused(
                                VisualizationErrorCodes.RELEASE_TESTS_RED,
                                "The latest test run is ${status.name}; a release needs its run GREEN.",
                                mapOf("reason" to status.name.lowercase(), "session_id" to latest.sessionId.toString()),
                            )
                        }

                        else -> {
                            null
                        }
                    }
                }
            }
        if (refusal != null) return refusal
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
