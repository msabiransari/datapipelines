package co.datapipelines.pipeline

import co.datapipelines.typesystem.Dialect
import java.util.UUID

/**
 * The pipeline validator's view of the template registry and the Freemarker engine
 * (pipeline-contract §12.6).
 *
 * Same inversion as [DatasourceRegistry]: the engine lives in the `templates` module, which
 * depends on *this* one (§4.2), so the contract is declared here and implemented there.
 *
 * ## Why a dry **render**, not a variable list
 *
 * D3 deleted the template's own `params_schema`: the pipeline's `parameters` map is the
 * single declaration point for template variables. Proving the two agree therefore means
 * actually rendering — a Freemarker template's variable references can be conditional,
 * come from a macro in an imported library, or be built by an expression, and no static
 * scan of the body sees all of them. §7.4 is explicit that validation "dry-renders every
 * referenced template against the pipeline's declared parameters (using defaults where
 * present, type-appropriate sample values otherwise)".
 */
interface TemplateDryRenderer {
    /** Resolves a `{id, version}` reference against the registry, within [workspaceId] (design §3: refs are workspace-local). */
    fun lookup(
        workspaceId: UUID,
        ref: TemplateRef,
    ): TemplateLookup

    /**
     * Renders [ref] against [context] and reports whether it succeeded.
     *
     * [context] is the pipeline's parameter map after defaults and sample values are
     * applied — the same shape the executor builds at run time (§7.1 step 4), so a render
     * that passes here is a render that passes then.
     *
     * Implementations must not let a render failure escape as an exception: a broken
     * template is one §12 failure among possibly many, and §17.2 requires them collected.
     */
    fun dryRender(
        workspaceId: UUID,
        ref: TemplateRef,
        context: Map<String, Any?>,
    ): DryRenderOutcome

    /**
     * 042 B2 — which of [declared] does the referenced template body reference inside `${}`
     * interpolations. A declared parameter is a VALUE and must be referenced as `:name`, bound
     * as a SQL parameter; interpolation is for structure only. Every name returned here fails
     * validation with `template.validation.parameter_interpolated`.
     *
     * Empty when the body interpolates none of [declared] — including when the reference
     * resolves to no stored version, which [lookup] reports with its own outcome.
     */
    fun interpolatedParameters(
        workspaceId: UUID,
        ref: TemplateRef,
        declared: Set<String>,
    ): List<String>
}

/** What the registry knows about a `{id, version}` reference. */
sealed interface TemplateLookup {
    /** No template with this id exists — §12.6 `template_not_found`. */
    data object TemplateNotFound : TemplateLookup

    /** The id exists but not at this version — §12.6 `template_version_not_found`. */
    data object VersionNotFound : TemplateLookup

    /**
     * Resolved. [dialect] is the template's declared target dialect, checked against the
     * node's source dialect by §12.6 `template_dialect_mismatch` — non-null exactly when
     * [type] is [TemplateType.SQL], per the `chk_type_dialect` invariant (046,
     * template-hierarchy-design §5.1): an `html` template declares no dialect, and §12.6
     * refuses the reference itself with `template_type_mismatch` before any dialect could
     * be compared.
     */
    data class Found(
        val dialect: Dialect?,
        val type: TemplateType = TemplateType.SQL,
    ) : TemplateLookup
}

/**
 * The result of a save-time dry render.
 *
 * Three outcomes, not two, and the split is the contract: §12.6 gives the undeclared-variable
 * case its own code because it is the one an author fixes by declaring a parameter, and gives
 * everything else `template_render_failed` because those are fixed by editing the template.
 * Reporting a type-mismatched built-in as `template_parameter_undeclared` would send the
 * author to the wrong file.
 */
sealed interface DryRenderOutcome {
    /** The template rendered against the pipeline's declared parameters. */
    data object Success : DryRenderOutcome

    /**
     * The render referenced something the pipeline does not declare — §12.6
     * `template_parameter_undeclared` (D3).
     *
     * [variable] is the undefined name when the engine reports one; [detail] is the
     * engine's own message, which reaches the author's error response and must therefore
     * already be safe to echo.
     */
    data class UndeclaredVariable(
        val variable: String?,
        val detail: String,
    ) : DryRenderOutcome

    /**
     * The render failed for any other reason — §12.6 `template_render_failed` (added
     * 2026-08-08).
     *
     * This variant is why [TemplateDryRenderer.dryRender] must not throw: a broken template is
     * one §12 failure among possibly many, and an exception escaping the engine would abort
     * §17.2's exhaustive collection and surface as a 500 for what is an author error.
     */
    data class RenderFailed(
        val detail: String,
    ) : DryRenderOutcome
}
