package co.datapipelines.auth

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.ClientRegistrations
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver

/**
 * Builds the OIDC `ClientRegistrationRepository` from the generic provider list
 * (`datapipelines.auth.oidc.providers`, auth.md §5.1/§5.2). Provider-agnostic: each
 * entry supplies only `name`, `client-id`, `client-secret`, `issuer-uri` and an
 * optional `display-name`.
 *
 * The `issuer-uri` triggers OIDC discovery at startup via
 * [ClientRegistrations.fromIssuerLocation] — fetching `.well-known/openid-configuration`
 * to auto-populate the authorization, token, userinfo and JWKS endpoints. (The §5.2
 * sketch calls `.issuerUri()` on the bare builder, which stores the issuer but does
 * NOT discover endpoints; `fromIssuerLocation` is the mechanism auth.md §5.1
 * actually describes, so login works against any compliant provider.)
 */
@Configuration
class OidcConfig {
    private val log = LoggerFactory.getLogger(OidcConfig::class.java)

    @Bean
    fun clientRegistrationRepository(authProperties: AuthProperties): ClientRegistrationRepository {
        // A provider entry with a blank client-id is IGNORED with a WARN, not an
        // error (configuration.md §7): the stock application.yml ships a `google`
        // entry whose client-id defaults to empty, and a local-accounts deployment
        // must start with zero OIDC providers. A typo'd env var name therefore
        // degrades one provider to a log line — visible — while "no authentication
        // method at all" remains a ConfigValidator startup refusal.
        val providers =
            authProperties.oidc.providers.filter { provider ->
                if (provider.clientId.isBlank()) {
                    log.warn(
                        "OIDC provider '{}' ignored: client-id is empty (set its client-id or remove the entry)",
                        provider.name.ifBlank { "<unnamed>" },
                    )
                    false
                } else {
                    true
                }
            }
        if (providers.isEmpty()) {
            if (authProperties.local.enabled) {
                log.info("No OIDC providers configured; local password accounts are enabled (auth.md §5A)")
                return InMemoryClientRegistrationRepository(emptyList())
            }
            error("No OIDC providers configured. Set datapipelines.auth.oidc.providers in config, or enable local accounts (datapipelines.auth.local.enabled).")
        }
        val baseUrl = requireBaseUrl(authProperties)
        return InMemoryClientRegistrationRepository(providers.map { toRegistration(it, baseUrl) })
    }

    /**
     * The authorization-request resolver, with **PKCE** enabled (RFC 7636,
     * AUTH-SEC-8): `code_challenge`/`code_challenge_method` go on the authorization
     * redirect and the `code_verifier` is carried in the request's attributes — which
     * is why [CookieOAuth2AuthorizationRequestRepository] round-trips `attributes`.
     *
     * PKCE binds the authorization code to this exact login attempt, so a code
     * intercepted at the redirect (referrer leak, hostile browser extension, a
     * mis-registered redirect URI at the IdP) cannot be exchanged by anyone else.
     */
    @Bean
    fun authorizationRequestResolver(clientRegistrationRepository: ClientRegistrationRepository): OAuth2AuthorizationRequestResolver =
        DefaultOAuth2AuthorizationRequestResolver(
            clientRegistrationRepository,
            OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI,
        ).apply { setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce()) }

    /**
     * `datapipelines.auth.base-url` is mandatory once any provider is configured
     * (auth.md §5.2, Configuration §3.4). Failing here is the point: without it the
     * only alternative is the request-derived `{baseUrl}` placeholder, which lets a
     * hostile `Host` / `X-Forwarded-Host` header pick the `redirect_uri` sent to the
     * IdP — a control this deployment does not own.
     */
    private fun requireBaseUrl(authProperties: AuthProperties): String {
        val configured = authProperties.baseUrl?.trim().orEmpty()
        check(configured.isNotEmpty()) {
            "datapipelines.auth.base-url must be set when OIDC providers are configured " +
                "(auth.md §5.2 / configuration.md §3.4): OIDC redirect URIs are built absolutely from it, " +
                "never from request headers."
        }
        return configured.trimEnd('/')
    }

    private fun toRegistration(
        p: AuthProperties.Provider,
        baseUrl: String,
    ): ClientRegistration {
        require(p.name.isNotBlank()) { "Each OIDC provider requires a non-blank 'name' (auth.md §11.1)" }
        require(p.issuerUri.isNotBlank()) { "OIDC provider '${p.name}' requires an 'issuer-uri'" }
        return ClientRegistrations
            .fromIssuerLocation(p.issuerUri)
            .registrationId(p.name)
            .clientId(p.clientId)
            .clientSecret(p.clientSecret)
            .clientName(p.displayName ?: p.name)
            .scope("openid", "profile", "email")
            // ABSOLUTE, from datapipelines.auth.base-url — never the {baseUrl} template.
            .redirectUri("$baseUrl/login/oauth2/code/${p.name}")
            .build()
    }
}
