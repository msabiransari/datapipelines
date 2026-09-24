package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.method.HandlerMethod
import java.util.UUID

/**
 * AUTH-SEC-9 / AU-TEST-1: `@RequiredScope` declares the handler's §7.6 catalog permission and
 * the principal's ROLE decides (#215 — scopes are gone), denials are audited as
 * `auth.scope.denied`, an unauthenticated hit on a governed handler is `auth.api_key.missing`
 * (401), an **unannotated** handler on a governed surface is denied by default with
 * `auth.permission.undeclared`, and a key off its kind's surface is refused before its
 * permission is judged (B2's second line, behind `ApiKeyFilter`).
 */
class ScopeInterceptorTest {
    private val mapper = ObjectMapper()
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val interceptor = ScopeInterceptor(AuthErrorWriter(mapper), auditLogger)

    /** Method-level annotations, including a deliberately unannotated handler. */
    class ProbeController {
        @RequiredScope(Permission.PIPELINE_READ)
        fun read() = Unit

        @RequiredScope(Permission.DATASOURCE_GRANT)
        fun adminOnly() = Unit

        fun unannotated() = Unit
    }

    /** Class-level annotation — the documented fallback for every handler in a controller. */
    @RequiredScope(Permission.USER_MANAGE)
    class AdminController {
        fun anything() = Unit
    }

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    /**
     * The workspace context every request carries since RBAC round 1: a principal with no
     * resolved workspace is refused with `workspace.not_found` before any role is examined
     * (D-R5). A workspace ADMIN's session — the highest member role, and since B2 the only kind
     * of principal that reaches REST and the partials besides a key on its own surface.
     */
    private fun adminContext() = WorkspaceContext(UUID.randomUUID(), "acme", WorkspaceRole.WORKSPACE_ADMIN)

    private fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "a@b.com",
                "A",
                AuthMethod.OIDC,
                workspaceName = "acme",
                workspace = adminContext(),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun invoke(
        bean: Any,
        method: String,
        path: String = "/api/v1/probe",
    ): Pair<Boolean, MockHttpServletResponse> {
        val handler = HandlerMethod(bean, bean.javaClass.getMethod(method))
        val response = MockHttpServletResponse()
        val proceed = interceptor.preHandle(MockHttpServletRequest("GET", path), response, handler)
        return proceed to response
    }

    private fun body(response: MockHttpServletResponse): Map<*, *> =
        (mapper.readValue(response.contentAsString, Map::class.java)["error"] as Map<*, *>)

    @Test
    fun `a principal whose role holds the declared permission proceeds`() {
        authenticate()
        invoke(ProbeController(), "read").first.shouldBeTrue()
    }

    /**
     * B2's second line: the MCP key is confined to `/mcp`. `ApiKeyFilter` refuses it first on the
     * wire (`AuthHttpBoundaryTest`); the interceptor states the same confinement for every MVC
     * handler, so a principal that reached one anyway is refused with the confinement code before
     * its role is asked — even a workspace admin's key on a read.
     */
    @Test
    fun `an MCP key off mcp is refused with the confinement code before its role is judged (B2)`() {
        val key =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "a@b.com",
                "A",
                AuthMethod.API_KEY,
                "dpk_ABCDEFGHIJKL",
                workspaceName = "acme",
                workspace = adminContext(),
                keyKind = ApiKeyKind.USER,
            )
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(key, null, emptyList())

