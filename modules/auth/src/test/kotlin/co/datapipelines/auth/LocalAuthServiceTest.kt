package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

/**
 * The local-login security contract (auth.md §5A) against a real Postgres: the
 * enumeration guard (same outcome, same audit, SAME VERIFICATION COST for unknown
 * email vs wrong password), the per-account lockout (and that a locked account does
 * no Argon2 work), the `is_active` re-check, and the OIDC-only account that can
 * never log in locally.
 *
 * The [CountingHasher] is what makes the timing guarantee assertable: the no-row
 * path must burn exactly one Argon2 verification against the dummy hash, or
 * response timing enumerates valid emails (§5A.5).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalAuthServiceTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var users: UserRepository
    private lateinit var audit: AuditLogger
    private lateinit var hasher: CountingHasher
    private lateinit var service: LocalAuthService

    private val properties =
        AuthProperties(
            local =
                AuthProperties.Local(
                    enabled = true,
                    lockout = AuthProperties.Lockout(maxFailures = MAX_FAILURES, durationMinutes = LOCK_MINUTES),
                ),
        )

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(dataSource())
    }

    @BeforeEach
    fun setUp() {
        users = UserRepository(jdbc)
        audit = AuditLogger(jdbc, ObjectMapper())
        hasher = CountingHasher()
        service = LocalAuthService(users, hasher, properties, audit)
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        jdbc.jdbcTemplate.execute("TRUNCATE audit_log")
    }

    // -------------------------------------------------- enumeration resistance (§5A.5)

    @Test
    fun `unknown email and wrong password are the same outcome, the same audit, and the same verification cost`() {
        val user = users.insert("alice@company.com", "Alice", null, "google", "sub-1", isAdmin = false)
        users.setPassword(user.id, hasher.hash(CORRECT_PASSWORD), mustChange = false)

        val wrongPassword = service.authenticate("alice@company.com", WRONG_PASSWORD, IP, AGENT)
        val unknownEmail = service.authenticate("nobody@company.com", WRONG_PASSWORD, IP, AGENT)

        // Same outcome type...
        wrongPassword shouldBe LocalAuthService.LocalLoginResult.BadCredentials
        unknownEmail shouldBe LocalAuthService.LocalLoginResult.BadCredentials
        // ...the same audit event (one row each, no detail that tells them apart at the wire)...
        auditEvents("auth.login.bad_credentials") shouldBeExactly 2
        // ...and the same cost: EXACTLY ONE Argon2 verification each. The unknown-email
        // verification ran against the dummy hash (a real argon2id encoding), not a
        // cheap short-circuit — remove the dummy verify and this count drops to one.
        hasher.verifications shouldHaveSize 2
        hasher.verifications[1] shouldStartWith "\$argon2id\$"
    }

    @Test
    fun `an OIDC-only account can never log in locally and looks exactly like an unknown email`() {
        users.insert("oidc@company.com", "Oidc", null, "google", "sub-9", isAdmin = false)

        val result = service.authenticate("oidc@company.com", CORRECT_PASSWORD, IP, AGENT)

        result shouldBe LocalAuthService.LocalLoginResult.BadCredentials
        hasher.verifications shouldHaveSize 1
        hasher.verifications.single() shouldStartWith "\$argon2id\$"
        auditEvents("auth.login.bad_credentials") shouldBeExactly 1
    }

    // ------------------------------------------------------------ lockout (§5A.3)

    @Test
    fun `consecutive failures lock the account, the lock is audited, and a locked account does no Argon2 work`() {
        val user = users.insert("alice@company.com", "Alice", null, "google", "sub-1", isAdmin = false)
        users.setPassword(user.id, hasher.hash(CORRECT_PASSWORD), mustChange = false)

        repeat(MAX_FAILURES - 1) {
            service.authenticate("alice@company.com", WRONG_PASSWORD, IP, AGENT)
        }
        auditEvents("auth.login.locked") shouldBeExactly 0

        // The MAX_FAILURES-th failure locks.
        service.authenticate("alice@company.com", WRONG_PASSWORD, IP, AGENT)
        auditEvents("auth.login.locked") shouldBeExactly 1

        // While locked even the CORRECT password is refused — and costs no verification,
        // so a spray cannot keep the lock warm or spend native Argon2 memory.
        val verificationsBefore = hasher.verifications.size
        service.authenticate("alice@company.com", CORRECT_PASSWORD, IP, AGENT) shouldBe
            LocalAuthService.LocalLoginResult.Locked
        hasher.verifications shouldHaveSize verificationsBefore
    }

    @Test
    fun `an expired lock admits the next attempt and a success clears the counters`() {
        val user = users.insert("alice@company.com", "Alice", null, "google", "sub-1", isAdmin = false)
        users.setPassword(user.id, hasher.hash(CORRECT_PASSWORD), mustChange = false)
        repeat(MAX_FAILURES) { users.recordLocalLoginFailure(user.id, MAX_FAILURES, LOCK_MINUTES) }
        users.findLocalCredential("alice@company.com")?.lockedUntil.shouldNotBeNull()
        jdbc.jdbcTemplate.update("UPDATE users SET locked_until = NOW() - INTERVAL '1 minute' WHERE id = '${user.id}'")

        val result = service.authenticate("alice@company.com", CORRECT_PASSWORD, IP, AGENT)

        (result is LocalAuthService.LocalLoginResult.Success) shouldBe true
        val credential = users.findLocalCredential("alice@company.com")
        credential.shouldNotBeNull()
        credential.failedLoginCount shouldBe 0
    }

    // ------------------------------------------------------------ is_active (§4.2)

    @Test
    fun `a deactivated account with the correct password is rejected as inactive, exactly as on the OIDC path`() {
        val user = users.insert("alice@company.com", "Alice", null, "google", "sub-1", isAdmin = false)
        users.setPassword(user.id, hasher.hash(CORRECT_PASSWORD), mustChange = false)
        users.setActive(user.id, false)

        val result = service.authenticate("alice@company.com", CORRECT_PASSWORD, IP, AGENT)

        (result is LocalAuthService.LocalLoginResult.Inactive) shouldBe true
        // The SAME audit event the OIDC path writes (§10.1) — mirrored vocabulary.
        auditEvents("auth.login.user_inactive") shouldBeExactly 1
    }

    @Test
    fun `the correct password on an active account succeeds and stamps the login`() {
        val user = users.insert("alice@company.com", "Alice", null, "google", "sub-1", isAdmin = false)
        users.setPassword(user.id, hasher.hash(CORRECT_PASSWORD), mustChange = false)

        val result = service.authenticate("Alice@Company.com", CORRECT_PASSWORD, IP, AGENT)

        (result is LocalAuthService.LocalLoginResult.Success) shouldBe true
        (result as LocalAuthService.LocalLoginResult.Success).user.id shouldBe user.id
        users.findById(user.id)?.lastLoginAt.shouldNotBeNull()
    }

    // ------------------------------------------------------------------ helpers

    private fun auditEvents(event: String): Int =
        checkNotNull(
            jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE event = '$event'", emptyMap<String, Any>(), Int::class.java),
        ) { "COUNT(*) returned no row" }

    private fun dataSource(): DriverManagerDataSource = SharedPostgres.dataSource()

    /**
     * Records every encoded hash it is asked to verify against — the observable that
     * pins "the no-row path costs one Argon2 run" without paying 19 MiB per test.
     * `hash`/`verify` are honest otherwise: a stored `hash:<raw>` verifies only `<raw>`.
     */
    private class CountingHasher : SecretHasher {
        val verifications = mutableListOf<String>()

        override fun hash(raw: String): String = "hash:$raw"

        override fun verify(
            encodedHash: String,
            raw: String,
        ): Boolean {
            verifications += encodedHash
            return encodedHash == "hash:$raw"
        }
    }

    private companion object {
        const val MAX_FAILURES = 3
        const val LOCK_MINUTES = 15L
        const val CORRECT_PASSWORD = "correct-horse-battery-staple"
        const val WRONG_PASSWORD = "wrong-password"
        const val IP = "10.0.0.9"
        const val AGENT = "LocalAuthServiceTest/1.0"
    }
}
