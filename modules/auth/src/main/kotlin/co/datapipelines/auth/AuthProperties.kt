package co.datapipelines.auth

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The internal-JWT signing secret, bound from `datapipelines.jwt.secret`
 * (Configuration §2 — a top-level required key, NOT under `datapipelines.auth`).
 * Kept separate from [AuthProperties] because its YAML home differs.
 */
@ConfigurationProperties(prefix = "datapipelines.jwt")
data class JwtProperties(
    val secret: String = "",
)

/**
 * All `datapipelines.auth.*` keys (Configuration §3.4, auth.md §11).
 *
 * `client-id` → `clientId` etc. is Spring's relaxed binding. `allowlist.domains`
 * binds a comma-separated string to `List<String>` (empty = open provisioning).
 */
@ConfigurationProperties(prefix = "datapipelines.auth")
data class AuthProperties(
    /**
     * `datapipelines.auth.base-url` (Configuration §3.4, auth.md §5.2): the
     * deployment's exact external origin, e.g. `https://dp.example.com` — scheme +
     * host [+ port], no trailing slash. OIDC redirect URIs are built **absolutely**
     * from it, never from request headers, so a hostile `Host`/`X-Forwarded-Host`
     * cannot choose the `redirect_uri` sent to the IdP. Startup fails when it is
     * unset while any OIDC provider is configured ([OidcConfig]).
     */
    val baseUrl: String? = null,
    val oidc: Oidc = Oidc(),
    val jwt: Jwt = Jwt(),
    val allowlist: Allowlist = Allowlist(),
    val apiKeys: ApiKeys = ApiKeys(),
    val rateLimit: RateLimit = RateLimit(),
    val local: Local = Local(),
    /** Configuration §3.4 / auth.md §4.4. `null` = no bootstrap admin. */
    val bootstrapAdminEmail: String? = null,
) {
    data class Oidc(
        val providers: List<Provider> = emptyList(),
    )

    data class Provider(
        val name: String = "",
        val clientId: String = "",
        val clientSecret: String = "",
        val issuerUri: String = "",
        val displayName: String? = null,
    )

    /**
     * There is deliberately **no** `algorithm` key (AU-API-7): Configuration §3.4
     * does not define one, and HS256 is pinned in [JwtService] code. A configurable
     * signing algorithm is a downgrade lever, not a feature.
     */
    data class Jwt(
        val ttlHours: Long = 8,
    )

    data class Allowlist(
        val domains: List<String> = emptyList(),
    )

    data class ApiKeys(
        val cacheTtlSeconds: Long = 60,
        val defaultScopes: List<String> = listOf("read"),
    )

    data class RateLimit(
        val loginPerMinute: Int = 10,
    )

    /**
     * Optional local username/password accounts (Configuration §3.4, auth.md §5A).
     * Disabled by default: an OIDC-only deployment behaves exactly as before.
     *
     * [bootstrapPasswordHash] / [bootstrapPassword] seed the FIRST ADMIN ONLY (the
     * `bootstrap-admin-email` user) — never ordinary users; passwords are not a
     * config medium, so every other credential lives hashed in the database. The
     * pre-computed Argon2id hash is the preferred form; the plaintext variant
     * exists for zero-setup demos and always forces a first-login change.
     */
    data class Local(
        val enabled: Boolean = false,
        val bootstrapPasswordHash: String? = null,
        val bootstrapPassword: String? = null,
        val lockout: Lockout = Lockout(),
    )

    /**
     * Per-account lockout (auth.md §5A.3) — distinct from the per-IP/route
     * [RateLimit], which cannot stop a slow spray against one account.
     */
    data class Lockout(
        val maxFailures: Int = 5,
        val durationMinutes: Long = 15,
    )

    /**
     * Email domain allowlist check (auth.md §4.3). Empty allowlist = open. The
     * comparison is case-insensitive on the domain part after the last `@`.
     */
    fun isDomainAllowed(email: String): Boolean {
        val domains = allowlist.domains.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (domains.isEmpty()) return true
        val domain = email.substringAfterLast('@', missingDelimiterValue = "").lowercase()
        return domain.isNotEmpty() && domain in domains
    }

    /**
     * Whether the cookies this module mints (`dp_session`, `dp_oauth2_authz`, `dp_csrf`)
     * carry the `Secure` flag (T33, auth.md §5.4/§8.4): keyed off [baseUrl]'s scheme. An
     * explicit `http://` base-url (local development) turns the flag off so login works
     * over plain HTTP; everything else — `https://`, or no base-url at all (no OIDC
     * configured, e.g. module test slices) — stays `Secure`. Fail-secure: production
     * MUST run https (auth.md §8.4 note), and the wrong default would silently drop
     * sessions there, while the wrong non-default only inconveniences a dev who can see
     * the base-url they set.
     */
    fun secureCookies(): Boolean = baseUrl?.startsWith("http://") != true
}
