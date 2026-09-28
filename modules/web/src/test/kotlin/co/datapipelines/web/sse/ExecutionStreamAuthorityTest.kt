package co.datapipelines.web.sse

import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.AuthCache
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.AuthenticatedPrincipal
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
import co.datapipelines.web.CapturingSseEmitter
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
    private val wsOther =
        Workspace(UUID.randomUUID(), "other", "other", isPersonal = false, createdBy = null, isDeleted = false, createdAt = Instant.now())

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

    private fun authority(nowMillis: () -> Long = System::currentTimeMillis) =
        ExecutionStreamAuthority(
            executions,
            PrincipalLiveness(users, WorkspaceLiveness { true }),
            workspaceService(),
            users,
            nowMillis,
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
        // The stale claim ("gone-ws") must not demote the instance authority. Since #263 the
        // re-judgement asks the OPEN-TIME resolution ("acme") rather than the claim, so the
        // claim is doubly irrelevant — the resolution below still runs the super-admin-aware
        // constructor (#216's), which is what this test pins.
        liveUser(isAdmin = true)
        every { repository.membershipsOf(userId) } returns listOf(memberRow(WorkspaceRole.VIEWER))
        every { repository.findByName("gone-ws") } returns null
        every { repository.findByName("acme") } returns ws
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
    fun `a header-switched subscriber re-judges the workspace it OPENED in, not the claim (#263)`() {
        // Opened under `DP-Workspace: acme` while the JWT claim names `other`: the request
        // path resolved the HEADER, so the re-judgement must re-resolve `acme`. The pre-#263
        // shape re-resolved the claim instead, found no record there, compared `other` != the
        // open-time `acme`, and cut the stream at its first write.
        liveUser()
        every { repository.membershipsOf(userId) } returns
            listOf(
                WorkspaceMembership(ws.id, "acme", WorkspaceRole.AUTHOR, Instant.now(), workspaceActive = true),
                WorkspaceMembership(wsOther.id, "other", WorkspaceRole.VIEWER, Instant.now(), workspaceActive = true),
            )
        every { repository.findByName("acme") } returns ws
        every { repository.findByName("other") } returns wsOther
        ownRun()

        val subscriber = subscriberAtOpen().copy(workspaceName = "other")

        authority().mayRead(subscriber, executionId) shouldBe true
    }

    @Test
    fun `a header-switched subscriber is cut when the opened workspace's membership is revoked (#263)`() {
        // The same subscriber: the re-judgement still RE-CHECKS the acme membership through
        // the cache — the open-time resolution is a claim on the workspace, not an entitlement.
        liveUser()
        every { repository.membershipsOf(userId) } returns
            listOf(WorkspaceMembership(wsOther.id, "other", WorkspaceRole.VIEWER, Instant.now(), workspaceActive = true))
        every { repository.findByName("acme") } returns ws
        every { repository.findByName("other") } returns wsOther
        ownRun()
        // The fallback resolves the OTHER workspace, whose scoped read misses the execution: the cut
        // is the record-miss + id-mismatch branch, not the strict mock's answerless throw (the 262
        // security pass, observation 1 — a refusal through the fail-closed catch proves nothing).
        every { executions.findById(wsOther.id, executionId) } returns null

        val subscriber = subscriberAtOpen().copy(workspaceName = "other")

        authority().mayRead(subscriber, executionId) shouldBe false
    }

    @Test
    fun `a UI-shaped subscriber - claim equals the resolution - is judged exactly as before (#263)`() {
        liveUser()
        memberships(WorkspaceRole.AUTHOR)
        ownRun()

        authority().mayRead(subscriberAtOpen(), executionId) shouldBe true
    }

    @Test
    fun `a failure behind the cache is a refusal, not an exception - fail closed`() {
        liveUser()
        every { repository.membershipsOf(userId) } throws IllegalStateException("store down")
        ownRun()

        authority().mayRead(subscriberAtOpen(), executionId) shouldBe false
    }

    // ------------------------------------------------------------ token expiry (#263)

    @Test
    fun `a write before the token's expiry is judged normally (#263)`() {
        // Fixed clock, never a sleep: exp is one second in the future, the full standing is
        // green, so the write is served.
        val clock = AtomicLong(1_000_000L)
        liveUser()
        memberships(WorkspaceRole.AUTHOR)
        ownRun()

        val subscriber = subscriberAtOpen().copy(sessionExpiresAtMillis = 1_001_000L)

        authority({ clock.get() }).mayRead(subscriber, executionId) shouldBe true
    }

    @Test
    fun `a write at the token's expiry instant is refused - everything else green, only the expiry can refuse (#263)`() {
        // The rest of the standing is fully stubbed green: with the expiry check removed this
        // returns TRUE (that is the falsification), so the refusal can only be the expiry's.
        val clock = AtomicLong(1_000_000L)
        liveUser()
        memberships(WorkspaceRole.AUTHOR)
        ownRun()

        val subscriber = subscriberAtOpen().copy(sessionExpiresAtMillis = 1_000_000L)

        authority({ clock.get() }).mayRead(subscriber, executionId) shouldBe false
    }

    @Test
    fun `a subscriber with no recorded expiry is not expiry-judged - the pre-#263 shape`() {
        liveUser()
        memberships(WorkspaceRole.AUTHOR)
        ownRun()

        authority().mayRead(subscriberAtOpen(), executionId) shouldBe true
    }

    @Test
    fun `an expired cut ends the stream with the same final comment and marks it expired (#263)`() {
        // The delivered-then-cut pair: one write before expiry served, the next after it cut —
        // `:revoked` comment (the static string, unchanged from #230), stream revoked AND
        // expired, so the duration timer records `expired`, never a disconnect.
        val clock = AtomicLong(1_000_000L)
        liveUser()
        memberships(WorkspaceRole.AUTHOR)
        ownRun()

        val emitter = CapturingSseEmitter()
        val stream =
            ExecutionStream(
                executionId,
                userId,
                emitter,
                JsonMapper.builder().build(),
                nowMillis = clock::get,
                subscriber = subscriberAtOpen().copy(sessionExpiresAtMillis = 1_000_500L),
                authority = authority({ clock.get() }),
            )

        stream.send("execution_started", 1, mapOf("execution_id" to executionId.toString())) shouldBe true
        clock.set(1_000_500L)
        stream.send("node_started", 2, emptyMap()) shouldBe false

        emitter.completed.await(5, TimeUnit.SECONDS) shouldBe true
        stream.isRevoked shouldBe true
        stream.isExpired shouldBe true
        emitter.frames().any { it.contains("revoked") } shouldBe true
        emitter.eventNames() shouldBe listOf("execution_started")
    }

    @Test
    fun `a revocation whose token expires between the verdict and the close reason is tagged revoked, not expired (#271)`() {
        // The authority judges once and carries the reason: before #271 the stream re-asked
        // hasExpired AFTER mayRead refused, so a token expiring in between tagged a standing
        // revocation (the member was removed) as `expired`. The clock here ticks past the
        // expiry on its SECOND read — the first is the verdict's own expiry check.
        val reads = AtomicLong(0)
        liveUser()
        memberships()
        ownRun()

        val stream =
            ExecutionStream(
                executionId,
                userId,
                CapturingSseEmitter(),
                JsonMapper.builder().build(),
                subscriber = subscriberAtOpen().copy(sessionExpiresAtMillis = 1_000_500L),
                authority = authority({ if (reads.getAndIncrement() == 0L) 1_000_000L else 1_000_500L }),
            )

        stream.send("node_started", 1, emptyMap()) shouldBe false

        stream.isRevoked shouldBe true
        stream.isExpired shouldBe false
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
