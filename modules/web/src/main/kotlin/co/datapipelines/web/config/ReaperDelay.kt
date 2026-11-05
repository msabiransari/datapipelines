package co.datapipelines.web.config

/**
 * #818c (dp-lake record §10 RULING 4 amended 2026-11-05, item 4) — the reaper delay: the longest the pool reaper's
 * next pass can start after the instant it became necessary, derived from the shape of the one `dp-scheduled`
 * thread every `@Scheduled` job shares. `LakeRules` adds it TWICE to the reads rule (once between a generation's
 * age bound and the pass that retires it, once between its ceiling and the pass that hard-closes it). A derived
 * constant of this scheduling layer, never a configuration key: no operator can make the thread faster.
 *
 * ## The derivation
 *
 * `delay = the reaper's cadence + the time every OTHER job can hold the thread before the reap runs`.
 *
 * It is a SUM over the sibling jobs, not the longest one, because of how the scheduler orders work. Every job is
 * `fixedDelay` on one `ScheduledThreadPoolExecutor` thread (`ScheduledJobsTaskScheduler`, one thread): a run of a job
 * schedules that job's next run at its own end plus its delay, into a queue ordered by due time. When the reap
 * falls due while job X runs, it waits for X; but a job Y that fell due after X started and before the reap is
 * AHEAD of the reap in the queue, so the reap waits for Y as well. Each sibling can be ahead of one reap at most
 * once: its next run is scheduled only after it ends, and at least its own delay later, after the reap was due.
 * So the honest worst case waits for one run of every sibling job, and the delay is their sum. (The record's
 * wording, "the longest one sibling job can hold", reads as the max; the sum is the bound the queue actually
 * gives, and it is the larger, safe side.)
 *
 * A run's hold is the sum of its statements' JDBC `queryTimeout` bounds, in the order its shape issues them:
 *
 * - stale-execution sweep: the sweep `UPDATE` at 10 s, a hold of 10 s;
 * - dashboard refresh sweep: the sweep `UPDATE` at 10 s, 10 s;
 * - managed lake freshness tick: the catalog read (one per managed generation; one managed lake) and the regrant
 *   `INSERT`, each at 5 s, 10 s;
 * - retention tick, six steps in turn: the event `DELETE`; the refresh `DELETE`; the evaluation sweep `UPDATE`; the
 *   evaluation `DELETE`; the keys purge's FK read and two `DELETE`s; the audit cutoff read and one batch. Nine
 *   statements at 25 s, 225 s.
 *
 * `5 s cadence + 10 + 10 + 10 + 225 = 260 s`. The audit retention's loop counts as two statements because its two
 * second tick budget is checked between batches: a further batch starts only while less than the budget has
 * elapsed, so a run is at most `max(cutoff read, budget) + one batch`, which is two bounds while the budget does not
 * exceed one bound (the drift test asserts that, too).
 *
 * `ReaperDelayDerivationTest` recomputes this number from the production jobs themselves (each built by its
 * `@Bean` method and run once over a recording DataSource, every statement's bound read off the statement) and
 * the reaper's cadence, and fails on any difference, an unbounded statement, or a `@Scheduled` job it does not
 * count.
 *
 * What it does NOT count, stated rather than hidden: the time a statement waits for a metadata-pool connection
 * before it starts (Hikari's `connectionTimeout`; the statement bound starts at execution), `BoundedJdbc`'s own
 * documented limit (a `queryTimeout` is a cancel request, not a wall-clock guarantee against an unresponsive
 * server), and the reaper's own previous pass (in-memory walks plus the close of pools it reaps).
 */
object ReaperDelay {
    /** The derivation above, in seconds; pinned against the production jobs by `ReaperDelayDerivationTest`. */
    const val SECONDS: Long = 260L
}
