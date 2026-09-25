package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointKeyService
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyExpiryInvalidException
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.IssuedApiKey
import co.datapipelines.auth.KeyKindNotMintableException
import co.datapipelines.auth.KeyRole
import co.datapipelines.auth.UserRepository
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 091 — `/settings/api-keys` is a LINK now (ui-screens.md §4.10); 179 repointed it: your MCP
 * key is the top bar's, the workspace's API keys are the admin page's. What this test
 * protects is that the redirect-by-hand is not a dead end: the route still answers, and the
 * page it renders points at both screens the keys actually live on.
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
    fun `the page is the pointer and nothing else - no table, no form, no secret`() {
        val html =
            engine().process(
                "settings/api-keys",
                webContext().apply { fillLayoutChrome() },
            )

        html shouldContain "/api-console"
        html shouldContain "top bar"
        // …and the admin page's link renders for the roles that hold MANAGE_API_KEYS
        // (withRoles defaults to the fullest set), never a table or form here.
        html shouldContain "href=\"/api-keys\""
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
        ).withRoles()
}

/**
 * Keys v2 (A15) — the ONE partial left of the top bar's pair: the show-once secret read,
 * now serving the KEYS PAGE (the chip and delete-to-rotate are gone with the login mint).
 *
 * The properties worth a test here are the ones a screenshot cannot show: the secret is
 * served ONLY by the copy endpoint (never rendered into a page), only the key's CREATOR may
 * open it, and a cross-site or navigating request is refused before the one-shot open —
 * the copy survives for the real Copy click.
 */
class ApiKeysPartialControllerTest {
    private val apiKeyService = mockk<ApiKeyService>()
    private val controller = ApiKeysPartialController(apiKeyService)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private val principal =
        AuthenticatedPrincipal(
            userId = userId,
            email = "a@b.c",
            displayName = "A",
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext(workspaceId, "acme"),
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    @Test
    fun `the secret endpoint serves the opened plaintext, no-store, or 404`() {
        authenticate()
        every { apiKeyService.openSealedSecret("dpk_abc123", userId) } returns "dpk_abc123.supersecret"

        val response = controller.secret("dpk_abc123")

        response.statusCode shouldBe HttpStatus.OK
        response.body shouldBe "dpk_abc123.supersecret"
        response.headers.cacheControl shouldBe "no-store"

        // A key minted before V31 — or after its one read — has nothing to open.
        every { apiKeyService.openSealedSecret("dpk_abc123", userId) } returns null
        controller.secret("dpk_abc123").statusCode shouldBe HttpStatus.NOT_FOUND
    }

    @Test
    fun `the secret endpoint refuses a cross-site or navigating request before it opens anything`() {
        authenticate()
        every { apiKeyService.openSealedSecret("dpk_abc123", userId) } returns "dpk_abc123.supersecret"

        // A hostile page's link or window.open, a sibling subdomain, the address bar: all 403,
        // and the one-shot open never runs — the copy survives for the real Copy click.
        controller.secret("dpk_abc123", "cross-site", "navigate").statusCode shouldBe HttpStatus.FORBIDDEN
        controller.secret("dpk_abc123", "cross-site", "no-cors").statusCode shouldBe HttpStatus.FORBIDDEN
        controller.secret("dpk_abc123", "same-site", "cors").statusCode shouldBe HttpStatus.FORBIDDEN
        controller.secret("dpk_abc123", "none", "navigate").statusCode shouldBe HttpStatus.FORBIDDEN
        controller.secret("dpk_abc123", "same-origin", "navigate").statusCode shouldBe HttpStatus.FORBIDDEN
        verify(exactly = 0) { apiKeyService.openSealedSecret(any(), any()) }

        // The page's own copy fetch (same-origin, cors) opens it.
        val response = controller.secret("dpk_abc123", "same-origin", "cors")
        response.statusCode shouldBe HttpStatus.OK
        response.body shouldBe "dpk_abc123.supersecret"
        verify(exactly = 1) { apiKeyService.openSealedSecret("dpk_abc123", userId) }
    }

    @Test
    fun `a principal with no workspace has nothing to open`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                principal.copy(workspace = null),
                null,
                emptyList(),
            )

