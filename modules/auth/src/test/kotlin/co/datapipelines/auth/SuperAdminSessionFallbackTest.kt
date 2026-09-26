package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.method.HandlerMethod
import java.time.Instant
import java.util.UUID

/**
 * #216 — a super admin's SESSION fallback context keeps the super-admin authority.
 *
 * `resolveForSession`'s claim branch resolves through `contextFor`, which builds a super-admin
 * context (`WorkspaceContext.superAdminOver`) for a super admin. The FALLBACK — claim null or no
 * longer resolving, first active membership — built a plain member context, so the one
 * authorization axis the interceptor reads (`WorkspaceContext.superAdmin`, D7) was dropped: every
 * instance permission answered `auth.role_required` until the person switched workspace. Whichever
 * branch produces the context, a super admin is a super admin in it.
 *
 * Two levels, both red on the unfixed tree: the unit assertion on the resolved context, and the
 * interceptor walk that decides `user.manage` the way a real request is judged.
 */
class SuperAdminSessionFallbackTest {
    private val repository = mockk<WorkspaceRepository>()
    private val cache = AuthCache(AuthProperties())

    /** Real collaborators, the [ScopeInterceptorTest] shape: the interceptor's own judgment, on the wire. */
    private val interceptor = ScopeInterceptor(AuthErrorWriter(ObjectMapper()), mockk(relaxed = true))

    private val userId = UUID.randomUUID()
    private val ws =
        Workspace(
            UUID.randomUUID(),
            "acme",
            "acme",
            isPersonal = false,
            createdBy = null,
            isDeleted = false,
            createdAt = Instant.now(),
        )

    /** The sharpest case: the explicit membership is the LEAST role, so only the flag can carry the authority. */
    private val viewerMembership = WorkspaceMembership(ws.id, ws.name, WorkspaceRole.VIEWER, Instant.now(), workspaceActive = true)

    /** A super admin whose stamped claim no longer resolves — the fallback branch is the only answer. */
    private fun superAdminPrincipal(): AuthenticatedPrincipal {
        every { repository.membershipsOf(userId) } returns listOf(viewerMembership)
        return AuthenticatedPrincipal(
            userId = userId,
            email = "root@company.test",
            displayName = "Root",
            authMethod = AuthMethod.OIDC,
            workspaceName = "gone-ws",
            superAdmin = true,
        )
    }

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    @Test
    fun `the fallback context of a super admin carries superAdmin - with the explicit membership's role`() {
        every { repository.findByName("gone-ws") } returns null

        val context = workspaceService().resolveForSession(superAdminPrincipal(), "gone-ws")

        context shouldNotBe null
        context!!.superAdmin shouldBe true
        context.id shouldBe ws.id
        context.role shouldBe WorkspaceRole.VIEWER
        // `implicit` reads the EXPLICIT membership (D-R8), as the claim branch's contextFor does.
        context.implicit shouldBe false
    }

    @Test
    fun `such a session reaches user_manage through the interceptor`() {
        every { repository.findByName("gone-ws") } returns null
        val resolved = workspaceService().resolveForSession(superAdminPrincipal(), "gone-ws")!!
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(superAdminPrincipal().copy(workspace = resolved), null, emptyList())

        val handler = HandlerMethod(Probe(), Probe::class.java.getMethod("manageUsers"))
        val proceed = interceptor.preHandle(MockHttpServletRequest("GET", "/api/v1/users"), MockHttpServletResponse(), handler)

        proceed.shouldBeTrue()
    }

    /** One `user.manage` handler — the instance permission the issue names. */
    class Probe {
        @RequiredScope(Permission.USER_MANAGE)
        fun manageUsers() = Unit
    }

    private fun workspaceService() =
        WorkspaceService(
            repository,
            mockk(relaxed = true),
            mockk(relaxed = true),
            cache,
            null,
            mockk(relaxed = true),
            mockk(relaxed = true),
            AuthProperties(),
        )
}
