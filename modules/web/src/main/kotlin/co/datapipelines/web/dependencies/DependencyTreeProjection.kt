package co.datapipelines.web.dependencies

import co.datapipelines.dependencies.Constraint
import co.datapipelines.dependencies.Diagnostic
import co.datapipelines.dependencies.Edge
import co.datapipelines.dependencies.GraphSnapshot
import co.datapipelines.dependencies.Node
import co.datapipelines.dependencies.Operational
import co.datapipelines.dependencies.Resolution
import co.datapipelines.dependencies.Selector
import co.datapipelines.dependencies.VersionKind
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import java.time.format.DateTimeFormatter

/**
 * The §11.1 200 `data` object (dependency-graph record §11.1, frozen; schema `dependencies-v1.schema.json`). Every class
 * names its keys in the wire's order and includes nulls ALWAYS: the schema requires every key, null included, so a
 * global `NON_NULL` inclusion must never reach these.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder("schema_version", "roots", "nodes", "edges", "diagnostics", "complete", "acyclic", "observed_at")
data class DependencyTreeData(
    val schemaVersion: Int,
    val roots: List<String>,
    val nodes: List<WireNode>,
    val edges: List<WireEdge>,
    val diagnostics: List<WireDiagnostic>,
    val complete: Boolean,
    val acyclic: Boolean?,
    val observedAt: String,
)

/** A node: the §11.1 safe summary — no body, no SQL, no parameters, no key inventory. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder(
    "node_id",
    "entity_id",
    "family",
    "name",
    "display_name",
    "subtype",
    "version",
    "resolution",
    "lifecycle",
    "operational",
    "current_version",
    "body_hash",
    "capabilities",
)
data class WireNode(
    val nodeId: String,
    val entityId: String?,
    val family: String,
    val name: String?,
    val displayName: String?,
    val subtype: String?,
    val version: WireNumbered,
    val resolution: String,
    val lifecycle: String?,
    val operational: Map<String, Any>?,
    val currentVersion: Int?,
    val bodyHash: String?,
    val capabilities: WireCapabilities,
)

/** `version` and `requested_selector`: a kind token and its number (null where the kind has none). */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder("kind", "number")
data class WireNumbered(
    val kind: String,
    val number: Int?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder("release", "promotion")
data class WireCapabilities(
    val release: String,
    val promotion: String,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder("edge_id", "from", "to", "relationship", "source_path", "requested_selector", "resolved_version", "constraints")
data class WireEdge(
    val edgeId: String,
    val from: String,
    val to: String,
    val relationship: String,
    val sourcePath: String?,
    val requestedSelector: WireNumbered,
    val resolvedVersion: Int?,
    val constraints: List<Map<String, Any>>,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder("code", "node_id", "edge_id", "message")
data class WireDiagnostic(
    val code: String,
    val nodeId: String?,
    val edgeId: String?,
    val message: String,
)

/**
 * `GraphSnapshot` → the §11.1 wire, field by field through an allowlist (#875 G4). What is NOT projected is the point:
 * a node's `generation` and `observations`, the walk's `dependencyFirst` and `internalFingerprint`, an edge's `alias`
 * (record case (b): the schema has none), and a LIMIT diagnostic's `limit`/`maximum`/`trail` beyond its message — none
 * is a §11.1 field. The descriptors' `safeSummary` hook is not consulted: the walker's node already carries only
 * `NodeFacts`, and this allowlist is the one place a field is admitted onto the wire.
 *
 * An UNRESOLVED node (MISSING or UNSUPPORTED) is projected as G1's `resolution-missing.json` shows it: the name as the
 * visible parent spells it, no `entity_id`, no fact — and its `version` from the REQUEST (an exact or CURRENT selector
 * names an artifact version, an entity selector an unversioned object), the one representation for an absent and an
 * invisible target alike (§12; erratum (8)(c)). This projection removes no node, so it emits no `INCOMPLETE_VIEW`.
 *
 * A `parameter_names` constraint carries `unmet_names` (#892, erratum (11)) exactly when [UnmetParameterNames] marked it —
 * a non-empty subset of its `names`, after them; otherwise the key is absent, never `[]` or `null`.
 */
object DependencyTreeProjection {
    fun project(
        graph: GraphSnapshot,
        unmet: UnmetParameterNames.Unmet = UnmetParameterNames.Unmet.NONE,
    ): DependencyTreeData =
        DependencyTreeData(
            schemaVersion = graph.schemaVersion,
            roots = graph.roots,
            nodes = graph.nodes.map(::node),
            edges = graph.edges.map { edge(it, unmet) },
            diagnostics = graph.diagnostics.map(::diagnostic),
            complete = graph.complete,
            acyclic = graph.acyclic,
            observedAt = DateTimeFormatter.ISO_INSTANT.format(graph.observedAt),
        )

    private fun node(node: Node): WireNode {
        val resolved = node.resolution == Resolution.RESOLVED
        return WireNode(
            nodeId = node.nodeId,
            // erratum (9): a string for a RESOLVED node, null for every other resolution.
            entityId = node.identity?.stableEntityId.takeIf { resolved },
            family = node.family.wire,
            name = node.name,
            displayName = node.displayName.takeIf { resolved },
            subtype = node.subtype.takeIf { resolved },
            version = versionOf(node),
            resolution = node.resolution.wire,
            lifecycle = node.lifecycle?.wire.takeIf { resolved },
            operational = node.operational?.let(::operational).takeIf { resolved },
            currentVersion = node.currentVersion.takeIf { resolved },
            bodyHash = node.bodyHash.takeIf { resolved },
            capabilities = WireCapabilities(node.capabilities.release.wire, node.capabilities.promotion.wire),
        )
    }

    private fun versionOf(node: Node): WireNumbered {
        val facts = node.versionInfo.takeIf { node.resolution == Resolution.RESOLVED }
        if (facts != null) return WireNumbered(facts.kind.wire, facts.number)
        val kind = if (node.requestedSelector is Selector.Entity) VersionKind.UNVERSIONED else VersionKind.ARTIFACT
        return WireNumbered(kind.wire, node.requestedSelector.number.takeIf { kind == VersionKind.ARTIFACT })
    }

    /** The §11.1 per-family operational object — exactly one key each. */
    private fun operational(operational: Operational): Map<String, Any> =
        when (operational) {
            is Operational.Schedule -> mapOf("condition" to operational.condition.wire)
            is Operational.ApiEndpoint -> mapOf("enabled" to operational.enabled)
            is Operational.Datasource -> mapOf("readonly" to operational.readonly)
            is Operational.Visualization -> mapOf("evidence_ready" to operational.evidenceReady)
        }

    private fun edge(
        edge: Edge,
        unmet: UnmetParameterNames.Unmet,
    ): WireEdge =
        WireEdge(
            edgeId = edge.edgeId,
            from = edge.fromNodeId,
            to = edge.toNodeId,
            relationship = edge.relationship.wire,
            sourcePath = edge.sourcePath?.let(::jsonPointer),
            requestedSelector = WireNumbered(edge.requestedSelector.kind.wire, edge.requestedSelector.number),
            resolvedVersion = edge.resolvedVersion,
            constraints = edge.constraints.map { constraint(it) { names -> unmet.of(edge.edgeId, names) } },
        )

    private fun constraint(
        constraint: Constraint,
        unmetOf: (Constraint.ParameterNames) -> List<String>?,
    ): Map<String, Any> =
        when (constraint) {
            is Constraint.ParameterNames ->
                linkedMapOf<String, Any>("kind" to constraint.kind.wire, "names" to constraint.names).apply {
                    unmetOf(constraint)?.let { put(UNMET_NAMES, it) }
                }
            is Constraint.DatasourceAccess -> linkedMapOf("kind" to constraint.kind.wire, "mode" to constraint.mode.wire)
            is Constraint.DatasourceDialect -> linkedMapOf("kind" to constraint.kind.wire, "dialect" to constraint.dialect)
        }

    private fun diagnostic(diagnostic: Diagnostic): WireDiagnostic =
        WireDiagnostic(diagnostic.kind.wire, diagnostic.nodeId, diagnostic.edgeId, diagnostic.message)

    /**
     * A family locator as the §11.1 JSON pointer (RFC 6901). The release families spell a field as a dotted path with
     * bracketed indexes (`nodes[2].template`, `transform.template`, `pipeline_version`); a schedule's executor already
     * spells a pointer (`/pipeline`), which passes unchanged. The empty locator — the whole stored body — has no pointer
     * the schema admits (it requires at least one reference token), so it is null: "no single field".
     */
    internal fun jsonPointer(locator: String): String? {
        if (locator.isEmpty()) return null
        if (locator.startsWith("/")) return locator
        val tokens =
            locator.split('.').flatMap { part ->
                val name = part.substringBefore('[')
                val indexes = INDEX.findAll(part).map { it.groupValues[1] }.toList()
                listOf(name) + indexes
            }
        return tokens.joinToString(separator = "") { "/" + it.replace("~", "~0").replace("/", "~1") }
    }

    private val INDEX = Regex("\\[([0-9]+)]")

    /** The erratum (11) key of a `parameter_names` constraint element. */
    const val UNMET_NAMES = "unmet_names"
}
