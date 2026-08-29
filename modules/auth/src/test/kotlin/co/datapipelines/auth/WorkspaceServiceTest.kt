package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Workspace membership resolution and provisioning (design §5/§7): the `DP-Workspace`
 * switch, session fallback, login stamping, provisioning modes and the personal-workspace
 * name rules (sanitization + collision suffix).
 */
class WorkspaceServiceTest {
    private val repository = mockk<WorkspaceRepository>()
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val cache = AuthCache(AuthProperties())
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val lastUsed = mockk<LastUsedWorkspaceStore>(relaxed = true)

    private val userId = UUID.randomUUID()
    private val wsA = workspace("alpha")
    private val wsB = workspace("beta")

    private fun service(mode: WorkspaceProvisioningMode = WorkspaceProvisioningMode.SELF_SERVE) =
        WorkspaceService(repository, userRepository, cache, WorkspacesProperties(provisioningMode = mode), lastUsed, auditLogger)

    private fun workspace(name: String): Workspace =
        Workspace(UUID.randomUUID(), name, name, isPersonal = false, createdBy = null, isDeleted = false, createdAt = Instant.now())

    private fun membership(ws: Workspace): WorkspaceMembership = WorkspaceMembership(ws.id, ws.name, WorkspaceRole.OWNER, Instant.now())

    private fun principal(
        admin: Boolean = false,
        memberships: List<WorkspaceMembership> = emptyList(),
    ): AuthenticatedPrincipal {
        every { repository.membershipsOf(userId) } returns memberships
        return AuthenticatedPrincipal(
            userId = userId,
            email = "alice@company.com",
            displayName = "Alice",
            scopes = if (admin) Scope.ADMIN.expand() else Scope.AUTHOR.expand(),
            authMethod = AuthMethod.OIDC,
        )
    }

    // ------------------------------------------------------------ resolveSwitch

    @Test
    fun `a switch to a member workspace resolves`() {
        val principal = principal(memberships = listOf(membership(wsA)))
        every { repository.findByName("alpha") } returns wsA

        service().resolveSwitch(principal, "alpha") shouldBe WorkspaceContext(wsA.id, "alpha")
    }

    @Test
    fun `a switch naming a non-membership is membership_required - indistinguishable from unknown`() {
        val principal = principal(memberships = listOf(membership(wsA)))
        every { repository.findByName("beta") } returns wsB

        shouldThrow<WorkspaceMembershipRequiredException> { service().resolveSwitch(principal, "beta") }
    }

    @Test
    fun `a switch naming an unknown workspace is the same membership_required`() {
        val principal = principal(memberships = listOf(membership(wsA)))
        every { repository.findByName("ghost") } returns null

        shouldThrow<WorkspaceMembershipRequiredException> { service().resolveSwitch(principal, "ghost") }
    }

    @Test
    fun `an admin switches into a workspace without a membership (D4 bypass)`() {
        val principal = principal(admin = true, memberships = emptyList())
        every { repository.findByName("beta") } returns wsB

        service().resolveSwitch(principal, "beta") shouldBe WorkspaceContext(wsB.id, "beta")
    }

    // ------------------------------------------------------------ resolveForSession

    @Test
    fun `the stamped claim resolves while the membership is live`() {
        val principal = principal(memberships = listOf(membership(wsA), membership(wsB)))

        service().resolveForSession(principal, "beta") shouldBe WorkspaceContext(wsB.id, "beta")
    }

    @Test
    fun `a stamped claim whose membership is gone falls back to the first membership`() {
        val principal = principal(memberships = listOf(membership(wsA)))

        service().resolveForSession(principal, "revoked-ws") shouldBe WorkspaceContext(wsA.id, "alpha")
    }

    @Test
    fun `zero memberships resolve to no workspace (closed mode - operations then 403)`() {
        val principal = principal(memberships = emptyList())

        service().resolveForSession(principal, "alpha").shouldBeNull()
    }

    // ------------------------------------------------------------ workspaceForLogin

    @Test
    fun `login stamps the last-used workspace while it is still a membership`() {
        every { lastUsed.lastUsed(userId) } returns "beta"
        val user = user()
        principal(memberships = listOf(membership(wsA), membership(wsB)))

        service().workspaceForLogin(user, "alice@company.com") shouldBe WorkspaceContext(wsB.id, "beta")
    }

    @Test
    fun `a stale last-used falls back to the first membership`() {
        every { lastUsed.lastUsed(userId) } returns "long-gone"
        val user = user()
        principal(memberships = listOf(membership(wsA)))

        service().workspaceForLogin(user, "alice@company.com") shouldBe WorkspaceContext(wsA.id, "alpha")
    }

