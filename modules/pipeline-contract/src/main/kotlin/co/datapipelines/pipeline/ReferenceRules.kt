package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes
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
                val facts = datasources.describe(source.name)
                if (facts == null) {
                    into.add(
                        Validation.UNKNOWN_DATASOURCE,
                        "nodes[$index].source",
                        "Node '${node.id.truncateForError()}' reads from '${source.name.truncateForError()}', " +
                            "which is not a datasource registered in this environment.",
                        mapOf("node" to node.id.truncateForError(), "datasource" to source.name.truncateForError()),
                    )
                    return null
                }
                // Workspaces §6 shape 1/2: a DML or DDL node SOURCING a readonly datasource is
                // a write-shaped use. DQL reads are untouched — the check is on the node type,
                // never on the datasource alone.
                if (facts.readonly && (node.type == NodeType.DML || node.type == NodeType.DDL)) {
                    into.add(
                        Validation.DATASOURCE_READONLY,
                        "nodes[$index].source",
                        "Node '${node.id.truncateForError()}' is a ${node.type.wire} node sourcing " +
                            "'${source.name.truncateForError()}', which is a readonly datasource — " +
                            "write-shaped uses of it are forbidden.",
                        mapOf(
                            "node" to node.id.truncateForError(),
                            "datasource" to source.name.truncateForError(),
                            // Lowercase wire value, like every other machine-readable detail.
                            "shape" to "${node.type.wire.lowercase()}_source",
                        ),
                    )
                }
                facts.dialect
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
        val facts =
            datasources.describe(output.datasource) ?: run {
                into.add(
                    Validation.UNKNOWN_DATASOURCE,
                    "nodes[$index].output.datasource",
                    "Node '${node.id.truncateForError()}' writes back to '${output.datasource.truncateForError()}', " +
                        "which is not a datasource registered in this environment.",
                    mapOf("node" to node.id.truncateForError(), "datasource" to output.datasource.truncateForError()),
                )
                return
            }
        // Workspaces §6 shape 3: an output.target: "datasource" block names a write target for
        // ANY node type (DQL write-back and PIPELINE alike) — readonly refuses it regardless.
        if (facts.readonly) {
            into.add(
                Validation.DATASOURCE_READONLY,
                "nodes[$index].output.datasource",
                "Node '${node.id.truncateForError()}' writes back to '${output.datasource.truncateForError()}', " +
                    "which is a readonly datasource — write-shaped uses of it are forbidden.",
                mapOf(
                    "node" to node.id.truncateForError(),
                    "datasource" to output.datasource.truncateForError(),
                    "shape" to "output_target",
                ),
            )
        }
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
                checkInterpolatedParameters(path, node, templates, workspaceId, sampleContext, into)
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

    /**
     * 042 B2 — the bind-instead-of-interpolate rule. A declared parameter interpolated inside
     * `${}` puts a caller-supplied value into the SQL string; the bind form `:name` is the only
     * value path the round admits. The message names both forms so an author fixes it without
     * reading a spec (042 B3).
     */
    private fun checkInterpolatedParameters(
        path: String,
        node: Node,
        templates: TemplateDryRenderer,
        workspaceId: java.util.UUID,
        sampleContext: Map<String, Any?>,
        into: FailureCollector,
    ) {
        templates.interpolatedParameters(workspaceId, node.template, sampleContext.keys).forEach { name ->
            into.add(
                PipelineErrorCodes.Template.PARAMETER_INTERPOLATED,
                path,
                "Template '${node.template.key.truncateForError()}' interpolates declared parameter " +
                    "'${name.truncateForError()}' into the SQL as \${$name} — reference it as :$name " +
                    "instead. Declared parameters bind as SQL parameters; \${} interpolation is for " +
                    "structure only.",
                mapOf(
                    "template" to node.template.key.truncateForError(),
                    "parameter" to name.truncateForError(),
                    "bind_form" to ":$name",
                ),
            )
        }
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
