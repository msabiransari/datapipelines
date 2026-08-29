package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.JwtService
import co.datapipelines.auth.Scope
import co.datapipelines.auth.User
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceDuplicateNameException
import co.datapipelines.auth.WorkspaceInUseException
import co.datapipelines.auth.WorkspaceMemberRow
import co.datapipelines.auth.WorkspaceMembershipRequiredException
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.auth.WorkspacesProperties
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * The workspace screen's mutating actions (ui-screens.md §4.13) — previously ZERO-covered.
 * Every action delegates to [WorkspaceService] and bounces back with `?ok=`/`?error=`
 * instead of an error page. Includes the 022 review F8 pin: an unknown member email is
 * the template's `user_not_found` banner, not a 500 ([WorkspaceService.UnknownMemberEmailException]
 * is an IllegalStateException, so the AuthException-only wrapper let it escape).
 */
class WorkspacesUiControllerTest {
    private val workspaceService = mockk<WorkspaceService>()
    private val userService = mockk<UserService>()
    private val jwtService = mockk<JwtService>()
    private val controller =
        WorkspacesUiController(
            workspaceService,
            userService,
            jwtService,
            AuthProperties(),
            WorkspacesProperties(),
            mockk<ThemeResolver>(),
        )

    private val userId = UUID.randomUUID()
    private val principal =
        AuthenticatedPrincipal(
            userId,
            "alice@acme.test",
            "Alice",
            setOf(Scope.AUTHOR),
            AuthMethod.OIDC,
            workspace = WorkspaceContext(UUID.randomUUID(), "acme"),
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun memberRow(email: String = "bob@acme.test") =
        WorkspaceMemberRow(UUID.randomUUID(), email, "Bob", WorkspaceRole.MEMBER, Instant.EPOCH)

    @Test
    fun `create redirects with ok=created - and a duplicate name is the banner, not an error page`() {
        authenticate()
        every { workspaceService.create(principal, "globex", "Globex") } returns mockk()
        controller.create("globex", "Globex") shouldBe "redirect:/workspaces?ok=created"

        every { workspaceService.create(principal, "acme", "acme") } throws WorkspaceDuplicateNameException("acme")
        controller.create(" acme ", null) shouldBe "redirect:/workspaces?error=duplicate_name"
    }

    @Test
    fun `join adds the caller's own email and redirects ok=joined`() {
        authenticate()
        every { workspaceService.addMember(principal, "globex", "alice@acme.test") } returns memberRow("alice@acme.test")

        controller.join("globex") shouldBe "redirect:/workspaces?ok=joined"
    }

    @Test
    fun `addMember redirects ok=member_added`() {
        authenticate()
        every { workspaceService.addMember(principal, "acme", "bob@acme.test") } returns memberRow()

        controller.addMember("acme", "bob@acme.test") shouldBe "redirect:/workspaces?ok=member_added"
    }

    @Test
    fun `addMember with an unknown email is the user_not_found banner - never a 500`() {
        authenticate()
        every { workspaceService.addMember(principal, "acme", "ghost@nowhere.test") } throws
            WorkspaceService.UnknownMemberEmailException("ghost@nowhere.test")

        controller.addMember("acme", "ghost@nowhere.test") shouldBe "redirect:/workspaces?error=user_not_found"
    }

    @Test
    fun `removeMember redirects ok=member_removed - and an owner target is the in_use banner`() {
        authenticate()
        val target = UUID.randomUUID()
        every { workspaceService.removeMember(principal, "acme", target) } returns Unit
        controller.removeMember("acme", target) shouldBe "redirect:/workspaces?ok=member_removed"

        every { workspaceService.removeMember(principal, "acme", target) } throws
            WorkspaceInUseException("acme", emptyMap(), blockedBy = "owner_membership")
        controller.removeMember("acme", target) shouldBe "redirect:/workspaces?error=in_use"
    }

    @Test
    fun `delete redirects ok=deleted - and owning content is the in_use banner`() {
        authenticate()
        every { workspaceService.delete(principal, "acme") } returns Unit
        controller.delete("acme") shouldBe "redirect:/workspaces?ok=deleted"

        every { workspaceService.delete(principal, "acme") } throws
            WorkspaceInUseException("acme", mapOf("pipelines" to 1))
        controller.delete("acme") shouldBe "redirect:/workspaces?error=in_use"
    }

    @Test
    fun `switch re-stamps the session cookie and redirects home - a refusal is the banner`() {
        authenticate()
        val user =
            User(
                userId,
                "alice@acme.test",
                "Alice",
                null,
                "google",
                "sub-a",
                isActive = true,
                isAdmin = false,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
        every { workspaceService.resolveSwitch(principal, "globex") } returns WorkspaceContext(UUID.randomUUID(), "globex")
        every { userService.snapshot(userId) } returns user
        every { jwtService.issue(user, "globex") } returns "fresh-jwt"

        val response = MockHttpServletResponse()
        controller.switch(response, "globex") shouldBe "redirect:/"
        response.getCookie("dp_session")?.value shouldBe "fresh-jwt"

        every { workspaceService.resolveSwitch(principal, "rival") } throws WorkspaceMembershipRequiredException()
        controller.switch(MockHttpServletResponse(), "rival") shouldBe "redirect:/workspaces?error=switch_refused"
    }
}
