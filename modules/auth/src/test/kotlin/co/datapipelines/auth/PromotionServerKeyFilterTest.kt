package co.datapipelines.auth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * The promotion credential gate at the filter boundary (versioning §10.6).
 *
 * The four fail-closed proofs §10.6 demands, each as its own assertion about what a REQUEST
 * gets:
 *
 * 1. A receiver with **no key configured** refuses every promotion request.
 * 2. A **wrong key** refuses — with the SAME code and body as (1), so a caller cannot tell a
 *    disabled receiver from a wrong key. (The compare's timing-safety is pinned separately by
 *    [PromotionServerKeysTest], which asserts the comparison function itself.)
 * 3. The server key on **any other route** authenticates nothing: the filter does not even
 *    read the header there, so the request continues unauthenticated and the chain's
 *    `anyRequest().authenticated()` answers 401.
 * 4. A **valid key** authenticates as the system service account (R7) with `author` and
 *    nothing more — no workspace pinned, because the payload names its own.
 *
 * Plus the property that cannot be inspected any other way: the key never reaches the log.
 */
class PromotionServerKeyFilterTest {
    private val userService = mockk<UserService>()
    private val apiKeyRepository = mockk<ApiKeyRepository>(relaxed = true)
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val errorWriter = AuthErrorWriter(ObjectMapper())
    private val clientAddressResolver = ClientAddressResolver(emptyList())

    private val appender = ListAppender<ILoggingEvent>()
    private lateinit var logger: Logger

    @BeforeEach
    fun setUp() {
        SecurityContextHolder.clearContext()
        every { userService.systemActor() } returns systemActor
        logger = LoggerFactory.getLogger(PromotionServerKeyFilter::class.java) as Logger
        appender.start()
        logger.addAppender(appender)
        logger.level = Level.DEBUG
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        SecurityContextHolder.clearContext()
    }

    // -------------------------------------------------------------------- 1 + 2: fail closed

    @Test
    fun `a receiver with NO key configured refuses every promotion request`() {
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter(configuredKey = null).doFilter(promotionRequest(header = KEY), response, chain)

        response.status shouldBe 401
        errorCodeOf(response) shouldBe AuthErrorCodes.PROMOTION_KEY_INVALID
        withClue("the chain must STOP — nothing downstream may answer a promotion request") {
            chain.request shouldBe null
        }
        SecurityContextHolder.getContext().authentication shouldBe null
    }

    @Test
    fun `a request carrying NO header is refused the same way`() {
        val response = MockHttpServletResponse()

        filter(configuredKey = KEY).doFilter(promotionRequest(header = null), response, MockFilterChain())

        response.status shouldBe 401
        errorCodeOf(response) shouldBe AuthErrorCodes.PROMOTION_KEY_INVALID
    }

    @Test
    fun `a wrong key is refused, and byte-identically to a receiver with promotion disabled`() {
        // The no-oracle property, asserted as an equality rather than described in a comment:
        // if these two bodies ever diverge, the response starts telling a prober which
        // deployment has promotion configured at all.
        val wrongKey = MockHttpServletResponse()
        filter(configuredKey = KEY).doFilter(promotionRequest(header = "not-the-key"), wrongKey, MockFilterChain())

        val noKeyConfigured = MockHttpServletResponse()
        filter(configuredKey = null).doFilter(promotionRequest(header = "not-the-key"), noKeyConfigured, MockFilterChain())

        wrongKey.status shouldBe noKeyConfigured.status
        bodyWithoutCorrelationId(wrongKey) shouldBe bodyWithoutCorrelationId(noKeyConfigured)
    }

    @Test
    fun `a near-miss key is refused - the shared prefix buys nothing`() {
        val response = MockHttpServletResponse()

        filter(configuredKey = KEY).doFilter(promotionRequest(header = KEY.dropLast(1)), response, MockFilterChain())

        response.status shouldBe 401
    }

    // -------------------------------------------------------------------- 3: scope of the key

    @Test
    fun `the server key authenticates NOTHING on any other route - the header is not even read`() {
        listOf("/api/v1/pipelines", "/api/v1/templates", "/api/v1/datasources", "/mcp", "/dashboard").forEach { path ->
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()
            val request = MockHttpServletRequest("GET", path).apply { addHeader(PromotionServerKeyFilter.HEADER, KEY) }

            filter(configuredKey = KEY).doFilter(request, response, chain)

            withClue(path) {
                // The filter is INERT here: the request continues, unauthenticated, and the
                // chain's own `anyRequest().authenticated()` is what answers it.
                chain.request.shouldNotBeNull()
                SecurityContextHolder.getContext().authentication shouldBe null
                response.status shouldBe 200
            }
            SecurityContextHolder.clearContext()
        }
    }

