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
     * `enabled` — false restores the pre-#266 path everywhere: every audit row and every event row
     * and replay-log entry written by its own caller, one statement each. The writers are still
     * built (idle); nothing is queued.
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
) {
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
    }

    companion object {
        const val MAX_SHUTDOWN_DRAIN_MS = 25_000L
    }
}
