package co.datapipelines.auth

import jakarta.servlet.Filter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component

/**
 * The three servlet filters the auth chain installs (auth.md §8.2), grouped so
 * [SecurityConfig] takes one collaborator instead of three and the pair of places
 * that need them — the chain and the registration-disabling config below — cannot
 * drift apart.
 */
@Component
data class AuthFilters(
    val apiKey: ApiKeyFilter,
    val jwt: JwtAuthenticationFilter,
    val loginRateLimit: LoginRateLimitFilter,
)

/**
 * Keeps the auth filters OUT of the servlet container's own filter chain (AU-API-10).
 *
 * Spring Boot auto-registers every `Filter` **bean** with the servlet container. All
 * three are also placed explicitly in the security chain ([SecurityConfig]), so
 * without these registrations each one runs **twice** per request: two Argon2
 * verifications for one API key, two rate-limit increments for one login, and — worse
 * — the container-level copy runs on `permitAll` paths the security chain
 * deliberately does not authenticate.
 */
@Configuration
class AuthFilterRegistrationConfig(
    private val filters: AuthFilters,
) {
    @Bean
    fun apiKeyFilterRegistration(): FilterRegistrationBean<ApiKeyFilter> = disabled(filters.apiKey)

    @Bean
    fun jwtAuthenticationFilterRegistration(): FilterRegistrationBean<JwtAuthenticationFilter> = disabled(filters.jwt)

    @Bean
    fun loginRateLimitFilterRegistration(): FilterRegistrationBean<LoginRateLimitFilter> = disabled(filters.loginRateLimit)

    private fun <T : Filter> disabled(filter: T): FilterRegistrationBean<T> = FilterRegistrationBean(filter).apply { isEnabled = false }
}
