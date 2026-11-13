package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeProperties
import co.datapipelines.persistence.FailureShape
import co.datapipelines.pipeline.PipelineErrorCodes.Lake
import co.datapipelines.typesystem.DatapipelinesException
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.S3Object
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Java performs the trusted listing; author SQL sees only the later materialised tables. */
@Suppress("ThrowsCount") // each malformed listing condition is its own catalogued refusal
class TrustedDeliveryListing(
    private val client: S3Client,
    private val storage: LakeProperties.Storage,
    private val maxDeliveryBytes: Long,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(maxDeliveryBytes > 0) { "maxDeliveryBytes must be positive" }
    }

    /** Every page shares the admission-fixed deadline; no retry gets a fresh timeout. */
    fun list(
        key: DeliveryKey,
        deadline: Instant,
    ): DeliveryManifest {
        val prefix = key.prefix(storage.landingPrefix)
        val listed = readPages(prefix, deadline)
        val ordered = listed.objects.sortedBy { it.key }
        val done =
            ordered.singleOrNull { it.key == "${prefix}_done" }
                ?: throw refusal(Lake.DELIVERY_INCOMPLETE, "Delivery has no final _done marker.", true)
        if (done.size != 0L) throw invalidManifest()
        val files = ordered.filter { it.key != done.key && !it.key.endsWith('/') }
        if (files.isEmpty()) throw invalidManifest()
        return DeliveryManifest(key, ordered, files, format(files), deliveryFingerprint(ordered), listed.totalBytes)
    }

    private data class Listed(
        val objects: List<DeliveryObject>,
        val totalBytes: Long,
    )

    private fun readPages(
        prefix: String,
        deadline: Instant,
    ): Listed {
        val objects = mutableListOf<DeliveryObject>()
        val keys = mutableSetOf<String>()
        val tokens = mutableSetOf<String>()
        var total = 0L
        var token: String? = null
        try {
            do {
                val response = client.listObjectsV2(request(prefix, token, deadline))
                total = addPage(response.contents(), prefix, objects, keys, total)
                token = nextToken(response, tokens)
            } while (token != null)
        } catch (failure: SdkException) {
            log.warn(
                "event=lake.delivery.listing_failed cause={} sql_state={}",
                FailureShape.cause(failure),
                FailureShape.sqlState(failure),
            )
            // SDK messages and causes can contain the endpoint or credentials. Neither crosses the boundary.
            throw refusal(Lake.DELIVERY_LISTING_FAILED, "Could not list the delivery; retry when storage is available.", true)
        }
        return Listed(objects, total)
    }

    private fun addPage(
        page: List<S3Object>,
        prefix: String,
        objects: MutableList<DeliveryObject>,
        keys: MutableSet<String>,
        initialBytes: Long,
    ): Long {
        var total = initialBytes
        page.forEach { obj ->
            val entry = checkedObject(obj, prefix)
            if (!keys.add(entry.key)) throw invalidManifest()
            if (entry.size > maxDeliveryBytes - total) {
                throw refusal(Lake.DELIVERY_TOO_LARGE, "Delivery exceeds the configured byte limit.")
            }
            total += entry.size
            objects += entry
        }
        return total
    }

    private fun nextToken(
        response: ListObjectsV2Response,
        tokens: MutableSet<String>,
    ): String? {
        if (response.isTruncated() != true) return null
        val next = response.nextContinuationToken()?.takeIf { it.isNotEmpty() } ?: throw invalidManifest()
        if (!tokens.add(next)) throw invalidManifest()
        return next
    }

    private fun request(
        prefix: String,
        token: String?,
        deadline: Instant,
    ): ListObjectsV2Request {
        val remaining = Duration.between(clock.instant(), deadline)
        if (remaining.toMillis() <= 0) throw refusal(Lake.DELIVERY_LISTING_FAILED, "Delivery listing exceeded its deadline.", true)
        return ListObjectsV2Request
            .builder()
            .bucket(storage.bucket)
            .prefix(prefix)
            .continuationToken(token)
            .overrideConfiguration { it.apiCallTimeout(remaining).apiCallAttemptTimeout(remaining) }
            .build()
    }

    private fun checkedObject(
        obj: S3Object,
        prefix: String,
    ): DeliveryObject {
        val key = obj.key() ?: throw invalidManifest()
        val size = obj.size() ?: throw invalidManifest()
        val etag = obj.eTag()?.takeIf { it.isNotBlank() } ?: throw invalidManifest()
        if (!key.startsWith(prefix) || size < 0) throw invalidManifest()
        if (key.endsWith('/') && size != 0L) throw invalidManifest()
        return DeliveryObject(key, size, etag)
    }

    private fun format(files: List<DeliveryObject>): DeliveryFormat {
        val formats =
            files
                .map { obj ->
                    when {
                        obj.key.endsWith(".parquet", ignoreCase = true) -> DeliveryFormat.Parquet
                        obj.key.endsWith(".csv", ignoreCase = true) -> DeliveryFormat.Csv
                        else -> throw refusal(Lake.DELIVERY_UNSUPPORTED_FORMAT, "Delivery data files must all be Parquet or all CSV.")
                    }
                }.distinct()
        if (formats.size != 1) {
            throw refusal(Lake.DELIVERY_UNSUPPORTED_FORMAT, "Delivery data files must all be Parquet or all CSV.")
        }
        return formats.single()
    }

    private fun invalidManifest() = refusal(Lake.DELIVERY_INVALID_MANIFEST, "Storage returned an invalid or empty delivery manifest.")

    private fun refusal(
        code: String,
        message: String,
        retryable: Boolean = false,
    ) = DatapipelinesException(code, message, mapOf("retryable" to retryable))

    private companion object {
        val log = LoggerFactory.getLogger(TrustedDeliveryListing::class.java)
    }
}
