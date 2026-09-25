package co.datapipelines.web.workspaces

import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.AuthErrorWriter
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.ScopeInterceptor
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.auth.WorkspaceService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.method.HandlerMethod
import java.util.UUID

/**
 * The 025 review's blocking finding, pinned at the interceptor — and, since #215 B2, its
 * strongest form. The finding: an API key authenticates on every path and is CSRF-exempt, so a
 * key once drove all five `/api/v1/workspaces` mutations. Round 1 raised the floors; slice (b)
 * removes the question: **the MCP (`user`) key is confined to `/mcp`** (owner ruling 2026-09-24:
 * "MCP key should be only MCP"), so no key reaches ANY workspace route — a read, a membership
 * mutation or an instance verb — whatever its member's role. This suite runs the REAL
 * [ScopeInterceptor] (the second line; `ApiKeyFilter` refuses first, `ApiKeyFilterTest`) against
 * the REAL [WorkspacesController] handler methods.
 *
 * Until slice (b) the key of a workspace ADMIN passed the three membership mutations here (the
 * role axis admitted it, the `author` scope floor was met). That expectation was run against this
 * slice's code once and went red (the lane's falsification log); the refusal below replaced it.
 * The same admin's SESSION still passes them — the refusal is the credential kind's, not the role's.
 */
class WorkspacesControllerApiKeyTest {
    private val mapper = ObjectMapper()
    private val interceptor = ScopeInterceptor(AuthErrorWriter(mapper), mockk<AuditLogger>(relaxed = true))
    private val controller = WorkspacesController(mockk<WorkspaceService>(relaxed = true))

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    /** A workspace ADMIN's MCP key — the highest member role, uncapped, so nothing but its kind can refuse it. */
    private fun authenticateMcpKey() = authenticate(AuthMethod.API_KEY, keyKind = ApiKeyKind.MCP, keyId = "dpk_TESTKEY")

    private fun authenticateSession() = authenticate(AuthMethod.OIDC, keyKind = null, keyId = null)

