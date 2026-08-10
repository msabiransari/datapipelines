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
    private val service = ApiKeyService(repo, userService, cache, auditLogger, hasher, AuthProperties())

    private val ownerId = UUID.randomUUID()

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

    private fun issueKey(): IssuedApiKey {
        val hash = slot<String>()
        every { repo.insert(any(), ownerId, any(), capture(hash), any(), any()) } answers {
            ApiKey(firstArg(), ownerId, thirdArg(), hash.captured, arg(4), false, Instant.now(), null, arg(5))
        }
        every { userService.snapshot(ownerId) } returns
            User(ownerId, "o@c.com", "O", null, "kc", "s", true, false, Instant.now(), Instant.now(), null)
        val issued = service.issue(ownerId, "k", setOf(Scope.READ), setOf(Scope.READ))
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
        every { repo.revoke(issued.record.id, ownerId) } returns true
        every { repo.findById(issued.record.id) } returns issued.record.copy(isRevoked = true)

        service.revoke(issued.record.id, ownerId)

        shouldThrow<ApiKeyInvalidException> { service.validate(issued.plaintext) }
    }
}
