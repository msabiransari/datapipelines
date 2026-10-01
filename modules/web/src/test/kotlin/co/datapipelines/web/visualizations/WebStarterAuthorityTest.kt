package co.datapipelines.web.visualizations

import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.KeyRole
import co.datapipelines.auth.PrincipalLiveness
import co.datapipelines.auth.User
import co.datapipelines.auth.UserKind
import co.datapipelines.auth.UserService
import co.datapipelines.auth.Workspace
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.auth.WorkspaceService
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * DECISION 3's adapter (the owner's "current authority on token use"): the starter is re-judged from the live auth
 * state at the moment a capability is used — a person by their membership NOW (or the instance super admin flag), a
 * key identity by its `mcp` key's revocation, expiry, pin, creator liveness and key role NOW. Each refusal is falsified
 * against its own admitted twin in the same test, so a check that stopped mattering turns a case red.
 */
class WebStarterAuthorityTest {
    private val users = mockk<UserService>()
    private val workspaces = mockk<WorkspaceService>()
    private val workspaceRows = mockk<WorkspaceRepository>()
    private val keys = mockk<ApiKeyRepository>()
    private val liveness = mockk<PrincipalLiveness>()
    private val now = Instant.parse("2026-10-01T21:00:00Z")
    private val authority = WebStarterAuthority(users, workspaces, workspaceRows, keys, liveness) { now }

    private val workspaceId = UUID.randomUUID()
    private val workspace = Workspace(workspaceId, "acme", "Acme", false, null, false, now)
    private val personId = UUID.randomUUID()
    private val identityId = UUID.randomUUID()
    private val creatorId = UUID.randomUUID()

    init {
        every { workspaceRows.findById(workspaceId) } returns workspace
        every { liveness.check(any(), any()) } returns null
    }

    @Test
    fun `a person holds update while their membership role admits it - and not once demoted`() {
        every { users.snapshot(personId) } returns person()
        every { workspaces.contextFor(any(), "acme") } returns WorkspaceContext(workspaceId, "acme", WorkspaceRole.AUTHOR)
        authority.holdsUpdate(workspaceId, personId) shouldBe true

        every { workspaces.contextFor(any(), "acme") } returns WorkspaceContext(workspaceId, "acme", WorkspaceRole.VIEWER)
        withClue("demoted to viewer") { authority.holdsUpdate(workspaceId, personId) shouldBe false }

        every { workspaces.contextFor(any(), "acme") } returns WorkspaceContext(workspaceId, "acme", WorkspaceRole.PROMOTER)
        withClue("a promoter has no visualization.update") { authority.holdsUpdate(workspaceId, personId) shouldBe false }

        every { workspaces.contextFor(any(), "acme") } returns null
        withClue("removed from the workspace") { authority.holdsUpdate(workspaceId, personId) shouldBe false }

        every { workspaces.contextFor(any(), "acme") } returns WorkspaceContext(UUID.randomUUID(), "acme", WorkspaceRole.AUTHOR)
        withClue("a context of another workspace id") { authority.holdsUpdate(workspaceId, personId) shouldBe false }
    }

    @Test
    fun `a deactivated person, a deactivated workspace and a deleted one are refused before any role is read`() {
        every { users.snapshot(personId) } returns person()
        every { workspaces.contextFor(any(), "acme") } returns WorkspaceContext(workspaceId, "acme", WorkspaceRole.WORKSPACE_ADMIN)
        authority.holdsUpdate(workspaceId, personId) shouldBe true

        every { liveness.check(personId, any()) } returns PrincipalLiveness.Refusal.UserDeactivated(personId)
        withClue("deactivated person") { authority.holdsUpdate(workspaceId, personId) shouldBe false }
        every { liveness.check(personId, any()) } returns null

        every { workspaceRows.findById(workspaceId) } returns workspace.copy(isDeleted = true)
        withClue("deleted workspace") { authority.holdsUpdate(workspaceId, personId) shouldBe false }

        every { users.snapshot(personId) } returns null
        every { workspaceRows.findById(workspaceId) } returns workspace
        withClue("no such user") { authority.holdsUpdate(workspaceId, personId) shouldBe false }
    }

    @Test
    fun `a key identity holds update through its live mcp key's role - and loses it on revoke, expiry or a weaker role`() {
        every { users.snapshot(identityId) } returns identity()
        every { keys.findById(KEY_ID) } returns key()
        authority.holdsUpdate(workspaceId, identityId) shouldBe true

        val refused =
            mapOf(
                "revoked" to key().copy(isRevoked = true),
                "expired" to key().copy(expiresAt = now.minusSeconds(1)),
                "expiring exactly now" to key().copy(expiresAt = now),
                "a promoter key" to key().copy(role = KeyRole.PROMOTER),
                "pinned to another workspace" to key().copy(workspaceId = UUID.randomUUID()),
                "an endpoint key" to key().copy(kind = ApiKeyKind.ENDPOINT, role = KeyRole.API_CALLER),
                "another identity's key" to key().copy(userId = UUID.randomUUID()),
            )
        refused.forEach { (case, record) ->
            every { keys.findById(KEY_ID) } returns record
            withClue(case) { authority.holdsUpdate(workspaceId, identityId) shouldBe false }
        }

        every { keys.findById(KEY_ID) } returns key()
        every { liveness.check(creatorId, null) } returns PrincipalLiveness.Refusal.UserDeactivated(creatorId)
        withClue("the key's creator deactivated (A20)") { authority.holdsUpdate(workspaceId, identityId) shouldBe false }
    }

    @Test
    fun `an unsettleable answer is a refusal, never an exception`() {
        every { users.snapshot(personId) } throws IllegalStateException("cache down")
        authority.holdsUpdate(workspaceId, personId) shouldBe false
    }

    private fun person() =
        User(
            id = personId,
            email = "a@b.c",
            displayName = "A",
            provider = "test",
            providerSubject = "a-sub",
            isActive = true,
            isAdmin = false,
            createdAt = now,
            updatedAt = now,
        )

    private fun identity() =
        User(
            id = identityId,
            email = "$KEY_ID@keys.invalid",
            displayName = "agent key",
            provider = "key",
            providerSubject = KEY_ID,
            isActive = true,
            isAdmin = false,
            createdAt = now,
            updatedAt = now,
            kind = UserKind.SERVICE,
        )

    private fun key() =
        ApiKey(
            id = KEY_ID,
            userId = identityId,
            name = "agent key",
            keyHash = "argon2",
            isRevoked = false,
            createdAt = now,
            lastUsedAt = null,
            expiresAt = null,
            workspaceId = workspaceId,
            workspaceName = "acme",
            kind = ApiKeyKind.MCP,
            role = KeyRole.AUTHOR,
            createdBy = creatorId,
        )

    private companion object {
        const val KEY_ID = "dpk_abc123"
    }
}