    private fun authenticate(
        method: AuthMethod,
        keyKind: ApiKeyKind?,
        keyId: String?,
    ) {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "agent@company.com",
                "Agent",
                method,
                keyId = keyId,
                workspaceName = "acme",
                workspace = WorkspaceContext(UUID.randomUUID(), "acme", WorkspaceRole.WORKSPACE_ADMIN),
                keyKind = keyKind,
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    /** The membership verbs a workspace admin's SESSION drives. */
    private fun mutations(): List<Triple<String, HandlerMethod, String>> =
        listOf(
            Triple("PUT", handler("update", String::class.java, JsonNode::class.java), "/api/v1/workspaces/acme"),
            Triple("POST", handler("addMember", String::class.java, JsonNode::class.java), "/api/v1/workspaces/acme/members"),
            Triple("DELETE", handler("removeMember", String::class.java, UUID::class.java), "/api/v1/workspaces/acme/members/$USER_ID"),
        )

    /** The INSTANCE verbs (D-R11/D-R10) — a super admin's, never a key's (B1). */
    private fun instanceVerbs(): List<Triple<String, HandlerMethod, String>> =
        listOf(
            Triple("POST", handler("create", JsonNode::class.java), "/api/v1/workspaces"),
            Triple("DELETE", handler("delete", String::class.java), "/api/v1/workspaces/acme"),
            Triple("POST", handler("deactivate", String::class.java), "/api/v1/workspaces/acme/deactivate"),
            Triple("POST", handler("reactivate", String::class.java), "/api/v1/workspaces/acme/reactivate"),
        )

    /** The reads — once reachable by any key holding `read`. */
    private fun reads(): List<Triple<String, HandlerMethod, String>> =
        listOf(
            Triple("GET", handler("list"), "/api/v1/workspaces"),
            Triple("GET", handler("get", String::class.java), "/api/v1/workspaces/acme"),
            Triple("GET", handler("members", String::class.java), "/api/v1/workspaces/acme/members"),
        )

    private fun handler(
        name: String,
        vararg params: Class<*>,
    ): HandlerMethod = HandlerMethod(controller, WorkspacesController::class.java.getMethod(name, *params))

    private fun invoke(
        method: String,
        handler: HandlerMethod,
        path: String,
    ): Pair<Boolean, MockHttpServletResponse> {
        val response = MockHttpServletResponse()
        val proceed = interceptor.preHandle(MockHttpServletRequest(method, path), response, handler)
        return proceed to response
    }

    @Test
    fun `a workspace admin's MCP key is refused on every workspace route with the confinement code (B2)`() {
        authenticateMcpKey()
        val routes = everyMappedRoute()

        routes.forEach { (method, handler, path) ->
            val (proceed, response) = invoke(method, handler, path)
            withClue("$method $path") {
                proceed.shouldBeFalse()
                response.status shouldBe 403
                val error = mapper.readValue(response.contentAsString, Map::class.java)["error"] as Map<*, *>
                error["code"] shouldBe ScopeInterceptor.ENDPOINT_KEY_KIND_REFUSED
                (error["details"] as Map<*, *>)["reason"] shouldBe "mcp_key_off_surface"
            }
        }
        // Non-vacuity: the walk found the controller's routes — the reads, the membership verbs
        // and the instance verbs above among them — rather than an empty reflection result.
        routes.size shouldBeGreaterThanOrEqual MINIMUM_ROUTES
        routes
            .map { it.second.method }
            .toSet()
            .containsAll(
                (reads() + mutations() + instanceVerbs()).map { it.second.method },
            ).shouldBeTrue()
    }

    /**
     * Every handler [WorkspacesController] maps, as (verb, handler, concrete path) — read off its own
     * mapping annotations, so a route added later is walked without an edit here.
     */
    private fun everyMappedRoute(): List<Triple<String, HandlerMethod, String>> {
        val prefix =
            WorkspacesController::class.java
                .getAnnotation(RequestMapping::class.java)
                ?.value
                ?.firstOrNull()
                .orEmpty()
        return WorkspacesController::class.java.methods.mapNotNull { method ->
            val (verb, own) =
                method.getAnnotation(GetMapping::class.java)?.let { "GET" to it.value.firstOrNull().orEmpty() }
                    ?: method.getAnnotation(PostMapping::class.java)?.let { "POST" to it.value.firstOrNull().orEmpty() }
                    ?: method.getAnnotation(PutMapping::class.java)?.let { "PUT" to it.value.firstOrNull().orEmpty() }
                    ?: method.getAnnotation(PatchMapping::class.java)?.let { "PATCH" to it.value.firstOrNull().orEmpty() }
                    ?: method.getAnnotation(DeleteMapping::class.java)?.let { "DELETE" to it.value.firstOrNull().orEmpty() }
                    ?: return@mapNotNull null
            Triple(verb, HandlerMethod(controller, method), (prefix + own).replace(Regex("\\{[^}]+}"), "x"))
        }
    }

    @Test
    fun `the same workspace admin's SESSION passes the membership floors - the refusal is the kind's, not the role's`() {
        authenticateSession()

        mutations().forEach { (method, handler, path) ->
            invoke(method, handler, path).first.shouldBeTrue()
        }
        reads().forEach { (method, handler, path) ->
            invoke(method, handler, path).first.shouldBeTrue()
        }
    }

    /**
     * 114 §C.3a through the REAL interceptor: a session with no reachable workspace still
     * lists its own workspaces (`GET /api/v1/workspaces`, the switcher's `WORKSPACE_SWITCH` row —
     * the no-workspace page's own route is `WORKSPACES_READ`), and nothing else. A key in the
     * same state does not — `RoleMatrixTest` pins the matrix, this pins the wire.
     */
    @Test
    fun `a session with no workspace lists its own workspaces and passes no other operation`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    UUID.randomUUID(),
                    "nobody@company.com",
                    "Nobody",
                    AuthMethod.OIDC,
                    workspace = null,
                ),
                null,
                emptyList(),
            )

        invoke("GET", handler("list"), "/api/v1/workspaces").first.shouldBeTrue()
        val (proceed, response) =
            invoke("POST", handler("addMember", String::class.java, JsonNode::class.java), "/api/v1/workspaces/acme/members")
        proceed shouldBe false
        response.status shouldBe 404
    }

    private companion object {
        val USER_ID: UUID = UUID.randomUUID()

        /** The 2026-09-24 count is 13; the floor catches an empty or truncated reflection walk. */
        const val MINIMUM_ROUTES = 12
    }
}
