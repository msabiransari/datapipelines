package co.datapipelines.auth

import co.datapipelines.persistence.BatchingConfig
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The `datapipelines.persistence.*` keys ([Configuration §3.32](../../../../../../../docs/configuration.md))
 * — the bounds and cadence of every batching writer the application builds (#266): the audit
 * log's here in `auth`, the execution-event record's and the replay log's in `web`. Each writer
 * gets its OWN instance of these bounds; bound here because `auth` is the lowest module that
 * builds one, and `web` reads the same bean.
 *
 * Defaults here MUST equal configuration.md §3.32 — `AuthPropertiesSpecDriftTest` compares them.
 */
@ConfigurationProperties(prefix = "datapipelines.persistence")
data class PersistenceProperties(
    /**
     * `enabled` — false restores the pre-#266 path everywhere: every event row and replay-log entry
     * (and every audit row, whatever [audit] says) written by its own caller, one statement each. The
     * writers are still built (idle); nothing is queued.
     */
    val enabled: Boolean = true,
    val batchMaxEvents: Int = BatchingConfig.DEFAULT_BATCH_MAX_EVENTS,
    val batchMaxBytes: Long = BatchingConfig.ONE_MIB,
    val lingerMs: Long = 0,
    val queueMaxEvents: Int = BatchingConfig.DEFAULT_QUEUE_MAX_EVENTS,
    val queueMaxBytes: Long = BatchingConfig.DEFAULT_QUEUE_MAX_BYTES,
    val recordMaxWaitMs: Long = BatchingConfig.DEFAULT_RECORD_MAX_WAIT_MILLIS,
    /** Writer threads per store — and the size of `web`'s `dp-event-persist` pool (the execution row's writes and the direct fallbacks). */
    val writers: Int = BatchingConfig.DEFAULT_WRITERS,
    val shutdownDrainMs: Long = BatchingConfig.DEFAULT_SHUTDOWN_DRAIN_MILLIS,
    /** `audit.*` — the audit log's own switch (#266b); see [Audit]. */
    val audit: Audit = Audit(),
) {
    /**
     * `audit.enabled` — whether audit rows go through the audit writer at all (and only while
     * [enabled] is also true). **Off by default**, by measurement (#266 C, the orchestrator's ruling
     * of 2026-09-29): at every measured audit rate the writer never formed a batch (mean size 1.0 at
     * 500 rows/s, 1.0–1.1 at 5,000) and it made the tail worse (p99 +4 ms at 100 concurrent
     * executions), while event batching earned ×2.2–3.5. Off, every audit row is the pre-#266
     * INSERT on its caller's thread (or on the caller's transaction); on, the batched path of auth.md
     * §10.1A. The writer bean is built either way — idle when off, as under `enabled: false` — so its
     * meters read zero rather than vanish and the drain has nothing to do.
     */
    data class Audit(
        val enabled: Boolean = false,
    )

    /** The primitive's config; its `init` refuses an impossible bound, so a bad key fails the boot. */
    fun toConfig(): BatchingConfig =
        BatchingConfig(
            batchMaxEvents = batchMaxEvents,
            batchMaxBytes = batchMaxBytes,
            lingerMillis = lingerMs,
            queueMaxEvents = queueMaxEvents,
            queueMaxBytes = queueMaxBytes,
            recordMaxWaitMillis = recordMaxWaitMs,
            writers = writers,
            shutdownDrainMillis = shutdownDrainMs,
        )

    init {
        toConfig()
        // The writers drain in parallel inside ONE shutdown phase; Spring's per-phase budget
        // (`spring.lifecycle.timeout-per-shutdown-phase`, 30 s) must outlast the drain.
        require(shutdownDrainMs <= MAX_SHUTDOWN_DRAIN_MS) {
            "datapipelines.persistence.shutdown-drain-ms must be <= $MAX_SHUTDOWN_DRAIN_MS (the drain runs inside one 30 s shutdown phase)"
        }
        // #266b: one replay-log batch is one Redis Lua script, and Lua refuses to unpack more than
        // ~7,990 values. The script chunks its RPUSH as well; this bound is the second guard.
        require(batchMaxEvents <= MAX_BATCH_MAX_EVENTS) {
            "datapipelines.persistence.batch-max-events must be <= $MAX_BATCH_MAX_EVENTS (one replay-log batch runs inside one " +
                "Redis Lua script, and Lua refuses to unpack more than ~7,990 values)"
        }
    }

    companion object {
        const val MAX_SHUTDOWN_DRAIN_MS = 25_000L
        const val MAX_BATCH_MAX_EVENTS = 7_000
    }
}
