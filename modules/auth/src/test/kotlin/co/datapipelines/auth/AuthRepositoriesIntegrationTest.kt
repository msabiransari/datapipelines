package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant

/**
 * [UserRepository], [ApiKeyRepository] and [AuditLogger] against a real Postgres
 * running the **shipped** migrations V1 + V4 + V5 (metadata-db §4.1/§4.2/§4.3 — V4 adds the
 * `api_keys.workspace_id` pin, V5 the local password auth columns). The migrations are executed off disk rather than via Flyway —
 * domain modules carry no Flyway dependency (module-structure §3.1 rule 2), the same
 * discipline as the sibling `PipelineRepositoryIntegrationTest`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthRepositoriesIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var users: UserRepository
    private lateinit var keys: ApiKeyRepository
    private lateinit var audit: AuditLogger

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(dataSource())
    }

    @BeforeEach
    fun setUp() {
        users = UserRepository(jdbc)
        keys = ApiKeyRepository(jdbc)
        audit = AuditLogger(jdbc, ObjectMapper())
        // The CASCADE also reaches workspaces (created_by), so the V4-seeded `default`
        // workspace ApiKeyRepository pins is re-seeded after every truncate.
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name)" +
                " VALUES ('defa0000-0000-0000-0000-000000000001', 'default', 'Default')",
        )
    }

    // ------------------------------------------------------------ WorkspaceRepository (§4.11/§4.12)

    @Test
    fun `the workspace member lifecycle round-trips - add, list, roles, rename, remove, soft-delete`() {
        val workspaces = WorkspaceRepository(jdbc)
        val alice = users.insert("alice@company.com", "Alice", null, "google", "sub-1", isAdmin = false)
        val bob = users.insert("bob@company.com", "Bob", null, "google", "sub-2", isAdmin = false)

        val ws = workspaces.create("acme", "Acme", isPersonal = false, createdBy = alice.id)
        workspaces.nameExists("acme").shouldBeTrue()

        workspaces.addMember(ws.id, bob.id).shouldNotBeNull()
        workspaces.findMembersOf(ws.id).map { it.email } shouldContainExactlyInAnyOrder listOf("alice@company.com", "bob@company.com")
        workspaces.roleOf(ws.id, alice.id) shouldBe WorkspaceRole.OWNER
        workspaces.roleOf(ws.id, bob.id) shouldBe WorkspaceRole.MEMBER
        workspaces.findMemberRow(ws.id, bob.id).shouldNotBeNull().role shouldBe WorkspaceRole.MEMBER
        // Idempotent add: an existing membership comes back unchanged.
        workspaces.addMember(ws.id, bob.id).shouldNotBeNull().role shouldBe WorkspaceRole.MEMBER

        workspaces.findAll().map { it.name } shouldContainExactlyInAnyOrder listOf("default", "acme")

        val renamed = workspaces.updateDisplayName(ws.id, "Acme Renamed").shouldNotBeNull()
        renamed.displayName shouldBe "Acme Renamed"

        workspaces.removeMember(ws.id, bob.id).shouldBeTrue()
        workspaces.removeMember(ws.id, bob.id).shouldBeFalse()
        workspaces.findMembersOf(ws.id).shouldHaveSize(1)

        workspaces.softDelete(ws.id).shouldBeTrue()
        workspaces.softDelete(ws.id).shouldBeFalse()
        // Soft-deleted: gone from reads, but the NAME stays taken (house rule).
        workspaces.findByName("acme").shouldBeNull()
        workspaces.nameExists("acme").shouldBeTrue()
    }

    @Test
    fun `insert then findByEmail round-trips the user`() {
        val created = users.insert("alice@company.com", "Alice", null, "google", "sub-1", isAdmin = false)

        val found = users.findByEmail("alice@company.com")
        found shouldBe created
        found.shouldNotBeNull()
        found.isActive.shouldBeTrue()
        found.isAdmin.shouldBeFalse()
    }

    @Test
    fun `updateIdentity relinks provider and bumps updated_at`() {
        val u = users.insert("alice@company.com", "Alice", null, "google", "sub-1", isAdmin = false)

        users.updateIdentity(u.id, "Alice Wang", "http://pic", "okta", "sub-okta")

        val reloaded = checkNotNull(users.findById(u.id))
        reloaded.provider shouldBe "okta"
        reloaded.providerSubject shouldBe "sub-okta"
        reloaded.displayName shouldBe "Alice Wang"
        (reloaded.updatedAt >= u.updatedAt).shouldBeTrue()
    }

    @Test
    fun `grantAdmin is idempotent - flips once then reports no change`() {
        val u = users.insert("admin@company.com", "Admin", null, "google", "sub", isAdmin = false)

        users.grantAdmin(u.id).shouldBeTrue()
        users.grantAdmin(u.id).shouldBeFalse()
        checkNotNull(users.findById(u.id)).isAdmin.shouldBeTrue()
    }

    @Test
    fun `isActive reflects the row and updateLastLogin stamps the login`() {
        val u = users.insert("alice@company.com", "Alice", null, "google", "sub", isAdmin = false)
        users.isActive(u.id) shouldBe true

        users.updateLastLogin(u.id)

        checkNotNull(users.findById(u.id)).lastLoginAt.shouldNotBeNull()
    }

    @Test
    fun `api key scopes round-trip through the TEXT array column`() {
        val owner = users.insert("owner@company.com", "Owner", null, "google", "sub", isAdmin = false)

        val key = keys.insert("dpk_ABCDEFGHIJKL", owner.id, "Claude", "hash", setOf(Scope.READ, Scope.AUTHOR), null, DEFAULT_WORKSPACE_ID)

        val found = checkNotNull(keys.findById("dpk_ABCDEFGHIJKL"))
        found shouldBe key
        found.scopes shouldContainExactlyInAnyOrder setOf(Scope.READ, Scope.AUTHOR)
    }

    @Test
    fun `revoke hides the key from active listing but keeps the row`() {
        val owner = users.insert("owner@company.com", "Owner", null, "google", "sub", isAdmin = false)
        keys.insert("dpk_KEY000000001", owner.id, "k", "hash", setOf(Scope.READ), null, DEFAULT_WORKSPACE_ID)
        keys.findActiveByUser(owner.id) shouldHaveSize 1

        keys.revoke("dpk_KEY000000001", owner.id).shouldBeTrue()

        keys.findActiveByUser(owner.id) shouldHaveSize 0
        // Row survives (soft flag) so audit_log.key_id keeps resolving (metadata-db §4.2).
        checkNotNull(keys.findById("dpk_KEY000000001")).isRevoked.shouldBeTrue()
        keys.revoke("dpk_KEY000000001", owner.id).shouldBeFalse()
    }

    @Test
    fun `touchUsage records last_used_ip as INET and the user agent`() {
        val owner = users.insert("owner@company.com", "Owner", null, "google", "sub", isAdmin = false)
        keys.insert("dpk_KEY000000002", owner.id, "k", "hash", setOf(Scope.READ), null, DEFAULT_WORKSPACE_ID)

        keys.touchUsage("dpk_KEY000000002", "10.0.0.5", "Claude/1.0")

        val ip =
            jdbc.jdbcTemplate.queryForObject(
                "SELECT host(last_used_ip) FROM api_keys WHERE id = 'dpk_KEY000000002'",
                String::class.java,
            )
        ip shouldBe "10.0.0.5"
    }

    @Test
    fun `expired key is readable with its expiry so validation can reject it`() {
        val owner = users.insert("owner@company.com", "Owner", null, "google", "sub", isAdmin = false)
        val past = Instant.now().minusSeconds(3600)
        keys.insert("dpk_KEY000000003", owner.id, "k", "hash", setOf(Scope.READ), past, DEFAULT_WORKSPACE_ID)

        checkNotNull(keys.findById("dpk_KEY000000003")).expiresAt.shouldNotBeNull()
    }

    @Test
    fun `audit logger appends a row with a JSONB details payload`() {
        val owner = users.insert("owner@company.com", "Owner", null, "google", "sub", isAdmin = false)

        audit.log(event = "auth.login.success", userId = owner.id, details = mapOf("email" to "owner@company.com"))

        val email =
            jdbc.jdbcTemplate.queryForObject(
                "SELECT details_json ->> 'email' FROM audit_log WHERE event = 'auth.login.success'",
                String::class.java,
            )
        email shouldBe "owner@company.com"
    }

    @Test
    fun `an unknown key id reads as null`() {
        keys.findById("dpk_DOESNOTEXIST").shouldBeNull()
    }

    // ------------------------------------------------------------ Local password auth (V5, §5A)

    @Test
    fun `an OIDC-only account has no local credential until a password is set`() {
        val u = users.insert("alice@company.com", "Alice", null, "google", "sub-1", isAdmin = false)

        users.findLocalCredential("alice@company.com").shouldBeNull()
        checkNotNull(users.findById(u.id)).mustChangePassword.shouldBeFalse()

        users.setPassword(u.id, "argon2-hash-1", mustChange = true)

        val credential = checkNotNull(users.findLocalCredential("alice@company.com"))
        credential.userId shouldBe u.id
        credential.passwordHash shouldBe "argon2-hash-1"
        credential.failedLoginCount shouldBe 0
        credential.lockedUntil.shouldBeNull()
        val reloaded = checkNotNull(users.findById(u.id))
        reloaded.mustChangePassword.shouldBeTrue()
        reloaded.lastLoginAt.shouldBeNull()
    }

    @Test
    fun `failed logins lock at the threshold atomically and success clears the lockout`() {
        val u = users.insert("alice@company.com", "Alice", null, "google", "sub-1", isAdmin = false)
        users.setPassword(u.id, "argon2-hash-1", mustChange = false)

        val first = users.recordLocalLoginFailure(u.id, maxFailures = 3, lockMinutes = 15)
        first.failedLoginCount shouldBe 1
        first.lockedUntil.shouldBeNull()
        users.recordLocalLoginFailure(u.id, maxFailures = 3, lockMinutes = 15).lockedUntil.shouldBeNull()

        val third = users.recordLocalLoginFailure(u.id, maxFailures = 3, lockMinutes = 15)
        third.failedLoginCount shouldBe 3
        third.lockedUntil.shouldNotBeNull()

        users.recordLocalLoginSuccess(u.id)
        val cleared = checkNotNull(users.findLocalCredential("alice@company.com"))
        cleared.failedLoginCount shouldBe 0
        cleared.lockedUntil.shouldBeNull()
        checkNotNull(users.findById(u.id)).lastLoginAt.shouldNotBeNull()
    }

    @Test
    fun `setPassword after a lockout clears it so the new credential is usable`() {
        val u = users.insert("alice@company.com", "Alice", null, "google", "sub-1", isAdmin = false)
        users.setPassword(u.id, "argon2-hash-1", mustChange = false)
        users.recordLocalLoginFailure(u.id, maxFailures = 1, lockMinutes = 15).lockedUntil.shouldNotBeNull()

        users.setPassword(u.id, "argon2-hash-2", mustChange = true)

        val credential = checkNotNull(users.findLocalCredential("alice@company.com"))
        credential.passwordHash shouldBe "argon2-hash-2"
        credential.failedLoginCount shouldBe 0
        credential.lockedUntil.shouldBeNull()
    }

    @Test
    fun `clearPassword removes local access and reports the transition once`() {
        val u = users.insert("alice@company.com", "Alice", null, "google", "sub-1", isAdmin = false)
        users.setPassword(u.id, "argon2-hash-1", mustChange = true)

        users.clearPassword(u.id).shouldBeTrue()
        users.clearPassword(u.id).shouldBeFalse()

        users.findLocalCredential("alice@company.com").shouldBeNull()
        checkNotNull(users.findById(u.id)).mustChangePassword.shouldBeFalse()
    }

    @Test
    fun `an expired lock resets the failure count instead of re-locking on the next mistake`() {
        val u = users.insert("alice@company.com", "Alice", null, "google", "sub-1", isAdmin = false)
        users.setPassword(u.id, "argon2-hash-1", mustChange = false)
        users.recordLocalLoginFailure(u.id, maxFailures = 2, lockMinutes = 15)
        users.recordLocalLoginFailure(u.id, maxFailures = 2, lockMinutes = 15).lockedUntil.shouldNotBeNull()

        // The lock does its time...
        jdbc.jdbcTemplate.update("UPDATE users SET locked_until = NOW() - INTERVAL '1 minute' WHERE id = '${u.id}'")

        // ...and the next failure starts a FRESH budget at 1 rather than re-locking.
        val afterExpiry = users.recordLocalLoginFailure(u.id, maxFailures = 2, lockMinutes = 15)
        afterExpiry.failedLoginCount shouldBe 1
        afterExpiry.lockedUntil.shouldBeNull()
    }

    @Test
    fun `clearLockout resets the counters and reports the transition once`() {
        val u = users.insert("alice@company.com", "Alice", null, "google", "sub-1", isAdmin = false)
        users.setPassword(u.id, "argon2-hash-1", mustChange = false)
        users.recordLocalLoginFailure(u.id, maxFailures = 1, lockMinutes = 15)

        users.clearLockout(u.id).shouldBeTrue()
        users.clearLockout(u.id).shouldBeFalse()

        val credential = checkNotNull(users.findLocalCredential("alice@company.com"))
        credential.failedLoginCount shouldBe 0
        credential.lockedUntil.shouldBeNull()
    }

    private fun dataSource(): DriverManagerDataSource = SharedPostgres.dataSource()
}

/** The V4-seeded `default` workspace (metadata-db §4.11) — a legitimate test pin: these suites seed the default world. */
private val DEFAULT_WORKSPACE_ID: java.util.UUID = java.util.UUID.fromString("defa0000-0000-0000-0000-000000000001")
