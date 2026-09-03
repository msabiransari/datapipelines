package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

/**
 * The §4.5 system service account (R7) against a real database — the sibling of
 * [BootstrapActorProvisioningIntegrationTest], for the NON-human actor.
 *
 * The claims under test are the ones the design rests on, each stated as a fact about the
 * stored row or about what a caller can do to it:
 *
 * 1. The row is created once, with the reserved identity, and is **not an admin**.
 * 2. Re-provisioning (a restart) touches nothing — the id, the columns and `updated_at` are
 *    byte-identical, and no `auth.user.admin_granted` event is ever written for it.
 * 3. `systemActor()` is the one lookup, and it fails LOUDLY when the row is absent rather
 *    than returning null for a caller to mis-handle at a foreign key.
 * 4. **Login is disabled by construction**: the local-password paths refuse the row, so it
 *    can never acquire the credential that login would check.
 *
 * The audit sink is the real `audit_log` table for the same reason the bootstrap suite gives:
 * the invariant is "this row never produces a grant event", and only counting rows can show
 * that a call is absent rather than merely unstubbed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SystemActorProvisioningIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var users: UserRepository

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(dataSource())
    }

    @BeforeEach
    fun setUp() {
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        jdbc.jdbcTemplate.execute("TRUNCATE audit_log")
        users = UserRepository(jdbc)
    }

    /** The production wiring, with the real audit writer. */
    private fun service(bootstrapAdmin: String? = null): UserService {
        val properties = AuthProperties(bootstrapAdminEmail = bootstrapAdmin)
        return UserService(users, AuthCache(properties), properties, AuditLogger(jdbc, ObjectMapper()))
    }

    // -------------------------------------------------------------------- provisioning

    @Test
    fun `provisioning creates the reserved row - system provider, unresolvable address, not an admin`() {
        val actor = service().provisionSystemActor()

        actor.email shouldBe UserService.SYSTEM_ACTOR_EMAIL
        actor.provider shouldBe UserService.SYSTEM_PROVIDER
        actor.providerSubject shouldBe UserService.SYSTEM_ACTOR_SUBJECT
        actor.displayName shouldBe UserService.SYSTEM_ACTOR_DISPLAY_NAME
        actor.isActive shouldBe true
        // An actor, not an authority: every path that stamps it already knows its workspace.
        actor.isAdmin shouldBe false
        actor.hasLocalPassword shouldBe false
        userCount() shouldBe 1
        adminGrantedEvents() shouldBe 0
    }

    @Test
    fun `the address cannot resolve - the reserved TLD is the whole mechanism`() {
        // RFC 2606: `.invalid` is reserved and guaranteed never to resolve. Pinned as an
        // assertion because the safety of a mailbox nobody owns rests entirely on it.
        UserService.SYSTEM_ACTOR_EMAIL.substringAfterLast('.') shouldBe "invalid"
    }

    @Test
    fun `a restart re-provisions idempotently and touches nothing on the existing row`() {
        val service = service()
        val first = service.provisionSystemActor()
        val before = rowSnapshot()

        val again = service.provisionSystemActor()

        again.id shouldBe first.id
        // Not merely "no new row" — no WRITE at all: updated_at is in the snapshot.
        rowSnapshot() shouldBe before
        userCount() shouldBe 1
        adminGrantedEvents() shouldBe 0
    }

    @Test
    fun `the actor is provisioned even when no bootstrap admin is configured`() {
        // Unlike provisionBootstrapActor, this one has no config precondition: the row is a
        // referential precondition of the schema, not a feature an operator opts into.
        val actor = service(bootstrapAdmin = null).provisionSystemActor()
        actor.email shouldBe UserService.SYSTEM_ACTOR_EMAIL
    }

    // -------------------------------------------------------------------- the one lookup

    @Test
    fun `systemActor returns the provisioned row`() {
        val service = service()
        val provisioned = service.provisionSystemActor()

        service.systemActor().id shouldBe provisioned.id
    }

    @Test
    fun `systemActor fails loudly when the row was never provisioned`() {
        // A caller must not receive null and stamp it into a NOT NULL foreign key; absence
        // here is a wiring bug (the seeder did not run), reported as one.
        shouldThrow<IllegalStateException> { service().systemActor() }
    }

    // -------------------------------------------------------------- login disabled by construction

    @Test
    fun `an admin cannot create a local account at the reserved address`() {
        val service = service()
        service.provisionSystemActor()
        val passwords = passwordService(service)

        val result = passwords.createLocalUser(UserService.SYSTEM_ACTOR_EMAIL, "Impostor", actorId = service.systemActor().id)

        result shouldBe LocalPasswordService.CreateResult.EmailTaken
        userCount() shouldBe 1
        localPasswordHash() shouldBe null
    }

    @Test
    fun `an admin cannot reset a password onto the system actor`() {
        val service = service()
        val actor = service.provisionSystemActor()
        val passwords = passwordService(service)

        passwords.resetPassword(actor.id, actorId = actor.id) shouldBe null

        localPasswordHash() shouldBe null
        service.systemActor().hasLocalPassword shouldBe false
    }

    @Test
    fun `an ordinary account CAN be reset - the refusal is about this row, not about resets`() {
        // Falsification: the two refusals above must be caused by the reserved identity, not by
        // a broken fixture that makes every reset return null.
        val service = service()
        val ordinary = service.findOrCreateByEmail("bob@example.com", "Bob", null, "google", "bob-sub")
        val passwords = passwordService(service)

        passwords.resetPassword(ordinary.id, actorId = ordinary.id) shouldNotBe null
    }

    // -------------------------------------------------------------------- helpers

    private fun passwordService(service: UserService): LocalPasswordService =
        LocalPasswordService(
            users,
            service,
            Argon2SecretHasher(),
            AuthCache(AuthProperties()),
            AuditLogger(jdbc, ObjectMapper()),
            AuthProperties(),
        )

    private fun adminGrantedEvents(): Int =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE event = 'auth.user.admin_granted'",
                emptyMap<String, Any>(),
                Int::class.java,
            ),
        )

    private fun userCount(): Int = checkNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM users", emptyMap<String, Any>(), Int::class.java))

    private fun localPasswordHash(): String? =
        jdbc
            .queryForList(
                "SELECT password_hash FROM users WHERE email = :email",
                mapOf("email" to UserService.SYSTEM_ACTOR_EMAIL),
                String::class.java,
            ).firstOrNull()

    private fun rowSnapshot(): List<Map<String, Any?>> =
        jdbc.queryForList(
            "SELECT id, email, display_name, profile_picture_url, provider, provider_subject," +
                " is_active, is_admin, theme_preference, created_at, updated_at, last_login_at" +
                " FROM users ORDER BY email",
            emptyMap<String, Any>(),
        )

    private fun dataSource(): DriverManagerDataSource = SharedPostgres.dataSource()
}
