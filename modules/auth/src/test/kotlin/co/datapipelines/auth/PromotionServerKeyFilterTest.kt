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
    fun `a valid key authenticates as the system service account with author and no workspace`() {
        val chain = MockFilterChain()

        filter(configuredKey = KEY).doFilter(promotionRequest(header = KEY), MockHttpServletResponse(), chain)

        chain.request.shouldNotBeNull()
        val principal = SecurityContextHolder.getContext().authentication?.principal as AuthenticatedPrincipal
        principal.userId shouldBe systemActor.id
        principal.email shouldBe UserService.SYSTEM_ACTOR_EMAIL
        principal.authMethod shouldBe AuthMethod.PROMOTION
        principal.scopes shouldBe setOf(Scope.AUTHOR)
        // Not admin: the receiver resolves the target workspace by name from the payload, so
        // no membership bypass is needed and none is granted.
        principal.isAdmin shouldBe false
        // No workspace pinned — the credential belongs to a deployment, not to a workspace.
        principal.workspace shouldBe null
        principal.workspaceName shouldBe null
    }

    @Test
    fun `the granted authority is author and nothing above it`() {
        filter(configuredKey = KEY).doFilter(promotionRequest(header = KEY), MockHttpServletResponse(), MockFilterChain())

        val authorities =
            SecurityContextHolder
                .getContext()
                .authentication
                ?.authorities
                ?.map { it.authority }
        authorities shouldBe listOf("SCOPE_author")
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

    private fun filter(configuredKey: String?) =
        PromotionServerKeyFilter(
            PromotionProperties(serverKey = configuredKey),
            userService,
            errorWriter,
            auditLogger,
            clientAddressResolver,
        )

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
        const val PRESENTED = "promotion-fixture-wrong-key-also-not-a-secret"

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
