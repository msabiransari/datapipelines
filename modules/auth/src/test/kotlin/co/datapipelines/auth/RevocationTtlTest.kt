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
    private val service = ApiKeyService(repo, userService, cache, auditLogger, Argon2SecretHasher(), AuthProperties())

    private val ownerId = UUID.randomUUID()

    private fun issueKey(): IssuedApiKey {
        val hash = slot<String>()
        every { repo.insert(any(), ownerId, any(), capture(hash), any(), any()) } answers {
            ApiKey(firstArg(), ownerId, thirdArg(), hash.captured, arg(4), false, Instant.now(), null, arg(5))
        }
        every { userService.snapshot(ownerId) } returns
            User(ownerId, "o@c.com", "O", null, "kc", "s", true, false, Instant.now(), Instant.now(), null)
        return service.issue(ownerId, "k", setOf(Scope.READ), setOf(Scope.READ))
    }

    @Test
    fun `local revoke evicts the cache and kills the key immediately`() {
        val issued = issueKey()
        every { repo.findById(issued.record.id) } returns issued.record
        service.validate(issued.plaintext) // primes the cache

        every { repo.revoke(issued.record.id, ownerId) } returns true
        every { repo.findById(issued.record.id) } returns issued.record.copy(isRevoked = true)
        service.revoke(issued.record.id, ownerId)

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
