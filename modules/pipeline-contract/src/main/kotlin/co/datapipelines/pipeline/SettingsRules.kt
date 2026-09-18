package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation

/**
 * pipeline-contract §12.8 — `settings.tempdb` validation.
 *
 * `tempdb_engine_unsupported` is raised by [PipelineDeserializer]'s pre-scan, for the same
 * reason as the other wire-value codes: [StagingEngine] has exactly one constant in v1, so a
 * payload declaring `DUCKDB` cannot be bound at all and the verdict has to be reached on the
 * JSON tree. enums.md is explicit that reserved values "MUST NOT appear in generated code or
 * be accepted by validators in v1" — hence one constant, not a rejected-at-validation second
 * one.
 *
 * What remains for the typed model is the engine-specific `config` map — and, since 108, each
 * node's own `settings.timeout_seconds` (§6.4), whose ceiling is an operator setting the caller
 * passes in.
 */
internal object SettingsRules {
    /** Node types that run a SQL statement (§4.6) — the only ones `query_timeout_seconds` fits. */
    private val STATEMENT_NODE_TYPES = setOf(NodeType.DQL, NodeType.DML, NodeType.DDL)

    fun check(
        pipeline: Pipeline,
        nodeTimeoutMaxSeconds: Int,
        nodeTimeoutSeconds: Int,
        nodeQueryTimeoutMaxSeconds: Int,
        into: FailureCollector,
    ) {
        checkTempdb(pipeline, into)
        checkNodeTimeouts(pipeline, nodeTimeoutMaxSeconds, into)
        checkPipelineQueryTimeout(pipeline, nodeQueryTimeoutMaxSeconds, into)
        checkNodeQueryTimeouts(pipeline, nodeTimeoutMaxSeconds, nodeTimeoutSeconds, nodeQueryTimeoutMaxSeconds, into)
    }

    /**
     * §12.8 / §5.3 (156, #2) — `settings.query_timeout_seconds` (the pipeline-wide statement
     * timeout default) is a positive integer no larger than the operator's ceiling.
     */
    private fun checkPipelineQueryTimeout(
        pipeline: Pipeline,
        nodeQueryTimeoutMaxSeconds: Int,
        into: FailureCollector,
    ) {
        val requested = pipeline.settings.queryTimeoutSeconds ?: return
        if (requested in 1..nodeQueryTimeoutMaxSeconds) return
        into.add(
            Validation.PIPELINE_QUERY_TIMEOUT_INVALID,
            "settings.query_timeout_seconds",
            "'query_timeout_seconds' must be a positive integer no greater than $nodeQueryTimeoutMaxSeconds seconds; was $requested.",
            mapOf("timeout_seconds" to requested, "max" to nodeQueryTimeoutMaxSeconds),
        )
    }

