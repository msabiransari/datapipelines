package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * [WorkspaceRepository] + [WorkspaceService] against a real Postgres running the shipped
 * migrations through V23: the capability flags as the DATABASE stores them, the
 * `admin → author` constraint, the last-admin rule, deactivation, and membership ordering.
 *
 * It was `WorkspaceProvisioningIntegrationTest` until RBAC round 1 removed the provisioning
 * modes it existed to prove (D-R11). What survives is what still has a database answer:
 * everything the flags and the deactivation columns do.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkspaceMembershipIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var users: UserRepository
    private lateinit var workspaces: WorkspaceRepository
    private lateinit var apiKeys: ApiKeyRepository
    private val auditLogger = mockk<AuditLogger>(relaxed = true)

    private lateinit var alice: User
    private lateinit var admin: User

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(dataSource())
    }

    @BeforeEach
    fun setUp() {
        // The audit double is class-scoped (PER_CLASS instance); its recorded calls must not
        // leak across tests, or an `exactly = 1` in one test counts another test's event.
        io.mockk.clearMocks(auditLogger)
        users = UserRepository(jdbc)
        workspaces = WorkspaceRepository(jdbc)
        apiKeys = ApiKeyRepository(jdbc)
        // The CASCADE also reaches workspaces (created_by), so V4's `default` is re-seeded
        // after every truncate. `demo` is re-seeded here too — not because a migration ships
        // it (V23 deliberately does not; `DemoWorkspaceSeeder` owns it at boot) but because
        // this suite asserts the world a BOOTED deployment has, and it never boots one.
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        DefaultWorkspaceFixture.ensure(jdbc)
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name)" +
                " VALUES ('de000000-0000-0000-0000-000000000001', 'demo', 'Demo')",
        )
        alice = users.insert("alice@company.com", "Alice", null, "google", "sub-1", isAdmin = false)
        admin = users.insert("root@company.com", "Root", null, "google", "sub-2", isAdmin = true)
    }

    private fun service(cache: AuthCache = AuthCache(AuthProperties())) =
        WorkspaceService(
            workspaces,
            apiKeys,
            users,
            cache,
            null,
            auditLogger,
            WorkspaceInvitationRepository(jdbc),
            AuthProperties(),
        )

    private fun principal(
        user: User,
        superAdmin: Boolean = false,
    ) = AuthenticatedPrincipal(
        userId = user.id,
        email = user.email,
        displayName = user.displayName,
        authMethod = AuthMethod.OIDC,
        superAdmin = superAdmin,
    )

    @Test
    fun `create stores the creator as the workspace ADMIN, and the constraint makes them an author`() {
        val ws = service().create(principal(admin, superAdmin = true), "acme", "Acme")

        workspaces.roleOf(ws.id, admin.id) shouldBe WorkspaceRole.WORKSPACE_ADMIN
        workspaces.adminCount(ws.id) shouldBe 1
    }

    @Test
    fun `the database REFUSES a role outside the four - the value set is not a convention (V29)`() {
        val ws = workspaces.create("acme", "Acme", isPersonal = false, createdBy = admin.id)

        // Straight to SQL, past the service and past WorkspaceRole: the point is that the CHECK
        // is what makes "one of four values" true rather than hoped for.
        val refusal =
            shouldThrow<org.springframework.dao.DataIntegrityViolationException> {
                jdbc.jdbcTemplate.update(
                    "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, 'owner')",
                    ws.id,
                    alice.id,
                )
            }
        refusal.message
            .shouldNotBeNull()
            .contains("chk_workspace_member_role")
            .shouldBeTrue()
    }

    @Test
    fun `the role round-trips through the row - a promoter is not an author`() {
        val ws = workspaces.create("acme", "Acme", isPersonal = false, createdBy = admin.id)
        val promoterOnly = WorkspaceRole.PROMOTER

        workspaces.addMember(ws.id, alice.id, promoterOnly)

        workspaces.roleOf(ws.id, alice.id) shouldBe promoterOnly
        // …and the two permissions that separates (D5, D8): promote yes, authoring no —
        // and since 2026-09-20 release and switch are the author's, not the promoter's.
        Permission.PROMOTION_PROMOTE.satisfiedBy(promoterOnly, superAdmin = false).shouldBeTrue()
        Permission.TEMPLATE_UPDATE.satisfiedBy(promoterOnly, superAdmin = false).shouldBeFalse()
        Permission.PIPELINE_EXECUTE.satisfiedBy(promoterOnly, superAdmin = false).shouldBeFalse()
    }

    @Test
    fun `the last admin cannot be removed or demoted, against the real row count`() {
        val svc = service()
        val ws = svc.create(principal(admin, superAdmin = true), "acme", "Acme")
        // #208: nobody addresses their OWN row, so the last-admin rule is exercised by a SECOND
        // super admin acting on the workspace's only admin member (the creator).
        val root2 = users.insert("root2@company.com", "Root Two", null, "google", "sub-9", isAdmin = true)
        val actor = principal(root2, superAdmin = true)

        shouldThrow<WorkspaceLastAdminException> { svc.removeMember(actor, "acme", admin.id) }
        shouldThrow<WorkspaceLastAdminException> {
            svc.setMemberRole(actor, "acme", admin.id, WorkspaceRole.AUTHOR)
        }

        // Promote somebody else and the refusal lifts — the fix the message names.
        svc.addMember(actor, "acme", alice.email, WorkspaceRole.WORKSPACE_ADMIN)
        workspaces.adminCount(ws.id) shouldBe 2
        svc.removeMember(actor, "acme", admin.id)
        workspaces.adminCount(ws.id) shouldBe 1
    }

    /** Alice's login-minted key, as V31 holds it: one live `user` row pinned to `acme`. */
    private fun mintedKey(workspaceId: java.util.UUID): ApiKey =
        apiKeys.insert(
            id = "dpk_MEMBERKEY01",
            userId = alice.id,
            createdBy = alice.id,
            name = "mcp/acme",
            keyHash = "\$argon2id\$fixture",
            expiresAt = null,
            workspaceId = workspaceId,
            kind = ApiKeyKind.USER,
        )

    @Test
    fun `removing a member revokes their pinned user key in the same act (#200)`() {
        val svc = service()
        val actor = principal(admin, superAdmin = true)
        val ws = svc.create(actor, "acme", "Acme")
        svc.addMember(actor, "acme", alice.email, WorkspaceRole.AUTHOR)
        val key = mintedKey(ws.id)
        apiKeys.findLiveUserKey(alice.id, ws.id).shouldNotBeNull()

        svc.removeMember(actor, "acme", alice.id)

        // The membership is gone AND the key with it — REVOKED, never deleted, so
        // `audit_log.key_id` keeps resolving (metadata-db §4.2).
        workspaces.findMemberRow(ws.id, alice.id).shouldBeNull()
        apiKeys.findLiveUserKey(alice.id, ws.id).shouldBeNull()
        apiKeys
            .findById(key.id)
            .shouldNotBeNull()
            .isRevoked
            .shouldBeTrue()
        // The key event names the removal as its reason, against the revoked key's id.
        io.mockk.verify {
            auditLogger.log(
                event = "auth.api_key.revoked_by_admin",
                userId = admin.id,
                keyId = key.id,
                details =
                    match {
                        it["reason"] == "member_removed" && it["target_user_id"] == alice.id.toString()
                    },
            )
        }
        // A second sweep finds nothing live to revoke — the statement is idempotent.
        apiKeys.revokeUserKeyForWorkspace(alice.id, ws.id).shouldBeNull()
    }

    @Test
    fun `evictions outlive the commit - a reload racing the removal cannot re-cache a live key (#200 review M1)`() {
        val cache = AuthCache(AuthProperties())
        val svc = service(cache)
        val actor = principal(admin, superAdmin = true)
        val ws = svc.create(actor, "acme", "Acme")
        svc.addMember(actor, "acme", alice.email, WorkspaceRole.AUTHOR)
        val key = mintedKey(ws.id)
        // The racer: a key validation on ANOTHER connection (a fresh DataSource is never bound
        // to this thread's transaction), exactly what a request in flight does.
        val racer = ApiKeyRepository(NamedParameterJdbcTemplate(dataSource()))
        val tx = TransactionTemplate(DataSourceTransactionManager(jdbc.jdbcTemplate.dataSource!!))

        tx.execute {
            svc.removeMember(actor, "acme", alice.id)
            // Inside the transaction the UPDATE is uncommitted: the racer's read is READ
            // COMMITTED and sees the row as live, and the cache admits it — the window.
            cache
                .keyRecord(key.id) { racer.findById(it) }
                .shouldNotBeNull()
                .isRevoked
                .shouldBeFalse()
        }

        // After the commit the stale admission must be gone: the next read reloads and sees
        // the revoke. Red without the after-commit eviction (the stale live record would be
        // served for the cache TTL); green with it.
        cache
            .keyRecord(key.id) { racer.findById(it) }
            .shouldNotBeNull()
            .isRevoked
            .shouldBeTrue()
        cache.memberships(alice.id) { workspaces.membershipsOf(it) }.shouldBe(emptyList())
    }

    @Test
    fun `an admin of one workspace cannot reach another workspace member key - 404, key stays live (#200 review L2)`() {
        val svc = service()
        val root = principal(admin, superAdmin = true)
        // alice is acme's ADMIN and no member of globex; bob is globex's author with a live key.
        val bob = users.insert("bob@company.com", "Bob", null, "google", "sub-3", isAdmin = false)
        svc.create(root, "acme", "Acme")
        svc.addMember(root, "acme", alice.email, WorkspaceRole.WORKSPACE_ADMIN)
        val globex = svc.create(root, "globex", "Globex")
        svc.addMember(root, "globex", bob.email, WorkspaceRole.AUTHOR)
        val bobKey =
            apiKeys.insert(
                id = "dpk_GLOBEXKEY01",
                userId = bob.id,
                createdBy = bob.id,
                name = "mcp/globex",
                keyHash = "\$argon2id\$fixture",
                expiresAt = null,
                workspaceId = globex.id,
                kind = ApiKeyKind.USER,
            )

        shouldThrow<WorkspaceNotFoundException> { svc.revokeMemberKey(principal(alice), "globex", bob.id) }
        shouldThrow<WorkspaceNotFoundException> { svc.removeMember(principal(alice), "globex", bob.id) }
        shouldThrow<WorkspaceNotFoundException> { svc.liveUserKeyOwnerIds(principal(alice), "globex") }

        apiKeys
            .findById(bobKey.id)
            .shouldNotBeNull()
            .isRevoked
            .shouldBeFalse()
        workspaces.findMemberRow(globex.id, bob.id).shouldNotBeNull()
    }

    @Test
    fun `revokeMemberKey ends the key and KEEPS the member (#200 ruling 3) - and a member with no key is a no-op`() {
        val svc = service()
        val actor = principal(admin, superAdmin = true)
        val ws = svc.create(actor, "acme", "Acme")
        svc.addMember(actor, "acme", alice.email, WorkspaceRole.AUTHOR)
        val key = mintedKey(ws.id)

        svc.revokeMemberKey(actor, "acme", alice.id)

        // The member stays; only the credential dies.
        workspaces.findMemberRow(ws.id, alice.id).shouldNotBeNull()
        apiKeys.findLiveUserKey(alice.id, ws.id).shouldBeNull()
        io.mockk.verify {
            auditLogger.log(
                event = "auth.api_key.revoked_by_admin",
                userId = admin.id,
                keyId = key.id,
                details = match { it["reason"] == "admin_revoked" },
            )
        }
        // Idempotent: no live key means nothing revoked, nothing audited again.
        svc.revokeMemberKey(actor, "acme", alice.id)
        io.mockk.verify(exactly = 1) {
            auditLogger.log(event = "auth.api_key.revoked_by_admin", any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `a member's key of another kind or another workspace survives both verbs (#200)`() {
        val svc = service()
        val actor = principal(admin, superAdmin = true)
        val ws = svc.create(actor, "acme", "Acme")
        svc.addMember(actor, "acme", alice.email, WorkspaceRole.AUTHOR)
        val other = workspaces.create("other", "Other", isPersonal = false, createdBy = admin.id)
        // An ENDPOINT key pinned to acme and a USER key pinned elsewhere — neither is the
        // credential a membership mints, so neither may a membership revoke.
        val endpointKey =
            apiKeys.insert(
                id = "dpk_ENDPOINTK01",
                userId = alice.id,
                createdBy = alice.id,
                name = "ci",
                keyHash = "\$argon2id\$fixture",
                expiresAt = null,
                workspaceId = ws.id,
                kind = ApiKeyKind.ENDPOINT,
            )
        val foreignUserKey = mintedKey(other.id)

        svc.removeMember(actor, "acme", alice.id)

        apiKeys
            .findById(endpointKey.id)
            .shouldNotBeNull()
            .isRevoked
            .shouldBeFalse()
        apiKeys
            .findById(foreignUserKey.id)
            .shouldNotBeNull()
            .isRevoked
            .shouldBeFalse()
    }

    @Test
    fun `deactivation is reversible and purges NOTHING (D-R10)`() {
        val svc = service()
        val actor = principal(admin, superAdmin = true)
        val ws = svc.create(actor, "acme", "Acme")
        svc.addMember(actor, "acme", alice.email, WorkspaceRole.AUTHOR)

        svc.deactivate(actor, "acme").isActive.shouldBeFalse()

        // Everything it owns is still there: the row, its members, its name.
        workspaces.findById(ws.id).shouldNotBeNull()
        workspaces
            .findMembersOf(ws.id)
            .map { it.email }
            .contains(alice.email)
            .shouldBeTrue()
        workspaces.nameExists("acme").shouldBeTrue()
        // …and it is not selectable while it is off.
        workspaces.findAllActive().map { it.name } shouldContainExactly listOf("default", "demo")

        svc.reactivate(actor, "acme").isActive.shouldBeTrue()
        workspaces.findAllActive().map { it.name } shouldContainExactly listOf("acme", "default", "demo")
    }

    @Test
    fun `a member of a deactivated workspace cannot select it, and it looks like it never existed`() {
        val svc = service()
        val actor = principal(admin, superAdmin = true)
        svc.create(actor, "acme", "Acme")
        svc.addMember(actor, "acme", alice.email, WorkspaceRole.AUTHOR)
        svc.deactivate(actor, "acme")

        shouldThrow<WorkspaceNotFoundException> { svc.resolveSwitch(principal(alice), "acme") }
        shouldThrow<WorkspaceNotFoundException> { svc.resolveSwitch(principal(alice), "no-such-workspace") }
    }

    @Test
    fun `membership resolution sees exactly the user's workspaces, oldest first`() {
        // Seeded with explicit joined_at values: "first membership" is an ordering contract,
        // and two NOW() inserts could share a timestamp and make it untestable.
        val zeta = workspaces.create("zeta", "Zeta", false, admin.id)
        val alpha = workspaces.create("alpha", "Alpha", false, admin.id)
        jdbc.jdbcTemplate.update(
            "INSERT INTO workspace_members (workspace_id, user_id, role, joined_at)" +
                " VALUES (?, ?, 'author', ?), (?, ?, 'author', ?)",
            zeta.id,
            alice.id,
            java.sql.Timestamp.from(Instant.parse("2026-08-01T10:00:00Z")),
            alpha.id,
            alice.id,
            java.sql.Timestamp.from(Instant.parse("2026-08-02T10:00:00Z")),
        )

        service().memberships(alice.id).map { it.workspaceName } shouldContainExactly listOf("zeta", "alpha")
    }

    @Test
    fun `nobody administers their OWN membership - role, key and removal are refused and nothing changes (#208)`() {
        val svc = service()
        val root = principal(admin, superAdmin = true)
        val ws = svc.create(root, "acme", "Acme")
        svc.addMember(root, "acme", alice.email, WorkspaceRole.WORKSPACE_ADMIN)
        val key = mintedKey(ws.id)
        val self = principal(alice)

        shouldThrow<WorkspaceSelfMembershipException> { svc.setMemberRole(self, "acme", alice.id, WorkspaceRole.VIEWER) }
        shouldThrow<WorkspaceSelfMembershipException> { svc.revokeMemberKey(self, "acme", alice.id) }
        shouldThrow<WorkspaceSelfMembershipException> { svc.removeMember(self, "acme", alice.id) }
        // A super admin is a member like any other on their own row.
        svc.addMember(root, "acme", admin.email, WorkspaceRole.VIEWER)
        shouldThrow<WorkspaceSelfMembershipException> { svc.removeMember(root, "acme", admin.id) }

        workspaces.findMemberRow(ws.id, alice.id).shouldNotBeNull().role shouldBe WorkspaceRole.WORKSPACE_ADMIN
        apiKeys
            .findById(key.id)
            .shouldNotBeNull()
            .isRevoked
            .shouldBeFalse()
        // Another admin still can — the rule is about the caller, not the target.
        svc.setMemberRole(root, "acme", alice.id, WorkspaceRole.AUTHOR)
        workspaces.findMemberRow(ws.id, alice.id).shouldNotBeNull().role shouldBe WorkspaceRole.AUTHOR
    }

    @Test
    fun `the member row carries the user's instance-admin flag - a super admin member reads as such (#208)`() {
        val svc = service()
        val root = principal(admin, superAdmin = true)
        val ws = svc.create(root, "acme", "Acme")
        svc.addMember(root, "acme", alice.email, WorkspaceRole.AUTHOR)
        val rows = workspaces.findMembersOf(ws.id).associateBy { it.email }
        rows.getValue(admin.email).isSuperAdmin.shouldBeTrue()
        rows.getValue(alice.email).isSuperAdmin.shouldBeFalse()
        workspaces
            .findMemberRow(ws.id, admin.id)
            .shouldNotBeNull()
            .isSuperAdmin
            .shouldBeTrue()
    }

    @Test
    fun `the demo workspace's well-known id is the one the seeder pins (D-R11)`() {
        // The constant, not the migration: V23 does not seed `demo` — `DemoWorkspaceSeeder`
        // does, at boot, because it is the only half that can also import the example content.
        workspaces.findByName("demo").shouldNotBeNull().id shouldBe DemoWorkspaceSeeder.DEMO_WORKSPACE_ID
    }

    private fun dataSource(): DriverManagerDataSource = SharedPostgres.dataSource()
}
