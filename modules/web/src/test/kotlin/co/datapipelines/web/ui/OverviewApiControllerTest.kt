package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.mcp.McpToolCatalog
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.time.Instant
import java.util.UUID

/**
 * #585 — the API home controller maps its two reads onto the shared frame: four tiles, the
 * endpoints-by-state and keys-by-kind charts, two recent sections — and each read fails on its
 * own: a failed endpoints read is the frame's one unavailable inventory, a failed keys read drops
 * only the keys tile and empties only the keys chart. No number is shown for what was not read,
 * and no credential value reaches the model.
 */
class OverviewApiControllerTest {
    private val workspaceId = UUID.randomUUID()
    private val reads = mockk<ApiHomeReads>()
    private val controller =
        ApiOverviewController(
            reads,
            McpConnectionCard(AuthProperties(baseUrl = "https://app.example/")),
            mockk { every { resolve(any()) } returns "light" },
        )

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `the two reads become four tiles, two charts and two recent sections`() {
        authenticate(WorkspaceRole.WORKSPACE_ADMIN)
        answer()

        val model = ExtendedModelMap()
        controller.overview(model, MockHttpServletRequest()) shouldBe OverviewController.VIEW
        model["activeTheme"] shouldBe "light"
        model["mcpToolCount"] shouldBe McpToolCatalog.NAMES.size
        (model["mcpConfigJson"] as String) shouldContain "\"url\": \"https://app.example/mcp\""
        (model["mcpConfigJson"] as String) shouldContain "\"DP-API-Key\": \"dpk_…\""

        val page = model[OverviewController.MODEL] as OverviewPage
        page.family shouldBe "api-console"
        page.title shouldBe "API"
        page.catalogHref shouldBe "/api-console"
        page.guided shouldBe true
        page.lensed shouldBe false
        val panel = page.inventory.shouldNotBeNull()
        panel.counts.map { Triple(it.key, it.label, it.value) } shouldContainExactly
            listOf(
                Triple("endpoints", "Endpoints", 5L),
                Triple("release", "Serving a release", 2L),
                Triple("draft", "Serving a draft", 1L),
                Triple("keys", "Workspace keys", 3L),
            )
        panel.counts.map { it.href } shouldContainExactly listOf("/api-console", null, null, "/api-keys")
        val (states, kinds) = panel.charts
        states.key shouldBe "endpoint-state"
        states.bars.map { it.key to it.value } shouldContainExactly
            listOf("serving-release" to 2L, "serving-draft" to 1L, "nothing-to-serve" to 1L, "legacy" to 1L)
        states.bars.map { it.href }.toSet() shouldBe setOf("/api-console")
        states.bars.map { it.width } shouldContainExactly listOf("100.0", "50.0", "50.0", "50.0")
        kinds.key shouldBe "key-kind"
        kinds.bars.map { it.key to it.value } shouldContainExactly
            listOf("mcp" to 1L, "endpoint" to 0L, "server" to 0L, "dashboard" to 0L)
        kinds.bars.map { it.href }.toSet() shouldBe setOf("/api-keys")
        kinds.note.shouldNotBeNull() shouldContain "Not counted: 1 expired, 1 revoked."

        val (published, created) = page.recent.shouldNotBeNull().sections
        published.heading shouldBe "Recently published"
        published.entries.shouldNotBeNull().map { it.name } shouldContainExactly
            listOf(
                "/api/nyc/v1/newest · Revenue · v2 · draft",
                "/api/nyc/v1/middle · nyc/orphan · nothing to serve",
                "/api/nyc/v1/oldest · Mobility · v3",
                "/api/nyc/v1/again · Mobility · v3",
            )
        published.entries.map { it.href }.toSet() shouldBe setOf("/api-console")
        created.heading shouldBe "Recently created keys"
        created.entries.shouldNotBeNull().map { it.name } shouldContainExactly
            listOf("nightly · MCP key · live", "old-ci · API key · expired", "retired · Server key · revoked")
        created.entries.map { it.href }.toSet() shouldBe setOf("/api-keys")
        // No credential value is selected into the model: neither a key's id (its dpk_ handle)
        // nor its hash, anywhere in what the template is handed.
        val rendered = page.toString()
        KEYS.forEach { key ->
            rendered shouldNotContain key.id
            rendered shouldNotContain key.keyHash
        }
    }

    @Test
    fun `a member without api_key read sees the keys heading as their own`() {
        authenticate(WorkspaceRole.VIEWER)
        answer()

        model()
            .inventory
            .shouldNotBeNull()
            .counts
            .single { it.key == "keys" }
            .label shouldBe "Your keys"
    }

    @Test
    fun `a promoter's endpoint counts are marked admitted`() {
        authenticate(WorkspaceRole.PROMOTER)
        answer(lensed = true)

        model().lensed shouldBe true
    }

