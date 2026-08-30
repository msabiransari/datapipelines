package co.datapipelines.auth

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton

/**
 * Seeds the FIRST ADMIN's initial local credential from config (auth.md §5A.2) —
 * and only ever that one account. Passwords are not a config medium: config is
 * plaintext in `docker compose config`, `docker inspect`, image layers and pasted
 * issue reports, and offers no rotation, no revocation and no self-service. Every
 * other account's credential lives hashed in the database, created by an admin.
 *
 * ## Create-if-absent, and idempotent
 * Seeding fires only when the `bootstrap-admin-email` row does not exist yet:
 * a restart never resets a changed password, and an account the admin logged into
 * via OIDC first is left untouched. The credential — hash form preferred, plaintext
 * accepted for zero-setup demos — is always seeded with `must_change_password =
 * TRUE` (owner-ratified): the app refuses to proceed past the change-password
 * screen until the seeded credential is replaced (§5A.4).
 *
 * ## The seeded credential cannot survive silently
 * Every startup where the bootstrap-admin account still has
 * `must_change_password = TRUE` logs a WARN — a deployment still running its
 * one-time credential is visible to the operator. The plaintext seed is never
 * logged, audited, or carried anywhere but the hash call.
 *
 * ## Why `SmartInitializingSingleton`
 * The same ordering contract as `BootstrapDatasourceStartup` (whose KDoc is the
 * authority): `afterSingletonsInstantiated()` runs after every singleton —
 * Flyway's initializer included — is built, and *inside* `refresh()`, before the
 * web server accepts traffic. The bean exists unconditionally and is inert when
 * `local.enabled` is false or no seed is configured, keeping the bean graph
 * identical in every deployment.
 */
class LocalAdminSeeder(
    private val userRepository: UserRepository,
    private val userService: UserService,
    private val secretHasher: SecretHasher,
    private val authProperties: AuthProperties,
    private val auditLogger: AuditLogger,
) : SmartInitializingSingleton {
    private val log = LoggerFactory.getLogger(LocalAdminSeeder::class.java)

    override fun afterSingletonsInstantiated() = seedIfConfigured()

    fun seedIfConfigured() {
        val local = authProperties.local
        if (!local.enabled) return
        val hash = local.bootstrapPasswordHash?.takeIf { it.isNotBlank() }
        val plaintext = local.bootstrapPassword?.takeIf { it.isNotBlank() }
        if (hash == null && plaintext == null) {
            // Local accounts without a seed: every account is admin-created (§5A.1).
            log.info("event=auth.local.seed_skipped reason=no_seed_configured")
            return
        }
        // ConfigValidator refuses this combination at startup (§7) with both keys
        // named; the check here keeps the seeder honest when driven outside it.
        val email = authProperties.bootstrapAdminEmail?.trim()?.lowercase()
        check(!email.isNullOrEmpty()) {
            "datapipelines.auth.bootstrap-admin-email is required to seed the local admin credential (§5A.2)"
        }

        val existing = userRepository.findByEmail(email)
        if (existing != null) {
            if (existing.mustChangePassword) {
                log.warn(
                    "event=auth.local.one_time_credential_pending email={} " +
                        "message=\"the bootstrap admin account is still running a one-time credential " +
                        "(seeded or admin-reset); it must be changed at that account's next login (auth.md §5A.4)\"",
                    email,
                )
            } else {
                log.info("event=auth.local.seed_skipped email={} reason=account_exists", email)
            }
            return
        }

        // The ONE creation path (§4.4): insert + bootstrap-admin grant + its audit,
        // with the 'bootstrap' provider placeholder the first OIDC login completes.
        val user = userService.provisionBootstrapActor()
        val encoded = hash ?: secretHasher.hash(checkNotNull(plaintext))
        userRepository.setPassword(user.id, encoded, mustChange = true)
        auditLogger.log(
            "auth.password.seeded",
            userId = user.id,
            details = mapOf("actor" to "config", "email" to email, "form" to if (hash != null) "hash" else "plaintext"),
        )
        log.warn(
            "event=auth.local.seeded email={} " +
                "message=\"local admin credential seeded from config; it MUST be changed at first login (auth.md §5A.4)\"",
            email,
        )
    }
}
