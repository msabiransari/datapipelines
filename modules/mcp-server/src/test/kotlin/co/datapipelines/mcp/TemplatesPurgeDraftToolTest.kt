package co.datapipelines.mcp

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplatePin
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateUsageService
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.templates.TemplateVersionSummary
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant

/**
 * `templates_purge_draft` (107) — the D61/D62 guard ladder: sole-DRAFT, author-owned, unpinned
 * anywhere, ever. The repository verbs themselves are pinned in the templates module; this suite
 * owns the tool's guards and refusal shapes.
 */
class TemplatesPurgeDraftToolTest {
    private val templates = mockk<TemplateRepository>()
    private val pipelines = mockk<PipelineRepository>()
    private val parameterPins = mockk<co.datapipelines.parameters.ParameterSetTemplatePins>()
    private val visualizationPins = mockk<co.datapipelines.visualization.ArtifactDependents>()
    private val usage =
        co.datapipelines.application.templates.TemplateUsage(
            TemplateUsageService(templates, pipelines),
            parameterPins,
            pipelines,
            visualizationPins,
        )
    private val tool = TemplatesPurgeDraftTool(templates, usage, AuthoringGuard(true), McpFixtures.EVERYTHING_LENS)
    private val ctx = McpFixtures.ctx()

    /** The promoter's lens over parameter sets (178) — admits only the names given. */
    private fun narrowedSetLens(vararg admitted: String) =
        PromoterLens {
            co.datapipelines.application.lens.LensedView(
                ReadLens.Everything,
                ReadLens.Everything,
                parameterSets = ReadLens.Only(admitted.toSet()),
            )
        }

    /** The visualizations' lens (`visualization.read`'s promoter cell) — admits only the names given. */
    private fun narrowedVisualizationLens(vararg admitted: String) =
        PromoterLens {
            co.datapipelines.application.lens.LensedView(
                ReadLens.Everything,
                ReadLens.Everything,
                visualizations = ReadLens.Only(admitted.toSet()),
            )
        }

    private fun visualizationPin(name: String = "acme/charts/revenue") =
        co.datapipelines.visualization.ArtifactPin(java.util.UUID.randomUUID(), name, 2, PipelineVersionStatus.DRAFT, 1)

    /** The visualization arm's ONE scan: every stored version of any visualization pinning ANY version (R12). */
    private fun stubVisualizationPins(vararg pins: co.datapipelines.visualization.ArtifactPin) {
        every {
            visualizationPins.visualizationsPinningTemplate(McpFixtures.WORKSPACE_ID, id, null, co.datapipelines.visualization.PinScope.ANY)
        } returns pins.toList()
    }

    init {
        every { parameterPins.anyVersionPins(any(), any()) } returns emptyList()
        every { visualizationPins.visualizationsPinningTemplate(any(), any(), any(), any()) } returns emptyList()
    }

    private val id = "test/scratch.sql"

    private fun draftSummary(
        createdBy: java.util.UUID = McpFixtures.USER,
        status: PipelineVersionStatus = PipelineVersionStatus.DRAFT,
        version: Int = 1,
    ) = TemplateVersionSummary(id, version, Instant.parse("2026-09-01T00:00:00Z"), createdBy, status)

    private fun draftDetail() =
        TemplateVersionDetail(
            templateId = id,
            version = 1,
            status = PipelineVersionStatus.DRAFT,
            bodyHash = "hash-1",
            createdAt = Instant.parse("2026-09-01T00:00:00Z"),
            createdBy = McpFixtures.USER,
        )

    private fun stubSoleDraft() {
        every { templates.existsId(McpFixtures.WORKSPACE_ID, id) } returns true
        every { templates.listVersions(McpFixtures.WORKSPACE_ID, id) } returns listOf(draftSummary())
        every { pipelines.findAnyVersionTemplatePins(McpFixtures.WORKSPACE_ID, id) } returns emptyList()
        every { templates.findDraftDetail(McpFixtures.WORKSPACE_ID, id) } returns draftDetail()
        every { templates.purgeDraft(McpFixtures.WORKSPACE_ID, id, "hash-1", true) } returns true
    }

    @Test
    fun `a sole-draft unpinned own template is purged and acknowledged`() {
        stubSoleDraft()

        val payload = tool.call(McpArguments(mapOf("id" to id)), ctx) as Map<*, *>

        assertAll(
            { payload["id"] shouldBe id },
            { payload["purged"] shouldBe true },
        )
        verify(exactly = 1) { templates.purgeDraft(McpFixtures.WORKSPACE_ID, id, "hash-1", true) }
    }

