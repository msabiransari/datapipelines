package co.datapipelines.auth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/** API keys (auth.md §7): Argon2id issue/validate, revocation, expiry, escalation guard. */
class ApiKeyServiceTest {
    private val repo = mockk<ApiKeyRepository>(relaxed = true)
    private val userService = mockk<UserService>()
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val cache = AuthCache(AuthProperties())
    private val service = ApiKeyService(repo, userService, cache, auditLogger, Argon2SecretHasher(), AuthProperties())

    private val ownerId = UUID.randomUUID()

    private fun activeOwner() =
        User(ownerId, "owner@company.com", "Owner", null, "keycloak", "sub", true, false, Instant.now(), Instant.now(), null)

    /** Stubs `insert` to echo back a record built from its arguments (real keyHash). */
    private fun echoInsert(): io.mockk.CapturingSlot<String> {
        val hash = slot<String>()
        every { repo.insert(any(), ownerId, any(), capture(hash), any(), any()) } answers {
            ApiKey(
                id = firstArg(),
                userId = ownerId,
                name = thirdArg(),
                keyHash = hash.captured,
                scopes = arg(4),
                isRevoked = false,
                createdAt = Instant.now(),
                lastUsedAt = null,
                expiresAt = arg(5),
            )
        }
        return hash
    }

    @Test
    fun `issue returns a dpk_ plaintext and persists only the hash`() {
        echoInsert()
        val issued = service.issue(ownerId, "Claude", setOf(Scope.READ), setOf(Scope.AUTHOR))

        issued.plaintext shouldStartWith "dpk_"
        issued.record.id shouldStartWith "dpk_"
        // The stored hash is an Argon2id hash, never the plaintext.
        issued.record.keyHash shouldStartWith "\$argon2id\$"
        (issued.record.keyHash == issued.plaintext) shouldBe false
    }

    @Test
    fun `a freshly issued key validates and resolves the owner principal`() {
        echoInsert()
        val issued = service.issue(ownerId, "Claude", setOf(Scope.EXECUTE), setOf(Scope.AUTHOR))
        every { repo.findById(issued.record.id) } returns issued.record
        every { userService.snapshot(ownerId) } returns activeOwner()

        val principal = service.validate(issued.plaintext)

        principal.userId shouldBe ownerId
        principal.authMethod shouldBe AuthMethod.API_KEY
        principal.keyId shouldBe issued.record.id
        principal.scopes shouldContainExactlyInAnyOrder setOf(Scope.EXECUTE)
    }

    @Test
    fun `a wrong secret for a real key id is rejected as invalid`() {
        echoInsert()
        val issued = service.issue(ownerId, "Claude", setOf(Scope.READ), setOf(Scope.READ))
        every { repo.findById(issued.record.id) } returns issued.record
        every { userService.snapshot(ownerId) } returns activeOwner()

        val forged = "${issued.record.id}.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        shouldThrow<ApiKeyInvalidException> { service.validate(forged) }
    }

    @Test
    fun `a revoked key is rejected as invalid`() {
        echoInsert()
        val issued = service.issue(ownerId, "Claude", setOf(Scope.READ), setOf(Scope.READ))
        every { repo.findById(issued.record.id) } returns issued.record.copy(isRevoked = true)

        shouldThrow<ApiKeyInvalidException> { service.validate(issued.plaintext) }
    }

    @Test
    fun `an expired key maps to api_key expired`() {
        echoInsert()
        val issued = service.issue(ownerId, "Claude", setOf(Scope.READ), setOf(Scope.READ))
        every { repo.findById(issued.record.id) } returns issued.record.copy(expiresAt = Instant.now().minusSeconds(60))

        shouldThrow<ApiKeyExpiredException> { service.validate(issued.plaintext) }
    }

    @Test
    fun `a key whose owner is inactive is rejected as invalid`() {
        echoInsert()
        val issued = service.issue(ownerId, "Claude", setOf(Scope.READ), setOf(Scope.READ))
        every { repo.findById(issued.record.id) } returns issued.record
        every { userService.snapshot(ownerId) } returns activeOwner().copy(isActive = false)

        shouldThrow<ApiKeyInvalidException> { service.validate(issued.plaintext) }
    }