    @Test
    fun `the filter never consults the user service off its own route`() {
        val request = MockHttpServletRequest("GET", "/api/v1/pipelines").apply { addHeader(PromotionServerKeyFilter.HEADER, KEY) }

        filter(configuredKey = KEY).doFilter(request, MockHttpServletResponse(), MockFilterChain())

        verify(exactly = 0) { userService.systemActor() }
    }

    // -------------------------------------------------------------------- 4: the actor

    @Test
    fun `the config value authenticates as the system service account with the promotion_receiver role and no workspace`() {
        val chain = MockFilterChain()

        filter(configuredKey = KEY).doFilter(promotionRequest(header = KEY), MockHttpServletResponse(), chain)

        chain.request.shouldNotBeNull()
        val principal = SecurityContextHolder.getContext().authentication?.principal as AuthenticatedPrincipal
        principal.userId shouldBe systemActor.id
        principal.email shouldBe UserService.SYSTEM_ACTOR_EMAIL
        principal.authMethod shouldBe AuthMethod.PROMOTION
        // #215 record §3.2 / B6: the receiver's two permissions and nothing else — not the
        // System-account-with-author authority it used to carry.
        principal.keyRole shouldBe KeyRole.PROMOTION_RECEIVER
        principal.holds(Permission.PROMOTION_PUSH) shouldBe true
        principal.holds(Permission.PROMOTION_INVENTORY_READ) shouldBe true
        principal.holds(Permission.PIPELINE_CREATE) shouldBe false
        // Not a super admin: the receiver resolves the target workspace by name from the
        // payload, so no membership bypass is needed and none is granted.
        principal.isSuperAdmin shouldBe false
        // No workspace pinned — the credential belongs to a deployment, not to a workspace.
        principal.workspace shouldBe null
        principal.workspaceName shouldBe null
    }

    @Test
    fun `no Spring authority is granted - the key role is the whole answer`() {
        filter(configuredKey = KEY).doFilter(promotionRequest(header = KEY), MockHttpServletResponse(), MockFilterChain())

        val authorities =
            SecurityContextHolder
                .getContext()
                .authentication
                ?.authorities
                ?.map { it.authority }
        authorities shouldBe emptyList()
    }

    // -------------------------------------------------------------------- 5: the stored server key (091)

    @Test
    fun `a stored server key authenticates the peer as the key's own identity, and stamps its usage`() {
        val chain = MockFilterChain()
        val key = serverKeyRecord()

        // No configured value at all — the deployment has migrated off it entirely.
        filter(configuredKey = null, stored = key)
            .doFilter(promotionRequest(header = key.plaintextFixture()), MockHttpServletResponse(), chain)

        chain.request.shouldNotBeNull()
        val principal = SecurityContextHolder.getContext().authentication?.principal as AuthenticatedPrincipal
        // #215 C4: the ACTOR is the key's own `service` identity — never the admin who minted it
        // (a promoted version must not be stamped with a person who did not perform the
        // promotion), and no longer the System account either.
        principal.userId shouldBe keyIdentity.id
        principal.displayName shouldBe keyIdentity.displayName
        principal.authMethod shouldBe AuthMethod.PROMOTION
        principal.keyRole shouldBe KeyRole.PROMOTION_RECEIVER
        principal.workspace shouldBe null
        // The key's id rides along so the audit trail can name WHICH key across a rotation.
        principal.keyId shouldBe key.id
        principal.keyKind shouldBe ApiKeyKind.SERVER
        principal.isServerKey shouldBe true
        verify(exactly = 1) { apiKeyRepository.touchUsage(key.id, any(), any()) }
    }

    @Test
    fun `the configured value is still accepted, and does not become a key principal`() {
        // The one-release overlap: an operator who has not migrated keeps working, and their
        // request is NOT attributed to a stored key that does not exist.
        val chain = MockFilterChain()

        filter(configuredKey = KEY).doFilter(promotionRequest(header = KEY), MockHttpServletResponse(), chain)

        chain.request.shouldNotBeNull()
        val principal = SecurityContextHolder.getContext().authentication?.principal as AuthenticatedPrincipal
        principal.keyId shouldBe null
        principal.keyKind shouldBe null
        verify(exactly = 0) { apiKeyRepository.touchUsage(any(), any(), any()) }
    }

