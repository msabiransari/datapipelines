package co.datapipelines.lake.settle

import co.datapipelines.lake.LakeCatalogs
import org.apache.iceberg.aws.s3.S3FileIOProperties
import software.amazon.awssdk.core.retry.backoff.FullJitterBackoffStrategy
import software.amazon.awssdk.services.s3.S3Client
import java.lang.Math.addExact
import java.lang.Math.multiplyExact

/**
 * Conditional network-wait envelope in milliseconds, starting at the first GetObject request, for one metadata
 * read abandoned by the settlement tick. This is arithmetic for a stalled-store model, not a wall-clock deadline.
 * Each connection/request is accepted promptly, or its connect/header wait times out; any answered request hands
 * back a body that stalls. There are no throttles, redirects, partial/trickling headers, TLS renegotiations or
 * intervening successful body reads. DNS, credentials, request writes, TLS setup, local execution and scheduling
 * must complete separately; idle socket timeouts do not bound those operations. No unconditional end is claimed.
 *
 * One request costs at most `(requestRetries + 1) × (connect + socket) + sum(backoff caps)` within this model.
 * Each answered stream adds its blocked body read, Iceberg's abort probe, and final close, conservatively allowing
 * one socket timeout for each. The total is `(PINNED_ICEBERG_STREAM_REOPENS + 1) × (request + 3 × socket)`.
 * Failed requests yielding no stream are overcounted deliberately. Retry-close probes are outside the SDK request
 * envelope and counted once per stream, including the last stream's close.
 *
 * Pinned assumption: Iceberg 1.12.0 S3InputStream.read uses a Failsafe policy withMaxRetries(3); onRetry invokes
 * resetForRetry/openStream, closing the old stream before a new GetObject. closeStream calls abortStream (one
 * probe read) then close. Source:
 * https://github.com/apache/iceberg/blob/apache-iceberg-1.12.0/aws/src/main/java/org/apache/iceberg/aws/s3/S3InputStream.java
 * Compatibility tests pin both the version and the observed reopen/probe counts. The other inputs come from the
 * same catalog properties and S3FileIOProperties.applyRetryConfigurations used by AwsClientFactories.DefaultAwsClientFactory;
 * neither a client nor a connection is opened to derive them. SDK retries and body retries are distinct layers.
 */
object SettlementObjectReadEnd {
    /** Iceberg 1.12.0's private fixed policy; review its source before changing this compatibility assumption. */
    const val PINNED_ICEBERG_STREAM_REOPENS = 3

    /** Effective finite millisecond inputs, or explicit alternatives for evaluating this same model. */
    data class Inputs(
        val connectTimeoutMillis: Long,
        val socketTimeoutMillis: Long,
        val requestRetries: Int,
        val backoffBaseMillis: Long,
        val backoffMaxMillis: Long,
    )

    /**
     * Reads only configuration: no client build, credential resolution, reflection or I/O. Iceberg explicitly pins
     * the deprecated LEGACY retry-policy API; inspecting that exact policy avoids substituting SDK-wide defaults.
     * s3.retry.min-wait-ms/max-wait-ms affect throttling only, which this model excludes.
     */
    @Suppress("DEPRECATION")
    fun effectiveInputs(catalogProperties: Map<String, String>): Inputs {
        require(catalogProperties["http-client.type"] == "urlconnection") { "unsupported HTTP client" }
        require("s3.client-factory-impl" !in catalogProperties && "client.factory" !in catalogProperties) {
            "unsupported custom client factory"
        }
        val builder = S3Client.builder()
        S3FileIOProperties(catalogProperties).applyRetryConfigurations(builder)
        val policy = builder.overrideConfiguration().retryPolicy().orElseThrow()
        val strategy = policy.backoffStrategy()
        require(strategy is FullJitterBackoffStrategy) { "unsupported non-throttling backoff strategy" }
        val backoff = strategy.toBuilder()
        return Inputs(
            connectTimeoutMillis = timeout(catalogProperties, LakeCatalogs.HTTP_CONNECTION_TIMEOUT_MS),
            socketTimeoutMillis = timeout(catalogProperties, LakeCatalogs.HTTP_SOCKET_TIMEOUT_MS),
            requestRetries = policy.numRetries(),
            backoffBaseMillis = backoff.baseDelay().toMillis(),
            backoffMaxMillis = backoff.maxBackoffTime().toMillis(),
        )
    }

    /** Same derivation with the catalog's effective inputs; no property value is logged. */
    fun stalledReadEndByMillis(catalogProperties: Map<String, String>): Long = stalledReadEndByMillis(effectiveInputs(catalogProperties))

    /**
     * One pure derivation, in milliseconds from the first GetObject through the final close, under the model above.
     * Positive timeouts must fit URLConnection's signed-int millisecond range; zero means unbounded there. Backoff
     * values must fit the SDK's millisecond arithmetic. Invalid inputs and duration overflow fail instead of wrapping.
     */
    fun stalledReadEndByMillis(inputs: Inputs): Long {
        require(inputs.connectTimeoutMillis in 1..Int.MAX_VALUE.toLong()) { "unsupported connect timeout" }
        require(inputs.socketTimeoutMillis in 1..Int.MAX_VALUE.toLong()) { "unsupported socket timeout" }
        require(inputs.requestRetries in 0..MAX_MODELLED_GAPS) { "unsupported request retry count" }
        require(inputs.backoffBaseMillis in 1..Int.MAX_VALUE.toLong()) { "unsupported backoff base" }
        require(inputs.backoffMaxMillis in inputs.backoffBaseMillis..Int.MAX_VALUE.toLong()) { "unsupported backoff maximum" }
        val attempts = addExact(inputs.requestRetries.toLong(), 1L)
        val requestWait = multiplyExact(attempts, addExact(inputs.connectTimeoutMillis, inputs.socketTimeoutMillis))
        val requestEnd = addExact(requestWait, backoffCapsMillis(inputs))
        val streamTail = multiplyExact(STREAM_TAIL_SOCKET_TIMEOUTS, inputs.socketTimeoutMillis)
        return multiplyExact(PINNED_ICEBERG_STREAM_REOPENS + 1L, addExact(requestEnd, streamTail))
    }

    /** The SDK's exponential full-jitter ceiling, saturating at the configured maximum before doubling can wrap. */
    private fun backoffCapsMillis(inputs: Inputs): Long {
        var total = 0L
        var cap = inputs.backoffBaseMillis
        repeat(inputs.requestRetries) {
            total = addExact(total, cap)
            cap = if (cap > inputs.backoffMaxMillis / 2) inputs.backoffMaxMillis else multiplyExact(cap, 2L)
        }
        return total
    }

    private fun timeout(
        properties: Map<String, String>,
        key: String,
    ): Long = requireNotNull(properties[key]?.toLongOrNull()) { "missing or unparseable timeout: $key" }

    /** Body read, abort probe, close. These are outside the already-counted request/header envelope. */
    private const val STREAM_TAIL_SOCKET_TIMEOUTS = 3L

    /** Restricts this deliberately small model; a larger policy needs review, not an unbounded arithmetic loop. */
    private const val MAX_MODELLED_GAPS = 63
}
