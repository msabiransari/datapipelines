package co.datapipelines.pipeline

/**
 * Runs every pipeline-contract §12 validation — step 2 of the §17.2 pipeline.
 *
 * ```
 * JSON  →  PipelineDeserializer  →  PipelineValidator  →  CallerNodeResolver  →  Pipeline entity
 * ```
 *
 * ## Exhaustive, never fail-fast
 *
 * §17.2: "all checks run, all failures collected, returned together. This gives authors the
 * full picture on a broken pipeline." Rule groups are handed a [FailureCollector] and return
 * `Unit`, so no group can end the run early. The one deliberate exception is inside
 * [DagRules]: an empty `nodes` array stops the DAG group, because every remaining DAG rule
 * would report the same absence again.
 *
 * ## Where the other five §12 codes live
 *
 * `type_invalid`, `output_target_invalid`, `output_mode_invalid`, `parameter_type_invalid`
 * and `tempdb_engine_unsupported` are verdicts on *wire values* — a `type` of `"SELECT"` has
 * no typed representation to validate. [PipelineDeserializer]'s pre-scan raises them, with
 * the same catalog codes, before binding. A pipeline built in memory (a test, a UI editor's
 * model) cannot be in those states at all.
 *
 * One more, `duplicate_name`, is raised by [PipelineRepository]: it is a question only the
 * database can answer atomically, and a read-then-write pre-check here would be a check two
 * concurrent creates both pass.
 *
 * ## Save-time, universally
 *
 * D2 / §2 principle 8: nothing invalid ever reaches the database. This validator is that
 * gate for pipelines, and it is why §12.5, §12.6 and §12.9 take the environment's registries — an
 * unresolvable datasource, a template that cannot render, or a pipeline reference that does not
 * resolve is rejected at save, not discovered at 3am by an execution.
 */
class PipelineValidator(
    private val datasources: DatasourceRegistry,
    private val templates: TemplateDryRenderer,
    private val pipelines: PipelineResolver,
    private val maxCompositionDepth: Int,
) {
    /**
     * Runs §12 against [pipeline] and returns every failure. [workspaceId] is the workspace
     * the pipeline is being saved into: its template and PIPELINE-node references resolve
     * there (design 2026-08-16-workspaces §3 — cross-workspace references do not exist in v1).
     */
    fun validate(
        pipeline: Pipeline,
        workspaceId: java.util.UUID,
    ): ValidationResult {
        val collector = FailureCollector()
        StructuralRules.check(pipeline, datasources, collector)
        DagRules.check(pipeline, collector)
        NodeTypeRules.check(pipeline, collector)
        ReferenceRules.check(pipeline, datasources, templates, workspaceId, collector)
        ParameterRules.check(pipeline, collector)
        SettingsRules.check(pipeline, collector)
        CompositionRules.check(pipeline, pipelines, maxCompositionDepth, workspaceId, collector)
        return collector.toResult()
    }

    /** Runs §12 and throws [PipelineValidationException] if anything failed. */
    fun validateOrThrow(
        pipeline: Pipeline,
        workspaceId: java.util.UUID,
    ): Pipeline {
        validate(pipeline, workspaceId).orThrow()
        return pipeline
    }
}
