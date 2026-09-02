package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * Per-request workspace resolution (design §5): the API-key header refusal, the
 * membership-checked `DP-Workspace` switch, and the session fallback chain.
 */
class WorkspaceResolutionFilterTest {
    private val workspaceService = mockk<WorkspaceService>()
    private val lastUsed = mockk<LastUsedWorkspaceStore>(relaxed = true)
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val filter =
        WorkspaceResolutionFilter(
            workspaceService,
            lastUsed,
            AuthErrorWriter(ObjectMapper()),
            auditLogger,
            ClientAddressResolver(emptyList()),
        )

    private val userId = UUID.randomUUID()
    private val acme = WorkspaceContext(UUID.randomUUID(), "acme")

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun authenticate(principal: AuthenticatedPrincipal) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                principal,
                null,
                principal.scopes.map { SimpleGrantedAuthority("SCOPE_${it.wire}") },
            )
    }

    private fun oidcPrincipal() =
        AuthenticatedPrincipal(userId, "a@c.com", "A", Scope.AUTHOR.expand(), AuthMethod.OIDC, workspaceName = "acme")

    private fun keyPrincipal() =
        AuthenticatedPrincipal(
            userId,
            "a@c.com",
            "A",
            setOf(Scope.READ),
            AuthMethod.API_KEY,
            keyId = "dpk_1",
            workspaceName = acme.name,
            workspace = acme,
        )

    private fun run(
        principal: AuthenticatedPrincipal?,
        header: String?,
    ): MockHttpServletResponse {
        if (principal != null) authenticate(principal)
        val request = MockHttpServletRequest("GET", "/api/v1/pipelines")
        header?.let { request.addHeader(WorkspaceResolutionFilter.WORKSPACE_HEADER, it) }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        return response
    }

    @Test
    fun `an API-key request with DP-Workspace is refused 400 header_forbidden and the chain stops`() {
        val response = run(keyPrincipal(), "other-ws")

        response.status shouldBe 400
        response.contentAsString.contains("workspace.header_forbidden") shouldBe true
        // The pinned context is untouched — the refusal never rewrites the principal.
        (SecurityContextHolder.getContext().authentication.principal as AuthenticatedPrincipal).workspace shouldBe acme
        verify { auditLogger.log("auth.workspace.header_rejected", userId, "dpk_1", any(), null, any()) }
    }

    @Test
    fun `an API-key request without the header passes with the pinned workspace`() {
        val response = run(keyPrincipal(), null)

        response.status shouldBe 200
        (SecurityContextHolder.getContext().authentication.principal as AuthenticatedPrincipal).workspace shouldBe acme
    }

    @Test
    fun `a session switch to a member workspace replaces the principal and records last-used`() {
        every { workspaceService.resolveSwitch(any(), "beta") } returns WorkspaceContext(UUID.randomUUID(), "beta")

        val response = run(oidcPrincipal(), "beta")

        response.status shouldBe 200
        val principal = SecurityContextHolder.getContext().authentication.principal as AuthenticatedPrincipal
        principal.workspace?.name shouldBe "beta"
        verify { lastUsed.recordUsed(userId, "beta") }
    }

    @Test
    fun `a session switch naming a non-membership is refused 403 membership_required`() {
        every { workspaceService.resolveSwitch(any(), "beta") } throws WorkspaceMembershipRequiredException()

        val response = run(oidcPrincipal(), "beta")

        response.status shouldBe 403
        response.contentAsString.contains("workspace.membership_required") shouldBe true
    }

    @Test
    fun `a session request without the header resolves the stamped claim`() {
        every { workspaceService.resolveForSession(any(), "acme") } returns acme

        val response = run(oidcPrincipal(), null)

        response.status shouldBe 200
        (SecurityContextHolder.getContext().authentication.principal as AuthenticatedPrincipal).workspace shouldBe acme
    }

    @Test
    fun `a zero-membership session proceeds with no workspace - scoped operations 403 downstream`() {
        every { workspaceService.resolveForSession(any(), null) } returns null

        val principal = oidcPrincipal().copy(workspaceName = null)
        val response = run(principal, null)

        response.status shouldBe 200
        (SecurityContextHolder.getContext().authentication.principal as AuthenticatedPrincipal).workspace.shouldBeNull()
    }

    @Test
    fun `an unauthenticated request passes through untouched`() {
        val response = run(null, "acme")

        response.status shouldBe 200
        SecurityContextHolder.getContext().authentication.shouldBeNull()
    }
}
