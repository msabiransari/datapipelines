package co.datapipelines.auth

import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Local username/password authentication (auth.md §5A) — the optional second
 * sign-in method for deployments without an IdP. Verifies the Argon2id hash with
 * the same [SecretHasher] API keys use; on success the caller (the login
 * controller) mints the SAME session the OIDC path mints, so both flows converge
 * on one principal.
 *
 * ## Enumeration resistance (§5A.5)
 * Unknown email, an OIDC-only account (`password_hash IS NULL`), and a wrong
 * password are the SAME outcome — one [LocalLoginResult.BadCredentials], one
 * `auth.login.bad_credentials` audit row, one redirect — and, just as important,
 * the SAME cost: the no-row path still runs one Argon2 verification against
 * [DUMMY_HASH], so response timing cannot tell "no such account" from "wrong
 * password".
 *
 * ## A native verification fault is not a wrong password (#847, §5A.5)
 * [SecretHasher.verify] throws [SecretVerificationUnavailableException] when the native side
 * could not answer. Both verifications here — the account's and the dummy one — let it
 * propagate UNCAUGHT, before any failure counter or audit row: it is no attempt, and the login
 * boundary answers 503. The dummy verify must stay un-caught as well: catching it would answer
 * the unknown-email path "invalid credentials" while a real account answers 503, an enumeration
 * oracle. For the same reason [DUMMY_HASH] is a precomputed constant — no native hash runs on a
 * request, so a cold first attempt cannot fail differently from a warm one.
 *
 * ## Lockout (§5A.3)
 * Per-account, after `lockout.max-failures` consecutive failures — the per-IP
 * [LoginRateLimitFilter] cannot stop a slow spray against one account. While
 * locked, attempts do NO Argon2 work (a spray must not be able to keep the lock
 * warm or spend our native memory) and the failure count is untouched; an
 * expired lock starts a fresh budget (see [UserRepository.recordLocalLoginFailure]).
 */