    @Test
    fun `an unknown template is not-found`() {
        every { templates.existsId(McpFixtures.WORKSPACE_ID, id) } returns false

        val thrown = shouldThrow<DatapipelinesException> { tool.call(McpArguments(mapOf("id" to id)), ctx) }

        thrown.code shouldBe PipelineErrorCodes.Template.NOT_FOUND
        verify(exactly = 0) { templates.purgeDraft(any(), any(), any(), any()) }
    }

    @Test
    fun `a template with a RELEASED version is refused - humans discard releases`() {
        every { templates.existsId(McpFixtures.WORKSPACE_ID, id) } returns true
        every { templates.listVersions(McpFixtures.WORKSPACE_ID, id) } returns
            listOf(draftSummary(status = PipelineVersionStatus.DRAFT, version = 2), draftSummary(status = PipelineVersionStatus.RELEASED))

        val thrown = shouldThrow<DatapipelinesException> { tool.call(McpArguments(mapOf("id" to id)), ctx) }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Template.VERSION_LAST_RELEASE },
            { thrown.details["version_count"] shouldBe 2 },
        )
        verify(exactly = 0) { templates.purgeDraft(any(), any(), any(), any()) }
    }

    @Test
    fun `another user's draft is refused - a key purges only its own user's drafts`() {
        every { templates.existsId(McpFixtures.WORKSPACE_ID, id) } returns true
        every { templates.listVersions(McpFixtures.WORKSPACE_ID, id) } returns listOf(draftSummary(createdBy = McpFixtures.OTHER_USER))

        val thrown = shouldThrow<DatapipelinesException> { tool.call(McpArguments(mapOf("id" to id)), ctx) }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Auth.ROLE_REQUIRED },
            { thrown.details["reason"] shouldBe "not_creator" },
        )
        verify(exactly = 0) { templates.purgeDraft(any(), any(), any(), any()) }
    }

    @Test
    fun `a draft pinned anywhere ever is refused with the pinning pipelines named`() {
        every { templates.existsId(McpFixtures.WORKSPACE_ID, id) } returns true
        every { templates.listVersions(McpFixtures.WORKSPACE_ID, id) } returns listOf(draftSummary())
        every { pipelines.findAnyVersionTemplatePins(McpFixtures.WORKSPACE_ID, id) } returns
            listOf(
                TemplatePin(McpFixtures.PIPELINE_ID, "nyc/mobility/daily", 3, PipelineVersionStatus.RELEASED, "fetch", 1),
                TemplatePin(McpFixtures.PIPELINE_ID, "nyc/mobility/daily", 4, PipelineVersionStatus.DRAFT, "fetch", 1),
            )

        val thrown = shouldThrow<DatapipelinesException> { tool.call(McpArguments(mapOf("id" to id)), ctx) }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Template.IN_USE },
            { thrown.details["pinned_by"] shouldBe listOf("nyc/mobility/daily") },
        )
        verify(exactly = 0) { templates.purgeDraft(any(), any(), any(), any()) }
    }

    @Test
    fun `a set pin the caller's lens hides still refuses the purge - the guard scans the whole workspace (#300)`() {
        val narrowedTool =
            TemplatesPurgeDraftTool(
                templates,
                usage,
                AuthoringGuard(true),
                narrowedSetLens("acme/other/nowhere"),
            )
        every { templates.existsId(McpFixtures.WORKSPACE_ID, id) } returns true
        every { templates.listVersions(McpFixtures.WORKSPACE_ID, id) } returns listOf(draftSummary())
        every { pipelines.findAnyVersionTemplatePins(McpFixtures.WORKSPACE_ID, id) } returns emptyList()
        every { parameterPins.anyVersionPins(McpFixtures.WORKSPACE_ID, id) } returns
            listOf(
                co.datapipelines.parameters.ParameterSetPin(
                    setId = java.util.UUID.randomUUID(),
                    setName = "acme/sales/region_filters",
                    parameter = "state",
                    setVersion = 1,
                    versionStatus = PipelineVersionStatus.RELEASED,
                    pinnedVersion = 1,
                ),
            )

        val thrown = shouldThrow<DatapipelinesException> { narrowedTool.call(McpArguments(mapOf("id" to id)), ctx) }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Template.IN_USE },
            // The hidden set is FLAGGED only - the refusal echoes neither a name nor a count of what the caller cannot see.
            { thrown.details["referencing_parameter_sets"] shouldBe emptyList<String>() },
            { thrown.details["pins_hidden"] shouldBe true },
            { thrown.message shouldContain "parameter set version(s) outside your view" },
            { thrown.message shouldNotContain "1 parameter set" },
            { thrown.message shouldNotContain "acme/sales/region_filters" },
        )
        verify(exactly = 0) { templates.purgeDraft(any(), any(), any(), any()) }
    }

    @Test
    fun `a set pin the caller's lens ADMITS is refused and named as the web guard names it`() {
        every { templates.existsId(McpFixtures.WORKSPACE_ID, id) } returns true
        every { templates.listVersions(McpFixtures.WORKSPACE_ID, id) } returns listOf(draftSummary())
        every { pipelines.findAnyVersionTemplatePins(McpFixtures.WORKSPACE_ID, id) } returns emptyList()
        every { parameterPins.anyVersionPins(McpFixtures.WORKSPACE_ID, id) } returns
            listOf(
                co.datapipelines.parameters.ParameterSetPin(
                    setId = java.util.UUID.randomUUID(),
                    setName = "acme/sales/region_filters",
                    parameter = "state",
                    setVersion = 1,
                    versionStatus = PipelineVersionStatus.RELEASED,
                    pinnedVersion = 1,
                ),
            )

        val thrown = shouldThrow<DatapipelinesException> { tool.call(McpArguments(mapOf("id" to id)), ctx) }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Template.IN_USE },
            { thrown.details["referencing_parameter_sets"] shouldBe listOf("acme/sales/region_filters") },
            { thrown.details.containsKey("pins_hidden") shouldBe false },
        )
        verify(exactly = 0) { templates.purgeDraft(any(), any(), any(), any()) }
    }

    // ---- #320: the third arm

    @Test
    fun `a visualization that pins the draft refuses the purge - named as the web guard names it (320)`() {
        stubSoleDraft()
        stubVisualizationPins(visualizationPin())

        val thrown = shouldThrow<DatapipelinesException> { tool.call(McpArguments(mapOf("id" to id)), ctx) }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Template.IN_USE },
            { thrown.details["referencing_visualizations"] shouldBe listOf("acme/charts/revenue") },
            { thrown.details.containsKey("pins_hidden") shouldBe false },
            {
                thrown.message shouldBe
                    "Version of template '$id' is pinned by 1 visualization version(s): acme/charts/revenue; discard or repoint them first."
            },
        )
        verify(exactly = 0) { templates.purgeDraft(any(), any(), any(), any()) }
    }

    @Test
    fun `a visualization pin the caller's lens hides still refuses the purge - flagged, never named or counted (320)`() {
        val narrowedTool = TemplatesPurgeDraftTool(templates, usage, AuthoringGuard(true), narrowedVisualizationLens("acme/other/nowhere"))
        stubSoleDraft()
        stubVisualizationPins(visualizationPin("acme/charts/secret"), visualizationPin("acme/charts/secret"))

        val thrown = shouldThrow<DatapipelinesException> { narrowedTool.call(McpArguments(mapOf("id" to id)), ctx) }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Template.IN_USE },
            { thrown.details["referencing_visualizations"] shouldBe emptyList<String>() },
            { thrown.details["pins_hidden"] shouldBe true },
            { thrown.message shouldContain "visualization version(s) outside your view" },
            { thrown.message shouldNotContain "acme/charts/secret" },
            { thrown.message shouldNotContain "2 visualization" },
        )
        verify(exactly = 0) { templates.purgeDraft(any(), any(), any(), any()) }
    }

    @Test
    fun `a lens that admits only some visualizations names those and flags the rest`() {
        val narrowedTool = TemplatesPurgeDraftTool(templates, usage, AuthoringGuard(true), narrowedVisualizationLens("acme/charts/shown"))
        stubSoleDraft()
        stubVisualizationPins(visualizationPin("acme/charts/shown"), visualizationPin("acme/charts/secret"))

        val thrown = shouldThrow<DatapipelinesException> { narrowedTool.call(McpArguments(mapOf("id" to id)), ctx) }

        assertAll(
            { thrown.details["referencing_visualizations"] shouldBe listOf("acme/charts/shown") },
            { thrown.details["pins_hidden"] shouldBe true },
            { thrown.message shouldContain "acme/charts/shown and others outside your view" },
            { thrown.message shouldNotContain "acme/charts/secret" },
        )
    }
}
