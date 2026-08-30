package co.datapipelines.auth

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Bean wiring for the local password accounts slice (auth.md §5A) — a sibling of
 * [AuthConfiguration] rather than more methods on it, so each configuration class
 * stays within the house size limits. Every bean exists unconditionally and is
 * inert when `datapipelines.auth.local.enabled` is false: the login controller
 * answers 404, the seeder returns before touching anything, and the password
 * service is only reachable behind admin/session checks — the bean graph stays
 * identical in every deployment.
 */
@Configuration
class LocalAuthConfiguration {
    @Bean
    fun localAuthService(
        userRepository: UserRepository,
        secretHasher: SecretHasher,
        authProperties: AuthProperties,
        auditLogger: AuditLogger,
    ): LocalAuthService = LocalAuthService(userRepository, secretHasher, authProperties, auditLogger)

    @Bean
    fun localAdminSeeder(
        userRepository: UserRepository,
        userService: UserService,
        secretHasher: SecretHasher,
        authProperties: AuthProperties,
        auditLogger: AuditLogger,
    ): LocalAdminSeeder = LocalAdminSeeder(userRepository, userService, secretHasher, authProperties, auditLogger)

    @Bean
    fun localPasswordService(
        userRepository: UserRepository,
        userService: UserService,
        secretHasher: SecretHasher,
        authCache: AuthCache,
        auditLogger: AuditLogger,
        authProperties: AuthProperties,
    ): LocalPasswordService =
        LocalPasswordService(userRepository, userService, secretHasher, authCache, auditLogger, authProperties)
}
