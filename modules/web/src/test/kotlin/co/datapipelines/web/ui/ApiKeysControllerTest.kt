package co.datapipelines.web.ui

import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.IssuedApiKey
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
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

class ApiKeysControllerTest {
    private val apiKeyRepository = mockk<ApiKeyRepository>()
    private val themeResolver = mockk<ThemeResolver>()
    private val controller = ApiKeysController(apiKeyRepository, themeResolver)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private val principal =
        AuthenticatedPrincipal(
            userId = userId,
            email = "a@b.c",
            displayName = "A",
            scopes = setOf(Scope.AUTHOR),
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext(workspaceId, "acme"),
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun sampleKey(id: String = "dpk_abc123") =
        ApiKey(
            id = id,
            userId = userId,
            name = "Test Key",
            keyHash = "hash",
            scopes = setOf(Scope.READ),
            isRevoked = false,
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            lastUsedAt = null,
            expiresAt = null,
            workspaceId = workspaceId,
            workspaceName = "acme",
        )

    @Test
    fun `api keys page returns view with keys`() {
        authenticate()
        every { apiKeyRepository.findByUser(any()) } returns listOf(sampleKey())
        every { themeResolver.resolve(any()) } returns "saas"

        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = controller.apiKeys(model, mockk(relaxed = true))

        viewName shouldBe "settings/api-keys"
        @Suppress("UNCHECKED_CAST")
        (model["keys"] as List<*>).size shouldBe 1
        model["activeTheme"] shouldBe "saas"
    }

    @Test
    fun `api keys page renders the design-system table and badges`() {
        val html =
            engine().process(
                "settings/api-keys",
                webContext().apply {
                    fillLayoutChrome()
                    setVariable("keys", listOf(sampleKey(), sampleKey(id = "dpk_old999").copy(isRevoked = true)))
                    setVariable("scopes", listOf("read", "execute"))
                },
            )

        html shouldContain "<table class=\"ds-table\">"
        html shouldContain "ds-badge ds-badge-default" // the scope chips
        html shouldContain "ds-badge ds-badge-danger" // the revoked marker
        // The migration is only done when the inline header/cell styles are GONE.
        html shouldNotContain "border-bottom:1px solid var(--border-default)"
    }

    @Test
    fun `api keys empty state uses the ds-empty primitive and keeps the swap target`() {
        val html =
            engine().process(
                "settings/api-keys",
                webContext().apply {
                    fillLayoutChrome()
                    setVariable("keys", emptyList<ApiKey>())
                    setVariable("scopes", listOf("read"))
                },
            )

        html shouldContain "class=\"ds-empty\""
        html shouldContain "class=\"ds-empty-title\""
        // The revoke path and the post-create refresh target #keys-table-body — it must
        // exist even with zero keys, so the table renders around the empty state.
        html shouldContain "id=\"keys-table-body\""
        html shouldNotContain "ds-empty-state" // a class with no CSS anywhere (D4)
    }

    private fun WebContext.fillLayoutChrome() {
        setVariable("_csrf", mapOf("token" to "t"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", "acme")
        setVariable("activeTheme", "saas")
        setVariable("authenticated", true)
        setVariable("currentPath", "/settings/api-keys")
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
}

class ApiKeysPartialControllerTest {
    private val apiKeyService = mockk<ApiKeyService>()
    private val apiKeyRepository = mockk<ApiKeyRepository>()
    private val partialController = ApiKeysPartialController(apiKeyService, apiKeyRepository)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private val principal =
        AuthenticatedPrincipal(
            userId = userId,
            email = "a@b.c",
            displayName = "A",
            scopes = setOf(Scope.AUTHOR),
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext(workspaceId, "acme"),
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun sampleKey(id: String = "dpk_abc123") =
        ApiKey(
            id = id,
            userId = userId,
            name = "Test Key",
            keyHash = "hash",
            scopes = setOf(Scope.READ),
            isRevoked = false,
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            lastUsedAt = null,
            expiresAt = null,
            workspaceId = workspaceId,
            workspaceName = "acme",
        )

    private fun sampleIssued() =
        IssuedApiKey(
            record = sampleKey(),
            plaintext = "dpk_abc123.supersecret",
        )

    @Test
    fun `create returns partial with key plaintext`() {
        authenticate()
        every { apiKeyService.issue(any(), any(), any(), any(), any(), any()) } returns sampleIssued()
        every { apiKeyRepository.findByUser(any()) } returns listOf(sampleKey())

        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = partialController.create("Test Key", "read", null, model)

        viewName shouldBe "partials/api-key-created"
        model["key"] shouldBe "dpk_abc123.supersecret"
        model["keyId"] shouldBe "dpk_abc123"
    }

    @Test
    fun `create returns the once-shown panel, refreshes the table, and points a toast at it`() {
        authenticate()
        every { apiKeyService.issue(any(), any(), any(), any(), any(), any()) } returns sampleIssued()
        every { apiKeyRepository.findByUser(any()) } returns listOf(sampleKey())

        val model: ExtendedModelMap = ExtendedModelMap()
        val view = partialController.create("ci", "read", null, model)

        view shouldBe "partials/api-key-created"
        val html =
            engine().process(
                view,
                webContext().apply { model.forEach { (k, v) -> setVariable(k, v) } },
            )

        html shouldContain "Your new API key (shown once)" // the secret still persists inline
        html shouldContain "hx-swap-oob=\"beforeend:#toast\""
        html shouldContain "copy it now" // the toast POINTS, never carries
        // …and the issued plaintext never appears anywhere after the OOB marker.
        html.substringAfter("hx-swap-oob=\"beforeend:#toast\"") shouldNotContain "dpk_abc123.supersecret"
        // E2: the table is refreshed out-of-band, from the same row markup as the page.
        html shouldContain "id=\"keys-table-body\""
        html shouldContain "hx-swap-oob=\"true\""
        html shouldContain "ds-badge ds-badge-default"
    }

    @Test
    fun `revoke returns the rebuilt rows plus a success toast and drops the dead trigger`() {
        authenticate()
        every { apiKeyService.revoke("dpk_abc123", any()) } returns true
        every { apiKeyRepository.findByUser(any()) } returns listOf(sampleKey().copy(isRevoked = true))

        val response = partialController.revoke("dpk_abc123")

        response.body!! shouldContain "hx-swap-oob=\"beforeend:#toast\""
        response.body!! shouldContain "revoked"
        response.headers["HX-Trigger"] shouldBe null // E3: nothing has ever listened for keyRevoked
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
}
