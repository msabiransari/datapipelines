package co.datapipelines.application.endpoints

import co.datapipelines.typesystem.body.StructuredBody
import java.nio.charset.StandardCharsets
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The two keyed digests of a published mutation reservation (metadata-db §4.61; published-mutation-apis §4, §6).
 *
 * Both are HMAC-SHA256 under [key] — a purpose-bound subkey of the deployment secret (`JwtService.deriveSubkey`), so
 * neither the raw `Idempotency-Key` nor the request body is stored, and a stolen metadata dump cannot confirm a guessed
 * key or body without the secret. Every field joins length-prefixed, so no two different field lists can encode to the
 * same input.
 *
 * - [scope]: which reservation a request belongs to — workspace, credential, route ENTITY, method, concrete business
 *   path and the client key. A recreated route, a rotated credential or another concrete resource is another scope.
 * - [fingerprint]: whether a request in that scope is the SAME request — method, concrete path, every query parameter
 *   (names sorted, values in order) and the body's canonical form (object member order immaterial, array order
 *   material, an absent body distinct from `{}` and `[]`).
 */
class PublishedMutationDigests(
    key: ByteArray,
) {
    private val keySpec: SecretKeySpec

    init {
        require(key.size >= MIN_KEY_BYTES) { "the replay digest key must be at least $MIN_KEY_BYTES bytes" }
        keySpec = SecretKeySpec(key.copyOf(), HMAC_SHA256)
    }

    /** The scope digest a request's reservation is found and held under. */
    @Suppress("LongParameterList") // the scope IS these six facts
    fun scope(
        workspaceId: UUID,
        apiKeyId: String,
        endpointId: UUID,
        method: EndpointMethod,
        businessPath: String,
        clientKey: String,
    ): String =
        digest(
            SCOPE_DOMAIN,
            listOf(workspaceId.toString(), apiKeyId, endpointId.toString(), method.name, businessPath, clientKey),
        )

    /** The request fingerprint compared on every retry in a scope. */
    fun fingerprint(
        method: EndpointMethod,
        businessPath: String,
        query: Map<String, List<String>>,
        body: StructuredBody,
    ): String {
        val fields = mutableListOf(method.name, businessPath, query.size.toString())
        query.toSortedMap().forEach { (name, values) ->
            fields += name
            fields += values.size.toString()
            fields += values
        }
        val canonical = body.canonical()
        fields += if (canonical == null) BODY_ABSENT else BODY_PRESENT
        canonical?.let { fields += it }
        return digest(FINGERPRINT_DOMAIN, fields)
    }

    private fun digest(
        domain: String,
        fields: List<String>,
    ): String {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(keySpec)
        (listOf(domain) + fields).forEach { field ->
            val bytes = field.toByteArray(StandardCharsets.UTF_8)
            mac.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            mac.update(SEPARATOR)
            mac.update(bytes)
        }
        return HexFormat.of().formatHex(mac.doFinal())
    }

    companion object {
        /** The `JwtService.deriveSubkey` label of this key — one purpose, one subkey. */
        const val SUBKEY_LABEL = "published-mutation-replay-v1"

        private const val HMAC_SHA256 = "HmacSHA256"
        private const val MIN_KEY_BYTES = 32
        private const val SCOPE_DOMAIN = "scope"
        private const val FINGERPRINT_DOMAIN = "fingerprint"
        private const val BODY_ABSENT = "body:absent"
        private const val BODY_PRESENT = "body:present"
        private val SEPARATOR = byteArrayOf(':'.code.toByte())
    }
}

/**
 * The `Idempotency-Key` grammar of a published mutation (published-mutation-apis §5): 1–128 characters from `A–Z`,
 * `a–z`, `0–9`, `.`, `_`, `:` and `-`. Required on every published POST, PUT and DELETE, including a bodyless one.
 */
object PublishedIdempotencyKey {
    /** The longest key admitted. */
    const val MAX_LENGTH = 128

    /** `reason` of a missing key. */
    const val REASON_MISSING = "missing"

    /** `reason` of a key outside the grammar (length or characters). */
    const val REASON_MALFORMED = "malformed"

    private val GRAMMAR = Regex("^[A-Za-z0-9._:-]{1,$MAX_LENGTH}$")

    /** Null when [raw] is a valid key; otherwise the refusal reason. The value itself is never echoed by a caller. */
    fun refusal(raw: String?): String? =
        when {
            raw == null -> REASON_MISSING
            !GRAMMAR.matches(raw) -> REASON_MALFORMED
            else -> null
        }
}
