package co.datapipelines.auth

import co.datapipelines.persistence.FailureShape
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime

/**
 * The audit-log retention job (#310, metadata-db §8.2, auth.md §10.3): deletes `audit_log` rows
 * older than `datapipelines.audit.retention-days`. Scheduled by `RetentionSchedulingConfiguration`
 * in `web` as the retention sweep's last step; this class owns the SQL because the table and its
 * writer ([AuditLogger]) live in this module.
 *
 * ## One cutoff, from the database's clock, once per tick
 * `audit_log.timestamp` is the database's `NOW()` at write, so the cutoff is the database's
 * `NOW() - make_interval(days => :retentionDays)`, read ONCE at the start of the tick and bound
 * into every batch. The JVM's clock never decides which row goes: a replica whose clock runs fast
 * must not delete a day early. ([clock] measures only this tick's own time budget.) One cutoff for
 * every event — auth.md §10 draws no line between security events and tool-call rows, and the
 * floor ([AuditProperties.MIN_RETENTION_DAYS]) is what protects the trail, not an event list.
 *
 * ## Bounded, so a year's backlog never holds a lock or a transaction for minutes
 * Each batch is ONE auto-committed statement deleting at most [batchSize] rows, chosen through
 * `idx_audit_timestamp` (oldest first). Rows are only ever INSERTed, so a delete contends with
 * nothing but itself. A tick stops when a batch comes back short (drained), after [maxBatches]
 * batches, or once [tickBudget] has elapsed — the last two WARN, and the next tick continues: the
 * cutoff is `now − retention`, not a slot, so retention catches up by construction.
 *
 * ## Replicas
 * No leader election, the [KeyRetentionPurge] / `ExecutionEventRetention` rule: two replicas racing
 * select overlapping ids, the second DELETE finds them gone and counts zero. A short batch then ends
 * that replica's tick early, which is harmless — the other replica is draining the same set.
 *
 * ## Failure handling
 * A metadata-DB fault ends the tick, never the scheduler: the batches already committed stay
 * deleted and counted, the tick WARNs with what it purged, and the next tick retries — the
 * `ExecutionEventRetention` shape.
 *
 * ## What it logs
 * One INFO line per tick that deleted anything (`event=audit.retention`), a WARN when the tick
 * stopped with work left (`event=audit.retention_incomplete`) or failed
 * (`event=audit.retention_failed`), and the [PURGED_METER] counter. Counts and the cutoff only —
 * never a row's content.
 */
