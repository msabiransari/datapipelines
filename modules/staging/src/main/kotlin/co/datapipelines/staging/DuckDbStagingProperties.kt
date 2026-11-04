package co.datapipelines.staging

import java.nio.file.Path

/**
 * Resolved configuration for DuckDB staging databases (staging.md §8.5, §7.1).
 *
 * A plain immutable data class, not a Spring binding, for the same reason as [H2StagingProperties]:
 * the staging module has no Spring on its classpath (module-structure.md §5.5). The assembling
 * layer fills it from `datapipelines.lake.admission.*` (the bounds: memory limit, expansion
 * factor, scratch aggregate) and from `datapipelines.staging.h2.*` (the engine-neutral knobs:
 * batch sizes, query timeout, connection cap). It binds no key of its own.
 *
 * @property scratchDir the directory every database file, WAL and spill directory is created in.
 *   Operator configuration, never request input; the database name inside it is derived from the
 *   execution id alone. Must be absolute and contain no `;` (the driver splits its URL on `;`).
 * @property memoryLimitBytes the engine's `SET memory_limit` (`staging-memory-limit`). It bounds
 *   DuckDB's buffer manager only — not the JVM heap, not every allocation the engine makes
 *   (dp-lake design §11). This value supplies no combined JVM/native process-memory bound.
 * @property expansionFactor `staging-expansion-factor`: a non-lake database reserves
 *   `memoryLimitBytes × expansionFactor` of scratch (owner ruling 2026-11-02).
 * @property scratchCapacityBytes the aggregate `scratch-bytes` every reservation shares ([ScratchBudget]).
 * @property threads the engine's `SET threads`. Bounded so one execution cannot take every core of
 *   the host; [DEFAULT_THREADS] matches the default connection cap.
 * @property insertBatchSize rows per appender flush when staging (§4.3).
 * @property resultBatchSize JDBC fetch size when reading staged data back (§3.3).
 * @property queryTimeoutSeconds `Statement.setQueryTimeout` on staging queries (§3.3).
 * @property maxConnections the cap on leases held at once (§9); positive. As on H2, a cap of one
 *   deadlocks a script node, which stages rows inside the read lease it already holds — the
 *   default four leaves room for that nesting.
 */
data class DuckDbStagingProperties(
    val scratchDir: Path,
    val memoryLimitBytes: Long,
    val expansionFactor: Int,
    val scratchCapacityBytes: Long,
    val threads: Int = DEFAULT_THREADS,
    val insertBatchSize: Int = 1000,
    val resultBatchSize: Int = 10_000,
    val queryTimeoutSeconds: Int = 60,
    val maxConnections: Int = 4,
) {
    init {
        require(scratchDir.isAbsolute) { "the DuckDB staging scratch directory must be absolute, was $scratchDir" }
        require(';' !in scratchDir.toString()) { "the DuckDB staging scratch directory must not contain ';' (it is part of the JDBC URL)" }
        require(memoryLimitBytes >= MIN_MEMORY_LIMIT_BYTES) {
            "memoryLimitBytes must be at least $MIN_MEMORY_LIMIT_BYTES, was $memoryLimitBytes"
        }
        require(expansionFactor > 0) { "expansionFactor must be positive, was $expansionFactor" }
        require(scratchCapacityBytes > 0) { "scratchCapacityBytes must be positive, was $scratchCapacityBytes" }
        require(threads > 0) { "threads must be positive, was $threads" }
        require(insertBatchSize > 0) { "insertBatchSize must be positive, was $insertBatchSize" }
        require(resultBatchSize > 0) { "resultBatchSize must be positive, was $resultBatchSize" }
        require(queryTimeoutSeconds >= 0) { "queryTimeoutSeconds must be non-negative, was $queryTimeoutSeconds" }
        require(maxConnections > 0) { "maxConnections must be positive, was $maxConnections" }
    }

    /**
     * The reservation a database without a caller-supplied size takes: `memoryLimitBytes ×
     * expansionFactor` (owner ruling 2026-11-02). Saturates instead of overflowing, so an absurd
     * configuration refuses at [ScratchBudget.reserve] rather than wrapping negative.
     */
    val defaultReservationBytes: Long
        get() = if (memoryLimitBytes > Long.MAX_VALUE / expansionFactor) Long.MAX_VALUE else memoryLimitBytes * expansionFactor

    companion object {
        /**
         * Four threads: the default connection cap is four, and at the default 1 GiB memory limit
         * it leaves the buffer manager 256 MiB per thread. DuckDB's own default is every core of
         * the host, which on a shared server lets one execution's sort starve every other one.
         */
        const val DEFAULT_THREADS = 4

        /** The floor below which DuckDB cannot open a database at all (1 MiB). */
        const val MIN_MEMORY_LIMIT_BYTES = 1L shl 20

        /** [DEFAULT_THREADS], or fewer on a host with fewer cores. */
        fun boundedThreads(availableProcessors: Int = Runtime.getRuntime().availableProcessors()): Int =
            minOf(DEFAULT_THREADS, availableProcessors.coerceAtLeast(1))
    }
}
