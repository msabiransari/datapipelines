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

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---- PNG (RFC 2083): signature, then the IHDR chunk's big-endian dimensions at fixed offsets. -----

    private const val PNG_HEADER = 8

    private fun ByteArray.isPng(): Boolean =
        this[0] == 0x89.toByte() && this[1] == 0x50.toByte() && this[2] == 0x4E.toByte() && this[3] == 0x47.toByte() &&
            this[4] == 0x0D.toByte() && this[5] == 0x0A.toByte() && this[6] == 0x1A.toByte() && this[7] == 0x0A.toByte()

    private fun parsePng(bytes: ByteArray): Image {
        // The first chunk must be IHDR (length 13, then the type at 12..15, width 16..19, height 20..23).
        if (bytes.size < 24) throw invalid("truncated")
        if (bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte() || bytes[14] != 'D'.code.toByte() || bytes[15] != 'R'.code.toByte()) {
            throw invalid("no_ihdr")
        }
        val width = bytes.intAt(16)
        val height = bytes.intAt(20)
        // The IHDR chunk declares 13 data bytes; a file shorter than its own header is truncated.
        val declaredLength = bytes.intAt(8)
        if (declaredLength != 13 || bytes.size < 16 + declaredLength + 4) throw invalid("truncated")
        return bounded("image/png", width, height)
    }

    // ---- WebP (RFC 9649): RIFF size, then VP8 / VP8L / VP8X carry the dimensions. ---------------------

    private const val WEBP_HEADER = 12

    private fun ByteArray.isWebp(): Boolean =
        this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() && this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte() &&
            this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte() && this[10] == 'B'.code.toByte() && this[11] == 'P'.code.toByte()

    private fun parseWebp(bytes: ByteArray): Image {
        // The RIFF size counts everything after byte 8; a file shorter than its own declaration is truncated.
        val riffSize = bytes.intAtLe(4)
        if (riffSize.toLong() + 8 > bytes.size) throw invalid("truncated")
        return when (bytes.chunk()) {
            "VP8 " -> {
                // The lossy keyframe's start code (0x9D 0x01 0x2A), then two little-endian 14-bit dimensions.
                if (bytes.size < 30) throw invalid("truncated")
                if (bytes[23] != 0x9D.toByte() || bytes[24] != 0x01.toByte() || bytes[25] != 0x2A.toByte()) throw invalid("no_frame")
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

            else -> throw invalid("no_frame")
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

    private fun ByteArray.chunk(): String = String(this, 12, 4, Charsets.US_ASCII)

    private fun ByteArray.intAt(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or (this[offset + 3].toInt() and 0xFF)

    private fun ByteArray.intAtLe(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or ((this[offset + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.shortAtLe(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.tripleAtLe(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8) or ((this[offset + 2].toInt() and 0xFF) shl 16)
}
