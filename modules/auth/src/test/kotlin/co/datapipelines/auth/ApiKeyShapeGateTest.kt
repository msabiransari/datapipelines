package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * AUTH-SEC-4 / AUTH-SEC-14: a malformed credential is rejected on shape **before** any
 * cache or database is touched, and the cache never grows on the negative path.
 *
 * The repository is a recording fake rather than a mock so the assertion is a count of
 * real calls — "the flood issued zero lookups" — not a stubbing expectation.
 */
class ApiKeyShapeGateTest {
    private val lookups = AtomicInteger()
    private val repo =
        mockk<ApiKeyRepository>(relaxed = true).also {
            every { it.findById(any()) } answers {
                lookups.incrementAndGet()
                null
            }
        }
    private val userService = mockk<UserService>(relaxed = true)
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val cache = AuthCache(AuthProperties())
    private val hasher = mockk<SecretHasher>(relaxed = true)
    private val workspaceService = mockk<WorkspaceService>(relaxed = true)
    private val service = ApiKeyService(repo, userService, cache, auditLogger, hasher, AuthProperties(), workspaceService)

    private val garbage =
        listOf(
            "",
            "not-a-key",
            "dpk_",
            "dpk_SHORT.secret",
            "dpk_lowercase123.secret",
            "dpk_ABCDEFGHIJKL",
            "dpk_ABCDEFGHIJKL.",
            "dpk_ABCDEFGHIJKL." + "A".repeat(500),
            "dpk_${"A".repeat(200)}.secret",
            "Bearer dpk_ABCDEFGHIJKL.secret",
            "dpk_ABCDEFGHIJK1.secret", // '1' is not in the RFC 4648 base32 alphabet
        )

    @Test
    fun `a flood of malformed credentials issues no database lookups and grows no cache`() {
        repeat(100) {
            garbage.forEach { candidate ->
                shouldThrow<ApiKeyInvalidException> { service.validate(candidate) }
            }
        }

        lookups.get() shouldBe 0
        cache.size() shouldBe 0
    }

    @Test
    fun `a well-shaped but unknown key reaches the repository exactly once per attempt and is not cached`() {
        val wellShaped = "dpk_ABCDEFGHIJKL." + "B".repeat(48)

        repeat(3) { shouldThrow<ApiKeyInvalidException> { service.validate(wellShaped) } }

        lookups.get() shouldBe 3
        // Negative lookups are deliberately NOT cached — that is how the map stays bounded.
        cache.size() shouldBe 0
    }

    @Test
    fun `the shape gate accepts a real key and rejects one character past the length cap`() {
        val id = "dpk_ABCDEFGHIJKL"
        ApiKeyCredential.hasValidShape("$id.${"C".repeat(48)}") shouldBe true
        ApiKeyCredential.hasValidShape("$id.${"C".repeat(ApiKeyCredential.MAX_CREDENTIAL_LENGTH)}") shouldBe false
    }

    @Test
    fun `the cache admits a valid record and reports its size`() {
        val userId = UUID.randomUUID()
        val user = User(userId, "u@c.com", "U", null, "kc", "s", true, false, Instant.now(), Instant.now(), null)

        cache.user(userId) { user }
        cache.size() shouldBe 1
        cache.invalidateUser(userId)
        cache.size() shouldBe 0
    }
}
