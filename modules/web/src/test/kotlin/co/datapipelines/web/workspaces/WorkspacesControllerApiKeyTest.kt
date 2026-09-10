package co.datapipelines.web.workspaces

import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.AuthErrorWriter
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeInterceptor
import co.datapipelines.auth.WorkspaceService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.method.HandlerMethod
import java.util.UUID

/**
 * The 025 review's blocking finding, pinned at the interceptor: an API key authenticates
 * on EVERY path and is CSRF-exempt, so before the floor raise a `read`-scoped key drove
 * all five `/api/v1/workspaces` mutations (create, rename, soft-delete, add/remove
 * member) — the in-handler `requireOwnerOrAdmin` checks the USER's role, never the
 * credential's scope. `WORKSPACE_CREATE` and `MANAGE_WORKSPACE` are now floored at
 * `author` (auth.md §7.6), and this suite runs the REAL [ScopeInterceptor] against the
 * REAL [WorkspacesController] handler methods with `DP-API-Key` principals, so a floor
 * regression on any of the five turns red here.
 *
 * **RBAC round 1 raised the floors again, and this suite raised with them.** Creating and
 * deactivating a workspace are `super_admin` on the role axis and `admin` on the scope axis —
 * and `admin` is no longer a scope a key may hold (O-2) — so a key CANNOT reach them at all,
 * by either axis. Membership management is `ws_admin`. The suite therefore now asserts the
 * stronger property: the credential axis alone stops a key on the two instance verbs, and the
 * ROLE axis is what admits or refuses it on the rest.
 *
 * The handler-annotation layer is what this class proves; the pinned-workspace rule for
 * key principals (the same finding's second half) is proven where the rule lives, in
 * auth's `WorkspaceKeyPinTest`. `WorkspacesControllerTest` covers the payloads; this
 * class covers who may reach them.
 */
class WorkspacesControllerApiKeyTest {
    private val mapper = ObjectMapper()
    private val interceptor = ScopeInterceptor(AuthErrorWriter(mapper), mockk<AuditLogger>(relaxed = true))
    private val controller = WorkspacesController(mockk<WorkspaceService>(relaxed = true))

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticateKey(scope: Scope) {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "agent@company.com",
                "Agent",
                scope.expand().filterTo(mutableSetOf()) { it in co.datapipelines.auth.KEY_SCOPES },
                AuthMethod.API_KEY,
                keyId = "dpk_TESTKEY",
                workspaceName = "acme",
                // Both axes are judged now, so the key needs a resolved workspace or every
                // call is `workspace.not_found` before any floor is read. Its ISSUER is a
                // workspace admin here — the highest role a key's issuer can be short of super
                // admin — so what refuses below is a floor, never a missing membership.
                workspace =
                    co.datapipelines.auth.WorkspaceContext(
                        UUID.randomUUID(),
                        "acme",
                        co.datapipelines.auth.MembershipFlags(author = true, promoter = true, admin = true),
                    ),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    /** The five mutations the finding named, each with its HTTP verb and path. */
    /** The verbs a workspace admin's key may drive: `ws_admin` on the role axis, `author` on the scope one. */
    private fun mutations(): List<Triple<String, HandlerMethod, String>> =
        listOf(
            Triple("PUT", handler("update", String::class.java, JsonNode::class.java), "/api/v1/workspaces/acme"),
            Triple("POST", handler("addMember", String::class.java, JsonNode::class.java), "/api/v1/workspaces/acme/members"),
            Triple("DELETE", handler("removeMember", String::class.java, UUID::class.java), "/api/v1/workspaces/acme/members/$USER_ID"),
        )

    /**
     * The INSTANCE verbs (D-R11/D-R10): `super_admin` on the role axis and `admin` on the scope
     * axis — and no key holds `admin` any more (O-2), so a key is stopped twice over.
     */
    private fun instanceVerbs(): List<Triple<String, HandlerMethod, String>> =
        listOf(
            Triple("POST", handler("create", JsonNode::class.java), "/api/v1/workspaces"),
            Triple("DELETE", handler("delete", String::class.java), "/api/v1/workspaces/acme"),
            Triple("POST", handler("deactivate", String::class.java), "/api/v1/workspaces/acme/deactivate"),
            Triple("POST", handler("reactivate", String::class.java), "/api/v1/workspaces/acme/reactivate"),
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
    fun `a read-scoped api key is 403 on all five workspace mutations`() {
        authenticateKey(Scope.READ)

        mutations().forEach { (method, handler, path) ->
            val (proceed, response) = invoke(method, handler, path)

            proceed.shouldBeFalse()
            response.status shouldBe 403
            val body = mapper.readValue(response.contentAsString, Map::class.java)["error"] as Map<*, *>
            body["code"] shouldBe "auth.scope.insufficient"
        }
    }

    @Test
    fun `an author-scoped key whose issuer is a workspace admin passes the membership floors`() {
        // The floors are the interceptor's whole say: the key's workspace pin is the service's
        // gate (WorkspaceKeyPinTest), reached only past this point.
        authenticateKey(Scope.AUTHOR)

        mutations().forEach { (method, handler, path) ->
            invoke(method, handler, path).first.shouldBeTrue()
        }
    }

    @Test
    fun `NO key reaches the instance verbs - the scope axis alone stops it (O-2)`() {
        // The strongest form of the 025 finding's fix: it is not that a key needs a higher
        // scope, it is that the scope it would need cannot be issued to a key at all. Even a
        // key whose issuer is a super admin is refused, because the refusal is the CREDENTIAL's.
        authenticateKey(Scope.AUTHOR)

        instanceVerbs().forEach { (method, handler, path) ->
            invoke(method, handler, path).first.shouldBeFalse()
        }
    }

    @Test
    fun `a read-scoped api key still reads the workspace surface`() {
        // WORKSPACES_READ stays read-floored (§7.6 "List / read own workspaces & members").
        authenticateKey(Scope.READ)

        invoke("GET", handler("list"), "/api/v1/workspaces").first.shouldBeTrue()
        invoke("GET", handler("get", String::class.java), "/api/v1/workspaces/acme").first.shouldBeTrue()
        invoke("GET", handler("members", String::class.java), "/api/v1/workspaces/acme/members").first.shouldBeTrue()
    }

    private companion object {
        val USER_ID: UUID = UUID.randomUUID()
    }
}
