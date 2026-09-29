package co.datapipelines.visualization

import co.datapipelines.pipeline.DryRenderOutcome
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.TemplateLookup
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.TemplateVersionStatuses
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** [TemplateContractFacts.over] — the production adapter composes the registry's lookup, contract view and status read. */
class TemplateContractFactsTest {
    private val ref = ArtifactRef("finance/transforms/revenue_bars", 2)

    @Test
    fun `each lookup answer maps to its pin - missing, a missing or discarded version, not a transform, a transform`() {
        pin(TemplateLookup.TemplateNotFound, PipelineVersionStatus.RELEASED) shouldBe TemplatePin.NotFound
        pin(TemplateLookup.VersionNotFound, PipelineVersionStatus.RELEASED) shouldBe TemplatePin.VersionNotFound
        pin(TemplateLookup.Found(null, TemplateType.JSONATA), PipelineVersionStatus.DISCARDED) shouldBe TemplatePin.VersionNotFound
        pin(TemplateLookup.Found(null, TemplateType.JSONATA), null) shouldBe TemplatePin.VersionNotFound
        pin(TemplateLookup.Found(null, TemplateType.HTML), PipelineVersionStatus.RELEASED) shouldBe
            TemplatePin.NotTransform(TemplateType.HTML)
        pin(TemplateLookup.Found(null, TemplateType.JSONATA), PipelineVersionStatus.DRAFT) shouldBe
            TemplatePin.Transform(PipelineVersionStatus.DRAFT, ValidatorFakes.CONTRACT)
    }

    private fun pin(
        lookup: TemplateLookup,
        status: PipelineVersionStatus?,
    ): TemplatePin {
        val renderer =
            object : TemplateDryRenderer {
                override fun lookup(
                    workspaceId: UUID,
                    ref: TemplateRef,
                ): TemplateLookup = lookup

                override fun dryRender(
                    workspaceId: UUID,
                    ref: TemplateRef,
                    context: Map<String, Any?>,
                ): DryRenderOutcome = error("not asked")

                override fun interpolatedParameters(
                    workspaceId: UUID,
                    ref: TemplateRef,
                    declared: Set<String>,
                    guarded: Set<String>,
                ): List<String> = error("not asked")

                override fun boundParameters(
                    workspaceId: UUID,
                    ref: TemplateRef,
                ): List<String> = error("not asked")

                override fun transformContract(
                    workspaceId: UUID,
                    ref: TemplateRef,
                ) = ValidatorFakes.CONTRACT.takeIf { (lookup as? TemplateLookup.Found)?.type?.isTransform == true }
            }
        val statuses = TemplateVersionStatuses { _, name, version -> status.takeIf { name == ref.name && version == ref.version } }
        return TemplateContractFacts.over(renderer, statuses).pinOf(ValidatorFakes.WORKSPACE, ref)
    }
}
