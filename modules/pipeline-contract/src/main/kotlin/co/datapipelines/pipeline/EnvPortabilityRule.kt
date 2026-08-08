package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation

/**
 * pipeline-contract §11.4 / §12.1 — `forbidden_env_specific_value`.
 *
 * The scanned fields are exactly the ones §11.4 enumerates: `nodes[].source`,
 * `nodes[].output.datasource`, `nodes[].output.table`, `nodes[].id`, and every key and
 * string value under `settings`. Template bodies are out of scope (the template registry
 * validates its own entities), and parameter *values* are runtime data, not pipeline body.
 *
 * ## The registry exemption
 *
 * §11.4 closes with the rule that decides the false-positive rate: "a `source` of `pg-prod`
 * is fine (it is a datasource *name*; the check applies to values that are not references
 * into the datasource registry)". So a `source` or `output.datasource` that the environment
 * resolves is skipped — an unresolvable one is scanned, and also draws
 * `unknown_datasource` from §12.5.
 */
internal object EnvPortabilityRule {
    fun check(
        pipeline: Pipeline,
        datasources: DatasourceRegistry,
        into: FailureCollector,
    ) {
        pipeline.nodes.forEachIndexed { index, node ->
            scan(node.id, "nodes[$index].id", into)
            if (!isRegistryReference(node.source, datasources)) {
                scan(node.source, "nodes[$index].source", into)
            }
            scanOutput(index, node, datasources, into)
        }
        scanSettings(pipeline.settings, into)
    }

    private fun scanOutput(
        index: Int,
        node: Node,
        datasources: DatasourceRegistry,
        into: FailureCollector,
    ) {
        when (val output = node.output) {
            is NodeOutput.Tempdb -> {
                scan(output.table, "nodes[$index].output.table", into)
            }

            is NodeOutput.Datasource -> {
                if (!isRegistryReference(output.datasource, datasources)) {
                    scan(output.datasource, "nodes[$index].output.datasource", into)
                }
                scan(output.table, "nodes[$index].output.table", into)
            }

            NodeOutput.Caller, null -> {
                // No table or datasource name to scan.
            }
        }
    }

    private fun scanSettings(
        settings: PipelineSettings,
        into: FailureCollector,
    ) {
        scan(settings.tempdb.engine.wire, "settings.tempdb.engine", into)
        settings.tempdb.config.forEach { (key, value) ->
            scan(key, "settings.tempdb.config.$key", into)
            value.asTextOrNull()?.let { scan(it, "settings.tempdb.config.$key", into) }
        }
    }

    /** `tempdb` is the reserved literal, not a datasource; anything the registry knows is a name. */
    private fun isRegistryReference(
        value: String,
        datasources: DatasourceRegistry,
    ): Boolean = value == NodeSource.TEMPDB_LITERAL || datasources.dialectOf(value) != null

    private fun scan(
        value: String,
        path: String,
        into: FailureCollector,
    ) {
        val heuristic = EnvSpecificValueScanner.detect(value) ?: return
        into.add(
            Validation.FORBIDDEN_ENV_SPECIFIC_VALUE,
            path,
            "'${value.truncateForError()}' looks environment-specific (${heuristic.name}); " +
                "a pipeline body must be portable across environments (§11.4).",
            mapOf("value" to value.truncateForError(), "heuristic" to heuristic.name),
        )
    }
}
