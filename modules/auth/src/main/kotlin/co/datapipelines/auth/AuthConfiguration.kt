package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
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
@EnableConfigurationProperties(WorkspacesProperties::class)
class AuthConfiguration {
    @Bean
    fun userRepository(jdbc: NamedParameterJdbcTemplate): UserRepository = UserRepository(jdbc)

    @Bean
    fun apiKeyRepository(jdbc: NamedParameterJdbcTemplate): ApiKeyRepository = ApiKeyRepository(jdbc)

    @Bean
    fun workspaceRepository(jdbc: NamedParameterJdbcTemplate): WorkspaceRepository = WorkspaceRepository(jdbc)

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

    /**
     * [lastUsedWorkspaceStore] is an `ObjectProvider`: the Redis implementation lives in
     * `web` (module-structure §3.1 rule 3), so auth-only contexts (the module's own test
     * slices) legitimately have none — last-used then degrades to first-membership, by
     * design (see the port's KDoc).
     *
     * [personalWorkspaceSeeder] (D9) is optional for the same layering reason — its
     * implementation drives `web`'s import services — but for a different operational one:
     * absent means "this deployment configured no examples file", not "degrade quietly".
     *
     * [contentCheck] (the `workspace.in_use` port) is optional the same way: `web` wires the
     * real one over the pipeline/template/datasource repositories; auth-only test slices
     * default to "no content", which is true of every workspace they create.
     */
    @Suppress("LongParameterList") // the wiring bean — every parameter is an @Bean reference (019 precedent)
    @Bean
    fun workspaceService(
        workspaceRepository: WorkspaceRepository,
        userRepository: UserRepository,
        authCache: AuthCache,
        workspacesProperties: WorkspacesProperties,
        lastUsedWorkspaceStore: ObjectProvider<LastUsedWorkspaceStore>,
        auditLogger: AuditLogger,
        personalWorkspaceSeeder: ObjectProvider<PersonalWorkspaceSeeder>,
        contentCheck: ObjectProvider<WorkspaceContentCheck>,
    ): WorkspaceService =
        WorkspaceService(
            workspaceRepository,
            userRepository,
            authCache,
            workspacesProperties,
            lastUsedWorkspaceStore.getIfAvailable(),
            auditLogger,
            personalWorkspaceSeeder.getIfAvailable(),
            contentCheck.getIfAvailable() ?: WorkspaceContentCheck.NONE,
        )

    @Bean
    fun apiKeyService(
        apiKeyRepository: ApiKeyRepository,
        userService: UserService,
        authCache: AuthCache,
        auditLogger: AuditLogger,
        secretHasher: SecretHasher,
        authProperties: AuthProperties,
        workspaceService: WorkspaceService,
    ): ApiKeyService = ApiKeyService(apiKeyRepository, userService, authCache, auditLogger, secretHasher, authProperties, workspaceService)

    @Bean
    fun authErrorWriter(objectMapper: ObjectMapper): AuthErrorWriter = AuthErrorWriter(objectMapper)

    /**
     * The single client-address resolver (R8/T46): constructed here so an invalid
     * `datapipelines.auth.trusted-proxies` entry — anything that does not parse as a CIDR —
     * refuses STARTUP at bean construction, whatever binding form carried it. Empty default:
     * the resolver then returns `remoteAddr` and `X-Forwarded-For` is ignored everywhere.
     */
    @Bean
    fun clientAddressResolver(authProperties: AuthProperties): ClientAddressResolver = ClientAddressResolver(authProperties.trustedProxies)

    @Bean
    fun authEntryPoint(authErrorWriter: AuthErrorWriter): AuthEntryPoint = AuthEntryPoint(authErrorWriter)

    @Bean
    fun authAccessDeniedHandler(
        authErrorWriter: AuthErrorWriter,
        clientAddressResolver: ClientAddressResolver,
    ): AuthAccessDeniedHandler = AuthAccessDeniedHandler(authErrorWriter, clientAddressResolver)

    @Bean
    fun auditLogoutHandler(
        auditLogger: AuditLogger,
        clientAddressResolver: ClientAddressResolver,
    ): AuditLogoutHandler = AuditLogoutHandler(auditLogger, clientAddressResolver)

    @Bean
    fun oidcSuccessHandler(
        userService: UserService,
        jwtService: JwtService,
        auditLogger: AuditLogger,
        authProperties: AuthProperties,
        workspaceService: WorkspaceService,
        clientAddressResolver: ClientAddressResolver,
    ): OidcSuccessHandler =
        OidcSuccessHandler(userService, jwtService, auditLogger, authProperties, workspaceService, clientAddressResolver)

    @Bean
    fun scopeInterceptor(
        authErrorWriter: AuthErrorWriter,
        auditLogger: AuditLogger,
    ): ScopeInterceptor = ScopeInterceptor(authErrorWriter, auditLogger)

    @Bean
    fun forcedPasswordChangeInterceptor(
        userService: UserService,
        authErrorWriter: AuthErrorWriter,
    ): ForcedPasswordChangeInterceptor = ForcedPasswordChangeInterceptor(userService, authErrorWriter)

    @Bean
    fun cookieOAuth2AuthorizationRequestRepository(
        jwtService: JwtService,
        objectMapper: ObjectMapper,
        authProperties: AuthProperties,
    ): CookieOAuth2AuthorizationRequestRepository =
        CookieOAuth2AuthorizationRequestRepository(
            jwtService,
            objectMapper,
            secureCookies = authProperties.secureCookies(),
        )

    /**
     * The servlet filters the auth chain installs (auth.md §8.2), built as plain
     * objects — deliberately NOT exposed as individual beans (see class KDoc).
     */
    @Suppress("LongParameterList")
    @Bean
    fun authFilters(
        apiKeyService: ApiKeyService,
        apiKeyRepository: ApiKeyRepository,
        auditLogger: AuditLogger,
        jwtService: JwtService,
        userService: UserService,
        authProperties: AuthProperties,
        authErrorWriter: AuthErrorWriter,
        workspaceService: WorkspaceService,
        lastUsedWorkspaceStore: ObjectProvider<LastUsedWorkspaceStore>,
        clientAddressResolver: ClientAddressResolver,
    ): AuthFilters =
        AuthFilters(
            apiKey = ApiKeyFilter(apiKeyService, apiKeyRepository, auditLogger, clientAddressResolver),
            jwt = JwtAuthenticationFilter(jwtService, userService, clientAddressResolver),
            loginRateLimit = LoginRateLimitFilter(clientAddressResolver, authProperties, authErrorWriter),
            workspaceResolution =
                WorkspaceResolutionFilter(
                    workspaceService,
                    lastUsedWorkspaceStore.getIfAvailable(),
                    authErrorWriter,
                    auditLogger,
                    clientAddressResolver,
                ),
        )
}
