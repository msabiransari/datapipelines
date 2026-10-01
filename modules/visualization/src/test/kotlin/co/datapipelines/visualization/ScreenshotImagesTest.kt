package co.datapipelines.visualization

import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The screenshot bytes are what they claim to be (the brief's §C): a header, a filename or a declared
 * Content-Type is NOT type validation — the DETECTED type wins, dimensions are read independently, and
 * malformed, truncated, spoofed or absurd files are refused. Every refused specimen here is falsified by
 * its own repaired control: the refusal follows the defect, not the fixture.
 */
class ScreenshotImagesTest {
    /** A minimal but well-formed 3x2 PNG: signature, IHDR, one IDAT byte, IEND. */
    private fun png(
        width: Int = 3,
        height: Int = 2,
    ): ByteArray {
        val ihdr =
            ByteArray(25).apply {
                intAtBe(0, 13) // the chunk's data length
                this[4] = 'I'.code.toByte(); this[5] = 'H'.code.toByte(); this[6] = 'D'.code.toByte(); this[7] = 'R'.code.toByte()
                intAtBe(8, width) // data: width, height, then bit depth 8, colour type 6, three zeros
                intAtBe(12, height)
                // bytes 16..20 (depth, colour, compression, filter, interlace) stay zero
                // bytes 21..24: CRC, unchecked by the parser
            }
        val idat = ByteArray(4 + 4 + 1 + 4).apply {
            intAtBe(0, 1)
            this[4] = 'I'.code.toByte(); this[5] = 'D'.code.toByte(); this[6] = 'A'.code.toByte(); this[7] = 'T'.code.toByte()
        }
        val iend = ByteArray(12).apply { this[4] = 'I'.code.toByte(); this[5] = 'E'.code.toByte(); this[6] = 'N'.code.toByte(); this[7] = 'D'.code.toByte() }
        return PNG_SIGNATURE + ihdr + idat + iend
    }

    private fun webpVp8(
        width: Int,
        height: Int,
    ): ByteArray {
        val body = ByteArray(10).apply {
            // frame tag (three bytes, keyframe), then the 0x9D 0x01 0x2A start code, then LE 14-bit dims.
            this[3] = 0x9D.toByte(); this[4] = 0x01.toByte(); this[5] = 0x2A.toByte()
            shortAtLe(6, width and 0x3FFF)
            shortAtLe(8, height and 0x3FFF)
        }
        val chunk = "VP8 ".toByteArray(Charsets.US_ASCII) + intLe(body.size + 0) + body
        val riff = "RIFF".toByteArray(Charsets.US_ASCII) + intLe(4 + chunk.size) + "WEBP".toByteArray(Charsets.US_ASCII)
        return riff + chunk
    }

    private fun webpVp8l(
        width: Int,
        height: Int,
    ): ByteArray {
        val bits = ((width - 1) and 0x3FFF) or (((height - 1) and 0x3FFF) shl 14)
        val payload = byteArrayOf(0x2F) + byteArrayOf(
            (bits and 0xFF).toByte(),
            ((bits shr 8) and 0xFF).toByte(),
            ((bits shr 16) and 0xFF).toByte(),
        )
        val chunk = "VP8L".toByteArray(Charsets.US_ASCII) + intLe(payload.size) + payload
        return "RIFF".toByteArray(Charsets.US_ASCII) + intLe(4 + chunk.size) + "WEBP".toByteArray(Charsets.US_ASCII) + chunk
    }

    private fun webpVp8x(
        width: Int,
        height: Int,
    ): ByteArray {
        val flags = ByteArray(4) // reserved
        val canvas = ByteArray(6).apply {
            tripleAtLe(0, width - 1)
            tripleAtLe(3, height - 1)
        }
        val chunk = "VP8X".toByteArray(Charsets.US_ASCII) + intLe(10) + flags + canvas
        return "RIFF".toByteArray(Charsets.US_ASCII) + intLe(4 + chunk.size) + "WEBP".toByteArray(Charsets.US_ASCII) + chunk
    }

    @Test
    fun `a PNG parses - detected type, independent positive dimensions, stable digest`() {
        val image = ScreenshotImages.parse("image/png", png(width = 640, height = 480))
        image.mediaType shouldBe "image/png"
        image.width shouldBe 640
        image.height shouldBe 480
        ScreenshotImages.sha256(png()).length shouldBe 64
    }

    @Test
    fun `a declared type that contradicts the bytes is refused - a renamed file is not a format`() {
        shouldThrow<DatapipelinesException> { ScreenshotImages.parse("image/webp", png()) }
            .let { it.details["reason"] shouldBe "media_type_mismatch" }
        // Falsification: the honest declaration passes.
        ScreenshotImages.parse("image/png", png()).width shouldBe 3
    }

    @Test
    fun `random bytes, a truncated PNG and a PNG whose first chunk is not IHDR are refused`() {
        shouldThrow<DatapipelinesException> { ScreenshotImages.parse(null, "hello world".toByteArray()) }
            .let { it.details["reason"] shouldBe "unrecognized" }
        shouldThrow<DatapipelinesException> { ScreenshotImages.parse(null, png().copyOfRange(0, 20)) }
            .let { it.details["reason"] shouldBe "truncated" }
        val swapped = png()
        swapped[12] = 'X'.code.toByte()
        shouldThrow<DatapipelinesException> { ScreenshotImages.parse(null, swapped) }
            .let { it.details["reason"] shouldBe "no_ihdr" }
    }

    @Test
    fun `a PNG header whose IHDR length lies is refused as truncated`() {
        val lying = png()
        lying.intAtBe(8, 999)
        shouldThrow<DatapipelinesException> { ScreenshotImages.parse(null, lying) }
            .let { it.details["reason"] shouldBe "truncated" }
    }

    @Test
    fun `WebP parses in all three chunk forms - VP8 lossy, VP8L lossless, VP8X extended`() {
        ScreenshotImages.parse("image/webp", webpVp8(800, 600)).let {
            it.mediaType shouldBe "image/webp"; it.width shouldBe 800; it.height shouldBe 600
        }
        ScreenshotImages.parse(null, webpVp8l(1024, 768)).let {
            it.width shouldBe 1024; it.height shouldBe 768
        }
        ScreenshotImages.parse(null, webpVp8x(10, 20)).let {
            it.width shouldBe 10; it.height shouldBe 20
        }
    }

    @Test
    fun `a WebP shorter than its RIFF size is truncated and a frameless WebP is refused`() {
        val truncated = webpVp8(4, 4).copyOfRange(0, 25)
        shouldThrow<DatapipelinesException> { ScreenshotImages.parse(null, truncated) }
            .let { it.details["reason"] shouldBe "truncated" }
        val noFrame = "RIFF".toByteArray(Charsets.US_ASCII) + intLe(4 + 14) + "WEBP".toByteArray(Charsets.US_ASCII) +
            "JUNK".toByteArray(Charsets.US_ASCII) + intLe(10) + ByteArray(10)
        shouldThrow<DatapipelinesException> { ScreenshotImages.parse(null, noFrame) }
            .let { it.details["reason"] shouldBe "no_frame" }
    }

    @Test
    fun `non-positive or absurd declared dimensions are refused - a spoofed header is not an image`() {
        shouldThrow<DatapipelinesException> { ScreenshotImages.parse(null, png(width = 0, height = 2)) }
            .let { it.details["reason"] shouldBe "dimensions" }
        shouldThrow<DatapipelinesException> { ScreenshotImages.parse(null, png(width = ScreenshotImages.MAX_DIMENSION + 1, height = 2)) }
            .let { it.details["reason"] shouldBe "dimensions_excessive" }
    }

    // ---- byte helpers (the parser's own offset grammar, test-side) ------------------------------------

    private fun ByteArray.intAtBe(
        offset: Int,
        value: Int,
    ) {
        this[offset] = (value ushr 24).toByte(); this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte(); this[offset + 3] = value.toByte()
    }

    private fun intLe(value: Int): ByteArray =
        byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())

    private fun ByteArray.shortAtLe(
        offset: Int,
        value: Int,
    ) {
        this[offset] = value.toByte(); this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.tripleAtLe(
        offset: Int,
        value: Int,
    ) {
        this[offset] = value.toByte(); this[offset + 1] = (value ushr 8).toByte(); this[offset + 2] = (value ushr 16).toByte()
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
