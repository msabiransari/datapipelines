package co.datapipelines.visualization

import co.datapipelines.parameters.ParameterSetBody
import co.datapipelines.parameters.ParameterSetJson
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.DocumentFixtures.obj
import co.datapipelines.visualization.VisualizationTestDb.AUTHOR
import co.datapipelines.visualization.VisualizationTestDb.WORKSPACE
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [VisualizationService]'s own rules against the real schema: validation before any write; the release's order —
 * at least one case, the transform pin RELEASED or a consented DRAFT (the cascade), the evidence gate INSIDE the
 * transaction (its default refuses), the flip; the pin guard a live dashboard version holds; the import lens; and the
 * two production adapters that read this database ([VisualizationRepository.pins], [ParameterSetFacts.over]).
 */
class VisualizationServiceIntegrationTest {
    private lateinit var h: LifecycleHarness

    @BeforeEach
    fun reset() {
        VisualizationTestDb.reset()
        h = LifecycleHarness()
    }

    private fun refusal(block: () -> Unit): DatapipelinesException = shouldThrow<DatapipelinesException>(block)

    @Test
    fun `the verbs answer the pointer pair and the purge scope from their own results (#372)`() {
        val v1 = h.createVisualization()
        val released1 = h.visualizations.release(WORKSPACE, v1.record.id, v1.detail.bodyHash, AUTHOR).version
        val v2draft =
            h.visualizations.write(
                WORKSPACE,
                v1.record.id,
                h.visualizationDocument { it.obj("presentation").put("title", "Second") },
                released1.detail.bodyHash,
                AUTHOR,
                WriteSurface.MCP,
            )
        val v2 = h.visualizations.release(WORKSPACE, v1.record.id, v2draft.detail.bodyHash, AUTHOR).version
        v2.detail.version shouldBe 2
        // Discard the CURRENT version: the pair from the result — before = 2, after = 1.
        h.visualizations.discardVersion(WORKSPACE, v1.record.id, 2, AUTHOR).pointer shouldBe PointerMove(before = 2, after = 1)
        // Restore moves the pointer only upward (D60): before = 1, after = 2.
        h.visualizations.restoreVersion(WORKSPACE, v1.record.id, 2).pointer shouldBe PointerMove(before = 1, after = 2)
        // The switch answers from/to, and the name the service already read for its 404.
        h.visualizations.switchCurrent(WORKSPACE, v1.record.id, 1) shouldBe
            Switched(v1.record.name, PointerMove(before = 2, after = 1))
        // A sole draft takes the visualization with it: scope = entity, from the result.
        val sole = h.createVisualization("acme/viz/sole")
        h.visualizations.purgeDraft(WORKSPACE, sole.record.id, sole.detail.bodyHash).scope shouldBe "entity"
        // A draft beside a release purges alone: scope = version.
        val beside =
            h.visualizations.write(
                WORKSPACE,
                v1.record.id,
                h.visualizationDocument { it.obj("presentation").put("title", "Beside") },
                v1.detail.bodyHash,
                AUTHOR,
                WriteSurface.MCP,
            )
        h.visualizations.purgeDraft(WORKSPACE, v1.record.id, beside.detail.bodyHash).scope shouldBe "version"
        // The entity purge is entity by definition — the only version is a DRAFT.
        val onlyDraft = h.createVisualization("acme/viz/only_draft")
        h.visualizations.purgeEntity(WORKSPACE, onlyDraft.record.id) shouldBe Purged.Entity
    }

    private fun rows(): Int =
        checkNotNull(h.jdbc.queryForObject("SELECT COUNT(*) FROM visualizations", emptyMap<String, Any>(), Int::class.java))

    @Test
    fun `an invalid body is refused before anything is written - every failure at once`() {
        val thrown =
            shouldThrow<ArtifactValidationException> {
                h.visualizations.create(
                    WORKSPACE,
                    h.visualizationDocument { it.obj("renderer").put("kind", "html") },
                    AUTHOR,
                    WriteSurface.MCP,
                )
            }
        thrown.code shouldBe VisualizationErrorCodes.RENDERER_UNSUPPORTED
        rows() shouldBe 0
    }

