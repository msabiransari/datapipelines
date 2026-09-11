package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant
import java.util.UUID

/**
 * The materialise CTE against a real Postgres running the shipped migrations through V24
 * (auth.md §4.6): an invitation is a membership waiting for its user, and the bridge is ONE
 * atomic statement — membership in, invitation gone — with the four rules the login path
 * depends on:
 *
 *  - **flags and dates carry forward**: the materialised membership holds the invited flags,
 *    and `joined_at` is the INVITATION's date, so a multi-workspace invitee's membership
 *    ordering (and therefore the stamped `active_workspace`) follows the admin's first
 *    decision, not the alphabetical accident of same-transaction timestamps;
 *  - **a deactivated workspace's invitations WAIT** (113 §B.5): not materialised while the
 *    workspace is deactivated, and live again once it is reactivated;
 *  - **a stale invitation for a workspace the user already belongs to is deleted**, not
 *    upgraded — membership-with-invitation is always a race, and the membership the user
 *    actually holds is the later, deliberate decision;
 *  - **the database keeps the invariants**: lowercase email and admin-implies-author, the
 *    same two rules the members table states.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkspaceInvitationMaterialiseIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var users: UserRepository
    private lateinit var workspaces: WorkspaceRepository
    private lateinit var invitations: WorkspaceInvitationRepository

    private lateinit var inviter: User

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(dataSource())
    }

    @BeforeEach
    fun setUp() {
        users = UserRepository(jdbc)
        workspaces = WorkspaceRepository(jdbc)
        invitations = WorkspaceInvitationRepository(jdbc)
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        alice("inviter@company.com")
        alice("invited@company.com")
        inviter = users.findByEmail("inviter@company.com").shouldNotBeNull()
    }

    private fun alice(email: String): User = users.insert(email, email.substringBefore('@'), null, "google", "sub-$email", isAdmin = false)

    private fun insertWorkspace(name: String): Workspace {
        jdbc.jdbcTemplate.update(
            "INSERT INTO workspaces (id, name, display_name) VALUES (?, ?, ?)",
            UUID.randomUUID(),
            name,
            name,
        )
        return workspaces.findByName(name).shouldNotBeNull()
    }

    private fun invite(
        workspace: Workspace,
        email: String,
        flags: MembershipFlags = MembershipFlags(author = true),
    ) {
        invitations.upsert(workspace.id, email, flags, inviter.id)
    }

    private fun invitedAtOf(
        workspace: Workspace,
        email: String,
    ): Instant =
        jdbc
            .query(
                "SELECT invited_at FROM workspace_invitations WHERE workspace_id = :ws AND email = :email",
                org.springframework.jdbc.core.namedparam
                    .MapSqlParameterSource()
                    .addValue("ws", workspace.id)
                    .addValue("email", email),
            ) { rs, _ -> rs.getObject("invited_at", java.time.OffsetDateTime::class.java).toInstant() }
            .single()

    private fun joinedAtOf(
        workspaceName: String,
        email: String,
    ): Instant =
        jdbc
            .query(
                "SELECT m.joined_at FROM workspace_members m JOIN workspaces w ON w.id = m.workspace_id" +
                    " JOIN users u ON u.id = m.user_id WHERE w.name = :name AND u.email = :email",
                org.springframework.jdbc.core.namedparam
                    .MapSqlParameterSource()
                    .addValue("name", workspaceName)
                    .addValue("email", email),
            ) { rs, _ -> rs.getObject("joined_at", java.time.OffsetDateTime::class.java).toInstant() }
            .single()

    @Test
    fun `materialise turns the invitation into a membership with the invited flags and deletes it`() {
        val ws = insertWorkspace("acme")
        invite(ws, "invited@company.com", MembershipFlags(promoter = true))
        val bob = users.findByEmail("invited@company.com").shouldNotBeNull()

        val materialised = invitations.materialiseFor("invited@company.com", bob.id)

        materialised.shouldHaveSize(1)
        materialised.single().workspaceName shouldBe "acme"
        materialised.single().flags shouldBe MembershipFlags(promoter = true)
        materialised.single().invitedBy shouldBe inviter.id
        invitations.findByWorkspace(ws.id).shouldHaveSize(0)
        workspaces
            .flagsOf(ws.id, bob.id)
            .shouldNotBeNull()
            .promoter
            .shouldBeTrue()
    }

    @Test
    fun `materialise is idempotent - a second call finds nothing and changes nothing`() {
        val ws = insertWorkspace("acme")
        invite(ws, "invited@company.com")
        val bob = users.findByEmail("invited@company.com").shouldNotBeNull()

        invitations.materialiseFor("invited@company.com", bob.id)
        invitations.materialiseFor("invited@company.com", bob.id) shouldBe emptyList()

        workspaces.findMembersOf(ws.id).shouldHaveSize(1)
    }

    @Test
    fun `joined_at is the INVITATION's date, so membership order follows the earlier invite`() {
        val first = insertWorkspace("alpha")
        val second = insertWorkspace("beta")
        invite(first, "invited@company.com")
        // The second invite is a real amount of time later, so the ordering claim is not
        // decided by clock resolution.
        Thread.sleep(20)
        invite(second, "invited@company.com")
        // Read the invitation dates BEFORE the materialise — the materialise DELETES the
        // rows it turns into memberships, which is the whole point.
        val firstAt = invitedAtOf(first, "invited@company.com")
        val secondAt = invitedAtOf(second, "invited@company.com")
        val bob = users.findByEmail("invited@company.com").shouldNotBeNull()

        val materialised = invitations.materialiseFor("invited@company.com", bob.id)

        // Returned in invited_at order...
        materialised.map { it.workspaceName } shouldContainExactly listOf("alpha", "beta")
        // ...and the memberships carry the invitation dates, which is what makes the login
        // path's "first membership" (joined_at order) the FIRST-INVITED workspace.
        joinedAtOf("alpha", "invited@company.com") shouldBe firstAt
        joinedAtOf("beta", "invited@company.com") shouldBe secondAt
        workspaces.membershipsOf(bob.id).map { it.workspaceName } shouldContainExactly listOf("alpha", "beta")
    }

    @Test
    fun `an invitation into a DEACTIVATED workspace waits - and reactivation makes it live`() {
        val ws = insertWorkspace("acme")
        invite(ws, "invited@company.com")
        val bob = users.findByEmail("invited@company.com").shouldNotBeNull()
        jdbc.jdbcTemplate.update("UPDATE workspaces SET deactivated_at = NOW() WHERE id = ?", ws.id)

        invitations.materialiseFor("invited@company.com", bob.id) shouldBe emptyList()

        // The invitation row SURVIVES the login: it waits for reactivation (113 §B.5).
        invitations.findByWorkspace(ws.id).shouldHaveSize(1)

        jdbc.jdbcTemplate.update("UPDATE workspaces SET deactivated_at = NULL WHERE id = ?", ws.id)
        val materialised = invitations.materialiseFor("invited@company.com", bob.id)
        materialised.shouldHaveSize(1)
        materialised.single().workspaceName shouldBe "acme"
    }

    @Test
    fun `a stale invitation for a workspace the user already belongs to is deleted, never upgraded`() {
        val ws = insertWorkspace("acme")
        val bob = users.findByEmail("invited@company.com").shouldNotBeNull()
        // The race: bob is ALREADY a member (added after his row appeared) when a stale
        // invitation from before his provisioning is still around.
        workspaces.addMember(ws.id, bob.id, MembershipFlags.VIEWER)
        // The service normalises before storing; the repository is the thin layer the CHECK
        // guards, so the test passes the flags the real caller would produce.
        invite(ws, "invited@company.com", MembershipFlags(author = true, admin = true))

        invitations.materialiseFor("invited@company.com", bob.id) shouldBe emptyList()

        // The membership keeps the flags the user actually holds; the ghost is gone.
        workspaces
            .flagsOf(ws.id, bob.id)
            .shouldNotBeNull()
            .admin
            .shouldBeFalse()
        invitations.findByWorkspace(ws.id).shouldHaveSize(0)
    }

    @Test
    fun `an unknown email materialises nothing - the login of a user nobody invited`() {
        insertWorkspace("acme")
        val stranger = alice("stranger@company.com")

        invitations.materialiseFor("stranger@company.com", stranger.id) shouldBe emptyList()
    }

    @Test
    fun `upsert REPLACES the flags and refreshes the audit-relevant columns`() {
        val ws = insertWorkspace("acme")
        invite(ws, "invited@company.com", MembershipFlags(author = true))
        val firstAt = invitedAtOf(ws, "invited@company.com")

        invite(ws, "invited@company.com", MembershipFlags(author = true, admin = true))

        invitations.findByWorkspace(ws.id).shouldHaveSize(1)
        val row = invitations.findByWorkspace(ws.id).single()
        row.flags shouldBe MembershipFlags(author = true, admin = true)
        row.invitedBy shouldBe inviter.id
        (row.invitedAt.isAfter(firstAt) || row.invitedAt == firstAt).shouldBeTrue()
    }

    @Test
    fun `revoke deletes exactly one invitation and answers false when there is none`() {
        val ws = insertWorkspace("acme")
        val other = insertWorkspace("beta")
        invite(ws, "invited@company.com")
        invite(other, "invited@company.com")

        invitations.delete(ws.id, "invited@company.com").shouldBeTrue()
        invitations.delete(ws.id, "invited@company.com").shouldBeFalse()
        // The OTHER workspace's invitation is untouched — one workspace, one email, one row.
        invitations.findByWorkspace(other.id).shouldHaveSize(1)
        invitations.find(ws.id, "invited@company.com").shouldBeNull()
    }

    @Test
    fun `the database keeps the invariants - lowercase email and admin implies author`() {
        val ws = insertWorkspace("acme")

        shouldThrow<DataIntegrityViolationException> {
            jdbc.jdbcTemplate.update(
                "INSERT INTO workspace_invitations (workspace_id, email, invited_by) VALUES (?, ?, ?)",
                ws.id,
                "Mixed@Case.com",
                inviter.id,
            )
        }
        shouldThrow<DataIntegrityViolationException> {
            jdbc.jdbcTemplate.update(
                "INSERT INTO workspace_invitations (workspace_id, email, admin, invited_by) VALUES (?, ?, TRUE, ?)",
                ws.id,
                "plain@case.com",
                inviter.id,
            )
        }
    }

    private fun dataSource(): DriverManagerDataSource = SharedPostgres.dataSource()
}
