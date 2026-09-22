package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The ONE liveness predicate (D15, roles design §3.5): user active ∧ pinned workspace active,
 * both through [AuthCache]'s TTL. Four combinations, plus the eviction contract — a
 * deactivate/reactivate on this instance is visible on the very next check rather than at
 * TTL expiry — proven against the REAL cache and repositories the services read through,
 * so a predicate that bypassed the cache (or a cache that never evicted) would fail here.
 */
class PrincipalLivenessTest {
    private val userRepository = mockk<UserRepository>()
    private val workspaceRepository = mockk<WorkspaceRepository>(relaxed = true)
    private val cache = AuthCache(AuthProperties())
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val userService = UserService(userRepository, cache, AuthProperties(), auditLogger)
    private val workspaceService =
        WorkspaceService(
            workspaceRepository,
            mockk(relaxed = true),
            userRepository,
            cache,
            null,
            auditLogger,
            mockk(relaxed = true),
            AuthProperties(),
        )
    private val liveness = PrincipalLiveness(userService, workspaceService)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private fun user(active: Boolean) = User(userId, "u@c.com", "U", null, "kc", "s", active, false, Instant.now(), Instant.now(), null)

    private fun workspace(active: Boolean) =
        Workspace(workspaceId, "acme", "Acme", false, null, false, Instant.now(), deactivatedAt = if (active) null else Instant.now())

    private fun stub(
        userActive: Boolean,
        workspaceActive: Boolean,
    ) {
        every { userRepository.findById(userId) } returns user(userActive)
        every { workspaceRepository.findById(workspaceId) } returns workspace(workspaceActive)
    }

    @Test
    fun `active user in an active workspace is live`() {
        stub(userActive = true, workspaceActive = true)

        liveness.check(userId, PrincipalLiveness.Pin(workspaceId, "acme")).shouldBeNull()
    }

    @Test
    fun `a deactivated user is refused with auth principal_deactivated, whatever the workspace`() {
        stub(userActive = false, workspaceActive = true)

        val refusal = liveness.check(userId, PrincipalLiveness.Pin(workspaceId, "acme"))

        refusal.shouldBeInstanceOf<PrincipalLiveness.Refusal.UserDeactivated>()
        refusal.userId shouldBe userId
        val thrown = shouldThrow<PrincipalDeactivatedException> { liveness.require(userId, PrincipalLiveness.Pin(workspaceId, "acme")) }
        thrown.code shouldBe AuthErrorCodes.PRINCIPAL_DEACTIVATED
        // The refusal is about the person: no workspace name travels with it.
        thrown.details.containsKey("workspace") shouldBe false
    }

    @Test
    fun `an active user pinned to a deactivated workspace keeps the 404 rule`() {
        stub(userActive = true, workspaceActive = false)

        val refusal = liveness.check(userId, PrincipalLiveness.Pin(workspaceId, "acme"))

        refusal.shouldBeInstanceOf<PrincipalLiveness.Refusal.WorkspaceDeactivated>()
        val thrown = shouldThrow<KeyWorkspaceInactiveException> { liveness.require(userId, PrincipalLiveness.Pin(workspaceId, "acme")) }
        thrown.code shouldBe AuthErrorCodes.KEY_WORKSPACE_INACTIVE
        thrown.status shouldBe 404
    }

    @Test
    fun `a deactivated user in a deactivated workspace is the USER refusal — the person is judged first`() {
        stub(userActive = false, workspaceActive = false)

        liveness.check(userId, PrincipalLiveness.Pin(workspaceId, "acme")).shouldBeInstanceOf<PrincipalLiveness.Refusal.UserDeactivated>()
    }

    @Test
    fun `no pin judges the user alone (a session resolving no workspace, the promotion peer)`() {
        every { userRepository.findById(userId) } returns user(true)

        liveness.check(userId, null).shouldBeNull()
    }

    @Test
    fun `a user the store no longer has is not live`() {
        every { userRepository.findById(userId) } returns null

        liveness.check(userId, null).shouldBeInstanceOf<PrincipalLiveness.Refusal.UserDeactivated>()
    }

    @Test
    fun `deactivating a user through the service is visible on the next check within the TTL`() {
        stub(userActive = true, workspaceActive = true)
        liveness.check(userId, null).shouldBeNull()
        every { userRepository.setActive(userId, false) } returns true
        every { userRepository.findById(userId) } returns user(false)

        userService.deactivate(userId, actorId = UUID.randomUUID())

        liveness.check(userId, null).shouldBeInstanceOf<PrincipalLiveness.Refusal.UserDeactivated>()
    }

    @Test
    fun `deactivating and reactivating a workspace through the service is visible on the next check within the TTL`() {
        stub(userActive = true, workspaceActive = true)
        val superAdmin =
            AuthenticatedPrincipal(
                userId = UUID.randomUUID(),
                email = "root@c.com",
                displayName = "Root",
                scopes = emptySet(),
                authMethod = AuthMethod.OIDC,
                superAdmin = true,
            )
        every { workspaceRepository.findByName("acme") } returns workspace(true)
        every { workspaceRepository.deactivate(workspaceId, superAdmin.userId) } returns true
        every { workspaceRepository.reactivate(workspaceId) } returns true
        every { workspaceRepository.findMembersOf(workspaceId) } returns emptyList()
        liveness.check(userId, PrincipalLiveness.Pin(workspaceId, "acme")).shouldBeNull()

        every { workspaceRepository.findById(workspaceId) } returns workspace(false)
        every { workspaceRepository.findByName("acme") } returns workspace(false)
        workspaceService.deactivate(superAdmin, "acme")
        liveness
            .check(userId, PrincipalLiveness.Pin(workspaceId, "acme"))
            .shouldBeInstanceOf<PrincipalLiveness.Refusal.WorkspaceDeactivated>()

        every { workspaceRepository.findById(workspaceId) } returns workspace(true)
        workspaceService.reactivate(superAdmin, "acme")
        liveness.check(userId, PrincipalLiveness.Pin(workspaceId, "acme")).shouldBeNull()
    }
}
