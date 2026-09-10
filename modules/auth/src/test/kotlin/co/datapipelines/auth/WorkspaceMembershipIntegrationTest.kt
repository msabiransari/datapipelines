package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
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
    private val auditLogger = mockk<AuditLogger>(relaxed = true)

    private lateinit var alice: User
    private lateinit var admin: User

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(dataSource())
    }

    @BeforeEach
    fun setUp() {
        users = UserRepository(jdbc)
        workspaces = WorkspaceRepository(jdbc)
        // The CASCADE also reaches workspaces (created_by), so V4's `default` is re-seeded
        // after every truncate. `demo` is re-seeded here too — not because a migration ships
        // it (V23 deliberately does not; `DemoWorkspaceSeeder` owns it at boot) but because
        // this suite asserts the world a BOOTED deployment has, and it never boots one.
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name)" +
                " VALUES ('defa0000-0000-0000-0000-000000000001', 'default', 'Default')," +
                " ('de000000-0000-0000-0000-000000000001', 'demo', 'Demo')",
        )
        alice = users.insert("alice@company.com", "Alice", null, "google", "sub-1", isAdmin = false)
        admin = users.insert("root@company.com", "Root", null, "google", "sub-2", isAdmin = true)
    }

    private fun service() = WorkspaceService(workspaces, users, AuthCache(AuthProperties()), null, auditLogger)

    private fun principal(
        user: User,
        superAdmin: Boolean = false,
    ) = AuthenticatedPrincipal(
        userId = user.id,
        email = user.email,
        displayName = user.displayName,
        // D-R1: a session carries no scopes at all.
        scopes = emptySet(),
        authMethod = AuthMethod.OIDC,
        superAdmin = superAdmin,
    )

    @Test
    fun `create stores the creator as the workspace ADMIN, and the constraint makes them an author`() {
        val ws = service().create(principal(admin, superAdmin = true), "acme", "Acme")

        workspaces.flagsOf(ws.id, admin.id) shouldBe MembershipFlags(author = true, admin = true)
        workspaces.adminCount(ws.id) shouldBe 1
    }

    @Test
    fun `the database REFUSES an admin who is not an author - the invariant is not a convention`() {
        val ws = workspaces.create("acme", "Acme", isPersonal = false, createdBy = admin.id)

        // Straight to SQL, past the service's normalisation: the point is that the CHECK is
        // what makes "normalised everywhere" true rather than hoped for.
        val refusal =
            shouldThrow<org.springframework.dao.DataIntegrityViolationException> {
                jdbc.jdbcTemplate.update(
                    "INSERT INTO workspace_members (workspace_id, user_id, author, promoter, admin)" +
                        " VALUES (?, ?, FALSE, FALSE, TRUE)",
                    ws.id,
                    alice.id,
                )
            }
        refusal.message
            .shouldNotBeNull()
            .contains("chk_workspace_member_admin_authors")
            .shouldBeTrue()
    }

    @Test
    fun `the three flags round-trip independently - a promoter is not an author`() {
        val ws = workspaces.create("acme", "Acme", isPersonal = false, createdBy = admin.id)
        val promoterOnly = MembershipFlags(promoter = true)

        workspaces.addMember(ws.id, alice.id, promoterOnly)

        workspaces.flagsOf(ws.id, alice.id) shouldBe promoterOnly
        // …and the two capabilities that separates: release yes, authoring no.
        Capability.PROMOTE.satisfiedBy(promoterOnly).shouldBeTrue()
        Capability.AUTHOR.satisfiedBy(promoterOnly).shouldBeFalse()
        // …while `switch` is held by both sides of that split (O-1).
        Capability.SWITCH.satisfiedBy(promoterOnly).shouldBeTrue()
    }

    @Test
    fun `the last admin cannot be removed or demoted, against the real row count`() {
        val svc = service()
        val ws = svc.create(principal(admin, superAdmin = true), "acme", "Acme")
        val actor = principal(admin, superAdmin = true)

        shouldThrow<WorkspaceLastAdminException> { svc.removeMember(actor, "acme", admin.id) }
        shouldThrow<WorkspaceLastAdminException> {
            svc.setMemberFlags(actor, "acme", admin.id, MembershipFlags(author = true))
        }

        // Promote somebody else and the refusal lifts — the fix the message names.
        svc.addMember(actor, "acme", alice.email, MembershipFlags(admin = true))
        workspaces.adminCount(ws.id) shouldBe 2
        svc.removeMember(actor, "acme", admin.id)
        workspaces.adminCount(ws.id) shouldBe 1
    }

    @Test
    fun `deactivation is reversible and purges NOTHING (D-R10)`() {
        val svc = service()
        val actor = principal(admin, superAdmin = true)
        val ws = svc.create(actor, "acme", "Acme")
        svc.addMember(actor, "acme", alice.email, MembershipFlags(author = true))

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
        svc.addMember(actor, "acme", alice.email, MembershipFlags(author = true))
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
            "INSERT INTO workspace_members (workspace_id, user_id, author, joined_at)" +
                " VALUES (?, ?, TRUE, ?), (?, ?, TRUE, ?)",
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
    fun `the demo workspace's well-known id is the one the seeder pins (D-R11)`() {
        // The constant, not the migration: V23 does not seed `demo` — `DemoWorkspaceSeeder`
        // does, at boot, because it is the only half that can also import the example content.
        workspaces.findByName("demo").shouldNotBeNull().id shouldBe DemoWorkspaceSeeder.DEMO_WORKSPACE_ID
    }

    private fun dataSource(): DriverManagerDataSource = SharedPostgres.dataSource()
}
