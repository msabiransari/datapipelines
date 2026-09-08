package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointKeyService
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyExpiryInvalidException
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.IssuedApiKey
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 091 — `/settings/api-keys` is a LINK now (ui-screens.md §4.10). What this test protects is
 * that the redirect-by-hand is not a dead end: the route still answers, and the page it renders
 * points at the screen the keys actually live on.
 */
class ApiKeysControllerTest {
    private val themeResolver = mockk<ThemeResolver>()
    private val controller = ApiKeysController(themeResolver)

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `the settings route still answers, and reads no keys at all`() {
        every { themeResolver.resolve(any()) } returns "saas"

        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = controller.apiKeys(model, mockk(relaxed = true))

        viewName shouldBe "settings/api-keys"
        model["activeTheme"] shouldBe "saas"
        // The page has nothing to hide, so it asks the repository for nothing — the whole
        // point of reducing it. A `keys` attribute here would mean the table came back.
        model.containsKey("keys") shouldBe false
    }

    @Test
    fun `the page is the link and nothing else - no table, no form, no secret`() {
        val html =
            engine().process(
                "settings/api-keys",
                webContext().apply { fillLayoutChrome() },
            )

        html shouldContain "/api-console"
        html shouldContain "Moved to the API screen"
        // The three things that moved. Leaving any of them here would mean two key surfaces
        // again, which is exactly what 091 removed.
        html shouldNotContain "id=\"keys-table\""
        html shouldNotContain "createKeyForm"
        html shouldNotContain "shown once"
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

/**
 * 091 — minting and revoking from the API screen (ui-screens.md §4.18).
 *
 * The properties worth a test here are the ones a screenshot cannot show: the secret appears in
 * exactly ONE render and the toast only points at it; the table refresh is an OOB swap at TABLE
 * level (a nested `tbody` OOB element is destroyed by the browser's fragment parser); and the
 * form's own values are re-resolved server-side, so a hand-crafted POST gets the same answer the
 * form's user would.
 */
class ApiKeysPartialControllerTest {
    private val apiKeyService = mockk<ApiKeyService>()
    private val apiKeyRepository = mockk<ApiKeyRepository>()

    // The real issuance service, so the kind/scope/bindings contract is exercised rather than
    // stubbed — a mock here would let a contradiction through that production refuses.
    private val bindingRepository = mockk<EndpointKeyBindingRepository>(relaxed = true)
    private val auditSink = mockk<AuditEventSink>(relaxed = true)
    private val partialController =
        ApiKeysPartialController(
            apiKeyService,
            apiKeyRepository,
            EndpointKeyService(apiKeyService, bindingRepository, auditSink),
            ApiKeyRows(bindingRepository),
        )

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
    fun `create mints through the shared service and returns the once-shown panel`() {
        authenticate()
        every { apiKeyService.issue(any(), any(), any(), any(), any(), any(), any()) } returns sampleIssued()
        every { apiKeyRepository.findByUser(any()) } returns listOf(sampleKey())

        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = partialController.create("user", "read", "Test Key", null, null, null, model)

        viewName shouldBe "partials/api-key-created"
        model["key"] shouldBe "dpk_abc123.supersecret"
        model["keyId"] shouldBe "dpk_abc123"
        model["keyKind"] shouldBe "user"
    }

    @Test
    fun `the expiry select becomes an instant server-side, and a bad one is refused`() {
        authenticate()
        val expires = slot<Instant>()
        every {
            apiKeyService.issue(any(), any(), any(), any(), any(), capture(expires), any())
        } returns sampleIssued()
        every { apiKeyRepository.findByUser(any()) } returns listOf(sampleKey())

        partialController.create("user", "read", "k", "30", null, null, ExtendedModelMap())
        val thirtyDays = Duration.between(Instant.now(), expires.captured).toDays()
        withClue("30 days from now, give or take the test's own clock") {
            (thirtyDays in 29..30) shouldBe true
        }

        // …and the form's values are not trusted: a preset nobody rendered is a 400, not a
        // silently unexpiring key. Broadening the credential is the wrong way to fail.
        val refused =
            shouldThrow<ApiKeyExpiryInvalidException> {
                partialController.create("user", "read", "k", "3000", null, null, ExtendedModelMap())
            }
        refused.details["reason"] shouldBe "unknown_preset"
    }

    @Test
    fun `a scopeless kind never carries a scope, even when the form posts a stale one`() {
        authenticate()
        val scopes = slot<Set<Scope>>()
        every {
            apiKeyService.issue(any(), any(), capture(scopes), any(), any(), any(), ApiKeyKind.SERVER)
        } returns sampleIssued()
        every { apiKeyRepository.findByUser(any()) } returns listOf(sampleKey())

        // The disabled select does not submit, but a resubmitted or hand-crafted form can still
        // carry one. EndpointKeyService REFUSES a scope on a scopeless kind — so the screen must
        // not manufacture one, or every server-key mint would fail with a message about a field
        // the user cannot see.
        partialController.create("server", "admin", "uat receiver", null, null, null, ExtendedModelMap())

        scopes.captured shouldBe emptySet()
    }

    @Test
    fun `an unknown kind is a catalogued refusal, not a 500`() {
        authenticate()

        val refused =
            shouldThrow<DatapipelinesException> {
                partialController.create("wizard", null, "k", null, null, null, ExtendedModelMap())
            }

        refused.code shouldBe PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED
        refused.details["reason"] shouldBe "kind_unknown"
    }

    @Test
    fun `bindings arrive as repeated checkboxes and reach the service as paths`() {
        authenticate()
        every { apiKeyService.issue(any(), any(), any(), any(), any(), any(), ApiKeyKind.ENDPOINT) } returns sampleIssued()
        every { apiKeyRepository.findByUser(any()) } returns listOf(sampleKey())

        partialController.create("endpoint", null, "serve", null, null, listOf("/nyc", "/lending"), ExtendedModelMap())

        verify { bindingRepository.insert(match { it.pathPrefix == "/nyc" }) }
        verify { bindingRepository.insert(match { it.pathPrefix == "/lending" }) }
    }

    @Test
    fun `create renders the secret once, refreshes the table out-of-band, and toasts a pointer`() {
        authenticate()
        every { apiKeyService.issue(any(), any(), any(), any(), any(), any(), any()) } returns sampleIssued()
        every { apiKeyRepository.findByUser(any()) } returns listOf(sampleKey())

        val model: ExtendedModelMap = ExtendedModelMap()
        val view = partialController.create("user", "read", "ci", null, null, null, model)
        val html =
            engine().process(
                view,
                webContext().apply { model.forEach { (k, v) -> setVariable(k, v) } },
            )

        html shouldContain "Your new API key (shown once)" // the secret persists inline
        html shouldContain "hx-swap-oob=\"beforeend:#toast\""
        html shouldContain "copy it now" // the toast POINTS, never carries
        // …and the issued plaintext never appears anywhere after the OOB marker.
        html.substringAfter("hx-swap-oob=\"beforeend:#toast\"") shouldNotContain "dpk_abc123.supersecret"
        // E2: the table is refreshed out-of-band, from the same markup as the page. The OOB
        // element MUST be the <table>: a <tbody hx-swap-oob> nested inside the response's div is
        // DESTROYED by the browser's HTML parser (table-only tags outside table context are
        // dropped tokens — verified against htmx 2.0.10's makeFragment in a real browser), so
        // the refresh would silently never happen.
        Regex("""<table[^>]*id="keys-table"[^>]*hx-swap-oob="true"""")
            .containsMatchIn(html) shouldBe true
        html shouldNotContain "<tbody id=\"keys-table-body\" hx-swap-oob"
        html shouldContain "id=\"keys-table-body\""
    }

    @Test
    fun `revoke rebuilds the rows from the PAGE's fragment and toasts`() {
        authenticate()
        every { apiKeyService.revoke("dpk_abc123", any()) } returns true
        every { apiKeyRepository.findByUser(any()) } returns listOf(sampleKey().copy(isRevoked = true))

        val model: ExtendedModelMap = ExtendedModelMap()
        val view = partialController.revoke("dpk_abc123", model)
        val html =
            engine().process(
                view,
                webContext().apply { model.forEach { (k, v) -> setVariable(k, v) } },
            )

        view shouldBe "partials/api-keys-rows"
        html shouldContain "hx-swap-oob=\"beforeend:#toast\""
        html shouldContain ">revoked<"
        // The dead key keeps its row and loses its affordance — and the rows come from the
        // page's own fragment, so there is no second markup to keep in step (091 deleted the
        // Kotlin row builder and the parity test that policed it).
        html shouldNotContain "hx-delete"
        html shouldContain "<td class=\"num\">"
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
