package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuthProperties
import co.datapipelines.mcp.McpToolCatalog
import co.datapipelines.pipeline.SearchNeedle
import co.datapipelines.web.ui.site.REPORT_PROBLEM_URL
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.ui.ExtendedModelMap
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import java.time.Instant
import java.util.UUID

/**
 * #585 — the API home at the RENDER (`overview/family.html` + `overview/guide-api-console.html` +
 * the shared `partials/mcp-connection.html`): the introduction, the three access steps, the
 * publish prompt that never asks for a credential, the console's MCP card with the PLACEHOLDER
 * and the catalog's tool count, both charts with their table twins, the role-gated Manage API
 * keys link — and no `dpk_` on the page beyond the placeholder.
 */
class OverviewApiRenderTest {
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

    @Test
    fun `the guide renders the introduction, the three access steps, the prompt and the related links`() {
        val html = html(page(), workspace = "<b>acme</b>")
        val doc = Jsoup.parse(html)

        doc.selectFirst("h1")!!.text() shouldBe "API"
        doc.selectFirst("[data-overview-view-all]")!!.attr("href") shouldBe "/api-console"
        doc.selectFirst("[data-overview-intro]")!!.text() shouldContain
            "A released, read-only pipeline can be published at a stable URL"
        doc.selectFirst("[data-overview-intro]")!!.text() shouldContain "DP-API-Key"
        val access = doc.select("[data-overview-access] .app-overview-steps li")
        access.size shouldBe 3
        access[0].text() shouldContain "endpoints_create"
        access[0].text() shouldContain "There is no form for it in the app"
        access[2].text() shouldContain "DP-API-Key"
        doc.select("[data-overview-prompt] .app-overview-steps li").size shouldBe 3
        html shouldNotContain "<b>acme</b>"
        val prompt = doc.selectFirst("textarea#overview-prompt").shouldNotBeNull()
        prompt.`val`() shouldContain "In workspace <b>acme</b>, publish the released pipeline [pipeline]"
        prompt.`val`() shouldContain "endpoints_create"
        prompt.`val`() shouldContain "endpoints_get"
        prompt.children().shouldBeEmpty()
        // The agent never mints, associates or copies a credential (skill/endpoints.md): the
        // prompt names no key at all, and carries nothing that looks like one.
        listOf("key", "dpk_", "password", "secret", "token", "://").forEach { prompt.`val`().lowercase() shouldNotContain it }
        doc.select("[data-overview-copy]").single().attr("type") shouldBe "button"
        doc.select("template[data-page-assets]").single().html() shouldContain "/js/overview.mjs"
        doc.select("[data-overview-related] a").map { it.attr("href") } shouldContainExactly
            listOf("/overview/pipelines", "/overview/dashboards", "/docs/rest-api", "/docs/mcp-server", "/add-mcp-server-to-claude-code")
    }

    @Test
    fun `the MCP card is the console's - the placeholder config and the catalog's tool count, no Copy`() {
        val html = html(page())
        val card = Jsoup.parse(html).select("[data-mcp-connection]").single()

        card.selectFirst("h2")!!.text() shouldBe "MCP server"
        // Derived at request time from McpToolCatalog.NAMES — never a literal (ApiConsoleRenderTest's check).
        card.text() shouldContain "${McpToolCatalog.NAMES.size} tools"
        val config = card.selectFirst("pre.app-code").shouldNotBeNull().text()
        config shouldContain "\"url\": \"https://app.example/mcp\""
        config shouldContain "\"DP-API-Key\": \"dpk_…\""
        card.text() shouldContain "The config wants an MCP key where the placeholder stands"
        card.text() shouldNotContain "top bar"
        card.select("[data-overview-copy], button").shouldBeEmpty()
        // The only dpk_ on the whole page is the config's placeholder.
        Regex("dpk_").findAll(html).count() shouldBe 1
    }

