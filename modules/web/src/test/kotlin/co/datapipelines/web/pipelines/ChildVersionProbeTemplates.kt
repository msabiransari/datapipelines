package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.DryRenderOutcome
import co.datapipelines.pipeline.ScriptContractView
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.TemplateLookup
import co.datapipelines.pipeline.TemplateRef
import java.util.UUID

/** A parent of one PIPELINE node renders nothing; a touch here is a defect, not a case. */
internal object ChildVersionProbeTemplates : TemplateDryRenderer {
    override fun lookup(
        workspaceId: UUID,
        ref: TemplateRef,
    ): TemplateLookup = error("the parent save must not look up a template (${ref.id})")

    override fun dryRender(
        workspaceId: UUID,
        ref: TemplateRef,
        context: Map<String, Any?>,
    ): DryRenderOutcome = error("the parent save must not render (${ref.id})")

    override fun interpolatedParameters(
        workspaceId: UUID,
        ref: TemplateRef,
        declared: Set<String>,
        guarded: Set<String>,
    ): List<String> = error("the parent save must not scan template bodies (${ref.id})")

    override fun boundParameters(
        workspaceId: UUID,
        ref: TemplateRef,
    ): List<String> = error("the parent save must not scan template bodies (${ref.id})")

    override fun scriptContract(
        workspaceId: UUID,
        ref: TemplateRef,
    ): ScriptContractView? = error("the parent save must not read a script contract (${ref.id})")
}

