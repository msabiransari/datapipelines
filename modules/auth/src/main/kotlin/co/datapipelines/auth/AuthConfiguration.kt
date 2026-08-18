package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * Explicit bean wiring for the auth module (015, module-structure.md §8.4): every
 * collaborator below used to enter the context by component scan; now each is a
 * declared `@Bean`. Method names match the old scanned bean names
 * (`userService`, `apiKeyRepository`, ...) so any by-name reference keeps resolving.
 *
 * ## The three auth filters are NOT beans (spec D4)
 * [AuthFilters] groups three PLAIN objects constructed inline in [authFilters].
 * A `Filter` that is never a top-level bean is never auto-registered with the
 * servlet container, so the AU-API-10 double-execution hazard (two Argon2
 * verifications per API key, two rate-limit increments per login, container-level
 * copies running on `permitAll` paths) is structurally impossible — that is why
 * `AuthFilterRegistrationConfig` and its three disabled `FilterRegistrationBean`
 * workarounds were deleted rather than ported. [SecurityConfig] keeps taking
 * [AuthFilters] and its `addFilterBefore` calls are untouched; the exact-once
 * behavior is proven at the wire by `AuthHttpBoundaryTest`.
 */
@Configuration
class AuthConfiguration {
    @Bean
    fun userRepository(jdbc: NamedParameterJdbcTemplate): UserRepository = UserRepository(jdbc)

    @Bean
    fun apiKeyRepository(jdbc: NamedParameterJdbcTemplate): ApiKeyRepository = ApiKeyRepository(jdbc)

    @Bean
    fun authCache(authProperties: AuthProperties): AuthCache = AuthCache(authProperties)

    @Bean
    fun auditLogger(
        jdbc: NamedParameterJdbcTemplate,
        objectMapper: ObjectMapper,
    ): AuditLogger = AuditLogger(jdbc, objectMapper)

    @Bean
    fun jwtService(
        jwtProperties: JwtProperties,
        authProperties: AuthProperties,
    ): JwtService = JwtService(jwtProperties, authProperties)

    @Bean
    fun userService(
        userRepository: UserRepository,
        authCache: AuthCache,
        authProperties: AuthProperties,
        auditLogger: AuditLogger,
    ): UserService = UserService(userRepository, authCache, authProperties, auditLogger)

    @Bean
    fun apiKeyService(
        apiKeyRepository: ApiKeyRepository,
        userService: UserService,
        authCache: AuthCache,
        auditLogger: AuditLogger,
        secretHasher: SecretHasher,
        authProperties: AuthProperties,
    ): ApiKeyService = ApiKeyService(apiKeyRepository, userService, authCache, auditLogger, secretHasher, authProperties)

    @Bean
    fun authErrorWriter(objectMapper: ObjectMapper): AuthErrorWriter = AuthErrorWriter(objectMapper)

    @Bean
    fun authEntryPoint(authErrorWriter: AuthErrorWriter): AuthEntryPoint = AuthEntryPoint(authErrorWriter)

    @Bean
    fun authAccessDeniedHandler(authErrorWriter: AuthErrorWriter): AuthAccessDeniedHandler = AuthAccessDeniedHandler(authErrorWriter)

    @Bean
    fun auditLogoutHandler(auditLogger: AuditLogger): AuditLogoutHandler = AuditLogoutHandler(auditLogger)

    @Bean
    fun oidcSuccessHandler(
        userService: UserService,
        jwtService: JwtService,
        auditLogger: AuditLogger,
        authProperties: AuthProperties,
    ): OidcSuccessHandler = OidcSuccessHandler(userService, jwtService, auditLogger, authProperties)

    @Bean
    fun scopeInterceptor(
        authErrorWriter: AuthErrorWriter,
        auditLogger: AuditLogger,
    ): ScopeInterceptor = ScopeInterceptor(authErrorWriter, auditLogger)

    @Bean
    fun cookieOAuth2AuthorizationRequestRepository(
        jwtService: JwtService,
        objectMapper: ObjectMapper,
    ): CookieOAuth2AuthorizationRequestRepository = CookieOAuth2AuthorizationRequestRepository(jwtService, objectMapper)

    /**
     * The three servlet filters the auth chain installs (auth.md §8.2), built as
     * plain objects — deliberately NOT exposed as individual beans (see class KDoc).
     */
    @Bean
    fun authFilters(
        apiKeyService: ApiKeyService,
        apiKeyRepository: ApiKeyRepository,
        auditLogger: AuditLogger,
        jwtService: JwtService,
        userService: UserService,
        authProperties: AuthProperties,
        authErrorWriter: AuthErrorWriter,
    ): AuthFilters =
        AuthFilters(
            apiKey = ApiKeyFilter(apiKeyService, apiKeyRepository, auditLogger),
            jwt = JwtAuthenticationFilter(jwtService, userService),
            loginRateLimit = LoginRateLimitFilter(authProperties, authErrorWriter),
        )
}
