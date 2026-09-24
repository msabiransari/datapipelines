package co.datapipelines.auth

import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * #215 B5 (owner ruling 2026-09-24, record A9): the MCP key's role is its member's, re-read per
 * request THROUGH the auth cache — so a role change reaches the key within the cache TTL (60 s by
 * default), with no eviction machinery and no shared stamp.
 *
 * Two-sided, on the injected clock (never a sleep): INSIDE the TTL the key may be judged by either
 * role and nothing throws; PAST it the key is judged by the new role. The role change is made the
 * way another instance makes it — the store answers differently and this instance's cache is NOT
 * evicted — because that is the case the TTL bounds (a local change evicts at once).
 *
 * Built from the real [ApiKeyService], [WorkspaceService] and [AuthCache]; only the stores are
 * doubles, so the cache the assertion depends on is the production one.
 */
class McpKeyRoleFreshnessTest {
    private var nowNanos = 0L
    private val ttlSeconds = 60L
    private val properties = AuthProperties(apiKeys = AuthProperties.ApiKeys(cacheTtlSeconds = ttlSeconds))
    private val cache = AuthCache(properties) { nowNanos }

    private val workspaceRepository = mockk<WorkspaceRepository>()
    private val keyRepository = mockk<ApiKeyRepository>(relaxed = true)
    private val userService = mockk<UserService>()
    private val hasher = Argon2SecretHasher()

    private val workspaceService =
        WorkspaceService(
            workspaceRepository,
            keyRepository,
            mockk(relaxed = true),
            cache,
            null,
            mockk(relaxed = true),
            mockk(relaxed = true),
            properties,
        )

    private val service =
        ApiKeyService(
            keyRepository,
            userService,
            cache,
            mockk(relaxed = true),
            hasher,
            workspaceService,
            PrincipalLiveness(userService) { true },
        )

    private val memberId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val keyId = "dpk_FRESHNESSKEY"
    private val plaintext = "$keyId." + "A".repeat(SECRET_CHARS)

    init {
        every { keyRepository.findById(keyId) } returns
            ApiKey(
                id = keyId,
                userId = memberId,
                name = "mcp/acme",
                keyHash = hasher.hash(plaintext),
                isRevoked = false,
                createdAt = Instant.now(),
                lastUsedAt = null,
                expiresAt = null,
                workspaceId = workspaceId,
                workspaceName = "acme",
                kind = ApiKeyKind.USER,
            )
        every { userService.isActive(memberId) } returns true
        every { userService.snapshot(memberId) } returns
            User(
                id = memberId,
                email = "member@company.com",
                displayName = "Member",
                provider = "test",
                providerSubject = "member",
                isActive = true,
                isAdmin = false,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
    }

    private fun storeSays(role: WorkspaceRole) {
        every { workspaceRepository.membershipsOf(memberId) } returns
            listOf(WorkspaceMembership(workspaceId, "acme", role, Instant.now(), workspaceActive = true))
    }

    private fun advanceSeconds(seconds: Long) {
        nowNanos += seconds * NANOS_PER_SECOND
    }

    @Test
    fun `a promotion reaches the MCP key within the TTL - either role inside it, the new role after it`() {
        storeSays(WorkspaceRole.VIEWER)
        service.validate(plaintext).heldRole shouldBe "viewer" // warms the cache

        storeSays(WorkspaceRole.AUTHOR) // promoted on another instance: no local eviction

        advanceSeconds(ttlSeconds - 1)
        val inside = service.validate(plaintext)
        inside.heldRole shouldBeIn listOf("viewer", "author")

        advanceSeconds(2)
        val after = service.validate(plaintext)
        after.heldRole shouldBe "author"
        after.holds(Permission.TEMPLATE_CREATE) shouldBe true
    }

    @Test
    fun `a demotion likewise - past the TTL the key is refused what the lower role lacks`() {
        storeSays(WorkspaceRole.AUTHOR)
        service.validate(plaintext).holds(Permission.TEMPLATE_CREATE) shouldBe true

        storeSays(WorkspaceRole.VIEWER)
        advanceSeconds(ttlSeconds + 1)

        val after = service.validate(plaintext)
        after.heldRole shouldBe "viewer"
        after.holds(Permission.TEMPLATE_CREATE) shouldBe false
    }

    @Test
    fun `a promotion to workspace admin still reaches the key capped at author (PK4)`() {
        storeSays(WorkspaceRole.VIEWER)
        service.validate(plaintext)

        storeSays(WorkspaceRole.WORKSPACE_ADMIN)
        advanceSeconds(ttlSeconds + 1)

        val after = service.validate(plaintext)
        after.heldRole shouldBe "author"
        after.holds(Permission.EXECUTION_READ_ALL) shouldBe false
        after.isSuperAdmin shouldBe false
    }

    private companion object {
        const val SECRET_CHARS = 48
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
