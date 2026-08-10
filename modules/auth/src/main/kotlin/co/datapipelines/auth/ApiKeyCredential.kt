package co.datapipelines.auth

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.web.util.matcher.RequestMatcher

/**
 * How an API key is carried on the wire, and the shape it must have before any
 * cache or database is touched (auth.md §7.1, §8.4, §8.5).
 *
 * ## Shape gate (AUTH-SEC-4 / AUTH-SEC-14)
 * A `dpk_` key is `dpk_` + 12 base32 chars + `.` + 48 base32 chars = 65 characters.
 * Anything longer than [MAX_CREDENTIAL_LENGTH], or whose key id does not match
 * [KEY_ID_PATTERN], is rejected **before** the negative-lookup path — so a flood of
 * garbage credentials costs a regex, not a database round trip plus a cache entry.
 */
object ApiKeyCredential {
    const val HEADER = "DP-API-Key"
    const val BEARER_PREFIX = "Bearer "
    const val KEY_PREFIX = "dpk_"
    const val MCP_PATH = "/mcp"

    /** `dpk_` + 12 chars of the RFC 4648 base32 alphabet (auth.md §7.1). */
    val KEY_ID_PATTERN = Regex("^dpk_[A-Z2-7]{12}$")

    /**
     * Generous cap over the 65-character key shape. Length is checked first so a
     * multi-megabyte header value is never regex-matched or hashed.
     */
    const val MAX_CREDENTIAL_LENGTH = 80

    /** True when [value] could be a well-formed `dpk_<id>.<secret>` credential. */
    fun hasValidShape(value: String): Boolean {
        if (value.length > MAX_CREDENTIAL_LENGTH || !value.startsWith(KEY_PREFIX)) return false
        val dot = value.indexOf('.')
        if (dot < 0 || dot == value.length - 1) return false
        return KEY_ID_PATTERN.matches(value.substring(0, dot))
    }

    /**
     * The credential carried by [request], or `null`. `DP-API-Key` works on every
     * surface; `Authorization: Bearer dpk_…` is accepted **only on `/mcp`** (§8.5).
     */
    fun extract(request: HttpServletRequest): String? {
        request
            .getHeader(HEADER)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        if (request.requestURI != MCP_PATH) return null
        val authorization = request.getHeader("Authorization")?.trim().orEmpty()
        if (!authorization.startsWith(BEARER_PREFIX)) return null
        return authorization.substring(BEARER_PREFIX.length).trim().takeIf { it.startsWith(KEY_PREFIX) }
    }
}

/**
 * The CSRF exemption matcher (auth.md §8.4 v2.4): **exemption follows the credential,
 * never the path.**
 *
 * A request is exempt only when its credential cannot be supplied ambiently by a
 * browser:
 * - it carries the `DP-API-Key` header — a header a hostile cross-origin page cannot
 *   set without a CORS preflight this deployment does not grant; or
 * - it targets `/mcp`, where session cookies are refused outright
 *   ([JwtAuthenticationFilter] does not run there, §8.5) and the equivalent
 *   `Authorization: Bearer dpk_` carrier lives. With no cookie able to authenticate,
 *   the premise of CSRF — ambient cookie authority — cannot hold, which is exactly
 *   why §8.5 states "`/mcp` is CSRF-exempt (no cookie auth)". Matching the path here
 *   also keeps an *unauthenticated* `POST /mcp` answering `401 auth.api_key.missing`
 *   rather than a misleading 403 CSRF error.
 *
 * Everything else — including cookie-authenticated `POST` under the `/api/v1` prefix — requires the
 * `dp_csrf` double-submit token. `SameSite=Strict` on `dp_session` is
 * defence-in-depth only: it does not stop a same-site subdomain attacker.
 */
class ApiKeyCredentialMatcher : RequestMatcher {
    override fun matches(request: HttpServletRequest): Boolean =
        !request.getHeader(ApiKeyCredential.HEADER).isNullOrBlank() ||
            request.requestURI == ApiKeyCredential.MCP_PATH
}