    @Test
    fun `an unknown key id is rejected as invalid`() {
        every { repo.findById(any()) } returns null
        shouldThrow<ApiKeyInvalidException> { service.validate("dpk_UNKNOWNKEYID.AAAAAAAAAAAAAAAAAAAAAAAA") }
    }

    @Test
    fun `escalation guard - a read creator cannot mint an author key (§7-4)`() {
        shouldThrow<ScopeInsufficientException> {
            service.issue(ownerId, "Escalate", setOf(Scope.AUTHOR), creatorScopes = setOf(Scope.READ))
        }
    }

    @Test
    fun `an empty scope request falls back to the CONFIGURED default-scopes (AU-API-3)`() {
        // A non-default configured value, so a hard-coded `read` cannot pass this test.
        val configured =
            AuthProperties(apiKeys = AuthProperties.ApiKeys(defaultScopes = listOf("execute")))
        val withDefaults = ApiKeyService(repo, userService, cache, auditLogger, Argon2SecretHasher(), configured)
        val scopes = slot<Set<Scope>>()
        every { repo.insert(any(), ownerId, any(), any(), capture(scopes), any()) } answers {
            ApiKey(firstArg(), ownerId, thirdArg(), arg(3), arg(4), false, Instant.now(), null, arg(5))
        }

        withDefaults.issue(ownerId, "Claude", emptySet(), creatorScopes = setOf(Scope.AUTHOR))

        scopes.captured shouldContainExactlyInAnyOrder setOf(Scope.EXECUTE)
    }

    @Test
    fun `an unusable configured default falls back to read (§7-5)`() {
        val configured = AuthProperties(apiKeys = AuthProperties.ApiKeys(defaultScopes = listOf("nonsense")))
        val withDefaults = ApiKeyService(repo, userService, cache, auditLogger, Argon2SecretHasher(), configured)
        val scopes = slot<Set<Scope>>()
        every { repo.insert(any(), ownerId, any(), any(), capture(scopes), any()) } answers {
            ApiKey(firstArg(), ownerId, thirdArg(), arg(3), arg(4), false, Instant.now(), null, arg(5))
        }

        withDefaults.issue(ownerId, "Claude", emptySet(), creatorScopes = setOf(Scope.AUTHOR))

        scopes.captured shouldContainExactlyInAnyOrder setOf(Scope.READ)
    }

    /**
     * API L5: the fallback above is silent degradation — an operator typo in
     * `default-scopes` produces `read` keys and no signal at all, so nobody finds out
     * until a key mysteriously lacks permission. The WARN must NAME the bad token
     * (rules/02), which is why this asserts the message content, not merely that
     * something was logged.
     */
    @Test
    fun `an unparseable configured default-scopes token is reported at WARN, naming the token`() {
        val configured =
            AuthProperties(apiKeys = AuthProperties.ApiKeys(defaultScopes = listOf("nonsense", "execute")))
        val withDefaults = ApiKeyService(repo, userService, cache, auditLogger, Argon2SecretHasher(), configured)
        val scopes = slot<Set<Scope>>()
        every { repo.insert(any(), ownerId, any(), any(), capture(scopes), any()) } answers {
            ApiKey(firstArg(), ownerId, thirdArg(), arg(3), arg(4), false, Instant.now(), null, arg(5))
        }
        val logged =
            captureWarnings(ApiKeyService::class.java) {
                withDefaults.issue(ownerId, "Claude", emptySet(), creatorScopes = setOf(Scope.AUTHOR))
            }

        logged.any { it.contains("nonsense") } shouldBe true
        // The good token still applies — one bad entry does not discard the whole list.
        scopes.captured shouldContainExactlyInAnyOrder setOf(Scope.EXECUTE)
    }

    /** Collects WARN-level messages emitted by [type]'s logger while [block] runs. */
    private fun captureWarnings(
        type: Class<*>,
        block: () -> Unit,
    ): List<String> {
        val logger = LoggerFactory.getLogger(type) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }
}
