package co.datapipelines.templates

import co.datapipelines.pipeline.DryRenderOutcome
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.TemplateLookup
import co.datapipelines.pipeline.TemplateRef

/**
 * The `templates`-side implementation of pipeline-contract's [TemplateDryRenderer] (§12.6).
 *
 * The contract is declared in `pipeline-contract` (which this module depends on) and
 * implemented here, where the engine and registry live — the same inversion the interface's
 * own KDoc describes. It is what lets a pipeline's save-time validation dry-render every
 * template its nodes reference against the pipeline's declared parameters (templates.md §7.2).
 *
 * ## Never throws
 *
 * A broken template is one §12 failure among possibly many; pipeline-contract §17.2 requires
 * them collected, not thrown. [dryRender] therefore returns a [DryRenderOutcome] for every
 * outcome, including engine failures — it delegates to [TemplateEngine.execute], which
 * classifies rather than raises.
 */
class TemplateDryRendererImpl(
    private val engines: WorkspaceTemplateEngines,
) : TemplateDryRenderer {
    /**
     * Splits the registry's null into the two §12.6 outcomes: a missing id is
     * `template_not_found`, an existing id at a missing version is `template_version_not_found`.
     */
    override fun lookup(
        workspaceId: java.util.UUID,
        ref: TemplateRef,
    ): TemplateLookup {
        val registry = engines.registryFor(workspaceId)
        val version = registry.lookup(ref.id, ref.version)
        return when {
            version != null -> TemplateLookup.Found(version.dialect, version.type)
            registry.existsId(ref.id) -> TemplateLookup.VersionNotFound
            else -> TemplateLookup.TemplateNotFound
        }
    }

    override fun dryRender(
        workspaceId: java.util.UUID,
        ref: TemplateRef,
        context: Map<String, Any?>,
    ): DryRenderOutcome =
        when (val outcome = engines.engineFor(workspaceId).execute(ref, context)) {
            is RenderOutcome.Success -> DryRenderOutcome.Success

            is RenderOutcome.UndefinedVariable -> DryRenderOutcome.UndeclaredVariable(outcome.variable, outcome.detail)

            // A missing template is `RenderFailed` here on purpose: at pipeline-save time the
            // not-found / version-not-found split is [lookup]'s verdict (§12.6), and the validator
            // has already reported it before a dry render is attempted.
            is RenderOutcome.NotFound -> DryRenderOutcome.RenderFailed(outcome.detail)

            is RenderOutcome.Failed -> DryRenderOutcome.RenderFailed(outcome.detail)
        }

    override fun interpolatedParameters(
        workspaceId: java.util.UUID,
        ref: TemplateRef,
        declared: Set<String>,
    ): List<String> {
        val version = engines.registryFor(workspaceId).lookup(ref.id, ref.version) ?: return emptyList()
        return InterpolatedParameterScanner.scan(version.body, declared)
    }

    override fun boundParameters(
        workspaceId: java.util.UUID,
        ref: TemplateRef,
    ): List<String> {
        val version = engines.registryFor(workspaceId).lookup(ref.id, ref.version) ?: return emptyList()
        return SqlBindScanner.scan(version.body)
    }
}
