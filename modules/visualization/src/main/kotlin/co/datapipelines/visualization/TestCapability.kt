package co.datapipelines.visualization

import co.datapipelines.typesystem.DatapipelinesException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The test session's two capabilities (the spec's §11.2, the owner's 2026-09-30 ruling): a PREVIEW token
 * minted at session start and a single-use UPLOAD capability minted at successful results submission.
 * Both are 32 cryptographically random bytes, base64url on the wire, stored HASH-only — the raw material
 * is shown to the caller exactly once, at issuance, and never logged, persisted or echoed again.
 *
 * The hash domain is purpose-prefixed, so a preview token can never verify as an upload capability or
 * vice versa (the cross-purpose refusal is a hash mismatch by construction, not a comparison somewhere).
 */
object TestCapability {
    /** The preview token's hash domain. */
    const val PREVIEW_PURPOSE = "visualization.test.preview.v1"

    /** The screenshot upload capability's hash domain. */
    const val UPLOAD_PURPOSE = "visualization.test.upload.v1"

    /** Both capabilities are 32 bytes (the spec's §11.2). */
    const val BYTES = 32

    private val secureRandom = SecureRandom()

    /** [random] is the service's seam; this is the production answer. */
    fun secureBytes(count: Int): ByteArray = ByteArray(count).also(secureRandom::nextBytes)

    /**
     * The wire form of a freshly minted capability: base64url, no padding. Callers hand it to the agent;
     * nothing server-side keeps it.
     */
    fun encode(material: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(material)

    /** SHA-256 over the purpose prefix and the wire form — the ONLY form ever stored. */
    fun hash(
        purpose: String,
        material: ByteArray,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(purpose.toByteArray(Charsets.US_ASCII))
        digest.update(':'.code.toByte())
        digest.update(material)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The stored form of a capability presented back to the server. */
    fun hashEncoded(
        purpose: String,
        encoded: String,
    ): String {
        val material =
            runCatching { Base64.getUrlDecoder().decode(encoded) }.getOrElse {
                throw DatapipelinesException(
                    code = VisualizationErrorCodes.TEST_SESSION_NOT_FOUND,
                    message = "The test capability is malformed; it is not a capability of any session.",
                    details = mapOf("reason" to "capability_malformed"),
                )
            }
        return hash(purpose, material)
    }
}