    @Test
    fun `both charts render with their table twins and the keys note names what was not counted`() {
        val doc = render(page())

        doc.select("[data-overview-inventory] [data-count]").map { it.attr("data-count") } shouldContainExactly
            listOf("endpoints", "release", "draft", "keys")
        doc.select("[data-overview-inventory] [data-count] dd.app-overview-count-v").map { it.text() } shouldContainExactly
            listOf("3", "1", "1", "3")
        doc.selectFirst("[data-count=keys] dt")!!.text() shouldBe "Workspace keys"
        doc.select("[data-chart-table=endpoint-state] tbody tr").map { it.text() } shouldContainExactly
            listOf("Serving a release 1", "Serving a draft 1", "Nothing to serve 0", "Legacy path 1")
        doc.select("[data-chart-table=key-kind] tbody tr").map { it.text() } shouldContainExactly
            listOf("MCP key 1", "API key 0", "Server key 0", "Dashboard key 0")
        doc.select("[data-chart-table=key-kind] thead th").map { it.text() } shouldContainExactly listOf("Kind", "Live keys")
        doc.select("[data-overview-chart][data-chart=endpoint-state] a").map { it.attr("href") }.toSet() shouldBe setOf("/api-console")
        doc.select("[data-overview-chart][data-chart=key-kind] a").map { it.attr("href") }.toSet() shouldBe setOf("/api-keys")
        doc.select("[data-overview-inventory] .app-card-note").joinToString(" ") { it.text() } shouldContain
            "Not counted: 1 expired, 1 revoked."
        doc.selectFirst("[data-overview-review]").shouldBeNull()
        doc.select("[data-overview-recent] h3").map { it.text() } shouldContainExactly
            listOf("Recently published", "Recently created keys")
        doc.select("[data-overview-recent] li a").map { it.text() } shouldContainExactly
            listOf(
                "/api/nyc/v1/newest · Revenue · v2 · draft",
                "/api/nyc/v1/oldest · Mobility · v3",
                "nightly · MCP key · live",
                "old-ci · API key · expired",
                "retired · Server key · revoked",
            )
        doc.select("[data-overview-recent] .ds-badge").shouldBeEmpty()
    }

    @Test
    fun `the manage link renders for the roles that administer keys and a sentence for everyone else`() {
        val admin = render(page())
        admin.selectFirst("[data-overview-manage-keys]").shouldNotBeNull().attr("href") shouldBe "/api-keys"
        admin.selectFirst("#overview-prompt-note")!!.text() shouldContain "its own MCP key"

        val viewer =
            render(
                page(keysHeading = ApiOverviewController.YOUR_KEYS),
                canAuthor = false,
                canAdminWorkspace = false,
                isSuperAdmin = false,
            )
        viewer.selectFirst("[data-overview-manage-keys]").shouldBeNull()
        viewer.selectFirst("[data-overview-access]")!!.text() shouldContain "Ask a workspace admin for this step."
        viewer.selectFirst("[data-count=keys] dt")!!.text() shouldBe "Your keys"
        viewer.selectFirst("#overview-prompt-note")!!.text() shouldContain "Your role here does not publish endpoints"
    }

    /** An unavailable inventory leaves no digit in the area; the steps and the MCP card still render. */
    @Test
    fun `a failed endpoints read is unavailable with no digit and the guide still answers`() {
        val down =
            render(
                page().copy(
                    inventory = null,
                    unavailable = "The endpoints could not be read.",
                    recent = ApiHomePage.recent(null, KEYS, NOW),
                ),
            )

        val area = down.selectFirst("[data-overview-inventory]").shouldNotBeNull()
        area.selectFirst("[data-overview-unavailable]").shouldNotBeNull().text() shouldContain "The endpoints could not be read."
        area.select("[data-overview-chart], [data-count], [data-overview-table]").shouldBeEmpty()
        Regex("""\d""").containsMatchIn(area.text()) shouldBe false
        down.selectFirst("[data-overview-recent]")!!.text() shouldContain "Recently published is unavailable."
        down.select("[data-overview-access] .app-overview-steps li").size shouldBe 3
        down.select("[data-mcp-connection]").size shouldBe 1
    }

    /** A failed keys read alone: three tiles, the keys chart caption and note say so, no key number. */
    @Test
    fun `a failed keys read empties only the keys chart`() {
        val doc = render(page(keys = null))

        doc.select("[data-overview-inventory] [data-count]").map { it.attr("data-count") } shouldContainExactly
            listOf("endpoints", "release", "draft")
        doc.selectFirst("[data-overview-chart][data-chart=key-kind]")!!.attr("aria-label") shouldBe "Keys by kind — unavailable"
        doc.select("[data-overview-chart][data-chart=key-kind] li").shouldBeEmpty()
        doc.select("[data-overview-inventory] .app-card-note").joinToString(" ") { it.text() } shouldContain
            "The keys could not be read, so no key is counted here."
        doc.selectFirst("[data-overview-recent]")!!.text() shouldContain "The keys could not be read."
    }

    @Test
    fun `an empty workspace shows zeros and both empty lines with the steps still shown`() {
        val empty =
            render(
                page(
                    endpoints = EndpointInventory(emptyList(), 0, emptyMap(), emptyMap()),
                    keys = emptyList(),
                ),
            )

        empty.select("[data-overview-inventory] [data-count] dd.app-overview-count-v").map { it.text() } shouldContainExactly
            listOf("0", "0", "0", "0")
        empty.select("[data-overview-chart] rect").map { it.attr("width") }.toSet() shouldBe setOf("0")
        empty.select("[data-overview-recent-empty]").map { it.text() } shouldContainExactly
            listOf(
                "Nothing is published yet. The three steps above say how.",
                "No keys yet. A key is created by a person on the Keys page.",
            )
        empty.select("[data-overview-access] .app-overview-steps li").size shouldBe 3
    }