        controller.secret("dpk_abc123").statusCode shouldBe HttpStatus.NOT_FOUND
        verify(exactly = 0) { apiKeyService.openSealedSecret(any(), any()) }
    }
}

/**
 * 179 (D17) — the `/api-keys` page's partials: create, delete, associate.
 *
 * The properties worth a test here are the ones a screenshot cannot show: the secret appears
 * in exactly ONE render and the toast only points at it; the table refresh is an OOB swap at
 * TABLE level (a nested `tbody` OOB element is destroyed by the browser's fragment parser);
 * and the form's own values are re-resolved server-side, so a hand-crafted POST gets the
 * same answer the form's user would.
 */
class ApiKeysAdminControllerTest {
    private val apiKeyService = mockk<ApiKeyService>()
    private val apiKeyRepository = mockk<ApiKeyRepository>()

    // The real issuance service, so the kind/bindings contract is exercised rather than
    // stubbed — a mock here would let a contradiction through that production refuses.
    private val bindingRepository = mockk<EndpointKeyBindingRepository>(relaxed = true)
    private val auditSink = mockk<AuditEventSink>(relaxed = true)
    private val publishedEndpoints = mockk<co.datapipelines.application.endpoints.PublishedEndpointRepository>()
    private val publishing = mockk<EndpointPublishService>()
    private val userRepository = mockk<UserRepository>()
    private val themeResolver = mockk<ThemeResolver>()
    private val controller =
        ApiKeysAdminController(
            apiKeyService,
            apiKeyRepository,
            EndpointKeyService(apiKeyService, bindingRepository, auditSink, publishedEndpoints),
            publishing,
            ApiKeyRows(bindingRepository),
            bindingRepository,
            userRepository,
            themeResolver,
        )

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private val principal =
        AuthenticatedPrincipal(
            userId = userId,
            email = "a@b.c",
            displayName = "A",
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext(workspaceId, "acme", co.datapipelines.auth.WorkspaceRole.WORKSPACE_ADMIN),
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
            name = "ci",
            keyHash = "hash",
            isRevoked = false,
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            lastUsedAt = null,
            expiresAt = null,
            workspaceId = workspaceId,
            workspaceName = "acme",
            kind = ApiKeyKind.ENDPOINT,
            role = KeyRole.API_CALLER,
        )

    private fun sampleIssued() =
        IssuedApiKey(
            record = sampleKey(),
            plaintext = "dpk_abc123.supersecret",
        )

    private fun stubPageReads() {
        every { publishing.list(any()) } returns emptyList()
        // The page reads the workspace's keys of EVERY kind (keys v2) and labels their users.
        every { apiKeyRepository.findByWorkspace(workspaceId) } returns listOf(sampleKey())
        every { userRepository.findById(userId) } returns null
        // #191: the bind-time check reads the workspace's published tree; these fixtures bind
        // under /nyc and /lending, so both subtrees are published here.
        every { publishedEndpoints.findByWorkspace(workspaceId) } returns
            listOf(publishedAt("/nyc/v1/revenue/{borough}"), publishedAt("/lending/v1/summary"))
    }

