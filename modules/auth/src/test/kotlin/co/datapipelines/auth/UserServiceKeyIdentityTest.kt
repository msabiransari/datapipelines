package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * #215 slice (b), record §3.3 (PK5): a key's own `service` identity, as [UserService] builds and
 * retires it. The row is built like the System actor, so login is impossible by construction, and
 * it can never be the bootstrap admin — not even when the configured bootstrap email is,
 * adversarially, the identity's own `<key id>@keys.invalid`.
 */
class UserServiceKeyIdentityTest {
    private val userRepository = mockk<UserRepository>(relaxUnitFun = true)
    private val authCache = mockk<AuthCache>(relaxUnitFun = true)
    private val auditLogger = mockk<AuditLogger>(relaxUnitFun = true)

    private val keyId = "dpk_IDENTITYKEYA"
    private val identityEmail = "dpk_identitykeya@keys.invalid"

    private fun service(bootstrapEmail: String? = null) =
        UserService(userRepository, authCache, AuthProperties(bootstrapAdminEmail = bootstrapEmail), auditLogger)

    private fun row(
        id: UUID,
        kind: UserKind,
        active: Boolean = true,
    ) = User(
        id = id,
        email = identityEmail,
        displayName = "ci",
        provider = UserService.KEY_PROVIDER,
        providerSubject = keyId,
        isActive = active,
        isAdmin = false,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        kind = kind,
    )

    @Test
    fun `provisionIdentity inserts a service row under the reserved key provider, never an admin`() {
        val created = row(UUID.randomUUID(), UserKind.SERVICE)
        every { userRepository.insert(any(), any(), any(), any(), any(), any(), any()) } returns created

        // The bootstrap email is set to the identity's own address: the kind still decides.
        service(bootstrapEmail = identityEmail).provisionIdentity(keyId, "ci") shouldBe created

        verify(exactly = 1) {
            userRepository.insert(
                email = identityEmail,
                displayName = "ci",
                profilePictureUrl = null,
                provider = UserService.KEY_PROVIDER,
                providerSubject = keyId,
                isAdmin = false,
                kind = UserKind.SERVICE,
            )
        }
        // The only audit the create path writes is the bootstrap grant — and it did not happen.
        verify { auditLogger wasNot Called }
    }

    @Test
    fun `deactivateIdentity deactivates the service row and evicts it from the liveness cache`() {
        val id = UUID.randomUUID()
        every { userRepository.findById(id) } returns row(id, UserKind.SERVICE)
        every { userRepository.setActive(id, active = false) } returns true

        service().deactivateIdentity(id)

        verify(exactly = 1) { userRepository.setActive(id, active = false) }
        verify(exactly = 1) { authCache.invalidateUser(id) }
    }

    @Test
    fun `deactivateIdentity refuses a person - a human is deactivated by user administration, never by a key`() {
        val id = UUID.randomUUID()
        every { userRepository.findById(id) } returns row(id, UserKind.HUMAN)

        shouldThrow<IllegalStateException> { service().deactivateIdentity(id) }

        verify(exactly = 0) { userRepository.setActive(any(), any()) }
    }

    @Test
    fun `deactivateIdentity of a row that is gone does nothing`() {
        val id = UUID.randomUUID()
        every { userRepository.findById(id) } returns null

        service().deactivateIdentity(id)

        verify(exactly = 0) { userRepository.setActive(any(), any()) }
        verify(exactly = 0) { authCache.invalidateUser(any()) }
    }
}
