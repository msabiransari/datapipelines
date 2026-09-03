package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
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
 * migrations V1 + V4 (metadata-db §4.11/§4.12): the provisioning service path for all
 * three modes, the personal-workspace name rules end to end, and membership resolution.
 *
 * The `DP-Workspace`/JWT plumbing is covered by `WorkspaceResolutionFilterTest` and the
 * cross-workspace isolation proof by `WorkspaceIsolationIntegrationTest`
 * (tests/integration-tests); this suite pins what the database actually stores.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkspaceProvisioningIntegrationTest {
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
        // The CASCADE also reaches workspaces (created_by), so the V4-seeded `default`
        // workspace is re-seeded after every truncate, exactly like AuthRepositoriesIntegrationTest.
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name)" +
                " VALUES ('defa0000-0000-0000-0000-000000000001', 'default', 'Default')",
        )
        alice = users.insert("alice@company.com", "Alice", null, "google", "sub-1", isAdmin = false)
        admin = users.insert("root@company.com", "Root", null, "google", "sub-2", isAdmin = true)
    }

    private fun service(mode: WorkspaceProvisioningMode) =
        WorkspaceService(workspaces, users, AuthCache(AuthProperties()), WorkspacesProperties(provisioningMode = mode), null, auditLogger)

    private fun principal(
        user: User,
        adminScope: Boolean,
    ) = AuthenticatedPrincipal(
        userId = user.id,
        email = user.email,
        displayName = user.displayName,
        scopes = if (adminScope) Scope.ADMIN.expand() else Scope.AUTHOR.expand(),
        authMethod = AuthMethod.OIDC,
    )

    @Test
    fun `auto-per-user first login creates the personal workspace and its owner membership`() {
        val created = service(WorkspaceProvisioningMode.AUTO_PER_USER).ensurePersonalWorkspace(alice, alice.email)

        created.isPersonal shouldBe true
        created.name shouldBe "alice"
        created.createdBy shouldBe alice.id
        workspaces.membershipsOf(alice.id).map { it.workspaceId to it.role } shouldBe listOf(created.id to WorkspaceRole.OWNER)
    }

    @Test
    fun `the same local-part in two domains collision-suffixes the second personal workspace`() {
        val other = users.insert("alice@other.io", "Alice Two", null, "google", "sub-3", isAdmin = false)
        val svc = service(WorkspaceProvisioningMode.AUTO_PER_USER)

        val first = svc.ensurePersonalWorkspace(alice, alice.email)
        val second = svc.ensurePersonalWorkspace(other, other.email)

        first.name shouldBe "alice"
        second.name shouldBe "alice-2"
    }

    @Test
    fun `a second login is idempotent - no second workspace, no second membership`() {
        val svc = service(WorkspaceProvisioningMode.AUTO_PER_USER)
        val first = svc.ensurePersonalWorkspace(alice, alice.email)

        val again = svc.ensurePersonalWorkspace(alice, alice.email)

        again.id shouldBe first.id
        workspaces.membershipsOf(alice.id) shouldHaveSize 1
    }

    @Test
    fun `self-serve create stores the workspace with the creator as owner`() {
        val created = service(WorkspaceProvisioningMode.SELF_SERVE).create(principal(alice, false), "acme", "Acme Corp")

        created.isPersonal shouldBe false
        workspaces.isMember(created.id, alice.id) shouldBe true
        workspaces.findByName("acme")?.id shouldBe created.id
    }

    @Test
    fun `closed refuses a non-admin and admits an admin, each at the service path`() {
        val svc = service(WorkspaceProvisioningMode.CLOSED)

        shouldThrow<WorkspaceCreationForbiddenException> { svc.create(principal(alice, false), "acme", "Acme Corp") }
        workspaces.findByName("acme") shouldBe null

        val created = svc.create(principal(admin, true), "acme", "Acme Corp")
        workspaces.isMember(created.id, admin.id) shouldBe true
    }

    @Test
    fun `membership resolution sees exactly the user's workspaces, oldest first`() {
        // Seeded with explicit joined_at values: "first membership" is an ordering
        // contract, and two NOW() inserts could share a timestamp and make it untestable.
        val zeta = workspaces.create("zeta", "Zeta", false, admin.id)
        val alpha = workspaces.create("alpha", "Alpha", false, admin.id)
        jdbc.jdbcTemplate.update(
            "INSERT INTO workspace_members (workspace_id, user_id, role, joined_at) VALUES (?, ?, 'member', ?), (?, ?, 'member', ?)",
            zeta.id,
            alice.id,
            java.sql.Timestamp.from(Instant.parse("2026-08-01T10:00:00Z")),
            alpha.id,
            alice.id,
            java.sql.Timestamp.from(Instant.parse("2026-08-02T10:00:00Z")),
        )
        val svc = service(WorkspaceProvisioningMode.SELF_SERVE)

        svc.memberships(alice.id).map { it.workspaceName } shouldContainExactly listOf("zeta", "alpha")
        svc.memberships(admin.id).map { it.workspaceName }.toSet() shouldBe setOf("zeta", "alpha")
    }

    private fun dataSource(): DriverManagerDataSource = SharedPostgres.dataSource()
}