class LocalAuthService(
    private val userRepository: UserRepository,
    private val secretHasher: SecretHasher,
    private val authProperties: AuthProperties,
    private val auditLogger: AuditLogger,
) {
    private val log = LoggerFactory.getLogger(LocalAuthService::class.java)

    sealed interface LocalLoginResult {
        /** Credential verified and account active — the caller mints the session. */
        data class Success(
            val user: User,
        ) : LocalLoginResult

        /** Unknown email, OIDC-only account, or wrong password — indistinguishable. */
        data object BadCredentials : LocalLoginResult

        /** The account is inside its lockout window. */
        data object Locked : LocalLoginResult

        /** Password verified, but the account is deactivated (§4.2). */
        data class Inactive(
            val user: User,
        ) : LocalLoginResult
    }

    fun authenticate(
        email: String,
        password: String,
        sourceIp: String?,
        userAgent: String?,
    ): LocalLoginResult {
        // §4.2: one canonical form at the boundary, exactly as the OIDC path.
        val normalized = email.trim().lowercase()
        // `findLocalCredential` reads PEOPLE only (`kind = 'human'`, #215 A.6): the System row and
        // a key's identity are hashless by construction, and the query refuses them by kind as
        // well, so a hash that somehow reached one still opens nothing — the unknown-user answer.
        val credential =
            userRepository.findLocalCredential(normalized)
                ?: return rejectUnknown(normalized, password, sourceIp, userAgent)

        val lockedUntil = credential.lockedUntil
        if (lockedUntil != null && lockedUntil.isAfter(Instant.now())) {
            auditFailure("locked", credential.userId, normalized, sourceIp, userAgent, mapOf("reason" to "locked"))
            return LocalLoginResult.Locked
        }

        // A native fault throws past here (#847): no failure recorded, no audit row.
        if (!secretHasher.verify(credential.passwordHash, password)) {
            return rejectWrongPassword(credential, normalized, sourceIp, userAgent)
        }

        return verifiedLogin(credential, normalized, sourceIp)
    }

    /** The password verified: the `is_active` re-check (§4.2), then the success bookkeeping. */
    private fun verifiedLogin(
        credential: LocalCredential,
        email: String,
        sourceIp: String?,
    ): LocalLoginResult {
        val user =
            checkNotNull(userRepository.findById(credential.userId)) {
                "User ${credential.userId} vanished mid-login"
            }
        if (!user.isActive) {
            // The SAME event the OIDC path writes (§10.1) — mirrored vocabulary. Safe
            // to surface as its own banner only because the password already verified:
            // it tells the caller nothing they did not just prove they know.
            auditLogger.log("auth.login.user_inactive", userId = user.id, sourceIp = sourceIp)
            log.info("event=auth.login.local_outcome outcome=user_inactive email={}", email)
            return LocalLoginResult.Inactive(user)
        }
        userRepository.recordLocalLoginSuccess(user.id)
        return LocalLoginResult.Success(user)
    }

    /** No credential row: unknown email or OIDC-only account — one dummy-cost verify, one audit. */
    private fun rejectUnknown(
        email: String,
        password: String,
        sourceIp: String?,
        userAgent: String?,
    ): LocalLoginResult {
        // Result ignored, exception NOT caught (#847): a native fault answers 503 here exactly as on a real account.
        secretHasher.verify(dummyHash(), password)
        auditFailure("bad_credentials", null, email, sourceIp, userAgent, emptyMap())
        return LocalLoginResult.BadCredentials
    }

    /** A verified-as-wrong password: record the failure, maybe lock, always [LocalLoginResult.BadCredentials]. */
    private fun rejectWrongPassword(
        credential: LocalCredential,
        email: String,
        sourceIp: String?,
        userAgent: String?,
    ): LocalLoginResult {
        val failure =
            userRepository.recordLocalLoginFailure(
                credential.userId,
                authProperties.local.lockout.maxFailures,
                authProperties.local.lockout.durationMinutes,
            )
        auditFailure("bad_credentials", credential.userId, email, sourceIp, userAgent, emptyMap())
        if (failure.lockedUntil != null) {
            // Observable in the audit log (§5A.3) — and clearable by an admin
            // (unlock or password reset on the user-administration screen).
            auditLogger.log(
                "auth.login.locked",
                userId = credential.userId,
                sourceIp = sourceIp,
                details = mapOf("email" to email, "locked_until" to failure.lockedUntil.toString()),
            )
        }
        return LocalLoginResult.BadCredentials
    }

    /** One `auth.login.bad_credentials` audit row plus its structured log line (no credential material). */
    private fun auditFailure(
        outcome: String,
        userId: java.util.UUID?,
        email: String,
        sourceIp: String?,
        userAgent: String?,
        extraDetails: Map<String, Any?>,
    ) {
        auditLogger.log(
            "auth.login.bad_credentials",
            userId = userId,
            sourceIp = sourceIp,
            userAgent = userAgent,
            details = mapOf("email" to email) + extraDetails,
        )
        log.info("event=auth.login.local_outcome outcome={} email={}", outcome, email)
    }

    private fun dummyHash(): String = DUMMY_HASH

    internal companion object {
        /**
         * A genuine Argon2id encoding (the same parameters the production hasher uses) of
         * [DUMMY_PLAINTEXT], verified against when no credential row exists so the no-row path
         * costs what the wrong-password path costs. PRECOMPUTED (#847): generated once by the
         * production hasher and pasted here, so no request ever runs a native hash outside
         * [BoundedSecretHasher]'s permits. Never a real account's hash, and nothing about it is
         * secret. A change to the hasher's parameters must regenerate it — `LocalAuthServiceTest`
         * pins the prefix to those parameters and goes red until it is.
         */
        const val DUMMY_HASH: String =
            "\$argon2id\$v=19\$m=19456,t=2,p=1\$GbtHdKmU7ra7U1jzVPrb1w\$YiPmmzSXMpImFc+zBnpsrhvD/4ve6cdDBjZ6pUPOQQU"

        /** The fixed, public string [DUMMY_HASH] encodes. */
        const val DUMMY_PLAINTEXT = "dummy-password-for-timing-equalization"
    }
}
