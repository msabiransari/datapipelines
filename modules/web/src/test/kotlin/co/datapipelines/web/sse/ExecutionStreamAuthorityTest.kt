package co.datapipelines.web.sse

import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.AuthCache
import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.LastUsedWorkspaceStore
import co.datapipelines.auth.PrincipalLiveness
import co.datapipelines.auth.User
import co.datapipelines.auth.UserService
import co.datapipelines.auth.Workspace
import co.datapipelines.auth.WorkspaceContentCheck
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceInvitationRepository
import co.datapipelines.auth.WorkspaceLiveness
import co.datapipelines.auth.WorkspaceMembership
import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionTrigger
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * #230 (P4) — the ONE predicate an open stream re-asks before every write, at unit level: the
 * SAME verdict a fresh `GET /executions/{id}/events` request would get, asked of the
 * subscriber's CURRENT standing, every read through the auth caches.
 *
 * The subscriber each case hands in is the FROZEN principal the stream carried away at open —
 * its `workspace` context and `superAdmin` flag are deliberately stale, because the whole point
 * of the re-judgement is that they must not decide anything.
 */
class ExecutionStreamAuthorityTest {
    private val repository = mockk<WorkspaceRepository>()
    private val executions = mockk<ExecutionRepository>()
    private val users = mockk<UserService>()

    private val userId = UUID.randomUUID()
    private val executionId = UUID.randomUUID()
    private val ws =
        Workspace(UUID.randomUUID(), "acme", "acme", isPersonal = false, createdBy = null, isDeleted = false, createdAt = Instant.now())

    /** The open-time snapshot: the member was an author with `acme` resolved, claim stamped. */
    private fun subscriberAtOpen(superAdmin: Boolean = false) =
        AuthenticatedPrincipal(
            userId = userId,
            email = "member@acme.test",
            displayName = "Member",
            authMethod = AuthMethod.OIDC,
            workspaceName = "acme",
            workspace = WorkspaceContext(ws.id, "acme", WorkspaceRole.AUTHOR),
            superAdmin = superAdmin,
        )

    private fun authority() =
        ExecutionStreamAuthority(
            executions,
            PrincipalLiveness(users, WorkspaceLiveness { true }),
            workspaceService(),
            users,
        )

    private fun liveUser(isAdmin: Boolean = false) {
        every { users.isActive(userId) } returns true
        every { users.snapshot(userId) } returns userRow(isAdmin)
    }

    private fun userRow(admin: Boolean): User = mockk { every { isAdmin } returns admin }

    private fun memberships(vararg roles: WorkspaceRole) {
        every {
            repository.membershipsOf(userId)
        } returns roles.map { memberRow(it) }
        every { repository.findByName("acme") } returns ws
    }

    private fun memberRow(role: WorkspaceRole) = WorkspaceMembership(ws.id, "acme", role, Instant.now(), workspaceActive = true)

    private fun ownRun(executedByThisUser: Boolean = true) {
        every { executions.findById(ws.id, executionId) } returns record(executedByThisUser)
    }

    private fun record(executedByThisUser: Boolean): ExecutionRecord =
        mockk {
            every { isOwnRunOf(userId, false) } returns executedByThisUser
            every { triggeredVia } returns ExecutionTrigger.REST
        }

    @Test
    fun `an author whose authority is unchanged keeps reading their own run`() {
        liveUser()
        memberships(WorkspaceRole.AUTHOR)
        ownRun()

        authority().mayRead(subscriberAtOpen(), executionId) shouldBe true
    }

    @Test
    fun `a deactivated subscriber's stream is refused - liveness first, through the same cache`() {
        every { users.isActive(userId) } returns false
        every { users.snapshot(userId) } returns userRow(false)
        memberships(WorkspaceRole.AUTHOR)
        ownRun()

        authority().mayRead(subscriberAtOpen(), executionId) shouldBe false
    }

    @Test
    fun `a removed member's stream is refused - the context a new request would resolve is none`() {
        liveUser()
        every { repository.membershipsOf(userId) } returns emptyList()
        every { repository.findByName("acme") } returns ws
        ownRun()

        authority().mayRead(subscriberAtOpen(), executionId) shouldBe false
    }

    @Test
    fun `a role lowered below execution read refuses the stream`() {
        liveUser()
        memberships(WorkspaceRole.PROMOTER)
        ownRun()

        authority().mayRead(subscriberAtOpen(), executionId) shouldBe false
    }

    @Test
    fun `a demoted super admin is judged by the role they actually hold now`() {
        // The open-time snapshot said super admin; `users.is_admin` has since been cleared. The
        // re-judgement must read the CURRENT flag, resolve a member context, and judge THAT.
        liveUser(isAdmin = false)
        memberships(WorkspaceRole.AUTHOR)
        ownRun()

        authority().mayRead(subscriberAtOpen(superAdmin = true), executionId) shouldBe true
    }

    @Test
    fun `a super admin with a stale claim and one membership keeps reading (#216)`() {
        // The fallback branch's context carries the instance authority, so the guard's
        // permission ask succeeds exactly as the request path's would.
        liveUser(isAdmin = true)
        every { repository.membershipsOf(userId) } returns listOf(memberRow(WorkspaceRole.VIEWER))
        every { repository.findByName("gone-ws") } returns null
        ownRun()

        val subscriber = subscriberAtOpen().copy(workspaceName = "gone-ws")

        authority().mayRead(subscriber, executionId) shouldBe true
    }

    @Test
    fun `another member's run needs execution read_all - a viewer is refused, a workspace admin is not`() {
        liveUser()
        every { executions.findById(ws.id, executionId) } returns record(executedByThisUser = false)

        memberships(WorkspaceRole.VIEWER)
        authority().mayRead(subscriberAtOpen(), executionId) shouldBe false

        memberships(WorkspaceRole.WORKSPACE_ADMIN)
        authority().mayRead(subscriberAtOpen(), executionId) shouldBe true
    }

    @Test
    fun `a failure behind the cache is a refusal, not an exception - fail closed`() {
        liveUser()
        every { repository.membershipsOf(userId) } throws IllegalStateException("store down")
        ownRun()

        authority().mayRead(subscriberAtOpen(), executionId) shouldBe false
    }

    private fun workspaceService() =
        WorkspaceService(
            repository,
            mockk<ApiKeyRepository>(relaxed = true),
            mockk(relaxed = true),
            AuthCache(AuthProperties()),
            null as LastUsedWorkspaceStore?,
            mockk<AuditLogger>(relaxed = true),
            mockk<WorkspaceInvitationRepository>(relaxed = true),
            AuthProperties(),
            WorkspaceContentCheck.NONE,
        )
}
