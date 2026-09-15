package co.datapipelines.web.ui

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication

/**
 * 143 (T315) — the rail's Admin item, rendered by the real layout and read as the ANCHOR it
 * is (its href, its `data-nav-admin` kind, its boost and its active state), never as the
 * word "Admin" somewhere in the page.
 *
 * Before this round `layouts/default.html` rendered `/admin/users` for every authenticated
 * principal — a dead link for everyone but a super admin (`AdminUsersController` requires
 * `USER_ADMINISTRATION`). Now the item exists only where it leads somewhere its reader may
 * go: instance users for a super admin, the current workspace's members for a workspace
 * admin, nothing for a viewer, an author or a pure promoter. The two booleans are
 * `UiWorkspaceAdvice`'s (`RoleModel.shell`); this test sets them the way the advice would.
 *
 * Falsification recorded in the handback: remove the `th:if` from the users anchor and the
 * viewer arm goes red.
 */
class AdminNavRenderTest {
    @Test
    fun `viewer, author and promoter render no Admin item at all`() {
        val html = render(users = false, members = false, currentPath = "/pipelines")

        adminAnchors(html) shouldBe emptyList()
        html shouldNotContain "data-nav-admin"
        html shouldNotContain "/admin/users"
        // The rest of the rail is intact — the item is absent, not the group.
        html shouldContain "data-nav-label=\"Workspaces\""
        html shouldContain "data-nav-label=\"Docs\""
    }

    @Test
    fun `a super admin's Admin item is the instance users link, boosted, active under admin`() {
        val html = render(users = true, members = false, currentPath = "/admin/users")

        val anchor = adminAnchors(html).single()
        anchor shouldContain "href=\"/admin/users\""
        anchor shouldContain "data-nav-admin=\"users\""
        anchor shouldContain "data-nav-section=\"/admin\""
        anchor shouldNotContain "hx-boost=\"false\""
        anchor shouldContain "class=\"app-nav-link active\""
        anchor shouldContain "aria-current=\"page\""
        html shouldNotContain "workspace-members"
    }

    @Test
    fun `a workspace admin's Admin item is the current workspace's members section, a full navigation`() {
        val html = render(users = false, members = true, currentPath = "/workspaces")

        val anchor = adminAnchors(html).single()
        anchor shouldContain "href=\"/workspaces#workspace-members\""
        anchor shouldContain "data-nav-admin=\"members\""
        anchor shouldContain "data-nav-section=\"/admin\""
        anchor shouldContain "hx-boost=\"false\""
        // Active state is the PATH rule: on /workspaces the Workspaces item is active and
        // the members link is not — it is a shortcut into that screen, not a section.
        anchor shouldNotContain "active"
        val active = Regex("""<a[^>]*\bclass="app-nav-link active"[^>]*>""").findAll(html).map { it.value }.toList()
        active.size shouldBe 1
        active.single() shouldContain "data-nav-label=\"Workspaces\""
        html shouldNotContain "/admin/users"
    }

    /** Whichever item renders, the rail still agrees with AppNav's table (ShellRenderTest's guard, per arm). */
    @Test
    fun `either Admin item resolves in the breadcrumb table as the Admin row`() {
        val users = render(users = true, members = false, currentPath = "/dashboard")
        val members = render(users = false, members = true, currentPath = "/dashboard")
        listOf(users, members)
            .forEach { html ->
                val links = Regex(NAV_LINK).findAll(html).toList()
                links.size shouldBe AppNav.ITEMS.size
                val admin = links.single { it.groupValues[1] == "/admin" }
                AppNav.bySection("/admin").shouldNotBeNull().label shouldBe admin.groupValues[3]
            }
    }

    // ----------------------------------------------------------------- fixtures

    private fun adminAnchors(html: String): List<String> =
        Regex("""<a[^>]*data-nav-section="/admin"[^>]*>""").findAll(html).map { it.value }.toList()

    private fun render(
        users: Boolean,
        members: Boolean,
        currentPath: String,
    ): String {
        val context =
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
            ).withRoles(navAdminUsers = users, navAdminMembers = members)
        context.setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
        context.setVariable("workspaceHeaderFragment", "")
        context.setVariable("workspaceOptions", emptyList<Any>())
        context.setVariable("activeWorkspace", "acme")
        context.setVariable("activeTheme", "saas")
        context.setVariable("authenticated", true)
        context.setVariable("currentPath", currentPath)
        context.setVariable("navCounts", NavCounts.Counts(1, 1))
        return engine.process("test-stub", context)
    }

    private val engine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }

    private companion object {
        const val NAV_LINK = """data-nav-section="([^"]+)" *\n? *data-nav-group="([^"]*)" data-nav-label="([^"]+)""""
    }
}
