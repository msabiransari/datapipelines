package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * #187 — the link-once rule in [UserService.findOrCreateByEmail], as a table over the STORED
 * row's `(provider, provider_subject)` against the incoming login:
 *
 * - the bootstrap placeholder is CLAIMED by the first real sign-in (the pre-provisioned
 *   admin's first login);
 * - the SAME `(provider, subject)` refreshes display name and picture, as before;
 * - anything else — a different provider, a different subject under the same provider, or a
 *   `local`/`system` row — REFUSES with [IdentityMismatchException] and updates NOTHING;
 *   re-linking is the super admin's explicit [UserService.resetIdentity], never a side
 *   effect of a login.
 */
class UserServiceIdentityLinkingTest {
    private val userRepository = mockk<UserRepository>(relaxUnitFun = true)
    private val authCache = mockk<AuthCache>(relaxUnitFun = true)
    private val auditLogger = mockk<AuditLogger>(relaxUnitFun = true)
    private val service =
        UserService(userRepository, authCache, AuthProperties(bootstrapAdminEmail = null), auditLogger)

    private val userId = UUID.randomUUID()

    init {
        // The re-read after updateIdentity; a fresh row every time keeps the stubs order-free.
        every { userRepository.findById(userId) } answers { stored(provider = "keycloak", subject = subject) }
    }

    private val email = "alice@company.com"
    private val subject = "sub-1"

    @Test
    fun `a bootstrap placeholder row is claimed by the first real sign-in`() {
        every { userRepository.findByEmail(email) } returns stored(provider = UserService.BOOTSTRAP_PROVIDER, subject = email)

        val provisioned = service.findOrCreateByEmail(email, "Alice", null, "keycloak", subject)

        provisioned.created shouldBe false
        verify(exactly = 1) {
            userRepository.updateIdentity(id = userId, any(), any(), provider = "keycloak", providerSubject = subject)
        }
    }

    @Test
    fun `the same identity refreshes the profile, exactly as before`() {
        every { userRepository.findByEmail(email) } returns stored(provider = "keycloak", subject = subject)

        service.findOrCreateByEmail(email, "Alice II", "pic.png", "keycloak", subject)

        verify(exactly = 1) {
            userRepository.updateIdentity(id = userId, displayName = "Alice II", profilePictureUrl = "pic.png", any(), any())
        }
    }

    @Test
    fun `a different provider with the same email is refused and the row is untouched`() {
        every { userRepository.findByEmail(email) } returns stored(provider = "google", subject = "g-sub")

        val refused =
            shouldThrow<IdentityMismatchException> {
                service.findOrCreateByEmail(email, "Alice", null, "keycloak", subject)
            }

        refused.userId shouldBe userId
        refused.storedProvider shouldBe "google"
        refused.incomingProvider shouldBe "keycloak"
        verify(exactly = 0) { userRepository.updateIdentity(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a different subject under the SAME provider is refused too`() {
        every { userRepository.findByEmail(email) } returns stored(provider = "keycloak", subject = "other-sub")

        shouldThrow<IdentityMismatchException> {
            service.findOrCreateByEmail(email, "Alice", null, "keycloak", subject)
        }
        verify(exactly = 0) { userRepository.updateIdentity(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a LOCAL row is refused - moving the account to OIDC is the admin's reset, not a side effect`() {
        every { userRepository.findByEmail(email) } returns stored(provider = UserService.LOCAL_PROVIDER, subject = email)

        shouldThrow<IdentityMismatchException> {
            service.findOrCreateByEmail(email, "Alice", null, "keycloak", subject)
        }
        verify(exactly = 0) { userRepository.updateIdentity(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a SYSTEM row can never be claimed`() {
        every { userRepository.findByEmail(UserService.SYSTEM_ACTOR_EMAIL) } returns
            stored(
                provider = UserService.SYSTEM_PROVIDER,
                subject = UserService.SYSTEM_ACTOR_SUBJECT,
                email = UserService.SYSTEM_ACTOR_EMAIL,
            )

        shouldThrow<IdentityMismatchException> {
            service.findOrCreateByEmail(UserService.SYSTEM_ACTOR_EMAIL, "System", null, "keycloak", subject)
        }
        verify(exactly = 0) { userRepository.updateIdentity(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a first login still creates the row`() {
        every { userRepository.findByEmail(email) } returns null
        every {
            userRepository.insert(
                email = email,
                displayName = "Alice",
                profilePictureUrl = null,
                provider = "keycloak",
                providerSubject = subject,
                isAdmin = false,
            )
        } returns stored(provider = "keycloak", subject = subject)

        val provisioned = service.findOrCreateByEmail(email, "Alice", null, "keycloak", subject)

        provisioned.created shouldBe true
    }

    // ------------------------------------------------------------- the reset verb (#187)

    @Test
    fun `the identity reset returns the row to the placeholder and is audited with the actor`() {
        every { userRepository.resetIdentityToBootstrap(userId) } returns true

        service.resetIdentity(userId, actorId = UUID.randomUUID())

        verify(exactly = 1) { authCache.invalidateUser(userId) }
        verify(exactly = 1) { auditLogger.log("auth.user.identity_reset", userId, any(), any(), any(), any()) }
    }

    @Test
    fun `a reset of a row already on the placeholder is no event`() {
        every { userRepository.resetIdentityToBootstrap(userId) } returns false

        service.resetIdentity(userId, actorId = UUID.randomUUID())

        verify(exactly = 0) { auditLogger.log(any(), any(), any(), any(), any(), any()) }
    }

    // ------------------------------------------------------------------------ fixture

    private fun stored(
        provider: String,
        subject: String,
        email: String = this.email,
    ) = User(
        id = userId,
        email = email,
        displayName = "Alice",
        profilePictureUrl = null,
        provider = provider,
        providerSubject = subject,
        isActive = true,
        isAdmin = false,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        lastLoginAt = null,
    )
}
