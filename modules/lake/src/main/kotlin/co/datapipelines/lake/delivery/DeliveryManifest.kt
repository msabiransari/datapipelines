package co.datapipelines.lake.delivery

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat

/** Sender-controlled path segments, validated before any storage or database use. */
data class DeliveryKey(
    val feed: String,
    val id: String,
) {
    init {
        if (!SEGMENT.matches(feed) || !SEGMENT.matches(id)) {
            throw DatapipelinesException(
                PipelineErrorCodes.Lake.DELIVERY_INVALID_KEY,
                "Delivery feed and id must match [a-z0-9_-]{1,64}.",
            )
        }
    }

    /** Object-store prefix, never a URL or a sender-selected bucket. */
    fun prefix(landingPrefix: String): String = landingPrefix.trim('/').let { root -> "${if (root.isEmpty()) "" else "$root/"}$feed/$id/" }

    private companion object {
        val SEGMENT = Regex("[a-z0-9_-]{1,64}")
    }
}

/** An exact object from the trusted listing; ETag is a validator, not a content hash. */
data class DeliveryObject(
    val key: String,
    val size: Long,
    val etag: String,
)

/** The single format accepted by one delivery. */
sealed interface DeliveryFormat {
    data object Parquet : DeliveryFormat

    data object Csv : DeliveryFormat
}

/** Frozen listing used both for staging and for the immutable recipe. Includes the exact done marker. */
data class DeliveryManifest(
    val key: DeliveryKey,
    val objects: List<DeliveryObject>,
    val files: List<DeliveryObject>,
    val format: DeliveryFormat,
    val fingerprint: String,
    val totalBytes: Long,
)

/** Length-prefixed UTF-8 triples in key order; embedded delimiters cannot alias another manifest. */
internal fun deliveryFingerprint(objects: List<DeliveryObject>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    objects.forEach { obj ->
        listOf(obj.key, obj.size.toString(), obj.etag).forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}
