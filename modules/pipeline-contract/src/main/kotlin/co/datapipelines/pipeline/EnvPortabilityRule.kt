package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import java.util.UUID

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
 * `unknown_datasource` from §12.5. A managed lake's validated qualified table identifier is
 * a portable registry reference too; an external datasource's table keeps the scan.
 */
internal object EnvPortabilityRule {
    fun check(
        pipeline: Pipeline,
        datasources: DatasourceRegistry,
        workspaceId: UUID,
        into: FailureCollector,
    ) {
        pipeline.nodes.forEachIndexed { index, node ->
            scan(node.id, "nodes[$index].id", into)
            if (!isRegistryReference(node.source, datasources, workspaceId)) {
                scan(node.source, "nodes[$index].source", into)
            }
            scanOutput(index, node, datasources, workspaceId, into)
        }
        scanSettings(pipeline.settings, into)
    }

    private fun scanOutput(
        index: Int,
        node: Node,
        datasources: DatasourceRegistry,
        workspaceId: UUID,
        into: FailureCollector,
    ) {
        when (val output = node.output) {
            is NodeOutput.Tempdb -> {
                scan(output.table, "nodes[$index].output.table", into)
            }

            is NodeOutput.Datasource -> {
                val facts =
                    if (output.datasource == NodeSource.TEMPDB_LITERAL) null else datasources.describe(output.datasource, workspaceId)
                if (facts == null && output.datasource != NodeSource.TEMPDB_LITERAL) {
                    scan(output.datasource, "nodes[$index].output.datasource", into)
                }
                // A validated managed table reference is portable, even though its dot resembles a hostname.
                val parts = output.table.split('.')
                val managedReference = facts?.managedLake == true && parts.size == 2 && parts.all { IDENTIFIER.matches(it) }
                if (!managedReference) scan(output.table, "nodes[$index].output.table", into)
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

    /** `tempdb` is the reserved literal, not a datasource; anything the registry knows FROM THIS WORKSPACE is a name. */
    private fun isRegistryReference(
        value: String,
        datasources: DatasourceRegistry,
        workspaceId: UUID,
    ): Boolean = value == NodeSource.TEMPDB_LITERAL || datasources.dialectOf(value, workspaceId) != null

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