    @Test
    fun `zero memberships stamp nothing under self-serve`() {
        every { lastUsed.lastUsed(userId) } returns null
        val user = user()
        principal(memberships = emptyList())

        service().workspaceForLogin(user, "alice@company.com").shouldBeNull()
    }

    @Test
    fun `zero memberships under auto-per-user provisions the personal workspace and stamps it`() {
        every { lastUsed.lastUsed(userId) } returns null
        val user = user()
        principal(memberships = emptyList())
        every { repository.nameExists("alice") } returns false
        every { repository.create("alice", "alice", true, userId) } answers
            { wsA.copy(name = "alice", isPersonal = true, createdBy = userId) }

        val stamped = service(WorkspaceProvisioningMode.AUTO_PER_USER).workspaceForLogin(user, "alice@company.com")

        stamped.shouldNotBeNull()
        stamped.name shouldBe "alice"
        verify { repository.create("alice", "alice", true, userId) }
    }

    // ------------------------------------------------------------ provisioning modes (create)

    @Test
    fun `closed mode refuses a non-admin creator`() {
        val principal = principal(memberships = emptyList())

        shouldThrow<WorkspaceCreationForbiddenException> {
            service(WorkspaceProvisioningMode.CLOSED).create(principal, "acme", "Acme")
        }
    }

    @Test
    fun `closed mode allows an admin creator`() {
        val principal = principal(admin = true, memberships = emptyList())
        every { repository.nameExists("acme") } returns false
        every { repository.create("acme", "Acme", false, userId) } returns wsA

        service(WorkspaceProvisioningMode.CLOSED).create(principal, "acme", "Acme") shouldBe wsA
    }

    @Test
    fun `self-serve allows any authenticated creator and audits the creation`() {
        val principal = principal(memberships = emptyList())
        every { repository.nameExists("acme") } returns false
        every { repository.create("acme", "Acme", false, userId) } returns wsA

        service().create(principal, "acme", "Acme") shouldBe wsA
        verify { auditLogger.log("auth.workspace.created", userId, null, null, null, any()) }
    }

    @Test
    fun `a malformed workspace name is rejected before the database is touched`() {
        val principal = principal(memberships = emptyList())

        shouldThrow<WorkspaceNameInvalidException> { service().create(principal, "ACME Corp!", "Acme") }
        verify(exactly = 0) { repository.create(any(), any(), any(), any()) }
    }

    // ------------------------------------------------------------ personal-workspace names

    @Test
    fun `the email local-part is sanitized to the name rule`() {
        sanitizeCases().forEach { (email, expected) ->
            every { repository.nameExists(any()) } returns false
            every { repository.create(expected, expected, true, userId) } answers
                { wsA.copy(name = expected, isPersonal = true, createdBy = userId) }
            every { repository.membershipsOf(userId) } returns emptyList()

            val created = service(WorkspaceProvisioningMode.AUTO_PER_USER).ensurePersonalWorkspace(user(), email)

            created.name shouldBe expected
        }
    }

    @Test
    fun `a taken personal name is collision-suffixed`() {
        every { repository.membershipsOf(userId) } returns emptyList()
        every { repository.nameExists("alice") } returns true
        every { repository.nameExists("alice-2") } returns false
        every { repository.create("alice-2", "alice-2", true, userId) } answers
            { wsA.copy(name = "alice-2", isPersonal = true, createdBy = userId) }

        service(WorkspaceProvisioningMode.AUTO_PER_USER).ensurePersonalWorkspace(user(), "alice@company.com").name shouldBe "alice-2"
    }

    @Test
    fun `a second login does not mint a second personal workspace`() {
        every { repository.membershipsOf(userId) } returns listOf(membership(wsA))
        every { repository.findById(wsA.id) } returns wsA

        service(WorkspaceProvisioningMode.AUTO_PER_USER).ensurePersonalWorkspace(user(), "alice@company.com") shouldBe wsA
        verify(exactly = 0) { repository.create(any(), any(), any(), any()) }
    }

    private fun user() =
        User(userId, "alice@company.com", "Alice", null, "google", "sub-1", true, false, Instant.now(), Instant.now(), null)

    private fun sanitizeCases(): List<Pair<String, String>> =
        listOf(
            "alice@company.com" to "alice",
            "Alice.Wang@company.com" to "alice-wang",
            "bob+dev@company.com" to "bob-dev",
            "...@company.com" to "personal",
        )
}
