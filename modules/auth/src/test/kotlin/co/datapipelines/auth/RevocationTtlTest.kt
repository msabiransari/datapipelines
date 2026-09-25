package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * D13 revocation latency at the service level (auth.md §11.4): a locally revoked key
 * is dead immediately; a key revoked "elsewhere" (cache not invalidated) keeps working
 * only until the TTL elapses — never the full key lifetime.
 */
class RevocationTtlTest {
    private var nowNanos = 0L
    private val ttlSeconds = 60L
    private val repo = mockk<ApiKeyRepository>(relaxed = true)
    private val userService = mockk<UserService>()
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val cache = AuthCache(AuthProperties(apiKeys = AuthProperties.ApiKeys(cacheTtlSeconds = ttlSeconds))) { nowNanos }
    private val workspaceService =
        mockk<WorkspaceService>(relaxed = true) {
            // A relaxed mock answers `isActive` false, which would refuse every key here for
            // the wrong reason. The default is the live workspace and a workspace-admin issuer.
            every { isActive(any()) } returns true
            every { requireIssuancePermission(any(), any(), any()) } answers {
                WorkspaceContext(secondArg(), "acme", WorkspaceRole.WORKSPACE_ADMIN)
            }
        }
    private val service =
        ApiKeyService(
            repo,
            userService,
            cache,
            auditLogger,
            Argon2SecretHasher(),
            workspaceService,
            PrincipalLiveness(userService, workspaceService),
        )

    private val ownerId = UUID.randomUUID()
    private val identityId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    /**
     * The issuer, as issuance names it since RBAC round 1 (D-R12/O-2): a key is minted by a
     * person with a role in the pinned workspace, not by a bare user id.
     */
    private val issuerPrincipal =
        AuthenticatedPrincipal(
            userId = ownerId,
            email = "owner@company.com",
            displayName = "Owner",
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext(workspaceId, "acme", WorkspaceRole.WORKSPACE_ADMIN),
        )

    /** The key's own `service` identity (#215 record §3.3). */
    private fun identity() =
        User(
            id = identityId,
            email = "dpk_k@keys.invalid",
            displayName = "k",
            provider = UserService.KEY_PROVIDER,
            providerSubject = "dpk_k",
            isActive = true,
            isAdmin = false,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            kind = UserKind.SERVICE,
        )

    private fun issueKey(): IssuedApiKey {
        val hash = slot<String>()
        // #215: an `endpoint` key acts as its own identity (B4) — created with it, validated as it.
        every { userService.provisionIdentity(any(), any()) } answers { identity() }
        every { repo.insert(any(), identityId, ownerId, any(), capture(hash), any(), any(), any(), ApiKeyKind.ENDPOINT) } answers {
            ApiKey(
                id = firstArg(),
                userId = identityId,
                name = arg(3),
                keyHash = hash.captured,
                isRevoked = false,
                createdAt = Instant.now(),
                lastUsedAt = null,
                expiresAt = arg(6),
                workspaceId = arg(7),
                workspaceName = "acme",
                kind = ApiKeyKind.ENDPOINT,
                role = KeyRole.API_CALLER,
                createdBy = ownerId,
            )
        }
        every { userService.isActive(identityId) } returns true
        every { userService.snapshot(identityId) } returns identity()
        every { userService.deactivateIdentity(identityId) } returns Unit
        return service.issue(issuerPrincipal, "k", workspaceId, kind = ApiKeyKind.ENDPOINT)
    }

    @Test
    fun `local revoke evicts the cache and kills the key immediately`() {
        val issued = issueKey()
        every { repo.findById(issued.record.id) } returns issued.record
        service.validate(issued.plaintext) // primes the cache

        every { repo.revoke(issued.record.id, ownerId) } returns issued.record.copy(isRevoked = true)
        every { repo.findById(issued.record.id) } returns issued.record.copy(isRevoked = true)
        service.revokeOwn(issued.record.id, ownerId)

        shouldThrow<ApiKeyInvalidException> { service.validate(issued.plaintext) }
    }

    @Test
    fun `a key revoked elsewhere keeps working until the TTL, then dies`() {
        val issued = issueKey()
        every { repo.findById(issued.record.id) } returns issued.record
        service.validate(issued.plaintext) // cached as valid

        // Revoked on another instance — our local cache is NOT invalidated.
        every { repo.findById(issued.record.id) } returns issued.record.copy(isRevoked = true)
        service.validate(issued.plaintext) // still served from the stale cache within TTL

        nowNanos += (ttlSeconds + 1) * 1_000_000_000L
        shouldThrow<ApiKeyInvalidException> { service.validate(issued.plaintext) }
    }
}
