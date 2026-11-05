package co.datapipelines.web.config

/**
 * #818d (dp-lake record §10 RULING 4, second amendment, #835 item 5) — the reaper delay: the longest the pool reaper's
 * next pass can act after the instant it became necessary. `LakeRules` adds it TWICE to the reads rule (once between a
 * generation's age bound and the pass that retires it, once between its ceiling and the pass that hard-closes it).
 * A derived constant of this scheduling layer, never a configuration key: no operator can make the thread faster.
 *
 * ## The derivation
 *
 * The reaper ticks ALONE on its own single thread, `dp-reaper` ([PoolReaperTaskScheduler], the `scheduler` attribute
 * of `DatasourcePoolReaperScheduler.reap`). No other job can sit ahead of it in that thread's queue, and its pass
 * waits on nothing outside this JVM: it issues no statement and closes nothing (it hands each close to the
 * `dp-pool-close` threads, `DeadlinePoolCloser`). So the delay is a property of this code alone:
 *
 * `delay = CADENCE_SECONDS + 2 × PASS_BOUND_SECONDS = 5 + 2 × 1 = 7 s`.
 *
 * Why the pass bound counts twice: the instant can fall just after the pass running at that moment looked at the
 * pool. That pass ends within one bound, the next starts one cadence later (`fixedDelay` counts from the end), and
 * it reaches the pool within one more bound. (The record's wording, "the cadence plus the bound on its own pass",
 * counts it once; twice is the larger, safe side, and the handback reports the difference.)
 *
 * [PASS_BOUND_SECONDS] is measured, not assumed: `ReaperDelayDerivationTest` runs the production pass over 1 000 live
 * pools and 100 retiring entries and asserts the measured pass is far inside it. Each production pass measures
 * itself, too: a pass over the bound logs one WARN `datasource.reaper_pass_slow elapsed_ms= bound_ms=` and counts
 * `datapipelines.datasource.pool.reaper_pass_slow` — a signal that the bound this constant promises did not hold
 * on this box (a stop-the-world pause, a starved CPU), never silence.
 *
 * ## What it no longer counts, and why
 *
 * Until #818d the reaper shared `dp-scheduled` with the four sibling jobs and this delay was their statement bounds
 * summed (260 s): the reap could wait behind one run of every sibling. On its own thread none of that applies (the
 * record closes #835 items 1 and 3 on that ground): a sibling's statement waiting on a metadata-pool connection
 * or an unresponsive server holds `dp-scheduled`, never `dp-reaper`; and the reaper's own previous pass no longer
 * closes pools, so a slow Hikari shutdown or a stuck driver close cannot delay the next pass.
 *
 * `ReaperDelayDerivationTest` recomputes [SECONDS] from the cadence and the pass bound, asserts that only the reaper
 * names the `dp-reaper` scheduler and that its pass issues no statement, and recomputes the closer's deadline.
 */
object ReaperDelay {
    /** The reaper's cadence, from its `fixedDelay` ([DatasourcePoolReaperScheduler.REAP_INTERVAL_MILLIS]). */
    const val CADENCE_SECONDS: Long = DatasourcePoolReaperScheduler.REAP_INTERVAL_MILLIS / MILLIS_PER_SECOND

    /**
     * The bound on one reaper pass: a clock comparison per live pool, the retiring queue's walk (an in-memory active
     * count per entry), a compare-and-remove plus a soft evict (which only schedules Hikari's idle closes on Hikari's
     * own executor) per retirement, and a hand-off per close. Measured in `ReaperDelayDerivationTest` over 1 000
     * live pools and 100 retiring entries, each retiring entry hard-closed and handed to the production closer: the
     * slowest of five rounds was 13 ms on the lane's machine (2026-11-05, evidence 818d), so one second keeps more
     * than seventy times that pass, kept whole so the rule stays legible. The test asserts a tenth of the bound.
     */
    const val PASS_BOUND_SECONDS: Long = 1L

    /** The derivation above, in seconds: `CADENCE_SECONDS + 2 × PASS_BOUND_SECONDS`. */
    const val SECONDS: Long = 7L
}

private const val MILLIS_PER_SECOND = 1_000L
