package co.datapipelines.web.config

import co.datapipelines.auth.ApiKeyCredential
import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.SecurityConfig
import co.datapipelines.web.CorrelationIdFilter
import co.datapipelines.web.api.CorrelationId
import co.datapipelines.web.ratelimit.RateLimitHeaders
import org.slf4j.LoggerFactory
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter

/**
 * CORS for the API and the SSE stream (rest-api.md §13), plus the registration of the correlation
 * filter that must run ahead of it.
 *
 * ## Why a `CorsFilter`, not `HttpSecurity.cors()`
 * `auth`'s [SecurityConfig] owns the one `SecurityFilterChain` bean and does not call `.cors()`,
 * so a `CorsConfigurationSource` bean alone would never be consulted. A container filter
 * registered **ahead** of the Spring Security chain both applies the headers and short-circuits
 * the preflight `OPTIONS` — which matters because a preflight carries no credentials and would
 * otherwise be 401'd by `.anyRequest().authenticated()` before any CORS header was written,
 * surfacing in the browser as an unexplained network failure rather than an auth error.
 *
 * ## Allowed origin
 * configuration.md defines **no** CORS key, and §13.1's default is same-origin. The deployment's
 * exact external origin is already configured — `datapipelines.auth.base-url` (§3.4), which exists
 * to be the one trusted origin — so that is the allowed origin, and when it is unset nothing
 * cross-origin is allowed. `allowCredentials` is `true` for the cookie UI, which makes a wildcard
 * origin illegal anyway: no configuration of this filter can open the API to an arbitrary site.
 *
 * ## Exposed headers
 * A browser can only read a response header that is exposed, so the correlation id (§3.4) and the
 * rate-limit headers (§12.2) are listed — they are useless to a browser client otherwise.
 */
@Configuration
class WebCorsConfiguration(
    private val authProperties: AuthProperties,
) {
    private val log = LoggerFactory.getLogger(WebCorsConfiguration::class.java)

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val origin =
            authProperties.baseUrl
                ?.trim()
                ?.trimEnd('/')
                ?.takeIf { it.isNotEmpty() }
        if (origin == null) {
            log.info("CORS: datapipelines.auth.base-url unset — no cross-origin request is allowed (same-origin only).")
        }
        val config =
            CorsConfiguration().apply {
                allowedOrigins = listOfNotNull(origin)
                allowedMethods = ALLOWED_METHODS
                allowedHeaders = allowedHeaders()
                exposedHeaders = exposedHeaders()
                allowCredentials = true
                maxAge = PREFLIGHT_MAX_AGE_SECONDS
            }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration(API_PATTERN, config)
            registerCorsConfiguration(ApiKeyCredential.MCP_PATH, config)
        }
    }

    /** Just after [CorrelationIdFilter] and well before Spring Security. */
    @Bean
    fun corsFilterRegistration(source: CorsConfigurationSource): FilterRegistrationBean<CorsFilter> =
        FilterRegistrationBean(CorsFilter(source)).apply {
            order = CorrelationIdFilter.ORDER + 1
            isAsyncSupported = true
        }

    /** The first thing that touches a request, so every response carries a quotable id. */
    @Bean
    fun correlationIdFilterRegistration(filter: CorrelationIdFilter): FilterRegistrationBean<CorrelationIdFilter> =
        FilterRegistrationBean(filter).apply {
            order = CorrelationIdFilter.ORDER
            isAsyncSupported = true
        }

    /** rest-api §13.1, verbatim — every §3.6 `DP-` header plus the standards it lists. */
    private fun allowedHeaders(): List<String> =
        listOf(
            HttpHeaders.AUTHORIZATION,
            ApiKeyCredential.HEADER,
            CorrelationId.HEADER,
            SecurityConfig.CSRF_HEADER,
            WebHeaders.RESULT_TTL,
            HttpHeaders.CONTENT_TYPE,
            WebHeaders.IDEMPOTENCY_KEY,
            HttpHeaders.ACCEPT,
        )

    private fun exposedHeaders(): List<String> =
        listOf(
            CorrelationId.HEADER,
            RateLimitHeaders.LIMIT,
            RateLimitHeaders.REMAINING,
            RateLimitHeaders.RESET,
            HttpHeaders.RETRY_AFTER,
        )

    private companion object {
        const val API_PATTERN = "/api/v1/**"
        const val PREFLIGHT_MAX_AGE_SECONDS = 3600L
        val ALLOWED_METHODS = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
    }
}
