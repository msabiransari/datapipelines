package co.datapipelines.web.parameters.stream

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

/**
 * The observed-evaluation stream's per-write re-judgement (spec §4.4) — `RefreshStreamAuthorityTest`'s six cases on
 * `parameter_set.evaluate`, plus the session expiry, a role that lost the permission, and a key principal refused
 * outright (the route is session-only).
 */
class ParameterEvaluationStreamAuthorityTest {
    private val userId = UUID.randomUUID()
    private val opening = WorkspaceContext(UUID.randomUUID(), "alpha", WorkspaceRole.VIEWER)
    private val liveness = mockk<PrincipalLiveness>()
    private val workspaces = mockk<WorkspaceService>()
    private val users = mockk<UserService>()
    private val now = 1_000_000L
    private val authority = ParameterEvaluationStreamAuthority(liveness, workspaces, users) { now }
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

        authority.verdict(subscriber) shouldBe StreamVerdict.REVOKED

        verify(exactly = 1) { workspaces.contextFor(any(), "alpha") }
    }

    @Test
    fun `a same name replacement with a different workspace identity is refused`() {
        establishLiveSubscriber()
        every { workspaces.contextFor(any(), opening.name) } returns WorkspaceContext(UUID.randomUUID(), opening.name, WorkspaceRole.VIEWER)

        authority.verdict(subscriber) shouldBe StreamVerdict.REVOKED
    }

    @Test
    fun `a stream without an opening workspace context is refused`() {
        establishLiveSubscriber()

        authority.verdict(subscriber.copy(workspace = null)) shouldBe StreamVerdict.REVOKED

        verify(exactly = 0) { workspaces.contextFor(any(), any()) }
    }

    @Test
    fun `an absent or unauthorized opening membership is refused`() {
        establishLiveSubscriber()
        every { workspaces.contextFor(any(), opening.name) } returns null

        authority.verdict(subscriber) shouldBe StreamVerdict.REVOKED
    }

    @Test
    fun `the same workspace with a role that still evaluates remains authorized`() {
        establishLiveSubscriber()
        every { workspaces.contextFor(any(), opening.name) } returns opening.copy(role = WorkspaceRole.AUTHOR)

        authority.verdict(subscriber) shouldBe StreamVerdict.ALLOWED
    }

    @Test
    fun `a role demoted to one without parameter_set evaluate is refused at the next write`() {
        establishLiveSubscriber()
        every { workspaces.contextFor(any(), opening.name) } returns opening.copy(role = WorkspaceRole.PROMOTER)

        authority.verdict(subscriber) shouldBe StreamVerdict.REVOKED
    }

    @Test
    fun `a workspace store failure remains a safe refusal`() {
        establishLiveSubscriber()
        every { workspaces.contextFor(any(), opening.name) } throws IllegalStateException("store unavailable")

        authority.verdict(subscriber) shouldBe StreamVerdict.REVOKED
    }

    @Test
    fun `an expired session is refused before any store read`() {
        authority.verdict(subscriber.copy(sessionExpiresAtMillis = now)) shouldBe StreamVerdict.EXPIRED

        verify(exactly = 0) { liveness.check(any(), any()) }
    }

    @Test
    fun `a deactivated user is refused`() {
        every { liveness.check(userId, null) } returns PrincipalLiveness.Refusal.UserDeactivated(userId)

        authority.verdict(subscriber) shouldBe StreamVerdict.REVOKED
    }

    @Test
    fun `a key principal is refused outright - the route is session-only`() {
        establishLiveSubscriber()
        every { workspaces.contextFor(any(), opening.name) } returns opening

        authority.verdict(subscriber.copy(keyId = "dpk_test")) shouldBe StreamVerdict.REVOKED

        verify(exactly = 0) { workspaces.contextFor(any(), any()) }
    }
}
