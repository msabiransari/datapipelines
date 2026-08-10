package co.datapipelines.staging

/**
 * Resolved staging configuration for one execution (staging.md §7.1; configuration.md §3.3).
 *
 * A plain immutable data class, **not** a Spring `@ConfigurationProperties`: the `staging`
 * module's dependencies are `typesystem`, `h2`, and `kotlinx-coroutines-core` only
 * (module-structure.md §5.5), with no Spring on the classpath. The binding from
 * `datapipelines.staging.h2.*` and the per-pipeline `settings.tempdb.config.max_memory_mb`
 * override (staging.md §3.1 / §8.1) both happen in the assembling layer, which constructs an
 * instance of this class with the **already-resolved** effective values and hands it to
 * [H2StagingFactory]. Nothing here re-reads global config mid-execution.
 *
 * Defaults mirror configuration.md §3.3 so a directly-constructed instance behaves as the
 * documented out-of-the-box configuration.
 *
 * @property mode the H2 `MODE=` compatibility parameter for the JDBC URL (§3.1).
 * @property maxMemoryMb per-execution memory budget in megabytes; the measured in-process
 *   footprint is compared against `maxMemoryMb * 1024` KB (§8.2).
 * @property insertBatchSize rows per `INSERT` batch when streaming source data in (§4.3).
 * @property resultBatchSize JDBC fetch size when reading staged data back out (§3.3, §6.1).
 * @property queryTimeoutSeconds `Statement.setQueryTimeout` applied to staging queries (§3.3).
 */
data class H2StagingProperties(
    val mode: String = "PostgreSQL",
    val maxMemoryMb: Long = 1024,
    val insertBatchSize: Int = 1000,
    val resultBatchSize: Int = 10_000,
    val queryTimeoutSeconds: Int = 60,
) {
    init {
        require(mode.matches(SAFE_MODE)) {
            "staging mode '$mode' is not a bare H2 mode name; it is interpolated into the JDBC URL"
        }
        require(maxMemoryMb > 0) { "maxMemoryMb must be positive, was $maxMemoryMb" }
        require(insertBatchSize > 0) { "insertBatchSize must be positive, was $insertBatchSize" }
        require(resultBatchSize > 0) { "resultBatchSize must be positive, was $resultBatchSize" }
        require(queryTimeoutSeconds >= 0) { "queryTimeoutSeconds must be non-negative, was $queryTimeoutSeconds" }
    }

    private companion object {
        /**
         * `mode` is interpolated into the JDBC URL (`;MODE=$mode`), so it is constrained to a
         * bare alphanumeric token — defence in depth even though the value is operator-supplied
         * config, not user input.
         */
        val SAFE_MODE = Regex("[A-Za-z][A-Za-z0-9]*")
    }
}