    /**
     * 180 (D15): the liveness predicate now refuses a server key whose OWNER is deactivated
     * (`auth.principal_deactivated`) or whose PIN is deactivated (`auth.key_workspace_inactive`,
     * the gap closed in 180) — two catalogued codes elsewhere, folded into the ONE answer here.
     * A caller holding a stolen server key must not learn which liveness failed.
     */
    @Test
    fun `a deactivated owner and a deactivated pin are the one answer too, and neither code leaks`() {
        listOf(
            PrincipalDeactivatedException(java.util.UUID.randomUUID()),
            KeyWorkspaceInactiveException("acme"),
        ).forEach { livenessRefusal ->
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            filter(configuredKey = KEY, refusal = livenessRefusal)
                .doFilter(promotionRequest(header = "dpk_ZZZZZZZZZZZZ.$SECRET_HALF"), response, chain)

            response.status shouldBe 401
            errorCodeOf(response) shouldBe AuthErrorCodes.PROMOTION_KEY_INVALID
            response.contentAsString.contains(livenessRefusal.code) shouldBe false
            response.contentAsString.contains("acme") shouldBe false
            chain.request shouldBe null
            SecurityContextHolder.clearContext()
        }
    }

    @Test
    fun `a key the store refuses is refused here, with the one answer and no stamp`() {
        // Wrong kind, revoked, expired, unknown, malformed, deactivated owner — the store
        // answers all of them the same way, and so must this filter. `stored = null` IS that
        // answer; distinguishing them here would turn the route into a key-classification
        // oracle for anyone holding a stolen credential.
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter(configuredKey = KEY, stored = null)
            .doFilter(promotionRequest(header = "dpk_ZZZZZZZZZZZZ.$SECRET_HALF"), response, chain)

        response.status shouldBe 401
        errorCodeOf(response) shouldBe AuthErrorCodes.PROMOTION_KEY_INVALID
        chain.request shouldBe null
        SecurityContextHolder.getContext().authentication shouldBe null
        verify(exactly = 0) { apiKeyRepository.touchUsage(any(), any(), any()) }
    }

    @Test
    fun `the configured value is compared FIRST - a matching config value never reaches the key store`() {
        // A deployment that has not migrated pays no database read per promotion request. The
        // proof is the strict mock: the store would throw "no answer found" if it were called.
        val keyService = mockk<ApiKeyService>()
        val chain = MockFilterChain()

        PromotionServerKeyFilter(
            PromotionProperties(serverKey = KEY),
            userService,
            errorWriter,
            auditLogger,
            clientAddressResolver,
            keyService,
            apiKeyRepository,
        ).doFilter(promotionRequest(header = KEY), MockHttpServletResponse(), chain)

        chain.request.shouldNotBeNull()
        verify(exactly = 0) { keyService.validateServerKey(any()) }
    }

    @Test
    fun `a stored key is not consulted off the promotion route either`() {
        val keyService = mockk<ApiKeyService>()
        val key = serverKeyRecord()
        val request =
            MockHttpServletRequest("GET", "/api/v1/pipelines")
                .apply { addHeader(PromotionServerKeyFilter.HEADER, key.plaintextFixture()) }

        PromotionServerKeyFilter(
            PromotionProperties(serverKey = null),
            userService,
            errorWriter,
            auditLogger,
            clientAddressResolver,
            keyService,
            apiKeyRepository,
        ).doFilter(request, MockHttpServletResponse(), MockFilterChain())

        verify(exactly = 0) { keyService.validateServerKey(any()) }
        SecurityContextHolder.getContext().authentication shouldBe null
    }

    // -------------------------------------------------------------------- the redaction property

    @Test
    fun `neither the presented nor the configured key ever reaches the log or the response`() {
        val response = MockHttpServletResponse()

        filter(configuredKey = KEY).doFilter(promotionRequest(header = PRESENTED), response, MockFilterChain())

        val logged = appender.list.joinToString("\n") { it.formattedMessage }
        withClue("the filter logged the credential") {
            logged.shouldNotContain(KEY)
            logged.shouldNotContain(PRESENTED)
        }
        withClue("the refusal envelope echoed the credential") {
            response.contentAsString.shouldNotContain(KEY)
            response.contentAsString.shouldNotContain(PRESENTED)
        }
        // The refusal IS logged and audited — silence would be the other failure.
        logged.contains(PromotionServerKeyFilter.AUDIT_REJECTED) shouldBe true
    }