class AuditLogRetention(
    private val jdbc: NamedParameterJdbcTemplate,
    private val retentionDays: Int,
    meterRegistry: MeterRegistry,
    private val clock: Clock = Clock.systemUTC(),
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val maxBatches: Int = DEFAULT_MAX_BATCHES,
    private val tickBudget: Duration = DEFAULT_TICK_BUDGET,
) {
    private val purgedCounter: Counter =
        Counter
            .builder(PURGED_METER)
            .description("audit_log rows deleted by the retention job")
            .register(meterRegistry)

    init {
        // The floor holds however this job is built, not only through AuditProperties' binding:
        // this is the class that deletes.
        require(retentionDays in AuditProperties.MIN_RETENTION_DAYS..AuditProperties.MAX_RETENTION_DAYS) {
            "audit retention must be ${AuditProperties.MIN_RETENTION_DAYS}..${AuditProperties.MAX_RETENTION_DAYS} days, " +
                "was $retentionDays"
        }
        require(batchSize > 0) { "batchSize must be positive, was $batchSize" }
        require(maxBatches > 0) { "maxBatches must be positive, was $maxBatches" }
        require(!tickBudget.isNegative) { "tickBudget must not be negative, was $tickBudget" }
    }

    /** Why a tick ended. */
    enum class Stop(
        val wire: String,
    ) {
        /** A batch came back short: nothing older than the cutoff is left. */
        DRAINED("drained"),

        /** [maxBatches] full batches ran; the next tick continues. */
        BATCH_CEILING("batch_ceiling"),

        /** [tickBudget] elapsed after a full batch; the next tick continues. */
        TIME_BUDGET("time_budget"),

        /** A metadata-DB fault; the committed batches stay deleted. */
        FAILED("failed"),
    }

    /** What one tick did — the counts the log line carries and the tests assert. */
    data class Result(
        val purged: Int,
        val batches: Int,
        val stop: Stop,
    )

    /** Runs one retention tick. Never throws a [DataAccessException] — see the class KDoc. */
    @Suppress("SwallowedException") // logged with its message and retried next tick, the sibling's rule
    fun purgeOnce(): Result {
        val started = clock.instant()
        var cutoff: OffsetDateTime? = null
        var purged = 0
        var batches = 0
        val stop =
            try {
                val tickCutoff = cutoff()
                cutoff = tickCutoff
                var outcome: Stop? = null
                while (outcome == null) {
                    val deleted = deleteBatch(tickCutoff)
                    batches++
                    purged += deleted
                    outcome =
                        when {
                            deleted < batchSize -> Stop.DRAINED
                            batches >= maxBatches -> Stop.BATCH_CEILING
                            Duration.between(started, clock.instant()) >= tickBudget -> Stop.TIME_BUDGET
                            else -> null
                        }
                }
                outcome
            } catch (e: DataAccessException) {
                // The class and the SQLState, never the message: Spring's message carries the
                // statement text and the driver's text (observability §9.2's rule; the 310 pass).
                LOG.warn(
                    "event=audit.retention_failed purged={} batches={} cutoff={} error={} sql_state={}",
                    purged,
                    batches,
                    cutoff,
                    e.javaClass.simpleName,
                    FailureShape.sqlState(e),
                )
                Stop.FAILED
            }
        purgedCounter.increment(purged.toDouble())
        report(purged, batches, cutoff, stop)
        return Result(purged, batches, stop)
    }

    /** The tick's one cutoff, from the database's clock (metadata-db §8.2). */
    private fun cutoff(): OffsetDateTime =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT NOW() - make_interval(days => :retentionDays)",
                mapOf("retentionDays" to retentionDays),
                OffsetDateTime::class.java,
            ),
        ) { "the database returned no cutoff" }

    /**
     * One bounded batch: at most [batchSize] of the oldest rows before [cutoff], by primary key.
     * `ORDER BY "timestamp"` walks `idx_audit_timestamp` rather than the heap, so each batch skips
     * the rows the previous one deleted instead of re-scanning them.
     */
    private fun deleteBatch(cutoff: OffsetDateTime): Int =
        jdbc.update(
            """
            DELETE FROM audit_log
             WHERE id IN (
                       SELECT id
                         FROM audit_log
                        WHERE "timestamp" < :cutoff
                        ORDER BY "timestamp"
                        LIMIT :batchSize
                   )
            """.trimIndent(),
            mapOf("cutoff" to cutoff, "batchSize" to batchSize),
        )

    private fun report(
        purged: Int,
        batches: Int,
        cutoff: OffsetDateTime?,
        stop: Stop,
    ) {
        if (purged > 0) {
            LOG.info("event=audit.retention purged={} cutoff={} batches={}", purged, cutoff, batches)
        }
        if (stop == Stop.BATCH_CEILING || stop == Stop.TIME_BUDGET) {
            LOG.warn(
                "event=audit.retention_incomplete purged={} cutoff={} batches={} reason={} " +
                    "message=\"rows older than the cutoff remain; the next tick continues\"",
                purged,
                cutoff,
                batches,
                stop.wire,
            )
        }
    }

    companion object {
        /** observability.md §4.1: rows deleted, no tags. */
        const val PURGED_METER = "datapipelines.audit.retention.purged"

        /** Rows per statement: one short transaction each, however large the backlog. */
        const val DEFAULT_BATCH_SIZE = 5_000

        /** Statements per tick: at most 250,000 rows an hour, far past a steady-state hour's writes. */
        const val DEFAULT_MAX_BATCHES = 50

        /**
         * Wall-clock per tick. Every `@Scheduled` job in the application runs on ONE thread — and
         * it is `WebSurfaceConfiguration`'s `sseLogScheduler`, the thread that also serves SSE
         * replays and idempotent-retry follows (Spring resolves the context's unique
         * `ScheduledExecutorService` when no `TaskScheduler` bean exists; `AuditLogRetentionE2eTest`
         * pins it; #316 tracks giving scheduled jobs their own thread). A backlog must therefore
         * never hold that thread for long: two seconds, checked between batches, so one batch may
         * overrun it. A steady-state tick is one short batch.
         */
        val DEFAULT_TICK_BUDGET: Duration = Duration.ofSeconds(2)

        private val LOG = LoggerFactory.getLogger(AuditLogRetention::class.java)
    }
}
