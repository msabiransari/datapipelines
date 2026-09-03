package co.datapipelines.auth

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The `datapipelines.deployment.promotion.*` keys (configuration.md §3.19, versioning §10.6).
 *
 * Two halves of one deployment's promotion posture, and a deployment may hold either, both or
 * neither:
 *
 * - **[serverKey] — the RECEIVER half.** The pre-shared secret an inbound promotion must
 *   present. **Absent means promotion is refused**, not "promotion is open": a deployment that
 *   never configured a key must not silently accept pushes ([PromotionServerKeys.matches]
 *   encodes the fail-closed rule, so no caller can get it wrong by forgetting a null check).
 * - **[target] — the SENDER half.** Exactly one configured higher environment (§10: "exactly
 *   one"), its base URL and the same secret. `ConfigValidator` refuses a target named without
 *   a key.
 *
 * The secret is a bearer credential: it never reaches a log line, an error message, an audit
 * `details` map or a `toString()`. [toString] is overridden here for that reason — a
 * `data class` would otherwise print both keys the first time anything logged the properties
 * object, which is exactly how bearer secrets escape.
 */
@ConfigurationProperties(prefix = "datapipelines.deployment.promotion")
class PromotionProperties(
    val serverKey: String? = null,
    val target: Target = Target(),
) {
    /** The single configured higher environment (§10.6's sender half). */
    class Target(
        val baseUrl: String? = null,
        val serverKey: String? = null,
    ) {
        /** True when a target is configured at all — a blank base-url is "no target". */
        val isConfigured: Boolean get() = !baseUrl.isNullOrBlank()

        override fun toString(): String = "Target(baseUrl=$baseUrl, serverKey=${redact(serverKey)})"
    }

    /** True when this deployment can RECEIVE — the fail-closed gate's one question. */
    val receives: Boolean get() = !serverKey.isNullOrBlank()

    override fun toString(): String = "PromotionProperties(serverKey=${redact(serverKey)}, target=$target)"

    private companion object {
        fun redact(value: String?): String = if (value.isNullOrBlank()) "(unset)" else "(set)"
    }
}

/**
 * The promotion server key's two operations, in one place so both have exactly one
 * implementation and one test (versioning §10.6: "a timing-safe compare and a redacted log
 * line are the whole discipline").
 */
object PromotionServerKeys {
    /**
     * Constant-time comparison of the [presented] credential against the [configured] one.
     *
     * `MessageDigest.isEqual` is the comparison — **never `==` or `String.equals`**, which
     * return on the first differing byte and leak the shared prefix length to a caller timing
     * its own requests. It is documented as taking a time independent of the contents of the
     * arrays it compares. `PromotionServerKeysTest` asserts the source of this file, so the
     * property cannot be refactored away by someone who reads only the signature.
     *
     * **Fail closed**: a null or blank [configured] key returns false for every input,
     * including a null or blank presented one. A receiver that configured no key refuses
     * everything, and it refuses with the same answer it gives a wrong key, so a caller cannot
     * distinguish "promotion is disabled here" from "your key is wrong".
     */
    fun matches(
        configured: String?,
        presented: String?,
    ): Boolean {
        if (configured.isNullOrBlank() || presented.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            configured.toByteArray(StandardCharsets.UTF_8),
            presented.toByteArray(StandardCharsets.UTF_8),
        )
    }

    /**
     * A short, non-reversible fingerprint of a key, for the `source_env` / `promoted_by` record
     * a receiver keeps of a push (versioning §10.6, R7). It identifies WHICH key was used
     * across a rotation without carrying the key: SHA-256, hex, truncated.
     *
     * Truncation is deliberate — the fingerprint's job is to distinguish two keys in an audit
     * trail, not to authenticate anything, and a full digest of a bearer secret is a better
     * offline-attack target than a 48-bit prefix of one.
     */
    fun fingerprint(key: String?): String {
        if (key.isNullOrBlank()) return "none"
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(StandardCharsets.UTF_8))
        return "sha256:" + digest.take(FINGERPRINT_BYTES).joinToString("") { "%02x".format(it) }
    }

    /** 6 bytes → 12 hex characters. Enough to tell two keys apart in a log; useless as a target. */
    private const val FINGERPRINT_BYTES = 6
}

/**
 * Matches the promotion route, for the CSRF exemption (auth.md §8.4/§8.6).
 *
 * The exemption follows the CREDENTIAL, not the path — and on this route the credential is
 * [PromotionServerKeyFilter.HEADER], a request header no cookie-bearing browser context can
 * forge, with no cookie accepted at all. The route is matched rather than the header because
 * [PromotionServerKeyFilter] refuses the whole route without a valid key: there is no
 * cookie-authenticated request here for CSRF to protect.
 */
class PromotionRouteMatcher : org.springframework.security.web.util.matcher.RequestMatcher {
    override fun matches(request: jakarta.servlet.http.HttpServletRequest): Boolean =
        request.requestURI.startsWith(PromotionServerKeyFilter.PROMOTION_PREFIX)
}