    /**
     * §12.8 / §4.11 (156, #2) — a node's own `settings.query_timeout_seconds`:
     *  1. legal only on a node type that runs a statement (`DQL`/`DML`/`DDL`);
     *  2. a positive integer no larger than the operator's ceiling;
     *  3. no larger than this SAME node's own effective wall-clock deadline
     *     (`settings.timeout_seconds`, else the operator's `node-timeout-seconds` default) — a
     *     statement budget the node's own lifecycle could never reach is never what an author
     *     meant, and refusing it at save (naming both numbers) is cheaper than an author
     *     discovering it from a `pipeline.node.timeout` that fired before the statement ever
     *     could.
     *
     * Rule 3 is deliberately scoped to values fully known at SAVE time — this node's own two
     * settings — and does not reach into the datasource/dialect tiers or the operator's global
     * `execution-timeout-seconds`: those can change independently of this saved body (exactly
     * as `node-timeout-max-seconds` already accepts for [checkNodeTimeouts]), and `ExecutorConfig`
     * deliberately does not cross-key-enforce operator settings against each other for the same
     * reason (dag-executor §5.3).
     */
    private fun checkNodeQueryTimeouts(
        pipeline: Pipeline,
        nodeTimeoutMaxSeconds: Int,
        nodeTimeoutSeconds: Int,
        nodeQueryTimeoutMaxSeconds: Int,
        into: FailureCollector,
    ) {
        pipeline.nodes.forEach { node ->
            val settings = node.settings ?: return@forEach
            val requested = settings.queryTimeoutSeconds ?: return@forEach
            val path = "nodes.${node.id}.settings.query_timeout_seconds"
            if (node.type !in STATEMENT_NODE_TYPES) {
                into.add(
                    Validation.NODE_QUERY_TIMEOUT_INVALID,
                    path,
                    "'query_timeout_seconds' is a SQL statement timeout; node '${node.id}' is a " +
                        "${node.type.wire} node and runs no statement.",
                    mapOf("node" to node.id, "node_type" to node.type.wire, "timeout_seconds" to requested),
                )
                return@forEach
            }
            if (requested !in 1..nodeQueryTimeoutMaxSeconds) {
                into.add(
                    Validation.NODE_QUERY_TIMEOUT_INVALID,
                    path,
                    "'query_timeout_seconds' must be a positive integer no greater than $nodeQueryTimeoutMaxSeconds " +
                        "seconds; was $requested.",
                    mapOf("node" to node.id, "timeout_seconds" to requested, "max" to nodeQueryTimeoutMaxSeconds),
                )
                return@forEach
            }
            val effectiveNodeDeadline = (settings.timeoutSeconds ?: nodeTimeoutSeconds).coerceAtMost(nodeTimeoutMaxSeconds)
            if (requested > effectiveNodeDeadline) {
                into.add(
                    Validation.NODE_QUERY_TIMEOUT_INVALID,
                    path,
                    "'query_timeout_seconds' ($requested) exceeds node '${node.id}''s own effective wall-clock " +
                        "deadline ($effectiveNodeDeadline seconds, from " +
                        "${if (settings.timeoutSeconds != null) "its settings.timeout_seconds" else "the operator default"}) " +
                        "— a statement budget the node could never reach.",
                    mapOf(
                        "node" to node.id,
                        "timeout_seconds" to requested,
                        "node_deadline_seconds" to effectiveNodeDeadline,
                    ),
                )
            }
        }
    }

    /**
     * §12.8 / §6.4 — a node's own wall-clock deadline must be a positive integer no larger than
     * `datapipelines.executor.node-timeout-max-seconds`.
     *
     * Refused rather than clamped, and refused at SAVE: an author who writes 14 400 and silently
     * runs at 900 debugs a timeout that says nothing about what they asked for. The ceiling is
     * the operator's, because a per-node override that could exceed it would let one pipeline
     * hold an execution slot for as long as it liked.
     */
    private fun checkNodeTimeouts(
        pipeline: Pipeline,
        nodeTimeoutMaxSeconds: Int,
        into: FailureCollector,
    ) {
        pipeline.nodes.forEach { node ->
            val requested = node.settings?.timeoutSeconds ?: return@forEach
            if (requested in 1..nodeTimeoutMaxSeconds) return@forEach
            into.add(
                Validation.NODE_TIMEOUT_INVALID,
                "nodes.${node.id}.settings.timeout_seconds",
                "'timeout_seconds' must be a positive integer no greater than $nodeTimeoutMaxSeconds seconds; was $requested.",
                mapOf("node" to node.id, "timeout_seconds" to requested, "max" to nodeTimeoutMaxSeconds),
            )
        }
    }

    private fun checkTempdb(
        pipeline: Pipeline,
        into: FailureCollector,
    ) {
        val tempdb = pipeline.settings.tempdb
        val allowed = TempdbSettings.ALLOWED_CONFIG_KEYS[tempdb.engine].orEmpty()
        tempdb.config.forEach { (key, value) ->
            if (key !in allowed) {
                into.add(
                    Validation.TEMPDB_CONFIG_INVALID,
                    "settings.tempdb.config.$key",
                    "'${key.truncateForError()}' is not a valid config key for engine ${tempdb.engine.wire}; " +
                        "allowed: $allowed.",
                    mapOf("key" to key.truncateForError(), "engine" to tempdb.engine.wire, "allowed" to allowed),
                )
                return@forEach
            }
            if (key == TempdbSettings.MAX_MEMORY_MB_KEY && !(value.isIntegralNumber && value.asInt() > 0)) {
                into.add(
                    Validation.TEMPDB_CONFIG_INVALID,
                    "settings.tempdb.config.$key",
                    "'$key' must be a positive integer number of megabytes.",
                    mapOf("key" to key, "engine" to tempdb.engine.wire),
                )
            }
        }
    }
}
