package co.datapipelines.visualization

import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldHaveLength
import org.junit.jupiter.api.Test

/**
 * The capability primitive (the owner's 2026-09-30 ruling): 32 random bytes, base64url on the wire,
 * hash-only at rest, and the purpose prefix that makes a preview token verify as nothing else — the
 * cross-purpose refusal is a hash mismatch by construction.
 */
class TestCapabilityTest {
    @Test
    fun `minting yields 32 random bytes whose wire form is base64url without padding`() {
        val first = TestCapability.secureBytes(TestCapability.BYTES)
        val second = TestCapability.secureBytes(TestCapability.BYTES)
        first.size shouldBe 32
        first shouldNotBe second // randomness, not a constant
        TestCapability.encode(first).shouldHaveLength(43) // 32 bytes -> 43 base64url chars, no padding
    }

    @Test
    fun `the hash is stable, 64 hex, and purpose-separated - a preview token never verifies as an upload`() {
        val material = TestCapability.secureBytes(TestCapability.BYTES)
        val preview = TestCapability.hash(TestCapability.PREVIEW_PURPOSE, material)
        val upload = TestCapability.hash(TestCapability.UPLOAD_PURPOSE, material)
        preview shouldHaveLength 64
        preview shouldBe TestCapability.hash(TestCapability.PREVIEW_PURPOSE, material)
        preview shouldNotBe upload // the cross-purpose refusal, by construction
        TestCapability.hashEncoded(TestCapability.UPLOAD_PURPOSE, TestCapability.encode(material)) shouldBe upload
    }

    @Test
    fun `a malformed capability is refused session_not_found - it verifies nothing`() {
        shouldThrow<DatapipelinesException> {
            TestCapability.hashEncoded(TestCapability.UPLOAD_PURPOSE, "not-base64-###")
        }.code shouldBe VisualizationErrorCodes.TEST_SESSION_NOT_FOUND
    }
}
