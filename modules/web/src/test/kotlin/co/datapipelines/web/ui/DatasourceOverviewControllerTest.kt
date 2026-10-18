package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.datasources.DatasourceDialectCounts
import co.datapipelines.datasources.DatasourceRecentGrant
import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
 * #583 — the Datasources overview controller maps the repository's two reads onto the shared
 * frame: the visible set becomes the total tile and the health/by-dialect charts, the newest
 * grants become Continue your work — and a failed read says so, never a zero (the FamilyInventory
 * rule the five lifecycle families' pages answer to).
 */
class DatasourceOverviewControllerTest {
    private val workspaceId = UUID.randomUUID()
    private val repository = mockk<DatasourceRepository>()
    private val controller = DatasourceOverviewController(repository, mockk { every { resolve(any()) } returns "light" })

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `the visible set becomes the tile and the two charts, the grants the recent section`() {
        authenticate()
        every { repository.visibleCounts(workspaceId) } returns
            listOf(
                DatasourceDialectCounts(Dialect.POSTGRES, total = 3, testedOk = 2, testedFailed = 0, neverTested = 1),
                DatasourceDialectCounts(Dialect.H2, total = 1, testedOk = 0, testedFailed = 1, neverTested = 0),
            )
        every { repository.recentGrants(workspaceId) } returns listOf(DatasourceRecentGrant("dw-sales", AT))

        val model = ExtendedModelMap()
        controller.overview(model, MockHttpServletRequest()) shouldBe OverviewController.VIEW
        model["activeTheme"] shouldBe "light"

        val page = model[OverviewController.MODEL] as OverviewPage
        page.family shouldBe "datasources"
        page.catalogHref shouldBe "/datasources"
        page.lensed shouldBe false
        page.guided shouldBe true
        val panel = page.inventory.shouldNotBeNull()
        panel.counts.single().let { count ->
            count.key shouldBe "datasources"
            count.value shouldBe 4L
            count.href.shouldBeNull()
        }
        val (health, dialect) = panel.charts
        health.key shouldBe "health"
        health.bars.map { it.key to it.value } shouldBe
            listOf("ok" to 2L, "failed" to 1L, "never" to 1L)
        health.bars.map { it.href } shouldBe listOf(null, null, null)
        dialect.bars.map { it.key to it.value } shouldBe listOf("POSTGRES" to 3L, "H2" to 1L)
        dialect.bars.map { it.href } shouldBe listOf("/datasources?dialect=POSTGRES", "/datasources?dialect=H2")
        dialect.headings shouldBe listOf("Dialect", "Datasources")

        val recent = page.recent.shouldNotBeNull()
        val section = recent.sections.single()
        val entries = section.entries.shouldNotBeNull()
        entries.single().let { entry ->
            entry.name shouldBe "dw-sales"
            entry.href shouldBe "/datasources/dw-sales"
            entry.hasDraft shouldBe false
            entry.datetime shouldBe AT.toString()
        }
    }

    @Test
    fun `a failed counts read is unavailable with no inventory and the recent list still answers`() {
        authenticate()
        every { repository.visibleCounts(workspaceId) } throws IllegalStateException("database down")
        every { repository.recentGrants(workspaceId) } returns emptyList()

        val page = model()

        page.inventory.shouldBeNull()
        page.unavailable shouldBe "Inventory could not be read."
        val recent = page.recent.shouldNotBeNull()
        recent.sections.single().entries shouldBe emptyList()
    }

    @Test
    fun `a failed grants read leaves the inventory up and the section says why`() {
        authenticate()
        every { repository.visibleCounts(workspaceId) } returns
            listOf(DatasourceDialectCounts(Dialect.POSTGRES, total = 2, testedOk = 2, testedFailed = 0, neverTested = 0))
        every { repository.recentGrants(workspaceId) } throws IllegalStateException("database down")

        val page = model()

        page.inventory.shouldNotBeNull()
        val recent = page.recent.shouldNotBeNull()
        val section = recent.sections.single()
        section.entries.shouldBeNull()
        section.unavailable shouldBe "Recently added could not be read."
    }

    private fun model(): OverviewPage {
        val model = ExtendedModelMap()
        controller.overview(model, MockHttpServletRequest())
        return model[OverviewController.MODEL] as OverviewPage
    }

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    UUID.randomUUID(),
                    "a@b.c",
                    "A",
                    AuthMethod.OIDC,
                    workspace = WorkspaceContext(workspaceId, "acme", WorkspaceRole.WORKSPACE_ADMIN),
                ),
                null,
            )
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-10-18T05:00:00Z")
    }
}
