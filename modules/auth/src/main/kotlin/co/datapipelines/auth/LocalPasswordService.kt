package co.datapipelines.auth

import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * Password mutations for local accounts (auth.md §5A): the self-service change,
 * and the user-administration operations (create local user, reset, disable
 * local access, unlock).
 *
 * There are **no email flows** — the product has no SMTP. A forgotten password
 * means an admin resets it here: a new random one-time credential under the
 * same forced-change rule as the config seed (§5A.2/§5A.4). Every mutation
 * evicts the liveness/user cache immediately (the forced-change gate reads
 * `must_change_password` through it) and audits with the actor, never any
 * credential material.
 */
class LocalPasswordService(
    private val userRepository: UserRepository,
    private val userService: UserService,
    private val secretHasher: SecretHasher,
    private val authCache: AuthCache,
    private val auditLogger: AuditLogger,
    private val authProperties: AuthProperties,
) {
    private val log = LoggerFactory.getLogger(LocalPasswordService::class.java)

    sealed interface ChangeResult {
        data object Success : ChangeResult

        /** The current-password check failed — same wording whatever the row state. */
        data object WrongCurrentPassword : ChangeResult

        /** Below (or above) the §5A.5 policy floor. */
        data class PolicyViolation(
            val reason: String,
        ) : ChangeResult

        /** The account has no local password to change (OIDC-only). */
        data object NoLocalAccount : ChangeResult

        /**
         * The account is inside its §5A.3 lockout window, so the current-password check
         * was not attempted. Without this, `changeOwn` is a brute-force oracle that the
         * lockout on `POST /login` does not cover.
         */
        data object AccountLocked : ChangeResult
    }

    sealed interface CreateResult {
        data class Success(
            val user: User,
            /** Shown to the admin exactly once — it is never stored retrievably. */
            val oneTimePassword: String,
        ) : CreateResult

        data object EmailTaken : CreateResult
    }

    /**
     * Self-service change (also the §5A.4 forced change): verifies the CURRENT
     * password first (a hijacked session must not be able to rotate the
     * credential), enforces the §5A.5 floor on the new one, and clears
     * `must_change_password` — the gate's key.
     */
    fun changeOwn(
        userId: UUID,
        currentPassword: String,
        newPassword: String,
    ): ChangeResult {
        val user = userRepository.findById(userId)
        val credential =
            user?.let { userRepository.findLocalCredential(it.email) }
                ?: return ChangeResult.NoLocalAccount

        rejectionFor(userId, credential, currentPassword, newPassword)?.let { return it }

        userRepository.setPassword(userId, secretHasher.hash(newPassword), mustChange = false)
        authCache.invalidateUser(userId)
        auditLogger.log("auth.password.changed", userId = userId)
        return ChangeResult.Success
    }

    /**
     * The three ways a self-service change is refused, in the order the login path uses,
     * or `null` when the change may proceed. Split out of [changeOwn] so the happy path
     * reads as one statement — and so each refusal carries its own audit trail beside the
     * condition that produced it.
     */
    private fun rejectionFor(
        userId: UUID,
        credential: LocalCredential,
        currentPassword: String,
        newPassword: String,
    ): ChangeResult? {
        // The §5A.3 lockout is consulted BEFORE any Argon2 work, exactly as the login path
        // does it: a locked account must cost an attacker nothing to probe, or the lock
        // becomes a CPU amplifier instead of a brake.
        val lockedUntil = credential.lockedUntil
        if (lockedUntil != null && lockedUntil.isAfter(Instant.now())) {
            auditLogger.log(
                "auth.password.change_failed",
                userId = userId,
                details = mapOf("reason" to "locked"),
            )
            return ChangeResult.AccountLocked
        }
        policyViolation(newPassword)?.let { return ChangeResult.PolicyViolation(it) }
        if (!secretHasher.verify(credential.passwordHash, currentPassword)) {
            // Counted and audited on the SAME counter as login (auth.md §5A.3). Before this,
            // the endpoint verified the current password with no lockout, no counting and no
            // audit on failure — an unmetered, silent guessing oracle sitting beside a login
            // path that was fully defended.
            val failure =
                userRepository.recordLocalLoginFailure(
                    userId,
                    authProperties.local.lockout.maxFailures,
                    authProperties.local.lockout.durationMinutes,
                )
            authCache.invalidateUser(userId)
            auditLogger.log(
                "auth.password.change_failed",
                userId = userId,
                details = mapOf("reason" to "bad_current_password"),
            )
            if (failure.lockedUntil != null) {
                auditLogger.log("auth.password.change_locked", userId = userId)
            }
            return ChangeResult.WrongCurrentPassword
        }
        return null
    }

    /**
     * Admin: create a local account with a random one-time password
     * (`must_change_password = TRUE`). Routes through the single §4.4 creation
     * path via [UserService.createLocalAccount] — the bootstrap grant semantics
     * are identical to every other creation path.
     */
    fun createLocalUser(
        email: String,
        displayName: String,
        actorId: UUID,
    ): CreateResult {
        val normalized = email.trim().lowercase()
        val oneTime = generateOneTimePassword()
        val user =
            try {
                userService.createLocalAccount(normalized, displayName.trim().ifEmpty { normalized.substringBefore('@') })
            } catch (duplicate: DuplicateKeyException) {
                // Check-then-insert raced another creator — losing the race is the
                // expected outcome, logged so the audit trail of attempts survives.
                log.debug("local account creation lost the race for {}", normalized, duplicate)
                return CreateResult.EmailTaken
            }
        userRepository.setPassword(user.id, secretHasher.hash(oneTime), mustChange = true)
        auditLogger.log(
            "auth.user.created",
            userId = user.id,
            details = mapOf("actor" to actorId.toString(), "email" to normalized, "method" to "local"),
        )
        return CreateResult.Success(user, oneTime)
    }

    /**
     * Admin: reset a password — a new random one-time credential,
     * `must_change_password = TRUE`, and the lockout cleared (the reset IS the
     * unlock path for an account that sprayed itself out, §5A.3).
     */
    fun resetPassword(
        userId: UUID,
        actorId: UUID,
    ): String? {
        val user = userRepository.findById(userId) ?: return null
        val oneTime = generateOneTimePassword()
        userRepository.setPassword(userId, secretHasher.hash(oneTime), mustChange = true)
        authCache.invalidateUser(userId)
        auditLogger.log(
            "auth.password.reset",
            userId = userId,
            details = mapOf("actor" to actorId.toString(), "email" to user.email),
        )
        return oneTime
    }

    /** Admin: disable local access — the account becomes OIDC-only (§5A.1). */
    fun disableLocalAccess(
        userId: UUID,
        actorId: UUID,
    ): Boolean {
        val changed = userRepository.clearPassword(userId)
        if (changed) {
            authCache.invalidateUser(userId)
            auditLogger.log("auth.password.disabled", userId = userId, details = mapOf("actor" to actorId.toString()))
        }
        return changed
    }

    /** Admin: clear a lockout without touching the credential (§5A.3). */
    fun unlock(
        userId: UUID,
        actorId: UUID,
    ): Boolean {
        val changed = userRepository.clearLockout(userId)
        if (changed) {
            auditLogger.log("auth.user.unlocked", userId = userId, details = mapOf("actor" to actorId.toString()))
        }
        return changed
    }

    private companion object {
        /**
         * The §5A.5 floor: 12 characters minimum, 128 maximum. Length is the factor
         * that resists offline cracking (NIST 800-63B — composition rules push users
         * to predictable patterns); the maximum bounds Argon2 input cost.
         */
        const val MIN_PASSWORD_LENGTH = 12
        const val MAX_PASSWORD_LENGTH = 128

        const val ONE_TIME_GROUPS = 3
        const val ONE_TIME_GROUP_LENGTH = 4
        const val BASE32 = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no I/O/0/1 — read-out-loud safe

        val random = SecureRandom()

        fun policyViolation(password: String): String? =
            when {
                password.length < MIN_PASSWORD_LENGTH -> {
                    "Password must be at least $MIN_PASSWORD_LENGTH characters"
                }

                password.length > MAX_PASSWORD_LENGTH -> {
                    "Password must be at most $MAX_PASSWORD_LENGTH characters"
                }

                else -> {
                    null
                }
            }

        /** `xxxx-xxxx-xxxx` of unambiguous base32 — random, one-time, transcribable. */
        fun generateOneTimePassword(): String =
            (1..ONE_TIME_GROUPS)
                .map { (1..ONE_TIME_GROUP_LENGTH).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("") }
                .joinToString("-")
    }
}
