package co.datapipelines.auth

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Bootstrap-admin provisioning under the amended auth.md §4.4 (AUTH-SEC-7): the grant
 * fires **only when the row is created**, matches the lowercase-normalized email, and
 * never re-grants after a deliberate revoke.
 */
class BootstrapAdminTest {
    private val repo = mockk<UserRepository>(relaxed = true)
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val cache = AuthCache(AuthProperties())
    private val props = AuthProperties(bootstrapAdminEmail = "Admin@Company.com")
    private val service = UserService(repo, cache, props, auditLogger)

    private fun user(
        id: UUID,
        email: String,
        admin: Boolean,
    ) = User(id, email, "N", null, "kc", "sub", true, admin, Instant.now(), Instant.now(), null)

    @Test
    fun `a new user with the bootstrap email is created as admin and audited once`() {
        val id = UUID.randomUUID()
        val isAdmin = slot<Boolean>()
        every { repo.findByEmail("admin@company.com") } returns null
        every { repo.insert(any(), any(), any(), any(), any(), capture(isAdmin)) } answers {
            user(id, "admin@company.com", admin = lastArg())
        }

        val result = service.findOrCreateByEmail("admin@company.com", "Admin", null, "kc", "sub")

        isAdmin.captured.shouldBeTrue()
        result.isAdmin.shouldBeTrue()
        verify(exactly = 1) { auditLogger.log("auth.user.admin_granted", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the bootstrap email is matched case-insensitively via lowercase normalization`() {
        val id = UUID.randomUUID()
        val isAdmin = slot<Boolean>()
        // The configured value is "Admin@Company.com"; the provider sends yet another casing.
        every { repo.findByEmail("admin@company.com") } returns null
        every { repo.insert(any(), any(), any(), any(), any(), capture(isAdmin)) } answers {
            user(id, "admin@company.com", admin = lastArg())
        }

        service.findOrCreateByEmail("ADMIN@COMPANY.COM", "Admin", null, "kc", "sub")

        // The lookup used the normalized address (so no second row is created) …
        verify { repo.findByEmail("admin@company.com") }
        // … and the normalized address is what was stored.
        verify { repo.insert("admin@company.com", any(), any(), any(), any(), any()) }
        isAdmin.captured.shouldBeTrue()
    }

    @Test
    fun `a case-variant login of an existing admin cannot mint a second admin row`() {
        val id = UUID.randomUUID()
        every { repo.findByEmail("admin@company.com") } returns user(id, "admin@company.com", admin = true)
        every { repo.findById(id) } returns user(id, "admin@company.com", admin = true)

        service.findOrCreateByEmail("Admin@Company.com", "Admin", null, "kc", "sub")

        verify(exactly = 0) { repo.insert(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { auditLogger.log("auth.user.admin_granted", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `after a deliberate revoke the next login of the bootstrap email stays revoked`() {
        val id = UUID.randomUUID()
        // The row exists and has had admin revoked (§10.1 auth.user.admin_revoked).
        every { repo.findByEmail("admin@company.com") } returns user(id, "admin@company.com", admin = false)
        every { repo.findById(id) } returns user(id, "admin@company.com", admin = false)

        val result = service.findOrCreateByEmail("admin@company.com", "Admin", null, "kc", "sub")

        result.isAdmin.shouldBeFalse()
        // The §4.4 path fires only at row creation — it never re-grants on a later login.
        verify(exactly = 0) { repo.grantAdmin(any()) }
        verify(exactly = 0) { auditLogger.log("auth.user.admin_granted", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a non-matching email is provisioned as a normal user, no admin grant`() {
        val id = UUID.randomUUID()
        val isAdmin = slot<Boolean>()
        every { repo.findByEmail("bob@company.com") } returns null
        every { repo.insert(any(), any(), any(), any(), any(), capture(isAdmin)) } answers {
            user(id, "bob@company.com", admin = lastArg())
        }

        service.findOrCreateByEmail("Bob@Company.com", "Bob", null, "kc", "sub")

        isAdmin.captured.shouldBeFalse()
        verify(exactly = 0) { auditLogger.log("auth.user.admin_granted", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `with no bootstrap email configured nobody is ever provisioned as admin`() {
        val id = UUID.randomUUID()
        val isAdmin = slot<Boolean>()
        val plain = UserService(repo, cache, AuthProperties(), auditLogger)
        every { repo.findByEmail("admin@company.com") } returns null
        every { repo.insert(any(), any(), any(), any(), any(), capture(isAdmin)) } answers {
            user(id, "admin@company.com", admin = lastArg())
        }

        plain.findOrCreateByEmail("admin@company.com", "Admin", null, "kc", "sub")

        isAdmin.captured.shouldBeFalse()
    }
}
