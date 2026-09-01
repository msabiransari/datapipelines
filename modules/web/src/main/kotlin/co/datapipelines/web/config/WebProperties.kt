package co.datapipelines.web.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The `datapipelines.sse.*` keys ([Configuration §3.6](../../../../../../../docs/configuration.md)).
 *
 * Defaults here MUST equal the defaults in configuration.md §3.6 — that document is the single
 * authority, and a binding class that quietly disagrees with it is a second authority.
 * `WebPropertiesSpecDriftTest` parses the §3.6/§3.7 tables and fails the build on any divergence.
 */
@ConfigurationProperties(prefix = "datapipelines.sse")
data class SseProperties(
    /** `heartbeat-interval-seconds` — SSE comment interval (rest-api §6.6). */
    val heartbeatIntervalSeconds: Long = 15,
    /** `disconnect-grace-seconds` — grace before a disconnected stream cancels its run (§6.8). */
    val disconnectGraceSeconds: Long = 30,
    /** `max-streams-per-user` — concurrent SSE streams per user (§12.1). */
    val maxStreamsPerUser: Int = 50,
) {
    init {
        require(heartbeatIntervalSeconds > 0) { "datapipelines.sse.heartbeat-interval-seconds must be > 0" }
        require(disconnectGraceSeconds >= 0) { "datapipelines.sse.disconnect-grace-seconds must be >= 0" }
        require(maxStreamsPerUser > 0) { "datapipelines.sse.max-streams-per-user must be > 0" }
    }
}

/**
 * The `datapipelines.rate-limit.*` keys (Configuration §3.7).
 *
 * Both limits are **per user** — an API key draws from its owner's budget, so minting more keys
 * raises nothing (rest-api §12.1).
 */
@ConfigurationProperties(prefix = "datapipelines.rate-limit")
data class RateLimitProperties(
    /** `requests-per-second`. */
    val requestsPerSecond: Long = 100,
    /** `requests-per-minute`. */
    val requestsPerMinute: Long = 1000,
) {
    init {
        require(requestsPerSecond > 0) { "datapipelines.rate-limit.requests-per-second must be > 0" }
        require(requestsPerMinute > 0) { "datapipelines.rate-limit.requests-per-minute must be > 0" }
    }
}

/**
 * The `datapipelines.result.*` keys (Configuration §3.5).
 *
 * Bound here and projected onto `dag`'s [co.datapipelines.executor.ResultConfig], which is a plain
 * data class by design: the executor takes already-resolved values and never reads configuration
 * itself. Keeping the binding in `web` is what makes that separation real.
 */
@ConfigurationProperties(prefix = "datapipelines.result")
data class ResultProperties(
    val ttlDefaultSeconds: Long = 300,
    val ttlMinSeconds: Long = 60,
    val ttlMaxSeconds: Long = 3600,
    val maxSizeBytes: Long = 104_857_600,
    val pageSizeRows: Int = 1000,
    val pageMaxRows: Int = 100_000,
) {
    init {
        // configuration.md §7 — the validator asserts exactly this ordering at startup.
        require(ttlMinSeconds <= ttlDefaultSeconds && ttlDefaultSeconds <= ttlMaxSeconds) {
            "datapipelines.result: ttl-min-seconds <= ttl-default-seconds <= ttl-max-seconds must hold"
        }
        require(pageSizeRows in 1..pageMaxRows) {
            "datapipelines.result.page-size-rows must be in 1..page-max-rows"
        }
        require(maxSizeBytes > 0) { "datapipelines.result.max-size-bytes must be > 0" }
    }
}

/**
 * The `datapipelines.executor.*` keys (Configuration §3.2), plus the two values
 * [co.datapipelines.executor.ExecutorConfig] needs that live in other namespaces:
 * `datapipelines.staging.h2.max-memory-mb` (§3.3) and `datapipelines.sse.heartbeat-interval-seconds`
 * (§3.6, the cancel-flag poll interval).
 */
@ConfigurationProperties(prefix = "datapipelines.executor")
data class ExecutorProperties(
    val maxParallelNodes: Int = 4,
    val maxConcurrentExecutionsPerUser: Int = 10,
    val maxConcurrentExecutionsGlobal: Int = 100,
    val nodeQueryTimeoutSeconds: Int = 60,
    val executionTimeoutSeconds: Long = 600,
)

/**
 * The `datapipelines.pipelines.*` keys ([Configuration §3.16](../../../../../../../docs/configuration.md)).
 *
 * Defaults here MUST equal the defaults in configuration.md §3.16 — that document is the single
 * authority, and a binding class that quietly disagrees with it is a second authority.
 * `WebPropertiesSpecDriftTest` fails the build on any divergence.
 */
@ConfigurationProperties(prefix = "datapipelines.pipelines")
data class PipelineProperties(
    /** `max-composition-depth` — the deepest PIPELINE-node chain admitted (composition depth guard). */
    val maxCompositionDepth: Int = 5,
) {
    init {
        require(maxCompositionDepth >= 1) { "datapipelines.pipelines.max-composition-depth must be >= 1" }
    }
}

/** The `datapipelines.staging.h2.*` subset `web` must pass to the executor (Configuration §3.3). */
@ConfigurationProperties(prefix = "datapipelines.staging.h2")
data class StagingH2Properties(
    val maxMemoryMb: Long = 1024,
    val insertBatchSize: Int = 1000,
    val resultBatchSize: Int = 10_000,
    val queryTimeoutSeconds: Int = 60,
    val mode: String = "PostgreSQL",
)

/** The `datapipelines.idempotency.*` keys (Configuration §3.8). */
@ConfigurationProperties(prefix = "datapipelines.idempotency")
data class IdempotencyProperties(
    val ttlSeconds: Long = 86_400,
)

/**
 * The `datapipelines.executions.*` keys ([Configuration §3.11](../../../../../../../docs/configuration.md)).
 *
 * Only the key running code reads is bound. (`event-retention-days` is deliberately NOT bound
 * here: `ExecutionEventRepository.deleteOlderThan` has no production caller either — an
 * unscheduled retention job of the exact M2 shape, reported in the 036 handback rather than
 * silently wired in this round.) The default MUST equal configuration.md §3.11 —
 * `WebPropertiesSpecDriftTest` fails the build on any divergence.
 */
@ConfigurationProperties(prefix = "datapipelines.executions")
data class ExecutionsProperties(
    /** `stale-timeout-minutes` — a `RUNNING` row older than this belongs to a dead instance. */
    val staleTimeoutMinutes: Long = 60,
) {
    init {
        require(staleTimeoutMinutes > 0) { "datapipelines.executions.stale-timeout-minutes must be > 0" }
    }
}
