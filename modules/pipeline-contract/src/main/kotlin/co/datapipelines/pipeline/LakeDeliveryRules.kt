package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Lake
import co.datapipelines.typesystem.StagingEngine
import java.util.UUID

/** Delivery structure and publication barrier, shared by save, validation and import. */
internal object LakeDeliveryRules {
    fun check(
        pipeline: Pipeline,
        datasources: DatasourceRegistry,
        workspaceId: UUID,
        into: FailureCollector,
    ) {
        val deliveries = pipeline.nodes.filter { it.delivery != null }
        if (deliveries.size > 1) {
            into.add(Lake.DELIVERY_ONE_PER_PIPELINE, "nodes", "A pipeline may declare only one delivery node.")
        }
        deliveries.forEach { node ->
            val path = "nodes[${pipeline.nodes.indexOf(node)}].delivery"
            if (node.type != NodeType.DQL || node.resolvedSource != NodeSource.Tempdb) {
                into.add(Lake.DELIVERY_INVALID_NODE, path, "A delivery belongs to a DQL node with source tempdb.")
            }
            if (pipeline.settings.tempdb.engine != StagingEngine.DUCKDB) {
                into.add(Lake.DELIVERY_REQUIRES_DUCKDB, path, "A delivery requires DuckDB staging.")
            }
        }
        val managed =
            pipeline.nodes.filter { node ->
                val output = node.output as? NodeOutput.Datasource
                output != null && datasources.describe(output.datasource, workspaceId)?.managedLake == true
            }
        val appends = managed.filter { (it.output as NodeOutput.Datasource).mode == WriteMode.APPEND }
        managed.filterNot { it in appends }.forEach { node ->
            into.add(
                Lake.APPEND_REPLACE_REFUSED,
                "nodes[${pipeline.nodes.indexOf(node)}].output.mode",
                "Managed lake publication supports append only.",
            )
        }
        checkAppends(pipeline, deliveries, appends, into)
    }

    private fun checkAppends(
        pipeline: Pipeline,
        deliveries: List<Node>,
        appends: List<Node>,
        into: FailureCollector,
    ) {
        val byId = pipeline.nodes.associateBy { it.id }
        val targets = mutableSetOf<Pair<String, String>>()
        appends.forEach { node ->
            val path = "nodes[${pipeline.nodes.indexOf(node)}]"
            val output = node.output as NodeOutput.Datasource
            if (node.type != NodeType.DQL || node.resolvedSource != NodeSource.Tempdb) {
                into.add(Lake.APPEND_INVALID_NODE, path, "Managed lake append requires a DQL node with source tempdb.")
            }
            if (!targets.add(output.datasource to output.table)) {
                into.add(Lake.APPEND_ONE_PER_TABLE, "$path.output", "Only one append per managed table is permitted.")
            }
            if (deliveries.isEmpty()) {
                into.add(Lake.APPEND_REQUIRES_DELIVERY, path, "Managed lake append requires a delivery node.")
            }
            val reached = ancestors(node, byId)
            val missing = pipeline.nodes.filter { it !in appends && it.id !in reached }.map { it.id }
            if (missing.isNotEmpty()) {
                into.add(
                    Lake.APPEND_BARRIER,
                    "$path.depends_on",
                    "Append must depend transitively on every non-append node.",
                    mapOf("node" to node.id.truncateForError(), "missing" to missing.map { it.truncateForError() }),
                )
            }
        }
        if (appends.isNotEmpty()) {
            pipeline.nodes.forEachIndexed { index, node ->
                if (node.type == NodeType.SCRIPT && (node.output as? NodeOutput.Tempdb)?.rejects != null && node.strict != true) {
                    into.add(
                        Lake.APPEND_REJECTS_NOT_STRICT,
                        "nodes[$index].strict",
                        "A rejects-producing script in a lake append pipeline must be strict.",
                    )
                }
            }
        }
    }

    // Iterative and cycle-safe: DagRules reports malformed graphs independently.
    private fun ancestors(
        node: Node,
        byId: Map<String, Node>,
    ): Set<String> {
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque(node.dependsOn)
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (visited.add(id)) byId[id]?.dependsOn?.let { pending.addAll(it) }
        }
        return visited
    }
}
