package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import co.datapipelines.typesystem.Dialect

/**
 * pipeline-contract §12.5 (datasources) and §12.6 (templates) — the rules that need the
 * environment.
 *
 * These are the only §12 rules whose verdict can differ between dev and prod, which is
 * exactly why §11.3's promotion flow re-runs them on import (`pipeline.import.*`). A
 * pipeline body that passes here is not portable *because* it passed here; it is portable
 * because §11.4 kept env-specific values out of it.
 */
internal object ReferenceRules {
    fun check(
        pipeline: Pipeline,
        datasources: DatasourceRegistry,
        templates: TemplateDryRenderer,
        workspaceId: java.util.UUID,
        into: FailureCollector,
    ) {
        val sampleContext = ParameterBinder(pipeline.parameters).sampleContext()
        pipeline.nodes.forEachIndexed { index, node ->
            if (node.type == NodeType.PIPELINE) {
                // §12.9 (CompositionRules) owns a PIPELINE node's references: it carries no
                // source and no template to resolve. Its `output` block, when §12.9 permits one,
                // is a standard §4.7 block, so a datasource target is still registry-checked here.
                checkOutputDatasource(index, node, datasources, into)
                return@forEachIndexed
            }
            val sourceDialect = checkSource(index, node, datasources, pipeline, into)
            checkOutputDatasource(index, node, datasources, into)
            checkTemplate(index, node, sourceDialect, templates, workspaceId, sampleContext, into)
        }
    }

    /**
     * §12.5 over `nodes[].source`, returning the dialect the node's SQL runs against — which
     * §12.6's dialect check then needs.
     *
     * `tempdb` is not looked up: it is the reserved literal (§4.8), and its dialect is the one
     * belonging to the declared staging engine — H2 in v1, per SPEC-REVIEW 2.1.8, which
     * replaced a hard-coded H2 with exactly this derivation so DuckDB templates become valid
     * the day that engine lands.
     */
    private fun checkSource(
        index: Int,
        node: Node,
        datasources: DatasourceRegistry,
        pipeline: Pipeline,
        into: FailureCollector,
    ): Dialect? =
        when (val source = node.resolvedSource) {
            NodeSource.Tempdb -> {
                pipeline.settings.tempdb.engine.dialect
            }

            is NodeSource.Datasource -> {
                datasources.dialectOf(source.name) ?: run {
                    into.add(
                        Validation.UNKNOWN_DATASOURCE,
                        "nodes[$index].source",
                        "Node '${node.id.truncateForError()}' reads from '${source.name.truncateForError()}', " +
                            "which is not a datasource registered in this environment.",
                        mapOf("node" to node.id.truncateForError(), "datasource" to source.name.truncateForError()),
                    )
                    null
                }
            }
        }

    /** §12.5 over `nodes[].output.datasource` — the write-back half of the same rule. */
    private fun checkOutputDatasource(
        index: Int,
        node: Node,
        datasources: DatasourceRegistry,
        into: FailureCollector,
    ) {
        val output = node.output as? NodeOutput.Datasource ?: return
        if (output.datasource.isBlank()) return
        if (datasources.dialectOf(output.datasource) != null) return
        into.add(
            Validation.UNKNOWN_DATASOURCE,
            "nodes[$index].output.datasource",
            "Node '${node.id.truncateForError()}' writes back to '${output.datasource.truncateForError()}', " +
                "which is not a datasource registered in this environment.",
            mapOf("node" to node.id.truncateForError(), "datasource" to output.datasource.truncateForError()),
        )
    }

    private fun checkTemplate(
        index: Int,
        node: Node,
        sourceDialect: Dialect?,
        templates: TemplateDryRenderer,
        workspaceId: java.util.UUID,
        sampleContext: Map<String, Any?>,
        into: FailureCollector,
    ) {
        val path = "nodes[$index].template"
        when (val lookup = templates.lookup(workspaceId, node.template)) {
            TemplateLookup.TemplateNotFound -> {
                into.add(
                    Validation.TEMPLATE_NOT_FOUND,
                    path,
                    "Template '${node.template.id.truncateForError()}' is not in the template registry.",
                    mapOf("template" to node.template.id.truncateForError()),
                )
            }

            TemplateLookup.VersionNotFound -> {
                into.add(
                    Validation.TEMPLATE_VERSION_NOT_FOUND,
                    path,
                    "Template '${node.template.id.truncateForError()}' has no version ${node.template.version}.",
                    mapOf("template" to node.template.id.truncateForError(), "version" to node.template.version),
                )
            }

            is TemplateLookup.Found -> {
                checkDialect(path, node, lookup.dialect, sourceDialect, into)
                dryRender(path, node, templates, workspaceId, sampleContext, into)
            }
        }
    }

    private fun checkDialect(
        path: String,
        node: Node,
        templateDialect: Dialect,
        sourceDialect: Dialect?,
        into: FailureCollector,
    ) {
        // A null source dialect means the datasource is unknown — already reported by §12.5.
        // Guessing a dialect to compare against would invent a second, misleading failure.
        if (sourceDialect == null || templateDialect == sourceDialect) return
        into.add(
            Validation.TEMPLATE_DIALECT_MISMATCH,
            path,
            "Template '${node.template.key.truncateForError()}' targets ${templateDialect.wire}, " +
                "but node '${node.id.truncateForError()}' runs against a ${sourceDialect.wire} source.",
            mapOf("template_dialect" to templateDialect.wire, "source_dialect" to sourceDialect.wire),
        )
    }

    private fun dryRender(
        path: String,
        node: Node,
        templates: TemplateDryRenderer,
        workspaceId: java.util.UUID,
        sampleContext: Map<String, Any?>,
        into: FailureCollector,
    ) {
        when (val outcome = templates.dryRender(workspaceId, node.template, sampleContext)) {
            DryRenderOutcome.Success -> {
                // The template rendered against the pipeline's declared parameters.
            }

            is DryRenderOutcome.UndeclaredVariable -> {
                into.add(
                    Validation.TEMPLATE_PARAMETER_UNDECLARED,
                    path,
                    "Template '${node.template.key.truncateForError()}' references a variable the pipeline does not " +
                        "declare${outcome.variable?.let { " ('${it.truncateForError()}')" }.orEmpty()}: " +
                        outcome.detail.truncateForError(),
                    mapOf(
                        "template" to node.template.key.truncateForError(),
                        "variable" to outcome.variable?.truncateForError(),
                        "declared_parameters" to sampleContext.keys.toList(),
                    ),
                )
            }

            is DryRenderOutcome.RenderFailed -> {
                into.add(
                    Validation.TEMPLATE_RENDER_FAILED,
                    path,
                    "Template '${node.template.key.truncateForError()}' failed to render at save time: " +
                        outcome.detail.truncateForError(),
                    mapOf("template" to node.template.key.truncateForError()),
                )
            }
        }
    }
}
