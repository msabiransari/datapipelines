package co.datapipelines.visualization

import co.datapipelines.typesystem.DatapipelinesException
import java.security.MessageDigest

/**
 * The screenshot's bytes are what they claim to be (the brief's §C): the stored media type is the
 * DETECTED one — a declared Content-Type, a filename or an extension is not type validation. The parser
 * reads the format's own header, independently derives positive dimensions, and refuses malformed,
 * truncated, spoofed or absurd files. It NEVER decodes: parsing is O(header), there is no decoder to be
 * unbounded, and the check reads only the byte array the request already delivered.
 */
@Suppress("MagicNumber") // the formats' own signature bytes and field offsets (RFC 2083 / RFC 9649), named in the comments
object ScreenshotImages {
    /** A validated image: the detected media type and the independently read dimensions. */
    data class Image(
        val mediaType: String,
        val width: Int,
        val height: Int,
    )

    /** Parsed and bounded. The declared type, when present, must AGREE with the bytes'. */
    fun parse(
        declaredMediaType: String?,
        bytes: ByteArray,
    ): Image {
        val image =
            when {
                bytes.size >= PNG_HEADER + 12 && bytes.isPng() -> parsePng(bytes)
                bytes.size >= WEBP_HEADER && bytes.isWebp() -> parseWebp(bytes)
                else -> throw invalid("unrecognized")
            }
        if (declaredMediaType != null && declaredMediaType != image.mediaType) {
            throw invalid("media_type_mismatch")
        }
        return image
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---- PNG (RFC 2083): signature, then the IHDR chunk's big-endian dimensions at fixed offsets. -----

    private const val PNG_HEADER = 8

    private fun ByteArray.isPng(): Boolean = startsWith(PNG_SIGNATURE)

    @Suppress("ThrowsCount") // each malformed PNG form is its own named refusal
    private fun parsePng(bytes: ByteArray): Image {
        // The first chunk must be IHDR (length 13, then the type at 12..15, width 16..19, height 20..23).
        if (bytes.size < 24) throw invalid("truncated")
        if (!bytes.hasAt(IHDR, 12)) throw invalid("no_ihdr")
        val width = bytes.intAt(16)
        val height = bytes.intAt(20)
        // The IHDR chunk declares 13 data bytes; a file shorter than its own header is truncated.
        val declaredLength = bytes.intAt(8)
        if (declaredLength != 13 || bytes.size < 16 + declaredLength + 4) throw invalid("truncated")
        return bounded("image/png", width, height)
    }

    // ---- WebP (RFC 9649): RIFF size, then VP8 / VP8L / VP8X carry the dimensions. ---------------------

    private const val WEBP_HEADER = 12

    /** The four-byte chunk tag that follows the RIFF/WEBP header. */
    private const val CHUNK_TAG = 4

    /** The bytes the RIFF size does NOT count (`RIFF` + the size itself). */
    private const val RIFF_PREFIX = 8L

    /** The smallest honest RIFF size: the `WEBP` form tag. */
    private const val WEBP_TAG = 4L

    private const val UNSIGNED_INT = 0xFFFF_FFFFL

    private fun ByteArray.isWebp(): Boolean = hasAt(RIFF, 0) && hasAt(WEBP, 8)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = hasAt(prefix, 0)

    private fun ByteArray.hasAt(
        prefix: ByteArray,
        offset: Int,
    ): Boolean = size >= offset + prefix.size && prefix.indices.all { this[offset + it] == prefix[it] }

    @Suppress("ThrowsCount") // each malformed WebP form is its own named refusal
    private fun parseWebp(bytes: ByteArray): Image {
        // The RIFF size is UNSIGNED and counts everything after byte 8: a file shorter than its own declaration is
        // truncated, as is one too short to carry the chunk tag read below, and a declaration with the top bit set
        // is not a negative number that skips the check (the 352 merge's F4 — a 12-byte header read past its end).
        if (bytes.size < WEBP_HEADER + CHUNK_TAG) throw invalid("truncated")
        val riffSize = bytes.intAtLe(4).toLong() and UNSIGNED_INT
        if (riffSize < WEBP_TAG || riffSize + RIFF_PREFIX > bytes.size) throw invalid("truncated")
        return when (bytes.chunk()) {
            "VP8 " -> {
                // The lossy keyframe's start code (0x9D 0x01 0x2A), then two little-endian 14-bit dimensions.
                if (bytes.size < 30) throw invalid("truncated")
                if (!bytes.hasAt(VP8_START_CODE, 23)) throw invalid("no_frame")
                bounded("image/webp", bytes.shortAtLe(26) and 0x3FFF, bytes.shortAtLe(28) and 0x3FFF)
            }

            "VP8L" -> {
                // The lossless stream's 0x2F signature byte, then width-1 and height-1 in 14 bits each.
                if (bytes.size < 24) throw invalid("truncated")
                if (bytes[20] != 0x2F.toByte()) throw invalid("no_frame")
                val bits = (bytes[21].toInt() and 0xFF) or ((bytes[22].toInt() and 0xFF) shl 8) or ((bytes[23].toInt() and 0xFF) shl 16)
                bounded("image/webp", (bits and 0x3FFF) + 1, ((bits shr 14) and 0x3FFF) + 1)
            }

            "VP8X" -> {
                // The extended format's canvas size: 24-bit (dimension - 1) each, little-endian, at 24 and 27.
                if (bytes.size < 30) throw invalid("truncated")
                bounded("image/webp", bytes.tripleAtLe(24) + 1, bytes.tripleAtLe(27) + 1)
            }

            else -> {
                throw invalid("no_frame")
            }
        }
    }

    /** Positive, and inside the server's sanity bound — a header claiming more is a spoof, not an image. */
    private fun bounded(
        mediaType: String,
        width: Int,
        height: Int,
    ): Image {
        if (width <= 0 || height <= 0) throw invalid("dimensions")
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) throw invalid("dimensions_excessive")
        return Image(mediaType, width, height)
    }

    private fun invalid(reason: String) =
        DatapipelinesException(
            code = VisualizationErrorCodes.TEST_SCREENSHOT_INVALID,
            message = "The bytes are not a readable PNG or WebP image: $reason.",
            details = mapOf("reason" to reason),
        )

    /** The per-side sanity bound: a browser screenshot is orders of magnitude below this; a header above it lies. */
    const val MAX_DIMENSION = 1_000_000

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** The lossy WebP keyframe's start code (RFC 9649 §9). */
    private val VP8_START_CODE = byteArrayOf(0x9D.toByte(), 0x01, 0x2A)

    private val IHDR = "IHDR".toByteArray(Charsets.US_ASCII)

    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)

    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)

    private fun ByteArray.chunk(): String = String(this, 12, 4, Charsets.US_ASCII)

    private fun ByteArray.intAt(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or (this[offset + 3].toInt() and 0xFF)

    private fun ByteArray.intAtLe(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or ((this[offset + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.shortAtLe(offset: Int): Int = (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.tripleAtLe(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8) or ((this[offset + 2].toInt() and 0xFF) shl 16)
}
