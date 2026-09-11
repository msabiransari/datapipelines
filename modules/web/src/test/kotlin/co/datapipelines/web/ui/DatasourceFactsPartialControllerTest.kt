package co.datapipelines.web.ui

import co.datapipelines.application.semantics.SemanticsService
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.MembershipFlags
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.servlet.ModelAndView
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import java.util.UUID

/**
 * The datasource LEARNED FACTS dialog (118 §7.3, ui-screens.md §4.5b) — read-only round 1.
 *
 * Proven: the handler claims `READ_RESOURCES` (any member reads facts — the store's predicate
 * decides which), the §5.3 gate answers an invisible datasource with the same refusal as an
 * unknown one BEFORE the service runs, the model derives the trust badge and the object
 * label, and the fragment renders trust/drift/conflict/provenance the way the design names them.
 */
class DatasourceFactsPartialControllerTest {
    private val datasources = mockk<DatasourceRegistry>()
    private val semantics = mockk<SemanticsService>()
    private val controller = DatasourceFactsPartialController(datasources, semantics)
    private val workspaceId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `the dialog is a member read - READ_RESOURCES, the listing's own floor`() {
        DatasourceFactsPartialController::class.java.methods
            .first { it.name == "dialog" }
            .getAnnotation(RequiredScope::class.java)
            .value shouldBe ScopeMatrix.RestOperation.READ_RESOURCES
    }

    @Test
    fun `an invisible datasource is refused before the service runs, exactly like an unknown one`() {
        authenticate()
        every { datasources.getVisible("theirs", workspaceId) } returns null

        val refusal = controller.dialog(ExtendedModelMap(), "theirs") as ModelAndView

        refusal.viewName shouldBe "partials/inline-refusal"
        verify(exactly = 0) { semantics.list(any(), any(), any()) }
    }

    @Test
    fun `the dialog renders each fact with its object, kind, trust badge, drift, conflict and provenance`() {
        authenticate()
        val datasource = datasource()
        every { datasources.getVisible("warehouse", workspaceId) } returns datasource
        every { semantics.list(any(), datasource, SemanticsService.ListQuery()) } returns
            listOf(
                fact(trust = "observed", extra = mapOf("conflict" to true)),
                fact(
                    trust = "stale",
                    extra =
                        mapOf(
                            "drift" to "column amount no longer exists",
                            "from_this_workspace" to false,
                            "source_pipeline" to mapOf("id" to "p1", "name" to "finance/revenue"),
                        ),
                ),
            )

        val model = ExtendedModelMap()
        controller.dialog(model, "warehouse") shouldBe "partials/datasource-facts :: dialog"
        val html = render(model)

        @Suppress("UNCHECKED_CAST")
        val rows = model["facts"] as List<Map<String, Any?>>
        assertAll(
            { rows[0]["trustBadge"] shouldBe "ds-badge-success" },
            { rows[1]["trustBadge"] shouldBe "ds-badge-danger" },
            { rows[0]["object"] shouldBe "public.orders.amount" },
            { html shouldContain "Learned facts — <span>warehouse</span>" },
            { html shouldContain "data-trust=\"stale\"" },
            { html shouldContain "column amount no longer exists" },
            { html shouldContain ">conflict</span>" },
            { html shouldContain "via another workspace" },
            { html shouldContain "finance/revenue" },
            { html shouldNotContain "Nothing learned yet" },
        )
    }

    @Test
    fun `no facts renders the empty state, and the trust mapping covers every design state`() {
        authenticate()
        val datasource = datasource()
        every { datasources.getVisible("warehouse", workspaceId) } returns datasource
        every { semantics.list(any(), datasource, SemanticsService.ListQuery()) } returns emptyList()

        val model = ExtendedModelMap()
        controller.dialog(model, "warehouse")

        assertAll(
            { render(model) shouldContain "Nothing learned yet" },
            { DatasourceFactsModel.trustBadge("verified") shouldBe "ds-badge-success" },
            { DatasourceFactsModel.trustBadge("needs_review") shouldBe "ds-badge-warning" },
            { DatasourceFactsModel.trustBadge("asserted") shouldBe "ds-badge-default" },
            { DatasourceFactsModel.trustBadge("retired") shouldBe "ds-badge-default" },
        )
    }

    private fun render(model: ExtendedModelMap): String {
        val context =
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
            )
        model.asMap().forEach { (k, v) -> context.setVariable(k, v) }
        val engine =
            SpringTemplateEngine().apply {
                setTemplateResolver(
                    ClassLoaderTemplateResolver().apply {
                        this.prefix = "templates/"
                        suffix = ".html"
                        characterEncoding = "UTF-8"
                    },
                )
            }
        return engine.process("partials/datasource-facts", setOf("dialog"), context)
    }

    private fun fact(
        trust: String,
        extra: Map<String, Any?>,
    ): Map<String, Any?> =
        mapOf(
            "id" to UUID.randomUUID().toString(),
            "scope" to "DATASOURCE",
            "kind" to "unit",
            "fact" to "amount is in cents",
            "trust" to trust,
            "evidence_summary" to "amount=1250",
            "recorded_via" to "mcp",
            "recorded_at" to "2026-09-11T10:00:00Z",
            "from_this_workspace" to true,
            "refs" to listOf(mapOf("schema" to "public", "table" to "orders", "column" to "amount")),
        ) + extra

    private fun datasource() =
        Datasource(
            name = "warehouse",
            displayName = "Warehouse",
            dialect = Dialect.POSTGRES,
            jdbcUrl = "jdbc:postgresql://db.internal:5432/app",
            username = "app",
        )

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                AuthenticatedPrincipal(
                    userId = UUID.randomUUID(),
                    email = "a@b.c",
                    displayName = "A",
                    scopes = emptySet(),
                    authMethod = AuthMethod.OIDC,
                    workspace = WorkspaceContext(workspaceId, "acme", MembershipFlags.VIEWER),
                ),
                null,
                org.springframework.security.core.authority.AuthorityUtils.NO_AUTHORITIES,
            )
    }
}