    private fun publishedAt(pattern: String) =
        co.datapipelines.application.endpoints.PublishedEndpoint.of(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            pathPattern = pattern,
            pipelineId = UUID.randomUUID(),
            timeoutSeconds = 60,
            description = "",
            isEnabled = true,
            createdBy = userId,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `create mints through the shared service and returns the once-shown panel`() {
        authenticate()
        stubPageReads()
        every { apiKeyService.issue(any(), any(), any(), any(), ApiKeyKind.ENDPOINT, null) } returns sampleIssued()

        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = controller.create("endpoint", null, "ci", null, null, null, model)

        viewName shouldBe "partials/api-key-created"
        model["key"] shouldBe "dpk_abc123.supersecret"
        model["keyId"] shouldBe "dpk_abc123"
        model["keyKind"] shouldBe "endpoint"
        // #215: the panel names the key's role — its kind's for the transport kinds (keys v2
        // gives the mcp kind the CHOICE).
        model["keyRole"] shouldBe "api caller"
    }

    @Test
    fun `create carries the requested role for an mcp key - the subset rule is the service's (A14)`() {
        authenticate()
        stubPageReads()
        val requestedRole = slot<KeyRole>()
        every { apiKeyService.issue(any(), any(), any(), any(), ApiKeyKind.MCP, capture(requestedRole)) } returns
            IssuedApiKey(
                record = sampleKey().copy(kind = ApiKeyKind.MCP, role = KeyRole.AUTHOR, name = "agent"),
                plaintext = "dpk_abc123.supersecret",
            )

        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = controller.create("mcp", "author", "agent", null, null, null, model)

        viewName shouldBe "partials/api-key-created"
        model["keyKind"] shouldBe "mcp"
        model["keyRole"] shouldBe "author"
        requestedRole.captured shouldBe KeyRole.AUTHOR
    }

    @Test
    fun `no kind, no mint - the catalogued refusal (keys v2 A15)`() {
        authenticate()
        stubPageReads()

        val refused =
            shouldThrow<KeyKindNotMintableException> {
                controller.create(null, null, "ci", null, null, null, ExtendedModelMap())
            }

        refused.code shouldBe "auth.key_kind_not_mintable"
        refused.status shouldBe 400
    }

    @Test
    fun `a pre-v2 kind wire word is an unknown kind now - refused, never reinterpreted (A19)`() {
        authenticate()
        stubPageReads()

        val refused =
            shouldThrow<DatapipelinesException> {
                controller.create("user", null, "ci", null, null, null, ExtendedModelMap())
            }

        refused.code shouldBe PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED
        refused.details["reason"] shouldBe "kind_unknown"
    }

    @Test
    fun `an unknown kind is a catalogued refusal, not a 500`() {
        authenticate()

        val refused =
            shouldThrow<DatapipelinesException> {
                controller.create("wizard", null, "k", null, null, null, ExtendedModelMap())
            }

        refused.code shouldBe PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED
        refused.details["reason"] shouldBe "kind_unknown"
    }

    @Test
    fun `the expiry select becomes an instant server-side, and a bad one is refused`() {
        authenticate()
        stubPageReads()
        val expires = slot<Instant>()
        every {
            apiKeyService.issue(any(), any(), any(), capture(expires), ApiKeyKind.ENDPOINT, any())
        } returns sampleIssued()

        controller.create("endpoint", null, "k", "30", null, null, ExtendedModelMap())
        val thirtyDays = Duration.between(Instant.now(), expires.captured).toDays()
        withClue("30 days from now, give or take the test's own clock") {
            (thirtyDays in 29..30) shouldBe true
        }

        // …and the form's values are not trusted: a preset nobody rendered is a 400, not a
        // silently unexpiring key. Broadening the credential is the wrong way to fail.
        val refused =
            shouldThrow<ApiKeyExpiryInvalidException> {
                controller.create("endpoint", null, "k", "3000", null, null, ExtendedModelMap())
            }
        refused.details["reason"] shouldBe "unknown_preset"
    }

    @Test
    fun `associations arrive as repeated checkboxes and reach issuance as paths`() {
        authenticate()
        stubPageReads()
        every { apiKeyService.issue(any(), any(), any(), any(), ApiKeyKind.ENDPOINT, null) } returns sampleIssued()

        controller.create("endpoint", null, "serve", null, null, listOf("/nyc", "/lending"), ExtendedModelMap())

        verify { bindingRepository.insert(match { it.pathPrefix == "/nyc" }) }
        verify { bindingRepository.insert(match { it.pathPrefix == "/lending" }) }
    }

    @Test
    fun `create renders the secret once, refreshes the table out-of-band, and toasts a pointer`() {
        authenticate()
        stubPageReads()
        every { apiKeyService.issue(any(), any(), any(), any(), ApiKeyKind.ENDPOINT, null) } returns sampleIssued()

        val model: ExtendedModelMap = ExtendedModelMap()
        val view = controller.create("endpoint", null, "ci", null, null, null, model)
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
    fun `delete hands the PRINCIPAL to the one revocation verb and rebuilds the rows (A14)`() {
        authenticate()
        every { apiKeyRepository.findById("dpk_abc123") } returns sampleKey()
        every { apiKeyService.revokeAs(match { it.userId == userId }, "dpk_abc123") } returns true
        every { publishing.list(any()) } returns emptyList()
        every { apiKeyRepository.findByWorkspace(workspaceId) } returns listOf(sampleKey().copy(isRevoked = true))
        every { userRepository.findById(userId) } returns null

        val model: ExtendedModelMap = ExtendedModelMap()
        val view = controller.revoke("dpk_abc123", model)
        val html =
            engine().process(
                view,
                webContext().apply { model.forEach { (k, v) -> setVariable(k, v) } },
            )

        view shouldBe "partials/api-keys-rows"
        // A14: the created-by / api_key.revoke / server_key.revoke judgement is the SERVICE's —
        // both delete routes hand it the principal and render its answer.
        verify { apiKeyService.revokeAs(match { it.userId == userId }, "dpk_abc123") }
        html shouldContain "hx-swap-oob=\"beforeend:#toast\""
        html shouldContain ">deleted<"
    }

    @Test
    fun `a silent refusal redraws the table and discloses nothing (A14)`() {
        authenticate()
        stubPageReads()
        every { apiKeyRepository.findById("dpk_other") } returns
            sampleKey("dpk_other").copy(workspaceId = UUID.randomUUID())
        every { apiKeyService.revokeAs(any(), "dpk_other") } returns false

        val model: ExtendedModelMap = ExtendedModelMap()
        val view = controller.revoke("dpk_other", model)

        view shouldBe "partials/api-keys-rows"
        verify { apiKeyService.revokeAs(any(), "dpk_other") }
    }

    @Test
    fun `associate writes the delta between the posted set and the current bindings`() {
        authenticate()
        stubPageReads()
        every { apiKeyRepository.findById("dpk_abc123") } returns sampleKey()
        every { bindingRepository.findByKey("dpk_abc123") } returns
            listOf(
                co.datapipelines.application.endpoints
                    .EndpointKeyBinding("/nyc", "dpk_abc123", workspaceId, userId, Instant.now()),
                co.datapipelines.application.endpoints
                    .EndpointKeyBinding("/old", "dpk_abc123", workspaceId, userId, Instant.now()),
            )

        controller.associate("dpk_abc123", listOf("/nyc", "/lending"), ExtendedModelMap())

        // /nyc was already bound (idempotent), /lending is added, /old is removed.
        verify(exactly = 1) { bindingRepository.insert(match { it.pathPrefix == "/lending" }) }
        verify(exactly = 0) { bindingRepository.insert(match { it.pathPrefix == "/nyc" }) }
        verify(exactly = 1) { bindingRepository.delete("/old", "dpk_abc123", workspaceId) }
    }

    @Test
    fun `associate refuses a key of another kind or workspace as not found`() {
        authenticate()
        // An MCP key (somebody's agent credential) must never take a binding from this page.
        every { apiKeyRepository.findById("dpk_user1") } returns sampleKey("dpk_user1").copy(kind = ApiKeyKind.MCP, role = KeyRole.AUTHOR)

        val refused =
            shouldThrow<DatapipelinesException> {
                controller.associate("dpk_user1", listOf("/nyc"), ExtendedModelMap())
            }
        refused.code shouldBe PipelineErrorCodes.Endpoint.NOT_FOUND
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
}
