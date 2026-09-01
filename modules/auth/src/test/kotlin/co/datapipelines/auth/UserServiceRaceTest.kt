package co.datapipelines.auth

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import java.time.Instant
import java.util.UUID

/**
 * The find-then-insert races of the first-login and bootstrap paths (ARCH-AUDIT M5/M6):
 * the loser of the insert race catches `DuplicateKeyException` and re-reads the winner's row
 * instead of failing the login / the context startup. Deterministic here — the repository is
 * mocked to return "absent" on the pre-check and throw on the insert, which is exactly the
 * interleaving two replicas produce against one fresh database.
 */
class UserServiceRaceTest {
    private val users = mockk<UserRepository>()
    private val audit = mockk<AuditLogger>(relaxed = true)
    private val service =
        UserService(users, AuthCache(AuthProperties()), AuthProperties(bootstrapAdminEmail = ADMIN_EMAIL), audit)

    @Test
    fun `findOrCreateByEmail - the first-login race links the winner's row instead of 500ing`() {
        val winner = user(email = EMAIL)
        every { users.findByEmail(EMAIL) } returnsMany listOf(null, winner)
        every { users.insert(any(), any(), any(), any(), any(), any()) } throws DuplicateKeyException("users_email_key")
        every { users.updateIdentity(any(), any(), any(), any(), any()) } just runs
        every { users.findById(winner.id) } returns winner

        val result = service.findOrCreateByEmail(EMAIL, "Ada", null, "google", "sub-1")

        result shouldBe winner
        verify(exactly = 1) { users.updateIdentity(winner.id, "Ada", null, "google", "sub-1") }
    }

    @Test
    fun `provisionBootstrapActor - the first-boot race returns the winner's row instead of crashing startup`() {
        val winner = user(email = ADMIN_EMAIL, provider = UserService.BOOTSTRAP_PROVIDER)
        every { users.findByEmail(ADMIN_EMAIL) } returnsMany listOf(null, winner)
        every { users.insert(any(), any(), any(), any(), any(), any()) } throws DuplicateKeyException("users_email_key")

        val result = service.provisionBootstrapActor()

        result shouldBe winner
    }

    private fun user(
        email: String,
        provider: String = "google",
    ) = User(
        id = UUID.randomUUID(),
        email = email,
        displayName = email.substringBefore('@'),
        provider = provider,
        providerSubject = email,
        isActive = true,
        isAdmin = false,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private companion object {
        const val ADMIN_EMAIL = "admin@example.com"
        const val EMAIL = "ada@example.com"
    }
}
