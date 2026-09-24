package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * AUTH-SEC-3: the Argon2id verification OUTCOME is cached per key for the cache TTL,
 * so a busy agent pays the hash cost once per TTL instead of once per request — while
 * the D13 per-request checks (record staleness, revocation, owner liveness) are
 * untouched.
 */
class ApiKeyVerificationCacheTest {
    private var nowNanos = 0L
    private val ttlSeconds = 60L
    private val repo = mockk<ApiKeyRepository>(relaxed = true)
    private val userService = mockk<UserService>()
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val cache = AuthCache(AuthProperties(apiKeys = AuthProperties.ApiKeys(cacheTtlSeconds = ttlSeconds))) { nowNanos }
    private val hasher = CountingHasher()
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
            hasher,
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

    /** Counts verifications so "how often did Argon2 run?" is an assertion, not a guess. */
    private class CountingHasher : SecretHasher {
        val verifications = AtomicInteger()

        override fun hash(raw: String): String = "hashed:$raw"

        override fun verify(
            encodedHash: String,
            raw: String,
        ): Boolean {
            verifications.incrementAndGet()
            return encodedHash == "hashed:$raw"
        }
    }

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
        every { repo.insert(any(), identityId, ownerId, any(), capture(hash), any(), any(), ApiKeyKind.ENDPOINT) } answers {
            ApiKey(
                id = firstArg(),
                userId = identityId,
                name = arg(3),
                keyHash = hash.captured,
                isRevoked = false,
                createdAt = Instant.now(),
                lastUsedAt = null,
                expiresAt = arg(5),
                workspaceId = arg(6),
                workspaceName = "acme",
                kind = ApiKeyKind.ENDPOINT,
                createdBy = ownerId,
            )
        }
        every { userService.isActive(identityId) } returns true
        every { userService.snapshot(identityId) } returns identity()
        every { userService.deactivateIdentity(identityId) } returns Unit
        val issued = service.issue(issuerPrincipal, "k", workspaceId, kind = ApiKeyKind.ENDPOINT)
        every { repo.findById(issued.record.id) } returns issued.record
        return issued
    }

    @Test
    fun `Argon2 verification runs once per key per TTL window under repeated requests`() {
        val issued = issueKey()

        repeat(20) { service.validate(issued.plaintext) }
        hasher.verifications.get() shouldBe 1

        nowNanos += (ttlSeconds + 1) * 1_000_000_000L
        repeat(20) { service.validate(issued.plaintext) }
        hasher.verifications.get() shouldBe 2
    }

    @Test
    fun `a wrong secret is rejected every time and is never cached as valid`() {
        val issued = issueKey()
        service.validate(issued.plaintext) // warm the cache with the CORRECT secret
        val forged = "${issued.record.id}.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

        repeat(3) { shouldThrow<ApiKeyInvalidException> { service.validate(forged) } }

        // The real key still works — the failed attempts did not poison its entry.
        service.validate(issued.plaintext).keyId shouldBe issued.record.id
    }

    @Test
    fun `revoking a key evicts its cached verification outcome`() {
        val issued = issueKey()
        service.validate(issued.plaintext)
        every { repo.revoke(issued.record.id, ownerId) } returns issued.record.copy(isRevoked = true)
        every { repo.findById(issued.record.id) } returns issued.record.copy(isRevoked = true)

        service.revoke(issued.record.id, ownerId)

        shouldThrow<ApiKeyInvalidException> { service.validate(issued.plaintext) }
    }
}
