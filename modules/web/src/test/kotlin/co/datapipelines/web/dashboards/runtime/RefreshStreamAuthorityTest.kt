package co.datapipelines.web.dashboards.runtime

import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.PrincipalLiveness
import co.datapipelines.auth.User
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.web.sse.StreamVerdict
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RefreshStreamAuthorityTest {
    private val userId = UUID.randomUUID()
    private val opening = WorkspaceContext(UUID.randomUUID(), "alpha", WorkspaceRole.VIEWER)
    private val liveness = mockk<PrincipalLiveness>()
    private val workspaces = mockk<WorkspaceService>()
    private val users = mockk<UserService>()
    private val apiKeys = mockk<ApiKeyRepository>()
    private val authority = RefreshStreamAuthority(liveness, workspaces, users, apiKeys)
    private val subscriber =
        AuthenticatedPrincipal(
            userId = userId,
            email = "viewer@example.test",
            displayName = "Viewer",
            authMethod = AuthMethod.OIDC,
            workspace = opening,
        )

    private fun establishLiveSubscriber() {
        every { liveness.check(userId, null) } returns null
        every { users.snapshot(userId) } returns
            User(
                id = userId,
                email = "viewer@example.test",
                displayName = "Viewer",
                provider = "test",
                providerSubject = "viewer",
                isActive = true,
                isAdmin = false,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
    }

    @Test
    fun `a different authorized workspace cannot keep an opening workspace stream alive`() {
        establishLiveSubscriber()
        val workspaceB = WorkspaceContext(UUID.randomUUID(), "beta", WorkspaceRole.VIEWER)
        every { workspaces.contextFor(any(), "alpha") } returns workspaceB
        every { workspaces.resolveForSession(any(), "alpha") } returns workspaceB

        authority.access(subscriber).verdict shouldBe StreamVerdict.REVOKED

        verify(exactly = 1) { workspaces.contextFor(any(), "alpha") }
        verify(exactly = 0) { workspaces.resolveForSession(any(), any()) }
    }

    @Test
    fun `a same name replacement with a different workspace identity is refused`() {
        establishLiveSubscriber()
        val replacement = WorkspaceContext(UUID.randomUUID(), opening.name, WorkspaceRole.VIEWER)
        every { workspaces.contextFor(any(), opening.name) } returns replacement
        every { workspaces.resolveForSession(any(), opening.name) } returns replacement

        authority.access(subscriber).verdict shouldBe StreamVerdict.REVOKED

        verify(exactly = 0) { workspaces.resolveForSession(any(), any()) }
    }

    @Test
    fun `a stream without an opening workspace context is refused`() {
        establishLiveSubscriber()

        authority.access(subscriber.copy(workspace = null)).verdict shouldBe StreamVerdict.REVOKED

        verify(exactly = 0) { workspaces.contextFor(any(), any()) }
    }

    @Test
    fun `an absent or unauthorized opening membership is refused`() {
        establishLiveSubscriber()
        every { workspaces.contextFor(any(), opening.name) } returns null

        authority.access(subscriber).verdict shouldBe StreamVerdict.REVOKED
    }

    @Test
    fun `a same workspace remains authorized and derives execution projection from its current role`() {
        establishLiveSubscriber()
        every { workspaces.contextFor(any(), opening.name) } returns opening.copy(role = WorkspaceRole.AUTHOR)
        every { workspaces.resolveForSession(any(), opening.name) } returns opening.copy(role = WorkspaceRole.AUTHOR)

        authority.access(subscriber) shouldBe RefreshStreamAccess(StreamVerdict.ALLOWED, executionRead = true)
    }

    @Test
    fun `a workspace store failure remains a safe refusal`() {
        establishLiveSubscriber()
        every { workspaces.contextFor(any(), opening.name) } throws IllegalStateException("store unavailable")

        authority.access(subscriber) shouldBe RefreshStreamAccess(StreamVerdict.REVOKED, executionRead = false)
    }
}