    @Test
    fun `release needs a case, a released or consented transform pin - and cascades the draft pin in the release's transaction`() {
        val noCases =
            h.visualizations.create(
                WORKSPACE,
                h.visualizationDocument("acme/viz/none") { it.remove("tests") },
                AUTHOR,
                WriteSurface.MCP,
            )
        refusal { h.visualizations.release(WORKSPACE, noCases.record.id, noCases.detail.bodyHash, AUTHOR) }.details["reason"] shouldBe
            "no_cases"

        val created = h.createVisualization()
        h.templateStatuses[ValidatorFakes.TRANSFORM_REF] = PipelineVersionStatus.DRAFT
        val draftPin = refusal { h.visualizations.release(WORKSPACE, created.record.id, created.detail.bodyHash, AUTHOR) }
        draftPin.code shouldBe VisualizationErrorCodes.RELEASE_DEPENDENCY_NOT_RELEASED
        draftPin.details["template_status"] shouldBe "DRAFT"
        (draftPin.details["pins_not_released"] as List<*>).size shouldBe 1
        h.templatesReleased shouldBe emptyList()

        val released =
            h.visualizations.release(
                WORKSPACE,
                created.record.id,
                created.detail.bodyHash,
                AUTHOR,
                releasePinnedTemplates = true,
            )
        released.templatesReleased shouldBe listOf(TemplateRef("finance/transforms/revenue_bars", 2))
        released.version.detail.status shouldBe PipelineVersionStatus.RELEASED
        h.templatesReleased shouldBe listOf(TemplateRef("finance/transforms/revenue_bars", 2))

        val missing = h.visualizations.create(WORKSPACE, h.visualizationDocument("acme/viz/missing"), AUTHOR, WriteSurface.MCP)
        h.templateStatuses.clear()
        refusal { h.visualizations.release(WORKSPACE, missing.record.id, missing.detail.bodyHash, AUTHOR, releasePinnedTemplates = true) }
            .details["template_status"] shouldBe "MISSING"
    }

    @Test
    fun `the default evidence gate refuses every release - tests_missing, gate_not_installed, nothing cascaded or flipped`() {
        val gated = LifecycleHarness(evidence = ReleaseEvidence.NOT_INSTALLED)
        val created = gated.createVisualization()
        gated.templateStatuses[ValidatorFakes.TRANSFORM_REF] = PipelineVersionStatus.DRAFT
        val refused =
            refusal {
                gated.visualizations.release(
                    WORKSPACE,
                    created.record.id,
                    created.detail.bodyHash,
                    AUTHOR,
                    releasePinnedTemplates = true,
                )
            }
        refused.code shouldBe VisualizationErrorCodes.RELEASE_TESTS_MISSING
        refused.details["reason"] shouldBe "gate_not_installed"
        gated.templatesReleased shouldBe emptyList()
        checkNotNull(gated.visualizationRepository.findDraft(WORKSPACE, created.record.id)).status shouldBe PipelineVersionStatus.DRAFT
    }

    @Test
    fun `an installed gate's refusal is its own code - tests_red stays a refusal, a stale hash stays a conflict`() {
        val red =
            LifecycleHarness(
                evidence = { _, candidate ->
                    EvidenceVerdict.Refused(
                        VisualizationErrorCodes.RELEASE_TESTS_RED,
                        "red",
                        mapOf("version" to candidate.version),
                    )
                },
            )
        val created = red.createVisualization()
        refusal { red.visualizations.release(WORKSPACE, created.record.id, created.detail.bodyHash, AUTHOR) }.code shouldBe
            VisualizationErrorCodes.RELEASE_TESTS_RED
        refusal { h.visualizations.release(WORKSPACE, h.createVisualization("acme/viz/stale").record.id, "stale", AUTHOR) }.code shouldBe
            VisualizationErrorCodes.VERSION_CONFLICT
    }

