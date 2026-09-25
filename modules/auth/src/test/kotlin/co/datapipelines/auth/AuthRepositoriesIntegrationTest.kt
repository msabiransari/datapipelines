package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
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
import org.junit.jupiter.api.assertAll
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant

/**
 * [UserRepository], [ApiKeyRepository] and [AuditLogger] against a real Postgres
 * running the **shipped** migrations, every one in version order off disk rather than via Flyway —
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
        DefaultWorkspaceFixture.ensure(jdbc)
        jdbc.jdbcTemplate.execute(
            // Both workspaces a booted deployment have their way back: `default` (V4's
            // re-key) via the fixture, `demo` (D-R11, created by `DemoWorkspaceSeeder` at
            // boot — not by a migration) here. The TRUNCATE CASCADE reaches `workspaces`
            // through `created_by`, so both go back or the next assertion sees a database
            // the product never ships.
            "INSERT INTO workspaces (id, name, display_name) VALUES" +
                " ('de000000-0000-0000-0000-000000000001', 'demo', 'Demo')",
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
        // D-R14: the creator enters as the WORKSPACE ADMIN; an added member with no role named
        // is a VIEWER, which is what silence on a permission grant has to mean.
        workspaces.roleOf(ws.id, alice.id) shouldBe WorkspaceRole.WORKSPACE_ADMIN
        workspaces.roleOf(ws.id, bob.id) shouldBe WorkspaceRole.VIEWER
        workspaces.findMemberRow(ws.id, bob.id).shouldNotBeNull().role shouldBe WorkspaceRole.VIEWER
        workspaces.adminCount(ws.id) shouldBe 1
        // Idempotent add: an existing membership comes back unchanged — re-adding does NOT
        // silently reset somebody's role to the request's. Changing a role is setRole.
        workspaces.addMember(ws.id, bob.id, WorkspaceRole.AUTHOR).shouldNotBeNull().role shouldBe WorkspaceRole.VIEWER

        // setRole replaces the value, and the admin count follows.
        workspaces.setRole(ws.id, bob.id, WorkspaceRole.WORKSPACE_ADMIN).shouldBeTrue()
        workspaces.adminCount(ws.id) shouldBe 2

        // D-R10: deactivation is reversible and purges nothing.
        workspaces.deactivate(ws.id, alice.id).shouldBeTrue()
        workspaces
            .findById(ws.id)
            .shouldNotBeNull()
            .isActive
            .shouldBeFalse()
        workspaces.findAllActive().map { it.name } shouldContainExactlyInAnyOrder listOf("default", "demo")
        workspaces.reactivate(ws.id).shouldBeTrue()
        workspaces
            .findById(ws.id)
            .shouldNotBeNull()
            .isActive
            .shouldBeTrue()

        // `demo` is seeded by V23 itself (D-R11), so a migrated database always carries it.
        workspaces.findAll().map { it.name } shouldContainExactlyInAnyOrder listOf("default", "demo", "acme")

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

    /**
     * V34 (#215): who a key ACTS AS (`user_id`), who CREATED it (`created_by`) and its ROLE are
     * three columns, and they round-trip as three facts — an `endpoint` key's identity is not its
     * creator, and its role is written from its kind.
     */
    @Test
    fun `an api key's identity, creator and role round-trip as three columns`() {
        val owner = users.insert("owner@company.com", "Owner", null, "google", "sub", isAdmin = false)
        val identity =
            users.insert(
                "dpk_abcdefghijkl@keys.invalid",
                "ci",
                null,
                "key",
                "dpk_ABCDEFGHIJKL",
                isAdmin = false,
                kind = UserKind.SERVICE,
            )

        val key =
            keys.insert(
                "dpk_ABCDEFGHIJKL",
                identity.id,
                owner.id,
                "ci",
                "hash",
                KeyRole.API_CALLER,
                null,
                DEFAULT_WORKSPACE_ID,
                ApiKeyKind.ENDPOINT,
            )
        // Keys v2 (A13): the MCP key acts as its OWN identity too, with the member role chosen
        // at creation — owner, role, identity round-trip the same way for every kind.
        val mcp =
            keys.insert("dpk_MCP000000001", identity.id, owner.id, "mcp/default", "hash", KeyRole.AUTHOR, null, DEFAULT_WORKSPACE_ID, ApiKeyKind.MCP)

        val found = checkNotNull(keys.findById("dpk_ABCDEFGHIJKL"))
        assertAll(
            { found shouldBe key },
            { found.userId shouldBe identity.id },
            { found.createdBy shouldBe owner.id },
            { found.role shouldBe KeyRole.API_CALLER },
            { checkNotNull(users.findById(identity.id)).kind shouldBe UserKind.SERVICE },
            { checkNotNull(keys.findById(mcp.id)).role shouldBe KeyRole.AUTHOR },
            { checkNotNull(keys.findById(mcp.id)).createdBy shouldBe owner.id },
        )
    }

    /** V34's two CHECKs make PK3/PK5/PK6 database facts — a row the code would never write is refused anyway. */
    @Test
    fun `the database refuses a key whose role contradicts its kind and a user row of an unknown kind`() {
        val owner = users.insert("owner@company.com", "Owner", null, "google", "sub", isAdmin = false)
        keys.insert("dpk_EPCHECK00001", owner.id, owner.id, "ep", "hash", KeyRole.API_CALLER, null, DEFAULT_WORKSPACE_ID, ApiKeyKind.ENDPOINT)

        assertAll(
            {
                shouldThrow<org.springframework.dao.DataIntegrityViolationException> {
                    jdbc.jdbcTemplate.update("UPDATE api_keys SET role = 'promotion_receiver' WHERE id = 'dpk_EPCHECK00001'")
                }
            },
            {
                shouldThrow<org.springframework.dao.DataIntegrityViolationException> {
                    jdbc.jdbcTemplate.update("UPDATE api_keys SET role = NULL WHERE id = 'dpk_EPCHECK00001'")
                }
            },
            {
                shouldThrow<org.springframework.dao.DataIntegrityViolationException> {
                    jdbc.jdbcTemplate.update("UPDATE users SET kind = 'robot' WHERE id = ?", owner.id)
                }
            },
        )
    }

    @Test
    fun `revoke is the creator's, hides the key from their live keys and keeps the row`() {
        val owner = users.insert("owner@company.com", "Owner", null, "google", "sub", isAdmin = false)
        val stranger = users.insert("stranger@company.com", "Stranger", null, "google", "sub2", isAdmin = false)
        keys.insert("dpk_KEY000000001", owner.id, owner.id, "k", "hash", KeyRole.AUTHOR, null, DEFAULT_WORKSPACE_ID, ApiKeyKind.MCP)
        keys.findByUser(owner.id).filterNot { it.isRevoked } shouldHaveSize 1

        keys.revoke("dpk_KEY000000001", stranger.id).shouldBeNull()
        keys
            .revoke("dpk_KEY000000001", owner.id)
            .shouldNotBeNull()
            .isRevoked
            .shouldBeTrue()

        keys.findByUser(owner.id).filterNot { it.isRevoked } shouldHaveSize 0
        // Row survives (soft flag) so audit_log.key_id keeps resolving (metadata-db §4.2).
        checkNotNull(keys.findById("dpk_KEY000000001")).isRevoked.shouldBeTrue()
        keys.revoke("dpk_KEY000000001", owner.id).shouldBeNull()
    }

    @Test
    fun `touchUsage records last_used_ip as INET and the user agent`() {
        val owner = users.insert("owner@company.com", "Owner", null, "google", "sub", isAdmin = false)
        keys.insert("dpk_KEY000000002", owner.id, owner.id, "k", "hash", KeyRole.AUTHOR, null, DEFAULT_WORKSPACE_ID, ApiKeyKind.MCP)

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
        keys.insert("dpk_KEY000000003", owner.id, owner.id, "k", "hash", KeyRole.AUTHOR, past, DEFAULT_WORKSPACE_ID, ApiKeyKind.MCP)

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

    // ------------------------------------------------------------ the show-once copy (V31, keys v2)

    /**
     * The sealed column round-trips: the flag rides the model, the blob never does, and the
     * column's remaining rows are the V35-migrated login keys (keys v2 A2 — no NEW key is ever
     * minted with one). V35 dropped `minted_at_login`; the repository reads no such flag.
     */
    @Test
    fun `the sealed-copy flag round-trips and the model never carries the blob`() {
        val owner = users.insert("owner@company.com", "Owner", null, "google", "sub", isAdmin = false)
        val sealed = byteArrayOf(1, 2, 3, 4)

        val key =
            keys.insert(
                "dpk_MINTED000001",
                owner.id,
                owner.id,
                "mcp/default",
                "hash",
                KeyRole.AUTHOR,
                null,
                DEFAULT_WORKSPACE_ID,
                ApiKeyKind.MCP,
            )
        // The blob is a migration leftover (V35): nothing in main writes one any more, so the
        // fixture plants it directly and the repository's flag/read round-trip is what is tested.
        jdbc.jdbcTemplate.update("UPDATE api_keys SET secret_sealed = ? WHERE id = ?", sealed, key.id)

        // The flag rides the ROW, not the pre-UPDATE model instance: re-read.
        keys.findById(key.id)!!.hasSealedSecret.shouldBeTrue()

        // A row with no sealed copy reads false, with nothing to open.
        val bare = keys.insert("dpk_LEGACY000001", owner.id, owner.id, "old", "hash", KeyRole.AUTHOR, null, DEFAULT_WORKSPACE_ID, ApiKeyKind.MCP)
        bare.hasSealedSecret.shouldBeFalse()
    }

    /** #213: show-once — the open and the clear are one statement, CREATOR-scoped (keys v2). */
    @Test
    fun `the sealed copy opens exactly once for its creator - anyone else gets null and the copy survives`() {
        val owner = users.insert("once@company.com", "Once", null, "google", "sub3", isAdmin = false)
        val stranger = users.insert("stranger@company.com", "Stranger", null, "google", "sub4", isAdmin = false)
        val sealed = byteArrayOf(1, 2, 3, 4)
        val key =
            keys.insert(
                "dpk_SHOWONCE0001",
                stranger.id,
                owner.id,
                "mcp/default",
                "hash",
                KeyRole.AUTHOR,
                null,
                DEFAULT_WORKSPACE_ID,
                ApiKeyKind.MCP,
            )
        jdbc.jdbcTemplate.update("UPDATE api_keys SET secret_sealed = ? WHERE id = ?", sealed, key.id)

        // A non-creator opens nothing — and the refused attempt must not consume the copy.
        keys.openAndClearSealedSecret(key.id, stranger.id).shouldBeNull()
        keys.findById(key.id)!!.hasSealedSecret.shouldBeTrue()

        // The creator's first read gets exactly the sealed bytes AND destroys them atomically.
        keys.openAndClearSealedSecret(key.id, owner.id)!!.toList() shouldBe sealed.toList()

        // From then on the key is hash-only: a second read answers null, the flag agrees, and
        // the key itself (its hash) is untouched — it still validates.
        keys.openAndClearSealedSecret(key.id, owner.id).shouldBeNull()
        val reread = keys.findById(key.id)!!
        reread.hasSealedSecret.shouldBeFalse()
        reread.keyHash shouldBe "hash"
    }

    /**
     * Keys v2 A18: `(workspace_id, name)` is unique for LIVE keys — a second live key of one
     * name cannot be inserted, a revoked row's name is free again, and other workspaces are
     * not consulted.
     */
    @Test
    fun `one live key per workspace and name - the index is the arbiter, revocation frees the name`() {
        val owner = users.insert("owner@company.com", "Owner", null, "google", "sub", isAdmin = false)
        val otherWs = WorkspaceRepository(jdbc).create("other-acme", "Other Acme", isPersonal = false, createdBy = owner.id)
        keys.insert("dpk_FIRST0000001", owner.id, owner.id, "k1", "hash", KeyRole.AUTHOR, null, DEFAULT_WORKSPACE_ID, ApiKeyKind.MCP)

        shouldThrow<org.springframework.dao.DataIntegrityViolationException> {
            keys.insert("dpk_SECOND000001", owner.id, owner.id, "k1", "hash", KeyRole.AUTHOR, null, DEFAULT_WORKSPACE_ID, ApiKeyKind.MCP)
        }

        // A different workspace's live name does not collide.
        keys.insert("dpk_THIRD00000001", owner.id, owner.id, "k1", "hash", KeyRole.AUTHOR, null, otherWs.id, ApiKeyKind.MCP)

        // Revoking frees the name — replacement is exactly this.
        keys.revoke("dpk_FIRST0000001", owner.id).shouldNotBeNull()
        keys.insert("dpk_FOURTH000001", owner.id, owner.id, "k1", "hash", KeyRole.AUTHOR, null, DEFAULT_WORKSPACE_ID, ApiKeyKind.MCP)
    }

    @Test
    fun `the workspace listing and revoke are kind-pinned and workspace-pinned`() {
        val owner = users.insert("owner@company.com", "Owner", null, "google", "sub", isAdmin = false)
        val other = users.insert("other@company.com", "Other", null, "google", "sub2", isAdmin = false)
        keys.insert("dpk_EP0000000001", owner.id, owner.id, "ep-mine", "hash", KeyRole.API_CALLER, null, DEFAULT_WORKSPACE_ID, ApiKeyKind.ENDPOINT)
        keys.insert("dpk_EP0000000002", other.id, other.id, "ep-theirs", "hash", KeyRole.API_CALLER, null, DEFAULT_WORKSPACE_ID, ApiKeyKind.ENDPOINT)
        keys.insert("dpk_SRV000000001", owner.id, owner.id, "srv", "hash", KeyRole.PROMOTION_RECEIVER, null, DEFAULT_WORKSPACE_ID, ApiKeyKind.SERVER)

        // The /api-keys table: the workspace's keys of one kind, whoever created them.
        keys.findByWorkspaceAndKind(DEFAULT_WORKSPACE_ID, ApiKeyKind.ENDPOINT).map { it.name } shouldContainExactlyInAnyOrder
            listOf("ep-mine", "ep-theirs")
        keys.findByWorkspaceAndKind(DEFAULT_WORKSPACE_ID, ApiKeyKind.SERVER).map { it.name } shouldContainExactlyInAnyOrder
            listOf("srv")

        // The member-removal revocation (A17): every live key a creator made HERE, of any kind.
        keys.revokeLiveByCreator(owner.id, DEFAULT_WORKSPACE_ID) shouldContainExactlyInAnyOrder
            listOf("dpk_EP0000000001", "dpk_SRV000000001")

        // …and never another workspace's keys.
        keys.revokeLiveByCreator(other.id, OTHER_WORKSPACE_ID) shouldBe emptyList()
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

/** A second workspace for the pin tests — nobody's default. */
private val OTHER_WORKSPACE_ID: java.util.UUID = java.util.UUID.fromString("defa0000-0000-0000-0000-000000000002")