    private fun page(
        endpoints: EndpointInventory = inventory(),
        keys: List<ApiKey>? = KEYS,
        keysHeading: String = ApiOverviewController.WORKSPACE_KEYS,
    ): OverviewPage =
        OverviewPage(
            family = ApiOverviewController.FAMILY,
            title = "API",
            catalogHref = ApiOverviewController.CONSOLE,
            lensed = false,
            inventory = ApiHomePage.panel(endpoints, keys?.let { KeyCounts.of(it, NOW) }, keysHeading),
            unavailable = null,
            recent = ApiHomePage.recent(endpoints, keys, NOW),
            guided = true,
        )

    private fun inventory(): EndpointInventory {
        val released = endpoint("/nyc/v1/oldest", NOW.minusSeconds(7200))
        val drafted = endpoint("/nyc/v1/newest", NOW.minusSeconds(60))
        return EndpointInventory(
            valid = listOf(released, drafted),
            legacyCount = 1,
            served =
                mapOf(
                    released.pipelineId to ServedVersion(3, draft = false),
                    drafted.pipelineId to ServedVersion(2, draft = true),
                ),
            names =
                mapOf(
                    released.pipelineId to PipelineName("nyc/mobility", "Mobility"),
                    drafted.pipelineId to PipelineName("nyc/revenue", "Revenue"),
                ),
        )
    }

    private fun endpoint(
        path: String,
        createdAt: Instant,
    ): PublishedEndpoint =
        PublishedEndpoint.of(
            id = UUID.randomUUID(),
            workspaceId = UUID.randomUUID(),
            pathPattern = path,
            pipelineId = UUID.randomUUID(),
            timeoutSeconds = 30,
            description = "",
            isEnabled = true,
            createdBy = UUID.randomUUID(),
            createdAt = createdAt,
            updatedAt = createdAt,
        )

    private fun render(
        page: OverviewPage,
        canAuthor: Boolean = true,
        canAdminWorkspace: Boolean = true,
        isSuperAdmin: Boolean = true,
    ): Document = Jsoup.parse(html(page, canAuthor = canAuthor, canAdminWorkspace = canAdminWorkspace, isSuperAdmin = isSuperAdmin))

    private fun html(
        page: OverviewPage,
        workspace: String = "acme",
        canAuthor: Boolean = true,
        canAdminWorkspace: Boolean = true,
        isSuperAdmin: Boolean = true,
    ): String {
        val path = ApiOverviewController.PATH
        val context =
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
            ).withRoles(canAuthor = canAuthor, canAdminWorkspace = canAdminWorkspace, isSuperAdmin = isSuperAdmin)
        context.setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
        context.setVariable("workspaceHeaderFragment", "")
        context.setVariable("workspaceOptions", emptyList<Any>())
        context.setVariable("activeWorkspace", workspace)
        context.setVariable("activeTheme", "saas")
        context.setVariable("authenticated", true)
        context.setVariable("currentPath", path)
        context.setVariable("crumbGroup", AppNav.crumbFor(path)?.first)
        context.setVariable("crumbPage", AppNav.crumbFor(path)?.second)
        context.setVariable("searchMaxLength", SearchNeedle.MAX_LENGTH)
        context.setVariable("reportProblemUrl", REPORT_PROBLEM_URL)
        // The MCP card's values from the component the controller stamps them with.
        ExtendedModelMap()
            .also { McpConnectionCard(AuthProperties(baseUrl = "https://app.example")).stamp(it) }
            .forEach { (name, value) -> context.setVariable(name, value) }
        context.setVariable(OverviewController.MODEL, page)
        return engine.process(OverviewController.VIEW, context)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-19T02:00:00Z")

        val KEYS: List<ApiKey> =
            listOf(
                key("nightly", ApiKeyKind.MCP, NOW.minusSeconds(60), revoked = false, expiresAt = null),
                key("old-ci", ApiKeyKind.ENDPOINT, NOW.minusSeconds(3600), revoked = false, expiresAt = NOW.minusSeconds(1)),
                key("retired", ApiKeyKind.SERVER, NOW.minusSeconds(7200), revoked = true, expiresAt = null),
            )

        fun key(
            name: String,
            kind: ApiKeyKind,
            createdAt: Instant,
            revoked: Boolean,
            expiresAt: Instant?,
        ): ApiKey =
            ApiKey(
                id = "dpk_SENTINEL$name",
                userId = UUID.randomUUID(),
                name = name,
                keyHash = "HASH-SENTINEL-$name",
                isRevoked = revoked,
                createdAt = createdAt,
                lastUsedAt = null,
                expiresAt = expiresAt,
                workspaceId = UUID.randomUUID(),
                workspaceName = "acme",
                kind = kind,
            )
    }
}
