package co.datapipelines.config

import java.net.URI
import java.net.URISyntaxException

/**
 * §7 / §3.4 (#655, #682) — provider entry shapes and the shared HTTP read timeout. Owns its file like
 * `MailRules`: the validator's companion is the registry, not the home, of the rule families.
 *
 * Provider-shape rules apply only to entries meant to be configured (a present `client-id`),
 * exactly like the companion's "at least one authentication method" rule: the stock entries
 * ship with empty client ids and must not refuse a local-accounts deployment.
 */
internal object AuthProviderRules {
    /** §7 / §3.4 — zero would disable the HTTP timeout, so only positive integers are valid. */
    fun checkProviderReadTimeout(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        snapshot.providerReadTimeoutSeconds?.let { raw ->
            val parsed = raw.trim().toLongOrNull()
            if (parsed == null || parsed < 1) {
                violations += "datapipelines.auth.oidc.provider-read-timeout-seconds '$raw' is not a positive integer (§3.4)."
            } else if (parsed > MAX_PROVIDER_TIMEOUT_SECONDS) {
                violations +=
                    "datapipelines.auth.oidc.provider-read-timeout-seconds '$raw' exceeds $MAX_PROVIDER_TIMEOUT_SECONDS seconds " +
                    "(§3.4): the HTTP client's signed 32-bit milliseconds must not overflow."
            }
        }
    }

    private const val MAX_PROVIDER_TIMEOUT_SECONDS = Int.MAX_VALUE / 1000L

    /** The two values `protocol` accepts (auth.md §5.1). */
    const val PROTOCOL_OIDC = "oidc"
    const val PROTOCOL_OAUTH2 = "oauth2"

    /** The only hosts a plain `http://` provider endpoint may name — the test stubs' loopback binds. */
    private val PLAIN_HTTP_HOSTS = setOf("localhost", "127.0.0.1")

    /**
     * Each configured entry against its protocol, every violation naming the provider AND the key:
     *
     * - `protocol` outside `oidc` / `oauth2` → refused (nothing else is judged for that entry);
     * - `oidc` → `issuer-uri` required; the `oauth2`-only keys must be unset, because a set one
     *   would be silently ignored by discovery;
     * - `oauth2` → `authorization-uri`, `token-uri`, `user-info-uri`, `user-name-attribute`
     *   required, `scopes` non-empty and without `openid`, `issuer-uri` EMPTY (a set one is the
     *   misleading GitHub entry #655 replaced: it reads as discovery that cannot happen), and
     *   every endpoint — `emails-uri` included when set — `https://`; plain `http://` only for
     *   `localhost` / `127.0.0.1`.
     */
    fun checkProviderProtocols(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        snapshot.oidcProviders.filter { it.clientId.isNotBlank() }.forEach { provider ->
            val label = provider.name.ifBlank { "<unnamed>" }
            when (provider.protocol) {
                PROTOCOL_OIDC -> {
                    violations += oidcProblems(provider).map { "OIDC provider '$label': $it" }
                }

                PROTOCOL_OAUTH2 -> {
                    violations += oauth2Problems(provider).map { "OAuth 2.0 provider '$label': $it" }
                }

                else -> {
                    violations +=
                        "Provider '$label': protocol '${provider.protocol}' is not one of $PROTOCOL_OIDC, $PROTOCOL_OAUTH2 " +
                        "(auth.md §5.1)."
                }
            }
        }
    }

    private fun oidcProblems(provider: OidcProviderSnapshot): List<String> =
        buildList {
            if (provider.issuerUri.isBlank()) add("issuer-uri is empty.")
            provider.oauth2OnlyKeysSet().forEach { key ->
                add("$key is set, but only protocol: $PROTOCOL_OAUTH2 reads it — discovery from issuer-uri decides the endpoints.")
            }
        }

    private fun oauth2Problems(provider: OidcProviderSnapshot): List<String> =
        buildList {
            val endpoints =
                listOf(
                    "authorization-uri" to provider.authorizationUri,
                    "token-uri" to provider.tokenUri,
                    "user-info-uri" to provider.userInfoUri,
                )
            endpoints.forEach { (key, value) ->
                if (value.isBlank()) add("$key is empty.") else uriProblem(value)?.let { add("$key '$value' $it") }
            }
            provider.emailsUri?.takeIf { it.isNotBlank() }?.let { value -> uriProblem(value)?.let { add("emails-uri '$value' $it") } }
            if (provider.userNameAttribute.isBlank()) add("user-name-attribute is empty.")
            if (provider.scopes.none { it.isNotBlank() }) add("scopes is empty.")
            if (provider.scopes.any { it.trim().equals("openid", ignoreCase = true) }) {
                add("scopes names 'openid' — that is protocol: $PROTOCOL_OIDC.")
            }
            if (provider.issuerUri.isNotBlank()) {
                add(
                    "issuer-uri is set ('${provider.issuerUri}'), but an OAuth 2.0 provider is not discovered — " +
                        "remove it and name the endpoints.",
                )
            }
        }

    /** Null when [value] is an acceptable outbound provider endpoint; otherwise the reason. */
    private fun uriProblem(value: String): String? {
        val uri =
            try {
                URI(value.trim())
            } catch (_: URISyntaxException) {
                return "is not a URI."
            }
        val host = uri.host?.lowercase() ?: return "has no host."
        return when (uri.scheme?.lowercase()) {
            "https" -> null
            "http" -> if (host in PLAIN_HTTP_HOSTS) null else "must be https:// (plain http:// only for localhost or 127.0.0.1)."
            else -> "must be https://."
        }
    }
}