        listOf("/api/v1/probe", "/partials/probe", "/pipelines").forEach { path ->
            val (proceed, response) = invoke(ProbeController(), "read", path)

            proceed.shouldBeFalse()
            response.status shouldBe 403
            body(response)["code"] shouldBe ScopeInterceptor.ENDPOINT_KEY_KIND_REFUSED
            (body(response)["details"] as Map<*, *>)["reason"] shouldBe "user_key_off_surface"
        }
        invoke(ProbeController(), "read", "/mcp").first.shouldBeTrue()
    }

    @Test
    fun `a permission the role lacks is 403 auth-role_required and audited as auth-scope-denied`() {
        authenticate()

        val (proceed, response) = invoke(ProbeController(), "adminOnly")

        proceed.shouldBeFalse()
        response.status shouldBe 403
        body(response)["code"] shouldBe AuthErrorCodes.ROLE_REQUIRED
        (body(response)["details"] as Map<*, *>)["required"] shouldBe "datasource.grant"
        (body(response)["details"] as Map<*, *>)["held"] shouldBe "workspace_admin"
        verify { auditLogger.log("auth.scope.denied", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the class-level annotation is the fallback when the method carries none`() {
        authenticate()

        val (proceed, response) = invoke(AdminController(), "anything")

        proceed.shouldBeFalse()
        response.status shouldBe 403
        (body(response)["details"] as Map<*, *>)["required"] shouldBe "user.manage"
    }

    /**
     * #215 A.6 — the ROLE refusal's details, on the REST surface and on a partial (one
     * interceptor, two paths): `required` names the catalog permission, `held` the role the
     * principal was judged as. The MCP surface's twin is `McpToolDispatcherTest`'s.
     */
    @Test
    fun `a role refusal names the catalog permission and the role - on the API and on a partial`() {
        listOf("/api/v1/probe", "/partials/probe").forEach { path ->
            val viewer =
                AuthenticatedPrincipal(
                    UUID.randomUUID(),
                    "v@b.com",
                    "V",
                    AuthMethod.OIDC,
                    workspace = WorkspaceContext(UUID.randomUUID(), "acme", WorkspaceRole.VIEWER),
                )
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(viewer, null, emptyList())

            val (proceed, response) = invoke(ProbeController(), "adminOnly", path)

            proceed.shouldBeFalse()
            response.status shouldBe 403
            body(response)["code"] shouldBe AuthErrorCodes.ROLE_REQUIRED
            val details = body(response)["details"] as Map<*, *>
            details["required"] shouldBe "datasource.grant"
            details["held"] shouldBe "viewer"
            details["operation"] shouldBe "datasource.grant"
        }
    }

    @Test
    fun `no principal on a scoped handler is 401 auth-api_key-missing`() {
        val (proceed, response) = invoke(ProbeController(), "read")

        proceed.shouldBeFalse()
        response.status shouldBe 401
        body(response)["code"] shouldBe "auth.api_key.missing"
    }

    @Test
    fun `an unannotated handler under the api prefix is denied by default`() {
        authenticate()

        val (proceed, response) = invoke(ProbeController(), "unannotated", path = "/api/v1/forgotten")

        proceed.shouldBeFalse()
        response.status shouldBe 403
        body(response)["code"] shouldBe AuthErrorCodes.PERMISSION_UNDECLARED
        (body(response)["details"] as Map<*, *>)["route"] shouldBe "GET /api/v1/forgotten"
        (body(response)["details"] as Map<*, *>)["reason"] shouldBe "handler_not_annotated"
    }

    @Test
    fun `an unannotated handler on the mcp endpoint is denied by default`() {
        authenticate()

        val (proceed, response) = invoke(ProbeController(), "unannotated", path = "/mcp")

        proceed.shouldBeFalse()
        response.status shouldBe 403
    }

    @Test
    fun `an unannotated handler under the partials prefix is denied by default`() {
        // 022 review F6: the htmx partials are reachable with an API key like any route —
        // they joined the governed prefixes so a forgotten annotation cannot fail open.
        authenticate()

        val (proceed, response) = invoke(ProbeController(), "unannotated", path = "/partials/datasources")

        proceed.shouldBeFalse()
        response.status shouldBe 403
        body(response)["code"] shouldBe AuthErrorCodes.PERMISSION_UNDECLARED
        (body(response)["details"] as Map<*, *>)["reason"] shouldBe "handler_not_annotated"
    }

    @Test
    fun `an annotated partial handler enforces its permission - a role that lacks it cannot mutate`() {
        // The F6 pin at the unit level: `adminOnly` declares the super admin's `datasource.grant`;
        // a workspace admin on a /partials path is denied exactly like its REST twin.
        authenticate()

        val (proceed, response) = invoke(ProbeController(), "adminOnly", path = "/partials/probe")

        proceed.shouldBeFalse()
        response.status shouldBe 403
        body(response)["code"] shouldBe AuthErrorCodes.ROLE_REQUIRED
    }

    @Test
    fun `an unannotated handler outside the matrix-governed surfaces is left alone`() {
        val (proceed, response) = invoke(ProbeController(), "unannotated", path = "/login")

        proceed.shouldBeTrue()
        response.status shouldBe 200
    }

    @Test
    fun `a non-handler-method (static resource) is never scope-checked`() {
        val response = MockHttpServletResponse()
        interceptor
            .preHandle(MockHttpServletRequest("GET", "/api/v1/probe"), response, "not-a-handler-method")
            .shouldBeTrue()
    }

    // ------------------------------------------------------------------ no reachable workspace, in a browser

    private fun authenticateSessionWithoutWorkspace() {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "nobody@b.com",
                "Nobody",
                AuthMethod.OIDC,
                workspace = null,
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun invokeWith(
        path: String,
        vararg headers: Pair<String, String>,
    ): Pair<Boolean, MockHttpServletResponse> {
        val handler = HandlerMethod(ProbeController(), ProbeController::class.java.getMethod("read"))
        val request = MockHttpServletRequest("GET", path)
        headers.forEach { (k, v) -> request.addHeader(k, v) }
        val response = MockHttpServletResponse()
        return interceptor.preHandle(request, response, handler) to response
    }

    /**
     * §11A.1 — a PERSON with no reachable workspace navigating to a page is sent to the
     * workspaces screen (the one that can explain their state), not handed a JSON envelope.
     * Still refused (the handler never runs) and still audited.
     */
    @Test
    fun `a session with no workspace navigating to a page is redirected to the workspaces screen`() {
        authenticateSessionWithoutWorkspace()

        val (proceed, response) = invokeWith("/pipelines", "Accept" to "text/html,application/xhtml+xml")

        proceed.shouldBeFalse()
        response.status shouldBe 302
        response.redirectedUrl shouldBe "/workspaces"
        verify { auditLogger.log("auth.scope.denied", any(), any(), any(), any(), any()) }
    }

    /** The API, a fragment swap and a non-HTML client keep the catalogued 404 envelope. */
    @Test
    fun `the same session gets the JSON 404 on the api, on a partial, and without an html accept`() {
        authenticateSessionWithoutWorkspace()

        listOf(
            invokeWith("/api/v1/pipelines", "Accept" to "text/html"),
            invokeWith("/pipelines", "Accept" to "text/html", "HX-Request" to "true"),
            invokeWith("/pipelines", "Accept" to "application/json"),
        ).forEach { (proceed, response) ->
            proceed.shouldBeFalse()
            response.status shouldBe 404
            body(response)["code"] shouldBe "workspace.not_found"
        }
    }

    /** A key never has "no workspace" as a browsing problem — it keeps the envelope on every path. */
    @Test
    fun `an api key with no workspace context is the JSON 404 even on a page path`() {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "agent@b.com",
                "Agent",
                AuthMethod.API_KEY,
                "dpk_ABCDEFGHIJKL",
                workspaceName = "acme",
                workspace = null,
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())

        val (proceed, response) = invokeWith("/pipelines", "Accept" to "text/html")

        proceed.shouldBeFalse()
        response.status shouldBe 404
        body(response)["code"] shouldBe "workspace.not_found"
    }
}
