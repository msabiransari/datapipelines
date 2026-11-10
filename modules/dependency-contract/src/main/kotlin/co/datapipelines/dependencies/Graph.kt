package co.datapipelines.dependencies

import java.time.Instant

/**
 * One node of a [GraphSnapshot] (§5.1). [nodeId] is this snapshot's opaque locator (`n<discovery index>`), never an
 * entity id. A resolved node carries its [identity] and [versionInfo]; an unresolved one (MISSING or UNSUPPORTED) carries
 * only what was requested — [requestedAddress] and [requestedSelector] — and the name the reference spelled.
 *
 * The §5.1 fields only — no JDBC URL, credential, property, key inventory or hash material (record §5.3).
 */
data class Node(
    val nodeId: String,
    val identity: ResolvedIdentity?,
    val requestedAddress: ObjectAddress,
    /**
     * The selector of the first request that reached this node. With [requestedAddress] it is an unresolved node's
     * deterministic key (§5.1: identical missing references share one node).
     */
    val requestedSelector: Selector,
    /** The stored name; for an unresolved node, the name the first reference spelled (authored by the caller). */
    val name: String?,
    val displayName: String?,
    val subtype: String?,
    val versionInfo: VersionInfo?,
    val resolution: Resolution,
    val lifecycle: Lifecycle?,
    val operational: Operational?,
    val capabilities: Capabilities,
    val currentVersion: Int?,
    val bodyHash: String?,
    /** The family's protected definition generation (§5.3, D10), null where the family has none. */
    val generation: Long?,
    val observations: List<Observation>,
) {
    init {
        require((identity != null) == (resolution == Resolution.RESOLVED)) { "a node has an identity exactly when it resolved" }
    }

    val family: FamilyKey get() = requestedAddress.family
}

/**
 * One reference occurrence (§5.1): every array occurrence is its own edge even when the targets deduplicate (§6).
 * [sourcePath] is the family's locator of the field in the dependent's stored body, kept VERBATIM because the release
 * `tree_hash` covers it (`nodes[2].template`); the §11.1 wire's JSON-pointer spelling is G4's projection.
 */
data class Edge(
    val edgeId: String,
    val fromNodeId: String,
    val toNodeId: String,
    val relationship: Relationship,
    val sourcePath: String?,
    val requestedSelector: Selector,
    /** The exact version the selector resolved to in this snapshot (a CURRENT selector's pointer resolution). */
    val resolvedVersion: Int?,
    val constraints: List<Constraint>,
)

/**
 * One bounded diagnostic (§7.2, §11.1). A LIMIT names its [limit] and [maximum]; a CYCLE carries its bounded [trail] of
 * node ids (the active path from the repeated node, then that node again). [message] is generic wording only — no
 * name, path or hash of anything the caller did not author.
 */
data class Diagnostic(
    val kind: DiagnosticKind,
    val nodeId: String?,
    val edgeId: String?,
    val message: String,
    val limit: LimitKind? = null,
    val maximum: Int? = null,
    val trail: List<String> = emptyList(),
) {
    init {
        require((kind == DiagnosticKind.LIMIT) == (limit != null && maximum != null)) { "exactly a LIMIT names a limit and maximum" }
    }
}

/**
 * One walked graph (§5.1, §7.2). [complete] is ENUMERATION completeness, not readiness: false after a LIMIT stop, beneath
 * an unexpanded DISCARDED node, at an UNSUPPORTED node, or when the diagnostics overflowed their bound. [acyclic] is
 * true/false only for a complete graph and null otherwise; [dependencyFirst] exists only for a complete acyclic graph.
 * [internalFingerprint] is [GraphFingerprint]'s digest — server-side only, never on the wire and never `tree_hash`.
 */
data class GraphSnapshot(
    val schemaVersion: Int,
    val roots: List<String>,
    val nodes: List<Node>,
    val edges: List<Edge>,
    val diagnostics: List<Diagnostic>,
    val complete: Boolean,
    val acyclic: Boolean?,
    val dependencyFirst: List<String>?,
    val observedAt: Instant,
    val internalFingerprint: String,
) {
    init {
        require((acyclic == null) == !complete) { "acyclic is known exactly when the graph is complete" }
        require((dependencyFirst != null) == (complete && acyclic == true)) { "dependency-first order exists only for a complete DAG" }
    }

    private val byId: Map<String, Node> by lazy { nodes.associateBy { it.nodeId } }

    fun node(nodeId: String): Node = byId.getValue(nodeId)

    companion object {
        /** The §11.1 `schema_version`; a consumer refuses a payload whose version it does not know. */
        const val SCHEMA_VERSION = 1
    }
}
