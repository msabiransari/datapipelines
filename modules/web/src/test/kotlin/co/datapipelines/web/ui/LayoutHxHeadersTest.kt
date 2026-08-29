package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceService
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.util.HtmlUtils
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import java.util.UUID

/**
 * Renders `layouts/default` through the real template engine and asserts the RENDERED
 * `hx-headers` attribute is valid JSON once the browser decodes the HTML entities —
 * the value htmx actually parses. Regression for the double-escaped workspace fragment:
 * a model value carrying `&quot;` entities is escaped AGAIN by `th:attr`, and htmx then
 * sends no headers at all (every mutation 403s `auth.csrf.invalid`).
 */
class LayoutHxHeadersTest {
    private val advice = UiWorkspaceAdvice(mockk<WorkspaceService>())
    private val objectMapper = ObjectMapper()

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

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(workspaceName: String?) {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "a@b.c",
                "A",
                setOf(Scope.AUTHOR),
                AuthMethod.OIDC,
                workspace = workspaceName?.let { WorkspaceContext(UUID.randomUUID(), it) },
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    /** Renders the layout and returns the `hx-headers` attribute as the browser decodes it. */
    private fun renderedHxHeaders(): String {
        val application = JakartaServletWebApplication.buildApplication(MockServletContext())
        val exchange = application.buildExchange(MockHttpServletRequest(), MockHttpServletResponse())
        val context = WebContext(exchange)
        context.setVariable("_csrf", mapOf("token" to "csrf-token-123"))
        context.setVariable("workspaceHeaderFragment", advice.workspaceHeaderFragment())
        context.setVariable("workspaceOptions", emptyList<Any>())
        context.setVariable("activeWorkspace", advice.activeWorkspace())
        context.setVariable("activeTheme", "light")

        val html = engine.process("test-stub", context)
        val attribute =
            Regex("""hx-headers="([^"]*)"""")
                .find(html)
                ?.groupValues
                ?.get(1) ?: error("hx-headers attribute missing from rendered layout")
        return HtmlUtils.htmlUnescape(attribute)
    }

    @Test
    fun `hx-headers is valid JSON with the workspace header when a workspace is active`() {
        authenticate("acme")

        val headers = objectMapper.readTree(renderedHxHeaders())

        headers.get("DP-CSRF-Token")?.asText() shouldBe "csrf-token-123"
        headers.get("DP-Workspace")?.asText() shouldBe "acme"
    }

    @Test
    fun `hx-headers is valid JSON without a workspace header when no workspace is active`() {
        authenticate(null)

        val headers = objectMapper.readTree(renderedHxHeaders())

        headers.get("DP-CSRF-Token")?.asText() shouldBe "csrf-token-123"
        headers.get("DP-Workspace") shouldBe null
    }

    @Test
    fun `advice emits the workspace fragment only when a workspace is active`() {
        authenticate("acme")
        advice.workspaceHeaderFragment() shouldNotBe ""

        authenticate(null)
        advice.workspaceHeaderFragment() shouldBe ""
    }
}
