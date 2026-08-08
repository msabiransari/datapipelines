package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation

/**
 * pipeline-contract §12.1 — structural validations, plus the §11.4 portability scan.
 *
 * Everything here is checkable from the document alone: no registry, no template engine, no
 * database. That is why it runs first — the failures it produces are the ones an author can
 * fix without knowing which environment they are saving into.
 */
internal object StructuralRules {
    fun check(
        pipeline: Pipeline,
        datasources: DatasourceRegistry,
        into: FailureCollector,
    ) {
        checkSchemaVersion(pipeline, into)
        checkName(pipeline, into)
        checkNodeIdentifiers(pipeline, into)
        checkOutputTables(pipeline, into)
        EnvPortabilityRule.check(pipeline, datasources, into)
    }

    private fun checkSchemaVersion(
        pipeline: Pipeline,
        into: FailureCollector,
    ) {
        if (pipeline.schemaVersion == Pipeline.SUPPORTED_SCHEMA_VERSION) return
        into.add(
            Validation.SCHEMA_VERSION_UNSUPPORTED,
            "schema_version",
            "schema_version ${pipeline.schemaVersion} is not supported; v1 reads ${Pipeline.SUPPORTED_SCHEMA_VERSION}.",
            mapOf("value" to pipeline.schemaVersion, "supported" to Pipeline.SUPPORTED_SCHEMA_VERSION),
        )
    }

    private fun checkName(
        pipeline: Pipeline,
        into: FailureCollector,
    ) {
        if (IDENTIFIER.matches(pipeline.name)) return
        into.add(
            Validation.NAME_INVALID,
            "name",
            "Pipeline name '${pipeline.name.truncateForError()}' must match [a-z0-9_]+, length 1-63.",
            mapOf("value" to pipeline.name.truncateForError()),
        )
    }

    private fun checkNodeIdentifiers(
        pipeline: Pipeline,
        into: FailureCollector,
    ) {
        pipeline.nodes.forEachIndexed { index, node ->
            if (!IDENTIFIER.matches(node.id)) {
                into.add(
                    Validation.INVALID_IDENTIFIER,
                    "nodes[$index].id",
                    "Node id '${node.id.truncateForError()}' must match [a-z0-9_]+, length 1-63.",
                    mapOf("value" to node.id.truncateForError()),
                )
            }
            if (isReservedIdentifier(node.id)) {
                into.add(
                    Validation.RESERVED_IDENTIFIER,
                    "nodes[$index].id",
                    "Node id '${node.id.truncateForError()}' is reserved: 'tempdb' and the __…__ namespace are not usable.",
                    mapOf("value" to node.id.truncateForError()),
                )
            }
        }
        duplicatesOf(pipeline.nodes.map { it.id }).forEach { duplicate ->
            into.add(
                Validation.DUPLICATE_NODE_ID,
                "nodes",
                "Node id '${duplicate.truncateForError()}' is declared more than once; ids are unique within a pipeline.",
                mapOf("value" to duplicate.truncateForError()),
            )
        }
    }

    /**
     * §12.1 `invalid_identifier` / `reserved_identifier` / `duplicate_output_table` over
     * `output.table`.
     *
     * Uniqueness is **per namespace** (§10.1, SPEC-REVIEW 2.1.9): tempdb tables share one
     * staging database and must be unique among themselves; write-back tables must be unique
     * per target datasource. A tempdb table and a write-back table may share a name, and two
     * write-backs to different datasources may too — global uniqueness would over-constrain
     * exactly the pipeline shape §9.3 shows as legitimate.
     *
     * Blank table names are skipped: their absence is §12.4's `output_table_missing`, and
     * reporting the same missing field twice under two codes is noise, not exhaustiveness.
     */
    private fun checkOutputTables(
        pipeline: Pipeline,
        into: FailureCollector,
    ) {
        val namespaced = mutableMapOf<String, MutableList<String>>()
        pipeline.nodes.forEachIndexed { index, node ->
            val (namespace, table) =
                when (val output = node.output) {
                    is NodeOutput.Tempdb -> NodeSource.TEMPDB_LITERAL to output.table
                    is NodeOutput.Datasource -> "datasource:${output.datasource.truncateForError()}" to output.table
                    else -> return@forEachIndexed
                }
            if (table.isBlank()) return@forEachIndexed
            checkTableIdentifier(index, table, into)
            namespaced.getOrPut(namespace) { mutableListOf() } += table
        }
        namespaced.forEach { (namespace, tables) ->
            duplicatesOf(tables).forEach { duplicate ->
                into.add(
                    Validation.DUPLICATE_OUTPUT_TABLE,
                    "nodes[].output.table",
                    "Output table '${duplicate.truncateForError()}' is declared more than once in namespace " +
                        "'$namespace'; §10.1 scopes uniqueness to tempdb and to each target datasource.",
                    mapOf("value" to duplicate.truncateForError(), "namespace" to namespace),
                )
            }
        }
    }

    private fun checkTableIdentifier(
        index: Int,
        table: String,
        into: FailureCollector,
    ) {
        if (!IDENTIFIER.matches(table)) {
            into.add(
                Validation.INVALID_IDENTIFIER,
                "nodes[$index].output.table",
                "Output table '${table.truncateForError()}' must match [a-z0-9_]+, length 1-63.",
                mapOf("value" to table.truncateForError()),
            )
        }
        if (isReservedIdentifier(table)) {
            into.add(
                Validation.RESERVED_IDENTIFIER,
                "nodes[$index].output.table",
                "Output table '${table.truncateForError()}' is reserved: 'tempdb' and the __…__ namespace are not usable.",
                mapOf("value" to table.truncateForError()),
            )
        }
    }

    private fun duplicatesOf(values: List<String>): List<String> =
        values
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .toList()
}