    @Test
    fun `a failed endpoints read is the unavailable inventory and the keys section still answers`() {
        authenticate(WorkspaceRole.WORKSPACE_ADMIN)
        answer()
        every { reads.endpoints(any()) } throws IllegalStateException("database down")

        val page = model()

        page.inventory.shouldBeNull()
        page.unavailable shouldBe "The endpoints could not be read."
        val (published, created) = page.recent.shouldNotBeNull().sections
        published.entries.shouldBeNull()
        published.unavailable shouldBe "The endpoints could not be read."
        created.entries.shouldNotBeNull().size shouldBe 3
    }

    @Test
    fun `a failed keys read drops the keys tile and empties only the keys chart`() {
        authenticate(WorkspaceRole.WORKSPACE_ADMIN)
        answer()
        every { reads.keys(any()) } throws IllegalStateException("database down")

        val page = model()

        page.unavailable.shouldBeNull()
        val panel = page.inventory.shouldNotBeNull()
        panel.counts.map { it.key } shouldContainExactly listOf("endpoints", "release", "draft")
        val (states, kinds) = panel.charts
        states.bars.map { it.value } shouldContainExactly listOf(2L, 1L, 1L, 1L)
        kinds.bars.shouldBeEmpty()
        kinds.caption shouldBe "Keys by kind — unavailable"
        kinds.note shouldBe "The keys could not be read, so no key is counted here."
        val created = page.recent.shouldNotBeNull().sections[1]
        created.entries.shouldBeNull()
        created.unavailable shouldBe "The keys could not be read."
    }

    @Test
    fun `an empty workspace shows zeros, zero-width bars and the two empty lines`() {
        authenticate(WorkspaceRole.WORKSPACE_ADMIN)
        every { reads.lensed(any()) } returns false
        every { reads.endpoints(any()) } returns EndpointInventory(emptyList(), 0, emptyMap(), emptyMap())
        every { reads.keys(any()) } returns emptyList()

        val page = model()

        val panel = page.inventory.shouldNotBeNull()
        panel.counts.map { it.value } shouldContainExactly listOf(0L, 0L, 0L, 0L)
        panel.charts
            .flatMap { it.bars }
            .map { it.width }
            .toSet() shouldBe setOf("0")
        panel.charts.flatMap { it.bars }.size shouldBe EndpointState.entries.size + ApiKeyKind.entries.size
        page.recent
            .shouldNotBeNull()
            .sections
            .map { it.entries } shouldContainExactly listOf(emptyList(), emptyList())
        page.recent.sections.map { it.emptyNote } shouldContainExactly
            listOf(
                "Nothing is published yet. The three steps above say how.",
                "No keys yet. A key is created by a person on the Keys page.",
            )
    }

    private fun answer(lensed: Boolean = false) {
        val released = endpoint("/nyc/v1/oldest", AT.minusSeconds(7200))
        // A second path on the same released pipeline: the release bar is twice the others.
        val again = endpoint("/nyc/v1/again", AT.minusSeconds(9000), released.pipelineId)
        val orphan = endpoint("/nyc/v1/middle", AT.minusSeconds(3600))
        val drafted = endpoint("/nyc/v1/newest", AT)
        every { reads.lensed(any()) } returns lensed
        every { reads.endpoints(any()) } returns
            EndpointInventory(
                valid = listOf(released, orphan, drafted, again),
                legacyCount = 1,
                served =
                    mapOf(
                        released.pipelineId to ServedVersion(3, draft = false),
                        drafted.pipelineId to ServedVersion(2, draft = true),
                    ),
                names =
                    mapOf(
                        released.pipelineId to PipelineName("nyc/mobility", "Mobility"),
                        orphan.pipelineId to PipelineName("nyc/orphan", null),
                        drafted.pipelineId to PipelineName("nyc/revenue", "Revenue"),
                    ),
            )
        every { reads.keys(any()) } returns KEYS
    }

    private fun model(): OverviewPage {
        val model = ExtendedModelMap()
        controller.overview(model, MockHttpServletRequest())
        return model[OverviewController.MODEL] as OverviewPage
    }

    private fun endpoint(
        path: String,
        createdAt: Instant,
        pipelineId: UUID = UUID.randomUUID(),
    ): PublishedEndpoint =
        PublishedEndpoint.of(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            pathPattern = path,
            pipelineId = pipelineId,
            timeoutSeconds = 30,
            description = "",
            isEnabled = true,
            createdBy = UUID.randomUUID(),
            createdAt = createdAt,
            updatedAt = createdAt,
        )

    private fun authenticate(role: WorkspaceRole) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    UUID.randomUUID(),
                    "a@b.c",
                    "A",
                    AuthMethod.OIDC,
                    workspace = WorkspaceContext(workspaceId, "acme", role),
                ),
                null,
            )
    }

    private companion object {
        val AT: Instant = Instant.now().minusSeconds(60)

        val KEYS: List<ApiKey> =
            listOf(
                key("nightly", ApiKeyKind.MCP, AT, revoked = false, expiresAt = null),
                key("old-ci", ApiKeyKind.ENDPOINT, AT.minusSeconds(3600), revoked = false, expiresAt = AT.minusSeconds(1)),
                key("retired", ApiKeyKind.SERVER, AT.minusSeconds(7200), revoked = true, expiresAt = null),
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
