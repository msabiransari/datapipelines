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
import co.datapipelines.auth.WorkspaceLastAdminException
import co.datapipelines.auth.WorkspaceMemberRow
import co.datapipelines.auth.WorkspaceMembership
import co.datapipelines.auth.WorkspaceMembershipRequiredException
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.auth.WorkspaceSelfMembershipException
import co.datapipelines.auth.WorkspaceService
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
import org.springframework.ui.ExtendedModelMap
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
    private val themeResolver = mockk<ThemeResolver>()
    private val userService = mockk<UserService>()
    private val jwtService = mockk<JwtService>()
    private val controller =
        WorkspacesUiController(
            workspaceService,
            userService,
            jwtService,
            AuthProperties(),
            themeResolver,
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
        WorkspaceMemberRow(UUID.randomUUID(), email, "Bob", WorkspaceRole.AUTHOR, Instant.EPOCH)

    // ------------------------------------------------------------------ 114 §C.1 members

    /**
     * The role dropdown reaches the SERVICE, through the same method the REST
     * `POST .../members` calls. There is deliberately no second code path: the membership
     * checks live in `WorkspaceService`, and a UI binder that re-implemented them would be one
     * more place for them to disagree.
     */
    @Test
    fun `addMember carries the ONE role into the service (D22)`() {
        authenticate()
        every { workspaceService.addMember(principal, "acme", "bob@acme.test", WorkspaceRole.PROMOTER) } returns
            WorkspaceService.AddMemberOutcome.Added(memberRow())

        controller.addMember("acme", "bob@acme.test", role = "promoter") shouldBe "redirect:/workspaces?ok=member_added"

        verify { workspaceService.addMember(principal, "acme", "bob@acme.test", WorkspaceRole.PROMOTER) }
    }

    /** A role outside the four is a form nobody rendered — refused before the service, as its own flash. */
    @Test
    fun `addMember with an unknown role bounces as unknown_role and never reaches the service`() {
        authenticate()

        controller.addMember("acme", "bob@acme.test", role = "owner") shouldBe "redirect:/workspaces?error=unknown_role"

        verify(exactly = 0) { workspaceService.addMember(any(), any(), any(), any()) }
    }

    /**
     * D22 — the ONE htmx partial: the dropdown's Save replaces the role through the same
     * `WorkspaceService.setMemberRole` the REST `PUT .../members/{userId}` calls, and answers
     * with the re-rendered row (the page's own fragment) plus an out-of-band toast.
     */
    @Test
    fun `setMemberRole replaces the role through the service and re-renders the row with a toast`() {
        authenticate()
        val target = UUID.randomUUID()
        val row = WorkspaceMemberRow(target, "bob@acme.test", "Bob", WorkspaceRole.AUTHOR, Instant.EPOCH)
        every { workspaceService.setMemberRole(principal, "acme", target, WorkspaceRole.AUTHOR) } returns row
        every { workspaceService.liveUserKeyOwnerIds(principal, "acme") } returns emptySet()
        val model = ExtendedModelMap()

        controller.setMemberRole(model, "acme", target, role = "author") shouldBe "partials/workspace-member-row :: saved"

        verify { workspaceService.setMemberRole(principal, "acme", target, WorkspaceRole.AUTHOR) }
        (model["member"] as MemberRowView).role shouldBe WorkspaceRole.AUTHOR
        // #200 — the swapped-in row carries the key state the page drew (here: none).
        (model["member"] as MemberRowView).hasKey shouldBe false
        model["toastVariant"] shouldBe "success"
        val html =
            engine().process(
                "partials/workspace-member-row",
                setOf("saved"),
                webContext().apply { model.forEach { (k, v) -> setVariable(k, v) } },
            )
        // The row that comes back is the page's own fragment: the select with the NEW role
        // selected, posting to the same partial, and the toast bound for the stack.
        html shouldContain "data-member=\"bob@acme.test\""
        html shouldContain "value=\"author\" selected"
        html shouldContain "hx-post=\"/partials/workspaces/acme/members/$target/role\""
        html shouldContain "hx-swap-oob=\"beforeend:#toast\""
        html shouldNotContain "checkbox"
    }

    /** The last admin cannot be demoted: the 409 from the service becomes a toast alone, retargeted at the stack. */
    @Test
    fun `demoting the last admin answers 409 with the last_admin toast and no row`() {
        authenticate()
        val target = UUID.randomUUID()
        every { workspaceService.setMemberRole(principal, "acme", target, any()) } throws
            WorkspaceLastAdminException("acme")

        val response = controller.setMemberRole(ExtendedModelMap(), "acme", target, role = "viewer")

        val entity = response as org.springframework.http.ResponseEntity<*>
        entity.statusCode.value() shouldBe 409
        entity.headers.getFirst("HX-Retarget") shouldBe "#toast"
        (entity.body as String) shouldContain "last workspace admin"
    }

    @Test
    fun `an unknown role on the partial is a 400 toast, before the service`() {
        authenticate()

        val response = controller.setMemberRole(ExtendedModelMap(), "acme", UUID.randomUUID(), role = "owner")

        (response as org.springframework.http.ResponseEntity<*>).statusCode.value() shouldBe 400
        verify(exactly = 0) { workspaceService.setMemberRole(any(), any(), any(), any()) }
    }

    // ------------------------------------------------------------------ 114 §C.3 deactivation

    @Test
    fun `deactivate and reactivate are the instance verbs, each with its own flash`() {
        authenticate()
        every { workspaceService.deactivate(principal, "acme") } returns mockk()
        every { workspaceService.reactivate(principal, "acme") } returns mockk()

        controller.deactivate("acme") shouldBe "redirect:/workspaces?ok=deactivated"
        controller.reactivate("acme") shouldBe "redirect:/workspaces?ok=reactivated"
    }

    @Test
    fun `the display name is its own verb and its own flash`() {
        authenticate()
        every { workspaceService.updateDisplayName(principal, "acme", "Acme Corp") } returns mockk()

        controller.renameDisplay("acme", " Acme Corp ") shouldBe "redirect:/workspaces?ok=display_name"
    }

    /**
     * §C.3(a) — zero ACTIVE memberships renders the no-workspace page instead of an empty
     * list. A user whose only workspace was deactivated is in exactly this state, and to them
     * a deactivated workspace and one that never existed must look the same (§11A.3).
     *
     * Reachable: `ScopeMatrix` lets a session through `WORKSPACES_READ` with no context
     * (`RoleMatrixTest`, auth.md §11A.1) — the branch is pinned here, the wire is pinned there.
     */
    @Test
    fun `a principal whose only memberships are deactivated gets the no-workspace page`() {
        authenticate()
        every { workspaceService.listOwn(principal) } returns
            listOf(WorkspaceMembership(UUID.randomUUID(), "acme", WorkspaceRole.AUTHOR, Instant.EPOCH, false))
        every { themeResolver.resolve(any()) } returns "saas"

        controller.screen(ExtendedModelMap(), MockHttpServletRequest()) shouldBe "workspaces/none"
    }

    @Test
    fun `one active membership among deactivated ones keeps the list`() {
        authenticate()
        every { workspaceService.listOwn(principal) } returns
            listOf(
                WorkspaceMembership(UUID.randomUUID(), "gone", WorkspaceRole.VIEWER, Instant.EPOCH, false),
                WorkspaceMembership(UUID.randomUUID(), "acme", WorkspaceRole.WORKSPACE_ADMIN, Instant.EPOCH, true),
            )
        every { workspaceService.membersWithInvitations(principal, "acme") } returns
            WorkspaceService.MemberListing(members = listOf(memberRow()), invitations = emptyList())
        every { themeResolver.resolve(any()) } returns "saas"

        val model = ExtendedModelMap()
        controller.screen(model, MockHttpServletRequest()) shouldBe "workspaces/index"

        // The deactivated one is still LISTED — this principal is not a super admin, but the
        // row carries `active=false` and the template decides what to draw from that.
        @Suppress("UNCHECKED_CAST")
        val rows = model["own"] as List<WorkspaceRowView>
        rows.map { it.name to it.active } shouldBe listOf("gone" to false, "acme" to true)
        // Only ACTIVE administered workspaces get a members table: reactivate first (§11A.2).
        (model["managed"] as Map<*, *>).keys shouldBe setOf("acme")
        (model["pending"] as Map<*, *>).keys shouldBe setOf("acme")
    }

    @Test
    fun `addMember for an email with no account redirects ok=member_invited (113)`() {
        authenticate()
        every { workspaceService.addMember(principal, "acme", "new@acme.test", WorkspaceRole.VIEWER) } returns
            WorkspaceService.AddMemberOutcome.Invited(email = "new@acme.test", role = WorkspaceRole.VIEWER)

        controller.addMember("acme", "new@acme.test", role = "viewer") shouldBe "redirect:/workspaces?ok=member_invited"
    }

    @Test
    fun `revokeInvitation goes through the service and redirects ok=invitation_revoked`() {
        authenticate()
        every { workspaceService.revokeInvitation(principal, "acme", "new@acme.test") } returns Unit

        controller.revokeInvitation("acme", "new@acme.test") shouldBe "redirect:/workspaces?ok=invitation_revoked"
        verify { workspaceService.revokeInvitation(principal, "acme", "new@acme.test") }
    }

    @Test
    fun `workspaces page renders the design-system tables and the active badge`() {
        val membership =
            WorkspaceMembership(UUID.randomUUID(), "acme", WorkspaceRole.WORKSPACE_ADMIN, Instant.EPOCH)
        val html =
            engine().process(
                "workspaces/index",
                webContext().apply {
                    fillLayoutChrome()
                    setVariable("own", listOf(WorkspaceRowView.of(membership, "acme", superAdmin = false)))
                    setVariable("canCreate", false)
                    setVariable("managed", mapOf("acme" to listOf(MemberRowView.of(memberRow(), hasKey = true))))
                    setVariable("workspaceRoles", WorkspaceRole.entries)
                },
            )

        // Both tables (own workspaces, per-managed members) are on the design system (029).
        html shouldContain "<table class=\"ds-table\">"
        // D22: the member row is the partial's fragment — the dropdown with the current role
        // selected, the row hook the browser suite addresses, the partial's post target.
        html shouldContain "data-member=\"bob@acme.test\""
        html shouldContain "value=\"author\" selected"
        html shouldContain "hx-post=\"/partials/workspaces/acme/members/"
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
                    setVariable("canCreate", false)
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

        // 076 §B: server-rendered inside the hidden #toast-flash bin (INSIDE #app-main,
        // so a boosted swap carries it); toast.js adopts it into the persistent #toast
        // stack on init/settle. The stack itself renders empty.
        Regex("""id="toast-flash"[^>]*>[\s\S]*?ds-toast ds-toast-success""")
            .containsMatchIn(html) shouldBe true
        html shouldNotContain "class=\"ds-surface\"" // the banner element is gone
        // EXACTLY ONE toast: th:replace outranks th:if on the same element (the host
        // is discarded before the condition runs), so a keyed block written as
        // `<div th:if th:replace>` renders EVERY keyed toast at once (030 bug).
        Regex("ds-toast-title").findAll(html).count() shouldBe 1
    }

    @Test
    fun `an error flash renders as a danger toast carrying the reviewed copy verbatim`() {
        val html =
            engine().process(
                "workspaces/index",
                webContextWithParams("error" to "in_use").apply { fillPageModel() },
            )

        Regex("""id="toast-flash"[^>]*>[\s\S]*?ds-toast ds-toast-danger""")
            .containsMatchIn(html) shouldBe true
        html shouldContain "This workspace still owns content (pipelines, templates or datasources), or still needs its owner."
        html shouldNotContain "class=\"ds-surface\""
        // One flash, one toast — not one per keyed message (see the ok-flash test).
        Regex("ds-toast-title").findAll(html).count() shouldBe 1
    }

    private fun WebContext.fillPageModel() {
        fillLayoutChrome()
        setVariable("own", emptyList<Any>())
        setVariable("canCreate", false)
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
        ).withRoles()

    @Test
    fun `create redirects with ok=created - and a duplicate name is the banner, not an error page`() {
        authenticate()
        every { workspaceService.create(principal, "globex", "Globex") } returns mockk()
        controller.create("globex", "Globex") shouldBe "redirect:/workspaces?ok=created"

        every { workspaceService.create(principal, "acme", "acme") } throws WorkspaceDuplicateNameException("acme")
        controller.create(" acme ", null) shouldBe "redirect:/workspaces?error=duplicate_name"
    }

    @Test
    fun `addMember redirects ok=member_added`() {
        authenticate()
        every { workspaceService.addMember(principal, "acme", "bob@acme.test", WorkspaceRole.VIEWER) } returns
            WorkspaceService.AddMemberOutcome.Added(memberRow())

        controller.addMember("acme", "bob@acme.test", role = "viewer") shouldBe "redirect:/workspaces?ok=member_added"
    }

    @Test
    fun `addMember with an unknown email is the user_not_found banner - never a 500`() {
        authenticate()
        every { workspaceService.addMember(principal, "acme", "ghost@nowhere.test", WorkspaceRole.VIEWER) } throws
            WorkspaceService.UnknownMemberEmailException("ghost@nowhere.test")

        controller.addMember("acme", "ghost@nowhere.test", role = "viewer") shouldBe
            "redirect:/workspaces?error=user_not_found"
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

    /** #208 — one's own row: the service's refusal reaches the page as the `self_membership` banner and the partial as a 409 toast. */
    @Test
    fun `a self-membership refusal is the self_membership banner on the verbs and a 409 toast on the role partial`() {
        authenticate()
        every { workspaceService.removeMember(principal, "acme", principal.userId) } throws WorkspaceSelfMembershipException("acme")
        controller.removeMember("acme", principal.userId) shouldBe "redirect:/workspaces?error=self_membership"
        every { workspaceService.revokeMemberKey(principal, "acme", principal.userId) } throws WorkspaceSelfMembershipException("acme")
        controller.revokeMemberKey("acme", principal.userId) shouldBe "redirect:/workspaces?error=self_membership"

        every { workspaceService.setMemberRole(principal, "acme", principal.userId, WorkspaceRole.VIEWER) } throws
            WorkspaceSelfMembershipException("acme")
        val response = controller.setMemberRole(ExtendedModelMap(), "acme", principal.userId, role = "viewer")
        (response as org.springframework.http.ResponseEntity<*>).statusCode.value() shouldBe 409
        response.body.toString() shouldContain "Ask another workspace admin"
    }

    /** #200 — the members-row verb posts through the ONE service method the REST twin calls. */
    @Test
    fun `revokeMemberKey redirects ok=member_key_revoked`() {
        authenticate()
        val target = UUID.randomUUID()
        every { workspaceService.revokeMemberKey(principal, "acme", target) } returns Unit
        controller.revokeMemberKey("acme", target) shouldBe "redirect:/workspaces?ok=member_key_revoked"
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
        // 179 (D16): the switch mints the user's MCP key in the target — the mint itself is
        // ApiKeyService's, tested there and in ApiKeyMintingTest; here it is stubbed.
        every { workspaceService.mintMcpKeyOnEntry(any(), any(), any()) } returns Unit

        val response = MockHttpServletResponse()
        controller.switch(response, "globex") shouldBe "redirect:/dashboard"
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
    fun `an API-key principal cannot create, add, change roles, remove, revoke keys, deactivate or delete`() {
        authenticateWithApiKey()
        val refusal = "redirect:/workspaces?error=session_required"

        controller.create("globex", "Globex") shouldBe refusal
        controller.addMember("globex", "bob@acme.test", role = "author") shouldBe refusal
        // The partial answers the same refusal as a toast (403, `workspace.session_required`).
        val partial = controller.setMemberRole(ExtendedModelMap(), "globex", UUID.randomUUID(), role = "author")
        (partial as org.springframework.http.ResponseEntity<*>).statusCode.value() shouldBe 403
        controller.removeMember("globex", UUID.randomUUID()) shouldBe refusal
        controller.revokeMemberKey("globex", UUID.randomUUID()) shouldBe refusal
        controller.renameDisplay("globex", "Globex") shouldBe refusal
        controller.deactivate("globex") shouldBe refusal
        controller.reactivate("globex") shouldBe refusal
        controller.delete("globex") shouldBe refusal

        // The gate is in FRONT of the service, not behind it.
        verify(exactly = 0) { workspaceService.create(any(), any(), any()) }
        verify(exactly = 0) { workspaceService.addMember(any(), any(), any(), any()) }
        verify(exactly = 0) { workspaceService.setMemberRole(any(), any(), any(), any()) }
        verify(exactly = 0) { workspaceService.removeMember(any(), any(), any()) }
        verify(exactly = 0) { workspaceService.revokeMemberKey(any(), any(), any()) }
        verify(exactly = 0) { workspaceService.updateDisplayName(any(), any(), any()) }
        verify(exactly = 0) { workspaceService.deactivate(any(), any()) }
        verify(exactly = 0) { workspaceService.reactivate(any(), any()) }
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
