package co.datapipelines.lake.settle

import co.datapipelines.lake.LakeCatalogs
import org.apache.iceberg.aws.s3.S3FileIOProperties
import java.lang.Math.addExact
import java.lang.Math.multiplyExact

/**
 * docs/lake.md §8's stated worst-case end of the settlement tick's metadata read on a store that accepts every
 * connection and every request and never answers in time (#866): one derivation whose inputs are read back from the
 * effective catalog properties the store client is built from, so the document's number cannot drift from the
 * client's bounds.
 *
 * The model. A GetObject request always ends within [requestEndMillis]: each of the SDK's attempts is a fresh
 * connection bounded by the connect timeout, waiting at most the socket timeout for the response headers, with the
 * full-jitter backoff capped per gap between attempts. Whether a request is finally answered changes only what
 * follows. A stream a request handed back costs at most three socket timeouts more: the blocked body read the caller
 * asked for, then the closure's probe read and close (`S3InputStream.abortStream` reads the stream once before
 * closing it; the UrlConnection client's response streams are abortable, so the probe runs). Iceberg 1.12.0's
 * `S3InputStream` retry policy allows three retries and every retry reopens the stream (`withMaxRetries(3)`,
 * `onRetry` reopens), so one read issues at most [STREAM_REOPENS] + 1 requests and closes at most that many streams.
 * The read's end is their sum: `(STREAM_REOPENS + 1) × (request end + 3 × socket timeout)`. With the shipped inputs —
 * 2 000 ms connect, 10 000 ms socket, five SDK retries (six attempts), 3 100 ms of backoff caps — that is 420 400 ms,
 * about 420 seconds.
 *
 * The pinned-library premises (Iceberg's retry policy and reopen, the SDK's LEGACY defaults DP never overrides, the
 * abortable streams) are observed against the shipped jars by `SettlementObjectReadEndCompatTest`: if the libraries
 * move, that test fails and this number is wrong until reviewed.
 *
 * Not counted, stated as residuals in docs/lake.md §8: DNS resolution and the credential chain's fetches on the
 * read's thread; a store answering with throttles, whose retries follow the throttling backoff instead; a store that
 * trickles bytes within the socket timeout, which the read's own stop bounds (#851).
 */
object SettlementObjectReadEnd {
    /**
     * `readStream(..., IN_STREAM)` recurses on a timed-out body read up to [STREAM_REOPENS] more times
     * (`IcebergS3InputFileStream#strategyBuilder`): each reopen is one new GetObject request.
     */
    const val STREAM_REOPENS = 3

    /** The AWS SDK's LEGACY full-jitter backoff base (software.amazon.awssdk.core.retry, 2.46.7). */
    const val SDK_BACKOFF_BASE_MILLIS = 100L

    /** The AWS SDK's full-jitter backoff ceiling per gap (`SdkDefaultRetrySetting.MAX_BACKOFF`), 20 s. */
    const val SDK_BACKOFF_MAX_MILLIS = 20_000L

    /**
     * One GetObject request's worst-case end: every attempt's connect plus header wait, plus the backoff caps between
     * attempts. A request's end does not depend on whether it is finally answered — an answer that arrives at all
     * arrives within the same bound, and the stream it hands back is a separate term.
     */
    fun requestEndMillis(
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        requestRetries: Int,
    ): Long =
        addExact(
            multiplyExact(requestRetries + 1L, addExact(connectTimeoutMillis, socketTimeoutMillis)),
            backoffCapsMillis(requestRetries),
        )

    /**
     * The worst-case end of one metadata read, from its first request to its last socket operation, on a store that
     * accepts and never answers: `(STREAM_REOPENS + 1) × (request end + 3 × socket timeout)`. Inputs this model does
     * not admit — a non-positive timeout, a retry count outside the backoff model's range — are refused, and an input
     * whose bound overflows the clock fails the arithmetic rather than wrapping.
     */
    fun stalledReadEndByMillis(
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        requestRetries: Int,
    ): Long {
        require(connectTimeoutMillis >= 1) { "the connect timeout must be positive: $connectTimeoutMillis" }
        require(socketTimeoutMillis >= 1) { "the socket timeout must be positive: $socketTimeoutMillis" }
        val cycle =
            addExact(
                requestEndMillis(connectTimeoutMillis, socketTimeoutMillis, requestRetries),
                multiplyExact(STREAM_TAIL_SOCKET_TIMEOUTS, socketTimeoutMillis),
            )
        return multiplyExact(STREAM_REOPENS + 1L, cycle)
    }

    /**
     * The same end read from the effective catalog properties ([LakeCatalogs.catalogProperties], the map the store
     * client is built from): the two socket bounds by key, the SDK retries from the properties' own default unless
     * the map carries the key. A key present but unparseable is refused rather than guessed.
     */
    fun stalledReadEndByMillis(catalogProperties: Map<String, String>): Long {
        val connect =
            requireNotNull(catalogProperties[LakeCatalogs.HTTP_CONNECTION_TIMEOUT_MS]?.toLongOrNull()) {
                "the catalog properties carry no effective connect timeout (${LakeCatalogs.HTTP_CONNECTION_TIMEOUT_MS})"
            }
        val socket =
            requireNotNull(catalogProperties[LakeCatalogs.HTTP_SOCKET_TIMEOUT_MS]?.toLongOrNull()) {
                "the catalog properties carry no effective socket timeout (${LakeCatalogs.HTTP_SOCKET_TIMEOUT_MS})"
            }
        val retries =
            catalogProperties[S3FileIOProperties.S3_RETRY_NUM_RETRIES]
                ?.let(::parseRetries)
                ?: S3FileIOProperties.S3_RETRY_NUM_RETRIES_DEFAULT
        return stalledReadEndByMillis(connect, socket, retries)
    }

    /**
     * The full-jitter backoff's worst case over [requestRetries] gaps: each gap at most base × 2^gap capped at the
     * SDK's ceiling, so the caps themselves cannot hide an unbounded retry policy.
     */
    private fun backoffCapsMillis(requestRetries: Int): Long {
        require(requestRetries in 0..MAX_MODELLED_GAPS) {
            "the backoff model covers $MAX_MODELLED_GAPS gaps, not $requestRetries"
        }
        var total = 0L
        for (gap in 0 until requestRetries) {
            val doubled = 1L shl gap
            // The uncapped gap is base × 2^gap, which overflows long before a sane retry count; saturate instead.
            val uncapped =
                if (SDK_BACKOFF_BASE_MILLIS > Long.MAX_VALUE / doubled) {
                    Long.MAX_VALUE
                } else {
                    multiplyExact(SDK_BACKOFF_BASE_MILLIS, doubled)
                }
            total = addExact(total, minOf(uncapped, SDK_BACKOFF_MAX_MILLIS))
        }
        return total
    }

    private fun parseRetries(raw: String): Int {
        val parsed = raw.toIntOrNull()
        require(parsed != null) { "the catalog properties carry an unparseable ${S3FileIOProperties.S3_RETRY_NUM_RETRIES}: $raw" }
        return parsed
    }

    /** One answered stream's tail in socket timeouts: the blocked body read, then the closure's probe read and close. */
    private const val STREAM_TAIL_SOCKET_TIMEOUTS = 3L

    /** Gaps whose doubled base still fits the model's arithmetic; the SDK's own ceiling is far below. */
    private const val MAX_MODELLED_GAPS = 63
}
