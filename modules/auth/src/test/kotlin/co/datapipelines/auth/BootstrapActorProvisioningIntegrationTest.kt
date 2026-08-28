package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The §6.1 bootstrap actor, and the §4.2 linking amendment, against a real database.
 *
 * ## Why the audit sink here is the audit_log TABLE
 * The invariant under test is "`auth.user.admin_granted` is emitted **exactly once** across
 * provision → restart → login". A `mockk<AuditLogger>()` cannot prove that: a strict mock is
 * green precisely when the call is MISSING, and a relaxed one counts calls to a double rather
 * than rows in the log the operator will actually read. So this suite runs the shipped
 * [AuditLogger] against the shipped table and counts rows — the same reason
 * `UserAdminIntegrationTest` does.
 *
 * ## What "not a second grant path" means as an assertion
 * It is not enough that provisioning grants correctly. §4.4's rule is that the grant fires only
 * at row **creation** — so the tests below check the three moments that could quietly re-fire it
 * (a restart, the completing login, an ordinary re-login) and count zero extra events at each.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BootstrapActorProvisioningIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var users: UserRepository

    @BeforeAll
    fun createSchema() {
        jdbc = NamedParameterJdbcTemplate(dataSource())
        RepoFiles.MIGRATION_PATHS.forEach { jdbc.jdbcTemplate.execute(RepoFiles.read(it)) }
    }

    @BeforeEach
    fun setUp() {
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        jdbc.jdbcTemplate.execute("TRUNCATE audit_log")
        users = UserRepository(jdbc)
    }

    /** The production wiring, with the real audit writer. [bootstrapAdmin] mirrors the config key. */
    private fun service(bootstrapAdmin: String? = ADMIN_EMAIL_MIXED_CASE): UserService {
        val properties = AuthProperties(bootstrapAdminEmail = bootstrapAdmin)
        return UserService(users, AuthCache(properties), properties, AuditLogger(jdbc, ObjectMapper()))
    }

    // -------------------------------------------------------------------- pre-provisioning

    @Test
    fun `pre-provisioning creates the actor row with placeholder identity, active and admin`() {
        val actor = service().provisionBootstrapActor()

        // The email is lowercase-normalized even though the configured value is mixed case (§4.2).
        actor.email shouldBe ADMIN_EMAIL
        actor.provider shouldBe UserService.BOOTSTRAP_PROVIDER
        actor.providerSubject shouldBe ADMIN_EMAIL
        actor.displayName shouldBe "sample-admin"
        actor.isActive shouldBe true
        actor.isAdmin shouldBe true
        actor.lastLoginAt.shouldBeNull()

        adminGrantedEvents() shouldBe 1
        grantActor() shouldBe "bootstrap"
    }

    @Test
    fun `a restart touches nothing on the existing row and grants nothing again`() {
        val first = service().provisionBootstrapActor()
        val before = userSnapshot()

        val again = service().provisionBootstrapActor()

        again.id shouldBe first.id
        // updated_at is in the snapshot on purpose: an idempotent-looking UPDATE would move it.
        userSnapshot() shouldBe before
        adminGrantedEvents() shouldBe 1
    }

    @Test
    fun `an admin deliberately revoked stays revoked across a restart`() {
        val actor = service().provisionBootstrapActor()
        users.revokeAdmin(actor.id) shouldBe true

        service().provisionBootstrapActor()

        checkNotNull(users.findById(actor.id)).isAdmin shouldBe false
        // The revocation is not audited by this path; the point is that no NEW grant was written.
        adminGrantedEvents() shouldBe 1
    }

    @Test
    fun `pre-provisioning refuses when no bootstrap admin email is configured`() {
        shouldThrow<IllegalStateException> { service(bootstrapAdmin = null).provisionBootstrapActor() }
        shouldThrow<IllegalStateException> { service(bootstrapAdmin = "  ").provisionBootstrapActor() }

        userCount() shouldBe 0
    }

    // -------------------------------------------------------------------- the §4.2 amendment

    @Test
    fun `the completing OIDC login links identity including display_name, and grants nothing`() {
        val service = service()
        val actor = service.provisionBootstrapActor()

        val linked =
            service.findOrCreateByEmail(
                email = "Sample-Admin@Example.COM",
                displayName = "Alice Admin",
                pictureUrl = "https://pictures.example.com/alice",
                provider = "google",
                providerSubject = "google-sub-1",
            )

        linked.id shouldBe actor.id
        linked.provider shouldBe "google"
        linked.providerSubject shouldBe "google-sub-1"
        linked.profilePictureUrl shouldBe "https://pictures.example.com/alice"
        // The §6.1 amendment: the local-part placeholder is replaced, once, by the `name` claim.
        linked.displayName shouldBe "Alice Admin"
        linked.isAdmin shouldBe true
        // Grant-wise the login changes nothing — the event count is still the one from creation.
        adminGrantedEvents() shouldBe 1
        userCount() shouldBe 1
    }

    @Test
    fun `a later re-login refreshes display_name from the ID token`() {
        // Owner-ratified 2026-08-28 (021 Deviation 3): IdP names stay fresh on every login —
        // the spec's no-clobber clause protected a profile-edit feature that does not exist.
        // Pinned so the behavior cannot silently flip again.
        val service = service()
        service.provisionBootstrapActor()
        service.findOrCreateByEmail(ADMIN_EMAIL, "Alice Admin", null, "google", "google-sub-1")

        val relogin =
            service.findOrCreateByEmail(
                ADMIN_EMAIL,
                "A. Admin (work)",
                "https://pictures.example.com/new",
                "google",
                "google-sub-1",
            )

        relogin.displayName shouldBe "A. Admin (work)"
        relogin.profilePictureUrl shouldBe "https://pictures.example.com/new"
        adminGrantedEvents() shouldBe 1
    }

    @Test
    fun `an ordinary user's display_name refreshes on re-login too`() {
        val service = service(bootstrapAdmin = null)
        service.findOrCreateByEmail("bob@example.com", "Bob", null, "google", "bob-sub")

        val relogin = service.findOrCreateByEmail("bob@example.com", "Robert", null, "okta", "bob-okta")

        relogin.displayName shouldBe "Robert"
        relogin.provider shouldBe "okta"
        adminGrantedEvents() shouldBe 0
    }

    // -------------------------------------------------------------------- one grant path

    @Test
    fun `the admin logging in BEFORE registration is the same single grant - provisioning then adds nothing`() {
        val service = service()
        val fromLogin = service.findOrCreateByEmail(ADMIN_EMAIL, "Alice Admin", null, "google", "google-sub-1")
        fromLogin.isAdmin shouldBe true
        adminGrantedEvents() shouldBe 1

        val actor = service.provisionBootstrapActor()

        actor.id shouldBe fromLogin.id
        actor.provider shouldBe "google"
        adminGrantedEvents() shouldBe 1
        userCount() shouldBe 1
    }

    // -------------------------------------------------------------------- helpers

    private fun adminGrantedEvents(): Int =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE event = 'auth.user.admin_granted'",
                emptyMap<String, Any>(),
                Int::class.java,
            ),
        )

    private fun grantActor(): String? =
        jdbc.queryForObject(
            "SELECT details_json->>'actor' FROM audit_log WHERE event = 'auth.user.admin_granted'",
            emptyMap<String, Any>(),
            String::class.java,
        )

    private fun userCount(): Int = checkNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM users", emptyMap<String, Any>(), Int::class.java))

    private fun userSnapshot(): List<Map<String, Any?>> =
        jdbc.queryForList(
            "SELECT id, email, display_name, profile_picture_url, provider, provider_subject," +
                " is_active, is_admin, theme_preference, created_at, updated_at, last_login_at" +
                " FROM users ORDER BY email",
            emptyMap<String, Any>(),
        )

    private fun dataSource(): DriverManagerDataSource =
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password).apply {
            setDriverClassName(postgres.driverClassName)
        }

    private companion object {
        /** Configured mixed-case on purpose: §4.2 normalization applies to the config value too. */
        const val ADMIN_EMAIL_MIXED_CASE = "Sample-Admin@Example.com"
        const val ADMIN_EMAIL = "sample-admin@example.com"

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("dp")
                .withPassword("dp")
    }
}
