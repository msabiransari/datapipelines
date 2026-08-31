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
import co.datapipelines.auth.WorkspaceMembership
import co.datapipelines.auth.WorkspaceMembershipRequiredException
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.auth.WorkspacesProperties
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
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
    fun `workspaces page renders the design-system tables and the active badge`() {
        val membership =
            WorkspaceMembership(UUID.randomUUID(), "acme", WorkspaceRole.OWNER, Instant.EPOCH)
        val html =
            engine().process(
                "workspaces/index",
                webContext().apply {
                    fillLayoutChrome()
                    setVariable("own", listOf(membership))
                    setVariable("joinable", emptyList<Any>())
                    setVariable("openJoin", false)
                    setVariable("canCreate", false)
                    setVariable("provisioningMode", "self-serve")
                    setVariable("managed", mapOf("acme" to listOf(memberRow())))
                },
            )

        // Both tables (own workspaces, per-managed members) are on the design system (029).
        html shouldContain "<table class=\"ds-table\">"
        html shouldContain "ds-badge ds-badge-primary" // the active-workspace chip
        html shouldNotContain "border-collapse: collapse"
    }

    @Test
    fun `workspaces empty state uses the ds-empty primitive`() {
        val html =
            engine().process(
                "workspaces/index",
                webContext().apply {
                    fillLayoutChrome()
                    setVariable("own", emptyList<Any>())
                    setVariable("joinable", emptyList<Any>())
                    setVariable("openJoin", false)
                    setVariable("canCreate", false)
                    setVariable("provisioningMode", "self-serve")
                    setVariable("managed", emptyMap<String, Any>())
                },
            )

        html shouldContain "class=\"ds-empty\""
        html shouldContain "class=\"ds-empty-title\""
        html shouldNotContain "ds-empty-state" // a class with no CSS anywhere (D4)
    }

    @Test
    fun `an ok flash renders as a success toast inside the stack, never a banner`() {
        val html =
            engine().process(
                "workspaces/index",
                webContextWithParams("ok" to "created").apply { fillPageModel() },
            )

        // Server-rendered INSIDE #toast, so toast.js arms it at DOMContentLoaded.
        Regex("""id="toast"[^>]*>(?:[\s\S](?!<main))*ds-toast ds-toast-success""")
            .containsMatchIn(html) shouldBe true
        html shouldNotContain "class=\"ds-surface\"" // the banner element is gone
        // EXACTLY ONE toast: th:replace outranks th:if on the same element (the host
        // is discarded before the condition runs), so a keyed block written as
        // `<div th:if th:replace>` renders EVERY keyed toast at once (030 bug).
        Regex("ds-toast-title").findAll(html.substringBefore("<main")).count() shouldBe 1
    }

    @Test
    fun `an error flash renders as a danger toast carrying the reviewed copy verbatim`() {
        val html =
            engine().process(
                "workspaces/index",
                webContextWithParams("error" to "in_use").apply { fillPageModel() },
            )

        Regex("""id="toast"[^>]*>(?:[\s\S](?!<main))*ds-toast ds-toast-danger""")
            .containsMatchIn(html) shouldBe true
        html shouldContain "This workspace still owns content (pipelines, templates or datasources), or still needs its owner."
        html shouldNotContain "class=\"ds-surface\""
        // One flash, one toast — not one per keyed message (see the ok-flash test).
        Regex("ds-toast-title").findAll(html.substringBefore("<main")).count() shouldBe 1
    }

    private fun WebContext.fillPageModel() {
        fillLayoutChrome()
        setVariable("own", emptyList<Any>())
        setVariable("joinable", emptyList<Any>())
        setVariable("openJoin", false)
        setVariable("canCreate", false)
        setVariable("provisioningMode", "self-serve")
        setVariable("managed", emptyMap<String, Any>())
    }

    private fun webContextWithParams(vararg params: Pair<String, String>): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(
                    MockHttpServletRequest().apply { params.forEach { (k, v) -> setParameter(k, v) } },
                    MockHttpServletResponse(),
                ),
        )

    private fun WebContext.fillLayoutChrome() {
        setVariable("_csrf", mapOf("token" to "t"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", "acme")
        setVariable("activeTheme", "saas")
        setVariable("authenticated", true)
        setVariable("currentPath", "/workspaces")
    }

    private fun engine(): SpringTemplateEngine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )

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

    // ---------------------------------------------------------------- D3: session-only

    /**
     * The escalation this gate exists for (022 review finding, orchestrator-verified
     * 2026-08-29): an API key authenticates on EVERY path and is CSRF-exempt, and
     * [WorkspacesUiController.switch]'s floor is `Scope.READ` by design (WORKSPACES_READ)
     * — so without the gate a READ-scoped key could POST /workspace/switch and get back a
     * `dp_session` cookie stamped with `scopesFor(user)`, i.e. the USER's author/admin
     * scopes, and for ANY workspace the user belongs to (the skeleton-key outcome D3's
     * header refusal exists to prevent). The sibling actions are `author`-floored since
     * the 025 defect round; the gate stays as their session-only second line.
     *
     * The mocks below would let the switch SUCCEED: remove the gate and the cookie
     * assertion goes red, which is what makes this a pin rather than a tautology.
     */
    @Test
    fun `an API-key principal cannot mint a session cookie through switch`() {
        authenticateWithApiKey()
        every { workspaceService.resolveSwitch(any(), any()) } returns WorkspaceContext(UUID.randomUUID(), "globex")
        every { userService.snapshot(userId) } returns
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
        every { jwtService.issue(any(), any()) } returns "escalated-jwt"

        val response = MockHttpServletResponse()
        controller.switch(response, "globex") shouldBe "redirect:/workspaces?error=switch_refused"

        // The security property, asserted directly: no credential was minted.
        response.getCookie("dp_session") shouldBe null
        // ...and the minting path was never reached at all.
        verify(exactly = 0) { jwtService.issue(any(), any()) }
    }

    /**
     * The same gate across the rest of the family. These are role-gated in-handler, so the
     * exposure was bounded — but scope is a property of the CREDENTIAL, not of its owner,
     * and a `read` key driving a workspace delete violates that outright.
     */
    @Test
    fun `an API-key principal cannot create, join, add, remove or delete`() {
        authenticateWithApiKey()
        val refusal = "redirect:/workspaces?error=session_required"

        controller.create("globex", "Globex") shouldBe refusal
        controller.join("globex") shouldBe refusal
        controller.addMember("globex", "bob@acme.test") shouldBe refusal
        controller.removeMember("globex", UUID.randomUUID()) shouldBe refusal
        controller.delete("globex") shouldBe refusal

        // The gate is in FRONT of the service, not behind it.
        verify(exactly = 0) { workspaceService.create(any(), any(), any()) }
        verify(exactly = 0) { workspaceService.addMember(any(), any(), any()) }
        verify(exactly = 0) { workspaceService.removeMember(any(), any(), any()) }
        verify(exactly = 0) { workspaceService.delete(any(), any()) }
    }

    private fun authenticateWithApiKey() {
        val keyPrincipal =
            AuthenticatedPrincipal(
                userId,
                "alice@acme.test",
                "Alice",
                // A minimum-privilege key — the escalation's whole point is that the USER
                // behind it is an author or admin.
                setOf(Scope.READ),
                AuthMethod.API_KEY,
                keyId = "dpk_TESTKEY",
                workspace = WorkspaceContext(UUID.randomUUID(), "acme"),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(keyPrincipal, null, emptyList())
    }
}
