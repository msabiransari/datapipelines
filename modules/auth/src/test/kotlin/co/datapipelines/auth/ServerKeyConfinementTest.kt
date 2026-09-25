package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.method.HandlerMethod
import java.util.UUID

/**
 * §7.7 (091) — where a `server`-kind key may go at all: the promotion receiver's route family,
 * and nowhere else.
 *
 * The sibling of [EndpointKeyConfinementTest], deliberately the same shape, because the two
 * kinds answer the same question differently: an endpoint key's authority is its bindings, a
 * server key's is a route family, and NEITHER is a scope — so the §7.6 matrix cannot judge
 * either and a central allowlist has to.
 *
 * ## Why an UNannotated route is in the refused list
 *
 * The confinement is decided before `@RequiredScope` is read. A UI page carries no annotation
 * and lives outside the governed prefixes, so an annotation-first order would let every screen
 * in the product — `/dashboard`, `/settings/api-keys`, the key list itself — answer a
 * credential whose entire authority is a deployment-to-deployment channel. `/dashboard` below
 * is that case, and it fails if the order is put back.
 */
class ServerKeyConfinementTest {
    private val mapper = ObjectMapper()
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val interceptor = ScopeInterceptor(AuthErrorWriter(mapper), auditLogger)

    @RequiredScope(Permission.PIPELINE_READ)
    class AnnotatedProbe {
        fun anything() = Unit
    }

    /** A handler with no `@RequiredScope`, like every UI controller in `web`. */
    class UnannotatedProbe {
        fun anything() = Unit
    }

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    @Test
    fun `a server key reaches the promotion route family`() {
        authenticate(ApiKeyKind.SERVER)

        assertAll(
            REACHABLE.map { path ->
                { withClue(path) { invoke(path).first.shouldBeTrue() } }
            },
        )
    }

    @Test
    fun `a server key is refused everywhere else, annotated or not`() {
        authenticate(ApiKeyKind.SERVER)

        assertAll(
            REFUSED.map { path ->
                {
                    withClue(path) {
                        val (proceed, response) = invoke(path, annotated = ANNOTATED_PREFIXES.any { path.startsWith(it) })
                        proceed.shouldBeFalse()
                        response.status shouldBe 403
                        error(response)["code"] shouldBe "endpoint.key_kind_refused"
                        error(response)["details"].let { it as Map<*, *> }["reason"] shouldBe "server_key_off_surface"
                    }
                }
            },
        )
    }

    @Test
    fun `an endpoint key is refused on an unannotated UI page too`() {
        // The same tightening, seen from the other kind: before 091 the confinement was read
        // after the annotation, so a scopeless endpoint key could render `/settings/api-keys`
        // and read the owner's whole key list. It is one rule for both kinds now.
        authenticate(ApiKeyKind.ENDPOINT)

        val (proceed, response) = invoke("/settings/api-keys", annotated = false)

        proceed.shouldBeFalse()
        response.status shouldBe 403
        error(response)["details"].let { it as Map<*, *> }["reason"] shouldBe "endpoint_key_off_surface"
    }

    @Test
    fun `the MCP key is confined to mcp - the promotion routes and every other route refuse it (B2)`() {
        // Until #215 slice (b) this was the complement — an ordinary key untouched by the server
        // confinement. Since B2 the MCP key is a confined kind itself: `/mcp` is its whole reach.
        authenticate(ApiKeyKind.MCP)

        assertAll(
            (REACHABLE + REFUSED).filterNot { it == "/mcp" || it.startsWith("/mcp/") }.map { path ->
                { withClue(path) { invoke(path, annotated = true).first.shouldBeFalse() } }
            },
        )
        invoke("/mcp", annotated = true).first.shouldBeTrue()
    }

