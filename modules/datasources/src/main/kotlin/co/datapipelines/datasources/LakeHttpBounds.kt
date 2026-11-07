package co.datapipelines.datasources

import kotlin.math.pow

/**
 * #818d (dp-lake record §10 RULING 4, second amendment, item 7) — the DuckDB HTTP timeout and retry settings every
 * LAKE engine that loads `httpfs` gets, as code constants: [STATEMENTS] join the adapter's engine-limit `SET`s, in
 * that fixed order, after `preserve_insertion_order`.
 *
 * ## Why they exist
 *
 * These settings bound silent-store waits as hygiene. They cannot bound a trickling transfer above curl's
 * low-speed threshold (duckdb-httpfs#519). Live reader leases protect snapshots independently of termination.
 * A silent-store test observes roughly 30.1 seconds with these settings and 120.5 seconds at engine defaults.
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
 * `LakeDialectAdapterTest` pins [STATEMENTS] and their place; the stalled-store test measures hygiene.
 *
 * ## The egress proxy (#845, owner ruling 2026-11-06 option B — hygiene, not safety)
 *
 * The settings above cannot end a store that trickles above curl's low-speed threshold. The managed LAKE datasource
 * therefore carries the dialect key [EGRESS_PROXY_KEY] (written by configuration, `datapipelines.lake.reads.egress-proxy`),
 * and [proxyStatement] points DuckDB's `http_proxy` at a proxy shipped with the deployment that resets every
 * object-store stream older than a fixed max-age. A cut transfer is an I/O error, which httpfs retries on a fresh
 * connection, so a reader against a store that trickles on every attempt ends within `(HTTP_RETRIES + 1) × max-age`
 * plus slack (probe A.0b, 2026-11-07: three cuts at a 20 s max-age ended the reader at 63.1 s). The `SET` is emitted
 * before the §15a lockdown, which then refuses `SET`/`RESET` of `http_proxy*` (probe A.0a). A temporary accommodation
 * until duckdb-httpfs#519 lands.
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
     * The conservative silent-store request-chain budget, milliseconds: every attempt timing out in each
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

    /** #845: the managed LAKE datasource's dialect key naming its egress proxy, `host:port`. */
    const val EGRESS_PROXY_KEY: String = "egress_proxy"

    /** The longest host name the grammar admits (RFC 1035's 253-character name). */
    private const val MAX_HOST_LENGTH = 253

    /** The highest TCP port. */
    private const val MAX_PORT = 65_535

    /** `host:port`: a DNS name or IPv4 literal (letters, digits, `.`, `-`), never a scheme, path, user or IPv6 literal. */
    private val EGRESS_PROXY_GRAMMAR = Regex("^([A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?):([0-9]{1,5})$")

    /**
     * Whether [value] is an egress proxy address this adapter will emit: `host:port`, a host of at most
     * [MAX_HOST_LENGTH] characters from `[A-Za-z0-9.-]` (starting and ending alphanumeric) and a port in 1–65535. The
     * grammar admits no quote, so the `SET` literal cannot be broken out of even before [proxyStatement] escapes it.
     */
    fun isEgressProxy(value: String): Boolean {
        val match = EGRESS_PROXY_GRAMMAR.matchEntire(value) ?: return false
        val (host, port) = match.destructured
        return host.length <= MAX_HOST_LENGTH && port.toInt() in 1..MAX_PORT
    }

    /** `SET http_proxy = '<host:port>'`, single quotes doubled; [hostPort] must satisfy [isEgressProxy]. */
    fun proxyStatement(hostPort: String): String {
        require(isEgressProxy(hostPort)) { "not an egress proxy address: host:port expected" }
        return "SET http_proxy = '" + hostPort.replace("'", "''") + "'"
    }

    private const val MILLIS_PER_SECOND = 1_000L
}
