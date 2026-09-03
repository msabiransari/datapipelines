package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

/**
 * AUTH-SEC-10 / AU-API-4 / AU-API-5: the audited administrative operations against a
 * real Postgres running the shipped schema — every §10.1 event lands in `audit_log`,
 * and each mutation evicts the liveness cache so a deactivated user is dead on the
 * next request rather than at TTL expiry.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserAdminIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var users: UserRepository
    private lateinit var cache: AuthCache
    private lateinit var service: UserService

    private val actorId = UUID.randomUUID()

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(dataSource())
        // V1 + V4: the login/session path now resolves workspaces (slice 2).
    }

    @BeforeEach
    fun setUp() {
        users = UserRepository(jdbc)
        cache = AuthCache(AuthProperties())
        service = UserService(users, cache, AuthProperties(), AuditLogger(jdbc, ObjectMapper()))
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        jdbc.jdbcTemplate.execute("TRUNCATE audit_log CASCADE")
    }

    /** `(provider, provider_subject)` is unique (metadata-db §4.1) — derive the subject from the email. */
    private fun newUser(
        email: String = "alice@company.com",
        admin: Boolean = false,
    ): User {
        val local = email.substringBefore('@')
        return users.insert(email, local.replaceFirstChar(Char::titlecase), null, "keycloak", "sub-$local", isAdmin = admin)
    }

    private fun events(): List<String> = jdbc.jdbcTemplate.queryForList("SELECT event FROM audit_log", String::class.java)

    private fun auditedTarget(event: String): UUID? =
        jdbc.jdbcTemplate
            .queryForList("SELECT user_id FROM audit_log WHERE event = ?", UUID::class.java, event)
            .firstOrNull()

    @Test
    fun `deactivate writes auth-user-deactivated and kills the liveness cache immediately`() {
        val user = newUser()
        service.isActive(user.id).shouldBeTrue() // primes the 60s cache

        service.deactivate(user.id, actorId).shouldBeTrue()

        // No TTL wait: the mutation evicted the entry on this instance.
        service.isActive(user.id).shouldBeFalse()
        events() shouldContain "auth.user.deactivated"
        auditedTarget("auth.user.deactivated") shouldBe user.id
    }

    @Test
    fun `activate writes auth-user-activated and only on a real transition`() {
        val user = newUser()
        service.deactivate(user.id, actorId)

        service.activate(user.id, actorId).shouldBeTrue()
        service.activate(user.id, actorId).shouldBeFalse() // already active — no event

        events().count { it == "auth.user.activated" } shouldBe 1
        service.isActive(user.id).shouldBeTrue()
    }

    @Test
    fun `grant and revoke admin are audited with the acting admin recorded`() {
        val user = newUser()

        service.grantAdmin(user.id, actorId).shouldBeTrue()
        checkNotNull(users.findById(user.id)).isAdmin.shouldBeTrue()
        service.revokeAdmin(user.id, actorId).shouldBeTrue()
        checkNotNull(users.findById(user.id)).isAdmin.shouldBeFalse()

        events() shouldContain "auth.user.admin_granted"
        events() shouldContain "auth.user.admin_revoked"
        val actor =
            jdbc.jdbcTemplate.queryForObject(
                "SELECT details_json ->> 'actor' FROM audit_log WHERE event = 'auth.user.admin_revoked'",
                String::class.java,
            )
        actor shouldBe actorId.toString()
    }

    @Test
    fun `a repeated admin grant is idempotent and emits no second event`() {
        val user = newUser()

        service.grantAdmin(user.id, actorId).shouldBeTrue()
        service.grantAdmin(user.id, actorId).shouldBeFalse()

        events().count { it == "auth.user.admin_granted" } shouldBe 1
    }

    @Test
    fun `search matches email and display name case-insensitively and pages stably`() {
        newUser(email = "alice@company.com")
        newUser(email = "bob@company.com")
        newUser(email = "carol@other.com")

        service.search("COMPANY.COM", offset = 0, limit = 10).map { it.email } shouldContainExactly
            listOf("alice@company.com", "bob@company.com")
        service.search("", offset = 0, limit = 2) shouldHaveSize 2
        service.search("", offset = 2, limit = 2).map { it.email } shouldContainExactly listOf("carol@other.com")
        service.search("alice", offset = 0, limit = 10) shouldHaveSize 1
    }

    @Test
    fun `theme preference round-trips and null means follow the deployment default`() {
        val user = newUser()
        checkNotNull(users.findById(user.id)).themePreference.shouldBeNull()

        service.setThemePreference(user.id, "saas")
        checkNotNull(users.findById(user.id)).themePreference shouldBe "saas"

        service.setThemePreference(user.id, null)
        checkNotNull(users.findById(user.id)).themePreference.shouldBeNull()
    }

    @Test
    fun `emails are stored and matched lowercase so provider casing cannot fork a row`() {
        val created = service.findOrCreateByEmail("Alice@Company.COM", "Alice", null, "keycloak", "sub-1")
        val again = service.findOrCreateByEmail("ALICE@company.com", "Alice Wang", null, "okta", "sub-2")

        again.id shouldBe created.id
        created.email shouldBe "alice@company.com"
        jdbc.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Int::class.java) shouldBe 1
        again.provider shouldBe "okta"
    }

    private fun dataSource(): DriverManagerDataSource = SharedPostgres.dataSource()
}