    @Test
    fun `the reach table states each kind's whole surface`() {
        // The table is the confinement. Asserting it directly means a future kind cannot be
        // added with a silently permissive default — `when` over the enum is exhaustive, so a
        // new kind does not compile until someone answers this question for it.
        assertAll(
            { ScopeInterceptor.reachableBy(ApiKeyKind.SERVER, "/api/v1/promotion/inventory") shouldBe true },
            { ScopeInterceptor.reachableBy(ApiKeyKind.SERVER, "/api/v1/promotion/push") shouldBe true },
            // Not a prefix match on the SEGMENT: `/api/v1/promotions` is a different route.
            { ScopeInterceptor.reachableBy(ApiKeyKind.SERVER, "/api/v1/promotions") shouldBe false },
            { ScopeInterceptor.reachableBy(ApiKeyKind.SERVER, "/api/nyc/v1/revenue") shouldBe false },
            { ScopeInterceptor.reachableBy(ApiKeyKind.ENDPOINT, "/api/v1/promotion/push") shouldBe false },
            { ScopeInterceptor.reachableBy(ApiKeyKind.ENDPOINT, "/api/nyc/v1/revenue") shouldBe true },
            // R-EP5 — the rule is the first segment, never a literal prefix: the reserved
            // categories (the product's own v<n> namespace, and 'api') open nothing.
            { ScopeInterceptor.reachableBy(ApiKeyKind.ENDPOINT, "/api/v1/revenue") shouldBe false },
            { ScopeInterceptor.reachableBy(ApiKeyKind.ENDPOINT, "/api/v10/revenue") shouldBe false },
            { ScopeInterceptor.reachableBy(ApiKeyKind.ENDPOINT, "/api/api/v1/revenue") shouldBe false },
            // A category that merely STARTS with a reserved word is an engineer's namespace.
            { ScopeInterceptor.reachableBy(ApiKeyKind.ENDPOINT, "/api/v1a/revenue") shouldBe true },
            { ScopeInterceptor.reachableBy(ApiKeyKind.ENDPOINT, "/api/apis/v1/revenue") shouldBe true },
            // B2: the MCP key reaches `/mcp` and nothing else.
            { ScopeInterceptor.reachableBy(ApiKeyKind.MCP, "/api/v1/promotion/push") shouldBe false },
            { ScopeInterceptor.reachableBy(ApiKeyKind.MCP, "/api/v1/pipelines") shouldBe false },
            { ScopeInterceptor.reachableBy(ApiKeyKind.MCP, "/mcp") shouldBe true },
            { ScopeInterceptor.reachableBy(ApiKeyKind.MCP, "/mcp/") shouldBe true },
            { ScopeInterceptor.reachableBy(ApiKeyKind.MCP, "/mcpx") shouldBe false },
        )
    }

    private fun authenticate(kind: ApiKeyKind) {
        val principal =
            AuthenticatedPrincipal(
                userId = UUID.randomUUID(),
                email = "a@b.com",
                displayName = "A",
                authMethod = AuthMethod.API_KEY,
                keyId = "dpk_ABCDEFGHIJKL",
                keyKind = kind,
                // Since RBAC round 1 `ScopeMatrix.allowed` judges BOTH axes, so a principal
                // with no resolved workspace is refused with `workspace.not_found` before any
                // scope is read (D-R5). These suites are about the KIND confinement, so the
                // context is a workspace admin's: everything the confinement lets through must
                // then reach the handler rather than dying on an unrelated refusal.
                workspaceName = "acme",
                workspace =
                    WorkspaceContext(
                        UUID.randomUUID(),
                        "acme",
                        WorkspaceRole.WORKSPACE_ADMIN,
                    ),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun invoke(
        path: String,
        annotated: Boolean = true,
    ): Pair<Boolean, MockHttpServletResponse> {
        val bean: Any = if (annotated) AnnotatedProbe() else UnannotatedProbe()
        val handler = HandlerMethod(bean, bean.javaClass.getMethod("anything"))
        val response = MockHttpServletResponse()
        return interceptor.preHandle(MockHttpServletRequest("GET", path), response, handler) to response
    }

    private fun error(response: MockHttpServletResponse): Map<*, *> =
        mapper.readValue(response.contentAsString, Map::class.java)["error"] as Map<*, *>

    private companion object {
        /**
         * The prefixes whose handlers really do declare a §7.6 operation. Everything else in
         * [REFUSED] is driven through an UNannotated handler, which is the shape of every UI
         * page — and the case the confinement order exists for.
         */
        val ANNOTATED_PREFIXES = listOf("/api/", "/partials/", "/mcp")

        /** The whole of a server key's reach (rest-api.md §18's two operations). */
        val REACHABLE =
            listOf(
                "/api/v1/promotion/inventory",
                "/api/v1/promotion/push",
            )

        /** Every one of these would be open to a server key without the central allowlist. */
        val REFUSED =
            listOf(
                "/api/v1/pipelines",
                "/api/v1/datasources",
                "/api/v1/templates",
                "/api/v1/auth/api-keys",
                "/api/v1/auth/me",
                "/api/v1/workspaces",
                "/api/v1/endpoints",
                "/api/v1/executions/2f1c9c2e-0000-0000-0000-000000000001/result",
                "/api/nyc/v1/revenue/Manhattan",
                "/partials/api-keys",
                "/mcp",
                // Unannotated, outside the governed prefixes — see the class KDoc.
                "/dashboard",
                "/api-console",
            )
    }
}
