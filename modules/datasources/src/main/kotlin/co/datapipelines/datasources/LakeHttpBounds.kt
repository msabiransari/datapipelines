package co.datapipelines.datasources

import kotlin.math.pow

/**
 * #818d (dp-lake record §10 RULING 4, second amendment, item 7) — the DuckDB HTTP timeout and retry settings every
 * LAKE engine that loads `httpfs` gets, as code constants: [STATEMENTS] join the adapter's engine-limit `SET`s, in
 * that fixed order, after `preserve_insertion_order`.
 *
 * ## Why they exist
 *
 * Interrupt-first stops a reader that is computing at once, but a reader blocked inside an object-store request is
 * not computing: DuckDB v1.5.5's `HTTPUtil::RunRequestWithRetry` has no interrupt check, so the interrupt lands only
 * when the request (with its retries) returns. These four settings bound that, and [ReaderTermination] is derived
 * from them. With the engine's defaults (30 s, 3 retries, 100 ms, backoff 4) the same count is 360.5 s, and a store
 * that accepts a connection and then sends nothing holds a reader 4 × 30 s + 0.5 s = 120.5 s; with these bounds,
 * 3 × 10 s + 0.1 s = 30.1 s (the stalled-store integration test observes both).
 *
 * ## The engine's semantics (read off DuckDB v1.5.5 `src/main/http/http_util.cpp` and the settings table, probed)
 *
 * - `http_timeout` (seconds) is the client's connection, write AND read timeout alike ("HTTP timeout
 *   read/write/connection/retry"); a stalled store therefore costs at most one of each per attempt.
 * - `http_retries` counts retries after the first attempt: `retries + 1` attempts, retried on any I/O error (a
 *   timeout included) and on statuses 408, 418, 429, 500, 503 and 504.
 * - The sleep before an attempt is `http_retry_wait_ms × http_retry_backoff^(tries − 2)`, taken for `tries` from 2 to
 *   `retries` only: the first retry is immediate, and the loop gives up without a sleep once `tries` passes
 *   `retries`. With two retries that is one sleep of [HTTP_RETRY_WAIT_MS].
 * - All four are GLOBAL settings and exist only once `httpfs` is loaded — which is why they are emitted only for a
 *   datasource with `catalog.kind` (the adapter's extension statements load `httpfs` exactly then).
 * - On a managed generation `SET lock_configuration = true` (the §15a lockdown) refuses `SET`, `SET SESSION`,
 *   `SET GLOBAL` and `RESET` of each of them afterwards (probed, 2026-11-05, evidence 818d), so author SQL cannot
 *   set them back. A non-managed LAKE pool is not configuration-locked (#816's posture, unchanged here).
 *
 * ## The values
 *
 * [HTTP_TIMEOUT_SECONDS] = 10: no byte for ten seconds on an open connection is a stalled store, not a slow one —
 * the read timeout is per receive, so a long transfer that keeps moving never hits it. [HTTP_RETRIES] = 2: two
 * fresh-connection retries absorb a transient fault without letting a dead store hold a reader for minutes.
 * The wait and backoff keep the engine's own values. The handback states the residual: a store that trickles a byte
 * just inside the timeout, forever, is not bounded by any of these (DuckDB has no whole-request deadline).
 *
 * `LakeDialectAdapterTest` pins [STATEMENTS] and their place; `ReaderTerminationDerivationTest` recomputes the chain.
 */
object LakeHttpBounds {
    /** `http_timeout`: each of the connect, write and read timeouts, seconds. */
    const val HTTP_TIMEOUT_SECONDS: Long = 10L

    /** `http_retries`: retries after the first attempt. */
    const val HTTP_RETRIES: Int = 2

    /** `http_retry_wait_ms`: the base of the sleep before a retry, milliseconds (the engine's default). */
    const val HTTP_RETRY_WAIT_MS: Long = 100L

    /** `http_retry_backoff`: the sleep's growth factor per retry (the engine's default). */
    const val HTTP_RETRY_BACKOFF: Int = 4

    /** The timeouts one attempt can run in sequence against a stalled store: connect, write, read. */
    const val TIMEOUT_PHASES_PER_ATTEMPT: Int = 3

    /** The four `SET`s, in this fixed order. Integers only: nothing here is interpolated from configuration. */
    val STATEMENTS: List<String> =
        listOf(
            "SET http_timeout = $HTTP_TIMEOUT_SECONDS",
            "SET http_retries = $HTTP_RETRIES",
            "SET http_retry_wait_ms = $HTTP_RETRY_WAIT_MS",
            "SET http_retry_backoff = $HTTP_RETRY_BACKOFF",
        )

    /**
     * The longest one object-store request chain can block a reader, milliseconds: every attempt timing out in each
     * phase, plus every sleep between attempts. `(2 + 1) × 3 × 10 000 + 100 = 90 100`.
     */
    fun requestChainMillis(
        timeoutSeconds: Long = HTTP_TIMEOUT_SECONDS,
        retries: Int = HTTP_RETRIES,
        retryWaitMillis: Long = HTTP_RETRY_WAIT_MS,
        backoff: Int = HTTP_RETRY_BACKOFF,
    ): Long {
        val attempts = retries + 1L
        val timeouts = attempts * TIMEOUT_PHASES_PER_ATTEMPT * timeoutSeconds * MILLIS_PER_SECOND
        val sleeps = (2..retries).sumOf { tries -> (retryWaitMillis * backoff.toDouble().pow(tries - 2)).toLong() }
        return timeouts + sleeps
    }

    private const val MILLIS_PER_SECOND = 1_000L
}
