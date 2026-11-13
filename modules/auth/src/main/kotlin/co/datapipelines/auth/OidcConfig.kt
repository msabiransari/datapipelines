package co.datapipelines.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.ClientRegistrations
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter
import org.springframework.security.oauth2.jwt.JwtDecoderFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestTemplate
import java.time.Duration

/**
 * Builds the login `ClientRegistrationRepository` from the generic provider list
 * (`datapipelines.auth.oidc.providers`, auth.md §5.1/§5.2). Provider-agnostic, and two
 * protocols (#655):
 *
 * - **`oidc`** (the default): the entry supplies `name`, `client-id`, `client-secret`,
 *   `issuer-uri` and an optional `display-name`. The `issuer-uri` triggers OIDC discovery
 *   at startup via [ClientRegistrations.fromIssuerLocation] — fetching
 *   `.well-known/openid-configuration` to auto-populate the authorization, token, userinfo
 *   and JWKS endpoints. (The §5.2 sketch calls `.issuerUri()` on the bare builder, which
 *   stores the issuer but does NOT discover endpoints; `fromIssuerLocation` is the mechanism
 *   auth.md §5.1 actually describes, so login works against any compliant provider.)
 * - **`oauth2`**: a provider that is OAuth 2.0 without OpenID Connect (GitHub) has no
 *   discovery document and no ID token, so its endpoints, scopes and subject attribute are
 *   configured explicitly and NOTHING is fetched at startup. Without the `openid` scope,
 *   Spring routes the login to the plain user-info service — [OAuth2IdentityUserService].
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
                if (!provider.isEnabled) {
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
                // InMemoryClientRegistrationRepository refuses an EMPTY list, and the
                // interface is functional — a null-answer repository is the honest empty.
                return ClientRegistrationRepository { null }
            }
            error(
                "No OIDC providers configured. Set datapipelines.auth.oidc.providers in config, " +
                    "or enable local accounts (datapipelines.auth.local.enabled).",
            )
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

    /**
     * The no-store authorized-client repository (#655). Spring Boot otherwise registers an
     * `AuthenticatedPrincipalOAuth2AuthorizedClientRepository` over an in-memory client
     * service, so every login would leave its provider ACCESS TOKEN in this JVM's heap, keyed
     * by user, for the life of the process — a GitHub OAuth-app token does not expire. Nothing
     * here calls a provider on the user's behalf after login: the token is needed only inside
     * the user-info request, and the session is the internal `dp_session` JWT.
     */
    @Bean
    fun authorizedClientRepository(): OAuth2AuthorizedClientRepository = NoStoreAuthorizedClientRepository

    /** The identity source of `protocol: oauth2` logins (#655); OIDC registrations never reach it. */
    @Bean
    fun oauth2IdentityUserService(authProperties: AuthProperties): OAuth2IdentityUserService = OAuth2IdentityUserService(authProperties)

    /** Code-for-token exchange for both protocols, retaining Spring's OAuth converters and errors. */
    @Bean
    fun providerTokenResponseClient(authProperties: AuthProperties): RestClientAuthorizationCodeTokenResponseClient {
        val client =
            RestClient
                .builder()
                .requestFactory(boundedRequestFactory(authProperties))
                .messageConverters {
                    it.clear()
                    it.add(FormHttpMessageConverter())
                    it.add(OAuth2AccessTokenResponseHttpMessageConverter())
                }.defaultStatusHandler(OAuth2ErrorResponseErrorHandler())
                .build()
        return RestClientAuthorizationCodeTokenResponseClient().apply { setRestClient(client) }
    }

    /**
     * OIDC user-info uses the same bound; the ID-token decoder runs through
     * [providerIdTokenDecoderFactory] (#686), whose JWKS fetch carries it too.
     */
    @Bean
    fun providerOidcUserService(authProperties: AuthProperties): OidcUserService {
        val rest = boundedRestTemplate(authProperties)
        rest.errorHandler = OAuth2ErrorResponseErrorHandler()
        val delegate = DefaultOAuth2UserService()
        delegate.setRestOperations(rest)
        return OidcUserService().apply { setOauth2UserService(delegate) }
    }

    /**
     * The ID-token decoder's JWKS fetch is bounded by the same key (#686): Spring's login
     * config resolves the `JwtDecoderFactory<ClientRegistration>` bean from this context and
     * hands it to its OIDC authentication provider in place of a plain
     * `OidcIdTokenDecoderFactory`, whose JWK client keeps Spring's unbounded default.
     * [BoundedOidcIdTokenDecoderFactory] mirrors that factory exactly — the same algorithm
     * resolution, the same validators, the same claim-type conversion, one decoder per
     * registration — and changes only the HTTP client the JWKS read runs on.
     */
    @Bean
    fun providerIdTokenDecoderFactory(authProperties: AuthProperties): JwtDecoderFactory<ClientRegistration> =
        BoundedOidcIdTokenDecoderFactory(boundedRestTemplate(authProperties))

    companion object {
        /** The shared connect/read policy for login-thread provider reads (#682). */
        fun boundedRequestFactory(authProperties: AuthProperties): SimpleClientHttpRequestFactory {
            val seconds = authProperties.oidc.providerReadTimeoutSeconds
            require(seconds in 1..MAX_PROVIDER_TIMEOUT_SECONDS) {
                "datapipelines.auth.oidc.provider-read-timeout-seconds '$seconds' must be in 1..$MAX_PROVIDER_TIMEOUT_SECONDS (§3.4)"
            }
            val timeout = Duration.ofSeconds(seconds)
            return SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(timeout)
                setReadTimeout(timeout)
            }
        }

        /** Emails retain ordinary HTTP errors; user-info delegates install OAuth2 error handling. */
        fun boundedRestTemplate(authProperties: AuthProperties): RestTemplate = RestTemplate(boundedRequestFactory(authProperties))

        private const val MAX_PROVIDER_TIMEOUT_SECONDS = Int.MAX_VALUE / 1000L
    }

    private fun toRegistration(
        p: AuthProperties.Provider,
        baseUrl: String,
    ): ClientRegistration {
        require(p.name.isNotBlank()) { "Each OIDC provider requires a non-blank 'name' (auth.md §11.1)" }
        val builder =
            when (p.protocol) {
                AuthProperties.Provider.PROTOCOL_OIDC -> oidcBuilder(p)

                AuthProperties.Provider.PROTOCOL_OAUTH2 -> oauth2Builder(p)

                else -> throw IllegalArgumentException(
                    "Provider '${p.name}': protocol '${p.protocol}' is not one of " +
                        "${AuthProperties.Provider.PROTOCOL_OIDC}, ${AuthProperties.Provider.PROTOCOL_OAUTH2}",
                )
            }
        return builder
            .registrationId(p.name)
            .clientId(p.clientId)
            .clientSecret(p.clientSecret)
            .clientName(p.displayName ?: p.name)
            // ABSOLUTE, from datapipelines.auth.base-url — never the {baseUrl} template.
            .redirectUri("$baseUrl/login/oauth2/code/${p.name}")
            .build()
    }

    /** `oidc`: discovery from the issuer, the three standard scopes. */
    private fun oidcBuilder(p: AuthProperties.Provider): ClientRegistration.Builder {
        require(p.issuerUri.isNotBlank()) { "OIDC provider '${p.name}' requires an 'issuer-uri'" }
        return ClientRegistrations
            .fromIssuerLocation(p.issuerUri)
            .scope("openid", "profile", "email")
    }

    /**
     * `oauth2` (#655): every endpoint explicit, no HTTP at startup. `client_secret_basic` is the
     * client authentication Spring's own GitHub template (`CommonOAuth2Provider.GITHUB`) uses.
     * The scopes must not include `openid` — that would route the login to the OIDC user
     * service, which demands an ID token this protocol never returns.
     */
    private fun oauth2Builder(p: AuthProperties.Provider): ClientRegistration.Builder {
        val required =
            mapOf(
                "authorization-uri" to p.authorizationUri,
                "token-uri" to p.tokenUri,
                "user-info-uri" to p.userInfoUri,
                "user-name-attribute" to p.userNameAttribute,
            )
        required.forEach { (key, value) -> require(value.isNotBlank()) { "OAuth 2.0 provider '${p.name}' requires '$key'" } }
        require(p.scopes.isNotEmpty()) { "OAuth 2.0 provider '${p.name}' requires 'scopes'" }
        require(p.scopes.none { it.equals("openid", ignoreCase = true) }) {
            "OAuth 2.0 provider '${p.name}' must not request the 'openid' scope (that is protocol: oidc)"
        }
        return ClientRegistration
            .withRegistrationId(p.name)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationUri(p.authorizationUri)
            .tokenUri(p.tokenUri)
            .userInfoUri(p.userInfoUri)
            .userNameAttributeName(p.userNameAttribute)
            .scope(p.scopes)
    }
}

/**
 * Stores nothing and loads nothing ([OidcConfig.authorizedClientRepository]): Spring's login
 * filter saves the authorized client before invoking the success handler, and no code path
 * here ever loads one back.
 */
internal object NoStoreAuthorizedClientRepository : OAuth2AuthorizedClientRepository {
    override fun <T : OAuth2AuthorizedClient> loadAuthorizedClient(
        clientRegistrationId: String,
        principal: Authentication?,
        request: HttpServletRequest,
    ): T? = null

    override fun saveAuthorizedClient(
        authorizedClient: OAuth2AuthorizedClient,
        principal: Authentication?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) = Unit

    override fun removeAuthorizedClient(
        clientRegistrationId: String,
        principal: Authentication?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) = Unit
}