    @Test
    fun `a version a live dashboard pins is never discarded or purged - version_pinned names the dashboards, until they let go`() {
        val v1 = h.createVisualization()
        h.visualizations.release(WORKSPACE, v1.record.id, v1.detail.bodyHash, AUTHOR)
        val dashboard = h.dashboards.create(WORKSPACE, h.dashboardDocument(1), AUTHOR, WriteSurface.MCP)
        val pinned = refusal { h.visualizations.discardVersion(WORKSPACE, v1.record.id, 1, AUTHOR) }
        pinned.code shouldBe VisualizationErrorCodes.VERSION_PINNED
        pinned.details["pinned_by"] shouldBe listOf("${DocumentFixtures.DASHBOARD_NAME}@1")
        refusal { h.visualizations.purgeEntity(WORKSPACE, v1.record.id) }.code shouldBe VisualizationErrorCodes.VERSION_PINNED
        val v2 =
            h.visualizations.write(
                WORKSPACE,
                v1.record.id,
                h.visualizationDocument {
                    it.put("display_name", "v2")
                },
                v1.detail.bodyHash,
                AUTHOR,
                WriteSurface.MCP,
            )
        h.visualizations.purgeDraft(WORKSPACE, v1.record.id, v2.detail.bodyHash)
        h.dashboards.purgeEntity(WORKSPACE, dashboard.record.id)
        h.visualizations
            .discardVersion(WORKSPACE, v1.record.id, 1, AUTHOR)
            .detail.status shouldBe PipelineVersionStatus.DISCARDED
    }

    @Test
    fun `import validates against THIS deployment - a transform pin it lacks is missing_template and nothing lands`() {
        val source = h.createVisualization()
        val export = ArtifactExport(source.record.id, "acme/imported/viz", null, null, null, source.body)
        h.fakes.templates.clear()
        val refused =
            shouldThrow<ArtifactValidationException> { h.visualizations.import(VisualizationTestDb.OTHER_WORKSPACE, export, AUTHOR) }
        refused.code shouldBe VisualizationErrorCodes.IMPORT_MISSING_TEMPLATE
        h.visualizationRepository.findRecord(VisualizationTestDb.OTHER_WORKSPACE, source.record.id) shouldBe null
    }

    @Test
    fun `the production pins adapter reads a visualization by name and version, any status - null when absent`() {
        val created = h.createVisualization()
        val pin = checkNotNull(h.visualizationRepository.pins.pinOf(WORKSPACE, ArtifactRef(DocumentFixtures.VISUALIZATION_NAME, 1)))
        pin.status shouldBe PipelineVersionStatus.DRAFT
        pin.body shouldBe created.body
        h.visualizationRepository.pins.pinOf(WORKSPACE, ArtifactRef(DocumentFixtures.VISUALIZATION_NAME, 2)) shouldBe null
        h.visualizationRepository.pins.pinOf(
            VisualizationTestDb.OTHER_WORKSPACE,
            ArtifactRef(DocumentFixtures.VISUALIZATION_NAME, 1),
        ) shouldBe
            null
    }

    @Test
    fun `the production parameter-set adapter answers a pinned version's parameters and their direct dependents`() {
        val sets = ParameterSetRepository(h.jdbc)
        val body =
            ParameterSetJson.mapper.readValue(
                """{"display_name":"Period","parameters":[
                   {"name":"year","label":"Year","type":"INTEGER","kind":"INPUT"},
                   {"name":"month","label":"Month","type":"INTEGER","kind":"INPUT","depends_on":["year"]}]}""",
                ParameterSetBody::class.java,
            )
        sets.create(WORKSPACE, UUID.randomUUID(), "finance/parameters/period", body, AUTHOR, CreateLifecycle.RELEASED, WriteSurface.SESSION)
        val facts = ParameterSetFacts.over(sets)
        val fact = checkNotNull(facts.setOf(WORKSPACE, ArtifactRef("finance/parameters/period", 1)))
        fact.status shouldBe PipelineVersionStatus.RELEASED
        fact.parameters shouldBe listOf(SetParameterFact("year", setOf("month")), SetParameterFact("month", emptySet()))
        fact.parameters.first().isParent shouldBe true
        facts.setOf(WORKSPACE, ArtifactRef("finance/parameters/period", 2)) shouldBe null
        facts.setOf(VisualizationTestDb.OTHER_WORKSPACE, ArtifactRef("finance/parameters/period", 1)) shouldBe null
    }
}
