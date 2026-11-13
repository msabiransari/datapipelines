package co.datapipelines.config

import co.datapipelines.web.endpoints.CommitEffects
import co.datapipelines.web.endpoints.ConversionLocks
import co.datapipelines.web.endpoints.StartupConversion
import co.datapipelines.web.endpoints.StartupConversionConflict
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionOperations

/**
 * The upgrade's in-place conversion runner (#786 §E.1; owner ruling 2026-11-08: one server, convert in place, no
 * floor). It runs every [StartupConversion] family once, in bean-creation of this component:
 *
 * - AFTER Flyway — each family reads through the JDBC template, which Boot makes depend on the Flyway initializer;
 * - BEFORE every `SmartInitializingSingleton` (the demo seeders, which then see converted APIs), before the web server
 *   starts in `finishRefresh` (so nothing is served from an unconverted row), and before the scheduler's
 *   `SmartLifecycle` admission starts and `ApplicationReadyEvent` fires (so nothing is dispatched).
 *
 * E.0 comes first for EVERY family (#786 §E.0, §D.5: "before any write, the runner inspects every version-less API
 * entity and every schema-1 schedule"): each family logs its own refused rows; when any family reported one, the
 * runner throws before ANY write. The throw fails the context refresh, so the server REFUSES TO START with a non-zero
 * exit and every family's structured list in the log.
 *
 * **One rollback boundary (#895).** Every family then converts in ONE transaction this runner owns, under one G0
 * acquisition order: each family plans its attempt (a plain read), the runner acquires the workspace guard ONCE over
 * the UNION of every family's workspaces, then the endpoint registry key, then each family's entity locks, and only
 * then does each family re-read its candidates under those locks and write (families in their declared order: the
 * APIs, then the schedules and their queued runs). No workspace is ever acquired after the registry key. So a refusal
 * found under the locks (a target lost its CURRENT), or any failed write, rolls back every API, schedule and queued
 * run of this startup — the store is left exactly as the start found it. A conflict (a lost CAS, a candidate outside
 * the guarded union) rolls the attempt back and the runner re-plans from fresh rows in a NEW transaction, at most
 * [MAX_ATTEMPTS] times. Holding the guards for the whole conversion is acceptable only because nothing is served or
 * dispatched yet.
 *
 * Success reports are commit-time effects: each family's converted lines, summaries and the registry invalidation are
 * published once, only after the joint commit, and discarded with a rolled-back attempt. Refusal and precondition
 * diagnostics are logged when they happen.
 */
class ConversionStartup(
    private val steps: List<StartupConversion>,
    private val transactions: TransactionOperations,
    private val locks: ConversionLocks,
) {
    private val log = LoggerFactory.getLogger(ConversionStartup::class.java)

    @PostConstruct
    fun convertBeforeServing() {
        val refused = steps.sumOf { it.logPrecondition() }
        check(refused == 0) {
            "The upgrade's conversion refused to start: $refused API(s) or schedule(s) reference a pipeline with no current " +
                "version to pin (each is logged above). $OPERATOR_FIX"
        }
        convertAll().forEach { it.publish() }
    }

    /** The joint conversion, retried from fresh rows on a conflict; returns the committed attempt's effects. */
    private fun convertAll(): List<CommitEffects> {
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return requireNotNull(transactions.execute { attemptOnce() }) { "the conversion transaction returned no outcome" }
            } catch (conflict: StartupConversionConflict) {
                // The whole transaction rolled back — every family's writes and buffered effects with it.
                log.warn(
                    "event=upgrade.conversion.retry attempt={} max_attempts={} reason=\"{}\"",
                    attempt + 1,
                    MAX_ATTEMPTS,
                    conflict.message,
                )
            }
        }
        // At startup nothing else should write these rows; refuse loudly rather than loop.
        error(
            "The upgrade's conversion lost a race to a concurrent writer $MAX_ATTEMPTS times; every attempt rolled back and " +
                "nothing was converted. Stop every other instance on this database, then start again.",
        )
    }

    /** One attempt, inside the runner's one transaction, in the G0 order: plan, workspaces, registry, entities, writes. */
    private fun attemptOnce(): List<CommitEffects> {
        val attempts = steps.map { it.plan() }
        val union = attempts.flatMapTo(sortedSetOf()) { it.workspaces }
        if (union.isNotEmpty()) {
            locks.workspaces(union)
            locks.endpointRegistry()
        }
        attempts.forEach { it.lockEntities() }
        return attempts.map { it.convert(union) }
    }

    companion object {
        /** The fix named when the combined precondition refuses (docs/deployment.md §8). */
        const val OPERATOR_FIX = "On the previous version, release the pipeline or delete the API or schedule, then upgrade again."

        /** Attempts of the joint conversion before a persistent conflict refuses the start (the schedule step's bound). */
        const val MAX_ATTEMPTS = 3
    }
}