    @Test
    fun `the audit row records the reason and the path, never the credential`() {
        val details = slot<Map<String, Any?>>()
        every {
            auditLogger.log(
                event = PromotionServerKeyFilter.AUDIT_REJECTED,
                userId = any(),
                keyId = any(),
                sourceIp = any(),
                details = capture(details),
            )
        } returns Unit

        filter(configuredKey = KEY).doFilter(promotionRequest(header = PRESENTED), MockHttpServletResponse(), MockFilterChain())

        details.captured["reason"] shouldBe "key_mismatch"
        details.captured["path"] shouldBe PROMOTION_PATH
        details.captured.values
            .joinToString()
            .shouldNotContain(PRESENTED)
    }

    // -------------------------------------------------------------------- helpers

    /**
     * The filter under test. [stored] is what the key store answers for a presented credential:
     * a record (accepted), or nothing — the store then throws the ordinary invalid-key
     * exception, which is what it does for a malformed, unknown, revoked, expired, wrong-kind
     * key or a deactivated owner. One knob, because the filter must treat all of them alike.
     */
    private fun filter(
        configuredKey: String?,
        stored: ApiKey? = null,
        refusal: AuthException = ApiKeyInvalidException(),
    ): PromotionServerKeyFilter {
        val keyService = mockk<ApiKeyService>()
        if (stored == null) {
            every { keyService.validateServerKey(any()) } throws refusal
        } else {
            every { keyService.validateServerKey(stored.plaintextFixture()) } returns ValidatedServerKey(stored, keyIdentity)
            every { keyService.validateServerKey(neq(stored.plaintextFixture())) } throws ApiKeyInvalidException()
        }
        return PromotionServerKeyFilter(
            PromotionProperties(serverKey = configuredKey),
            userService,
            errorWriter,
            auditLogger,
            clientAddressResolver,
            keyService,
            apiKeyRepository,
        )
    }

    /** The plaintext a fixture record is presented as — the id half is enough for a mock's match. */
    private fun ApiKey.plaintextFixture(): String = "$id.$SECRET_HALF"

    private fun promotionRequest(header: String?): MockHttpServletRequest =
        MockHttpServletRequest("POST", PROMOTION_PATH).apply {
            header?.let { addHeader(PromotionServerKeyFilter.HEADER, it) }
        }

    private fun errorCodeOf(response: MockHttpServletResponse): String =
        ObjectMapper()
            .readTree(response.contentAsString)
            .path("error")
            .path("code")
            .asText()

    /** The envelope minus its per-request correlation id — everything a prober could compare. */
    private fun bodyWithoutCorrelationId(response: MockHttpServletResponse): String {
        val tree = ObjectMapper().readTree(response.contentAsString) as com.fasterxml.jackson.databind.node.ObjectNode
        tree.remove("correlation_id")
        return tree.toString()
    }

    private companion object {
        const val PROMOTION_PATH = "/api/v1/promotion/push"

        /** Fixtures, deliberately low-entropy — see PromotionServerKeysTest's note. */
        const val KEY = "promotion-fixture-key-not-a-real-secret"

        /** The secret half of a fixture `dpk_` credential. Shape only; nothing verifies it here. */
        const val SECRET_HALF = "FIXTURESECRETNOTAREALSECRETAAAAAAAAAAAAAAAAAAAAAA"
        const val PRESENTED = "promotion-fixture-wrong-key-also-not-a-secret"

        /** A `server`-kind row as the store would return it (091, auth.md §7.7). */
        fun serverKeyRecord(): ApiKey =
            ApiKey(
                id = "dpk_SERVERKEY12",
                userId = UUID.randomUUID(),
                name = "uat receiver",
                keyHash = "argon2-hash-not-verified-here",
                isRevoked = false,
                createdAt = Instant.EPOCH,
                lastUsedAt = null,
                expiresAt = null,
                workspaceId = UUID.randomUUID(),
                workspaceName = "default",
                kind = ApiKeyKind.SERVER,
            )

        /** The stored server key's own identity (#215 record §3.3) — what C4 attributes the push to. */
        val keyIdentity =
            User(
                id = UUID.randomUUID(),
                email = "dpk_serverkey12@keys.invalid",
                displayName = "uat receiver",
                provider = UserService.KEY_PROVIDER,
                providerSubject = "dpk_SERVERKEY12",
                isActive = true,
                isAdmin = false,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                kind = UserKind.SERVICE,
            )

        val systemActor =
            User(
                id = UUID.randomUUID(),
                email = UserService.SYSTEM_ACTOR_EMAIL,
                displayName = UserService.SYSTEM_ACTOR_DISPLAY_NAME,
                provider = UserService.SYSTEM_PROVIDER,
                providerSubject = UserService.SYSTEM_ACTOR_SUBJECT,
                isActive = true,
                isAdmin = false,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
    }
}
