package co.datapipelines.dependencies

import java.time.Duration

/**
 * The ONE injected limits object (record §7.2, §25.4): the walk's depth/node/edge/diagnostic bounds, the payload and lock
 * budgets the surfaces enforce, and §25.4's metadata batch and timeouts. The release wiring builds it from the existing
 * `datapipelines.release.dependencies.*` bounds (nodes = max-versions, diagnostics = max-blockers); the three §25.4
 * values carry the record's defaults as named constants until #786 binds them to their keys.
 */
data class GraphLimits(
    /** Longest path, in edges, from a root. */
    val depth: Int,
    /** Distinct nodes, resolved and unresolved. */
    val nodes: Int,
    /** Edge occurrences. */
    val edges: Int,
    /**
     * Retained non-LIMIT diagnostics; the overflow appends one LIMIT `diagnostics`. It does not bound a cycle's trail —
     * the trail is the whole cycle on the active path, already bounded by [depth] + 1 — and the FIRST cycle and every
     * LIMIT are retained beyond it, because a release plan's refusal is decided by them.
     */
    val diagnostics: Int,
    /** The serialized payload budget a surface enforces after projection (§7.2 step 8); the walk does not serialize. */
    val wireBytes: Int,
    /** The cumulative mutation-lock wait, seconds, a release applies under. */
    val lockWaitSeconds: Int,
    /** At most this many requests of one family per provider call, 1..[MAX_RESOLVE_BATCH_SIZE] (§25.4). */
    val resolveBatchSize: Int = DEFAULT_RESOLVE_BATCH_SIZE,
    /** The cumulative metadata deadline a finite walk opens with (§25.4, 1..120 s). */
    val metadataTimeout: Duration = DEFAULT_METADATA_TIMEOUT,
    /** One statement's limit, never above [metadataTimeout] (§25.4, 1..30 s). */
    val statementTimeout: Duration = DEFAULT_STATEMENT_TIMEOUT,
) {
    init {
        require(depth >= 1 && nodes >= 1 && edges >= 1 && diagnostics >= 1) { "every traversal limit is at least 1: $this" }
        require(wireBytes >= 1 && lockWaitSeconds >= 0) { "the wire budget is positive and the lock wait not negative: $this" }
        require(resolveBatchSize in 1..MAX_RESOLVE_BATCH_SIZE) { "resolveBatchSize is 1..$MAX_RESOLVE_BATCH_SIZE, got $resolveBatchSize" }
        require(metadataTimeout in MIN_TIMEOUT..MAX_METADATA_TIMEOUT) { "metadataTimeout is 1..120 s, got $metadataTimeout" }
        require(statementTimeout in MIN_TIMEOUT..MAX_STATEMENT_TIMEOUT) { "statementTimeout is 1..30 s, got $statementTimeout" }
        require(statementTimeout <= metadataTimeout) { "statementTimeout $statementTimeout exceeds metadataTimeout $metadataTimeout" }
    }

    companion object {
        /** §25.4 `max-resolve-batch-size` default — #786 binds the key and replaces this constant. */
        const val DEFAULT_RESOLVE_BATCH_SIZE = 256

        /** §25.4 range ceiling of `max-resolve-batch-size`. */
        const val MAX_RESOLVE_BATCH_SIZE = 256

        /** §25.4 `metadata-timeout-seconds` default — #786 binds the key and replaces this constant. */
        val DEFAULT_METADATA_TIMEOUT: Duration = Duration.ofSeconds(30)

        /** §25.4 `statement-timeout-seconds` default — #786 binds the key and replaces this constant. */
        val DEFAULT_STATEMENT_TIMEOUT: Duration = Duration.ofSeconds(5)

        private val MIN_TIMEOUT: Duration = Duration.ofSeconds(1)
        private val MAX_METADATA_TIMEOUT: Duration = Duration.ofSeconds(120)
        private val MAX_STATEMENT_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
