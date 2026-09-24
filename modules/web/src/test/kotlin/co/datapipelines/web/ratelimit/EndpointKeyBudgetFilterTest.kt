package co.datapipelines.web.ratelimit

import co.datapipelines.auth.AuthErrorWriter
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.KeyRole
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.web.config.EndpointsProperties
import co.datapipelines.web.config.KeyRequestBudget
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * The serve path's per-key request budget (#224): the (max+1)th request inside a fixed window
 * answers the catalogued 429, the window boundary is FORCED through the clock seam (never
 * slept), keys do not share buckets, sessions are not budgeted, and only the serve subtree is
 * metered. Falsified at birth: with the filter's registration bean removed, the (max+1)th
 * request answers 200 — the E2E carries that direction, the wiring is one bean.
 */
class EndpointKeyBudgetFilterTest {
    private val registry = SimpleMeterRegistry()

    private val properties =
        EndpointsProperties(keyRequestBudget = KeyRequestBudget(windowSeconds = 60, maxRequests = 3))

    /** The clock seam: a fixed instant the tests advance by assignment. */
    private var now = 1_000_000L

    /** A fresh one-shot chain per call — MockFilterChain refuses a second invocation. */
    private var chain: MockFilterChain = MockFilterChain()

    private fun filter(maxTrackedKeys: Int = 10_000) =
        EndpointKeyBudgetFilter(properties, AuthErrorWriter(JsonMapper.builder().build()), registry, maxTrackedKeys, { now })

    private fun callerKey(suffix: String): AuthenticatedPrincipal =
        AuthenticatedPrincipal(
            userId = UUID.fromString("00000000-0000-0000-0000-00000000000$suffix"),
            email = "key@keys.invalid",
            displayName = "key-$suffix",
            authMethod = AuthMethod.API_KEY,
            keyId = "dpk_KEY${suffix}AAAA",
            workspace = WorkspaceContext(UUID.randomUUID(), "demo"),
            keyRole = KeyRole.API_CALLER,
        )

    private fun session(): AuthenticatedPrincipal =
        AuthenticatedPrincipal(
            userId = UUID.fromString("00000000-0000-0000-0000-000000000099"),
            email = "a@b.c",
            displayName = "A",
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext(UUID.randomUUID(), "demo"),
        )

    private fun principal(p: AuthenticatedPrincipal) {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(p, null, emptyList())
    }

    private fun serveRequest(): MockHttpServletRequest {
        val request = MockHttpServletRequest("GET", "/api/demo/nyc/mobility/revenue-by-borough")
        request.requestURI = "/api/demo/nyc/mobility/revenue-by-borough"
        return request
    }

    private fun EndpointKeyBudgetFilter.serve(): MockHttpServletResponse {
        chain = MockFilterChain()
        val response = MockHttpServletResponse()
        this.doFilter(serveRequest(), response, chain)
        return response
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `the (max+1)th request inside the window answers the catalogued 429 with Retry-After`() {
        principal(callerKey("1"))
        val f = filter()
        repeat(3) { f.serve().status shouldBe 200 }
        val fourth = f.serve()
        fourth.status shouldBe 429
        fourth.getHeader("Retry-After") shouldBe "60"
        fourth.contentAsString.contains("\"rate_limit.exceeded\"") shouldBe true
        fourth.contentAsString.contains("\"limit\":3") shouldBe true
        // The chain was STOPPED, not passed through: the handler never saw the request.
        chain.request.shouldBeNull()
    }

    @Test
    fun `the window rolls over and the key is served again - forced, not slept`() {
        principal(callerKey("1"))
        val f = filter()
        repeat(3) { f.serve().status shouldBe 200 }
        f.serve().status shouldBe 429
        now += 61_000L
        f.serve().status shouldBe 200
    }

    @Test
    fun `two keys do not share a bucket`() {
        principal(callerKey("1"))
        val f = filter()
        repeat(3) { f.serve().status shouldBe 200 }
        f.serve().status shouldBe 429
        principal(callerKey("2"))
        f.serve().status shouldBe 200
    }

    @Test
    fun `a session is not budgeted`() {
        principal(session())
        val f = filter()
        repeat(10) { f.serve().status shouldBe 200 }
        chain.request.shouldNotBeNull()
    }

    private fun EndpointKeyBudgetFilter.serveAt(uri: String): MockHttpServletResponse {
        chain = MockFilterChain()
        val request = MockHttpServletRequest("GET", uri)
        request.requestURI = uri
        val response = MockHttpServletResponse()
        this.doFilter(request, response, chain)
        return response
    }

    @Test
    fun `only the serve subtree is metered - the product's own routes and pages are not`() {
        principal(callerKey("1"))
        val f = filter()
        listOf("/api/v1/pipelines", "/mcp", "/login", "/demo-data").forEach { uri ->
            f.serveAt(uri).status shouldBe 200
            f.trackedKeyCount() shouldBe 0
        }
        // The serve subtree, same principal: metered.
        f.serve().status shouldBe 200
        f.trackedKeyCount() shouldBe 1
    }

    @Test
    fun `max-requests zero turns the budget off entirely`() {
        val off = EndpointsProperties(keyRequestBudget = KeyRequestBudget(windowSeconds = 60, maxRequests = 0))
        val f = EndpointKeyBudgetFilter(off, AuthErrorWriter(JsonMapper.builder().build()), registry, nowMillis = { now })
        principal(callerKey("1"))
        repeat(5) { f.serve().status shouldBe 200 }
        f.trackedKeyCount() shouldBe 0
    }

    @Test
    fun `a saturated table admits unmetered and counts the saturation - the failure mode is visible`() {
        principal(callerKey("1"))
        val f = filter(maxTrackedKeys = 1)
        repeat(3) { f.serve().status shouldBe 200 }
        registry.counter("datapipelines.web.endpoint_key_budget.saturated").count() shouldBe 0.0
        // A SECOND key at the ceiling: admitted unmetered (allow is the correct failure mode),
        // and the degradation is a counter an operator can alert on, not a quiet gap.
        principal(callerKey("2"))
        f.serve().status shouldBe 200
        registry.counter("datapipelines.web.endpoint_key_budget.saturated").count() shouldBe 1.0
    }
}
