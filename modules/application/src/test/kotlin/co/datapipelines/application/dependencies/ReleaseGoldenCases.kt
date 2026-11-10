package co.datapipelines.application.dependencies

import co.datapipelines.pipeline.release.DependencySource
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleasePin
import co.datapipelines.pipeline.release.ResolvedNode
import java.util.UUID

/**
 * An in-memory release graph for the golden cases (#868 section A): versions keyed by exact identity, each read
 * counted. Built fresh per run, so the old walk and the new one never share state.
 */
internal class GoldenGraph {
    val workspaceId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000868")
    private val versions = LinkedHashMap<ReleaseNodeIdentity, ResolvedNode>()
    val reads = HashMap<ReleaseNodeIdentity, Int>()

    fun id(
        key: String,
        version: Int = 1,
        family: ReleaseFamily = ReleaseFamily.TEMPLATE,
    ): ReleaseNodeIdentity = ReleaseNodeIdentity(workspaceId, family, key, version)

    /** Declares [identity] with its pins (`target` to `path`); hash and pointer are stable functions of the key. */
    fun node(
        identity: ReleaseNodeIdentity,
        status: ReleaseNodeStatus = ReleaseNodeStatus.DRAFT,
        vararg pins: Pair<ReleaseNodeIdentity, String>,
        currentVersion: Int? = null,
    ): ReleaseNodeIdentity {
        versions[identity] =
            ResolvedNode(
                identity = identity,
                name = "acme/${identity.entityId}",
                status = status,
                bodyHash = "hash-${identity.family.wire}-${identity.entityId}-${identity.version}",
                currentVersion = currentVersion,
                pins = pins.map { (target, path) -> ReleasePin(target, "acme/${target.entityId}", path) },
                entityUuid = UUID.nameUUIDFromBytes("${identity.family.wire}/${identity.entityId}".toByteArray()),
            )
        return identity
    }

    fun pin(
        parent: ReleaseNodeIdentity,
        target: ReleaseNodeIdentity,
        path: String,
    ) {
        val existing = versions.getValue(parent)
        versions[parent] = existing.copy(pins = existing.pins + ReleasePin(target, "acme/${target.entityId}", path))
    }

    /** A chain of [length] DRAFT templates below [top]: `top → c1 → … → c_length`. */
    fun chain(
        top: ReleaseNodeIdentity,
        length: Int,
        prefix: String,
    ): List<ReleaseNodeIdentity> {
        val links = (1..length).map { id("$prefix$it") }
        links.indices.reversed().forEach { i ->
            node(links[i], ReleaseNodeStatus.DRAFT, *listOfNotNull(links.getOrNull(i + 1)?.let { it to "imports[0]" }).toTypedArray())
        }
        if (links.isNotEmpty()) pin(top, links.first(), if (top.family == ReleaseFamily.PIPELINE) "nodes[0].template" else "imports[0]")
        return links
    }

    fun source(): DependencySource =
        DependencySource { identity ->
            reads.merge(identity, 1, Int::plus)
            versions[identity]
        }

    /** One source answering for every family — what a fully registered deployment would look like. */
    fun allFamilies(): Map<ReleaseFamily, DependencySource> = ReleaseFamily.entries.associateWith { source() }
}

/** One golden case: the graph, its root, the bounds it is walked under and the families that have a source. */
internal class GoldenCase(
    val name: String,
    val graph: GoldenGraph,
    val root: ReleaseNodeIdentity,
    val bounds: ReleaseBounds = ReleaseBounds(),
    val sources: Map<ReleaseFamily, DependencySource> = graph.allFamilies(),
    /** When set, the tree hash is taken under this wire bound instead of [ReleaseBounds.maxWireBytes]. */
    val wireBytes: ((canonicalSize: Int) -> Int)? = null,
)

/**
 * The fixture set every release golden is generated from (#868 section A.1; record §19 A02–A05, §5.4). Each entry
 * builds a FRESH graph, so the old [co.datapipelines.pipeline.release.ReleaseGraph] and the neutral walk read
 * independent copies.
 */
@Suppress("LongMethod") // one declarative table of cases
internal object ReleaseGoldenCases {
    private fun pipeline(
        g: GoldenGraph,
        key: String,
        version: Int = 1,
    ) = g.id(key, version, ReleaseFamily.PIPELINE)

    val ALL: List<() -> GoldenCase> =
        listOf(
            // §5.4's worked graph, artifact families only: dashboard → set → selector → common; dashboard → report →
            // totals → query → common (shared); dashboard → chart → formatter. Datasources/API/schedule are G2/G3's.
            {
                val g = GoldenGraph()
                val common = g.node(g.id("common", 4), ReleaseNodeStatus.RELEASED, currentVersion = 4)
                val selector = g.node(g.id("selector", 5), ReleaseNodeStatus.RELEASED, common to "imports[0]", currentVersion = 5)
                val query = g.node(g.id("query", 6), ReleaseNodeStatus.RELEASED, common to "imports[0]", currentVersion = 6)
                val formatter = g.node(g.id("formatter", 3), ReleaseNodeStatus.RELEASED, currentVersion = 3)
                val filters =
                    g.node(
                        g.id("filters", 2, ReleaseFamily.PARAMETER_SET),
                        ReleaseNodeStatus.DRAFT,
                        selector to "parameters[0].source.template",
                        currentVersion = 1,
                    )
                val totals = g.node(pipeline(g, "totals", 2), ReleaseNodeStatus.RELEASED, query to "nodes[0].template", currentVersion = 2)
                val report = g.node(pipeline(g, "report", 7), ReleaseNodeStatus.DRAFT, totals to "nodes[0].pipeline", currentVersion = 6)
                val chart =
                    g.node(
                        g.id("chart", 2, ReleaseFamily.VISUALIZATION),
                        ReleaseNodeStatus.DRAFT,
                        formatter to "transform.template",
                    )
                val sales =
                    g.node(
                        g.id("sales", 3, ReleaseFamily.DASHBOARD),
                        ReleaseNodeStatus.DRAFT,
                        filters to "parameter_set",
                        report to "sources[0].pipeline",
                        chart to "visualizations[0].visualization",
                        currentVersion = 2,
                    )
                GoldenCase("worked-graph", g, sales)
            },
            {
                val g = GoldenGraph()
                val shared = g.node(g.id("shared"))
                val left = g.node(g.id("left"), ReleaseNodeStatus.DRAFT, shared to "imports[0]")
                val right = g.node(g.id("right"), ReleaseNodeStatus.DRAFT, shared to "imports[0]")
                val root = g.node(pipeline(g, "orders"), ReleaseNodeStatus.DRAFT, left to "nodes[0].template", right to "nodes[1].template")
                GoldenCase("diamond-shared-template", g, root)
            },
            {
                val g = GoldenGraph()
                val v1 = g.node(g.id("region", 1), ReleaseNodeStatus.RELEASED, currentVersion = 2)
                val v2 = g.node(g.id("region", 2), ReleaseNodeStatus.RELEASED, currentVersion = 2)
                val root = g.node(pipeline(g, "orders"), ReleaseNodeStatus.DRAFT, v1 to "nodes[0].template", v2 to "nodes[1].template")
                GoldenCase("two-versions-of-one-template", g, root)
            },
            {
                val g = GoldenGraph()
                val a = g.id("a")
                g.node(a, ReleaseNodeStatus.DRAFT, a to "imports[0]")
                val root = g.node(pipeline(g, "orders"), ReleaseNodeStatus.DRAFT, a to "nodes[0].template")
                GoldenCase("self-cycle", g, root)
            },
            {
                val g = GoldenGraph()
                val a = g.id("a")
                val b = g.id("b")
                val c = g.id("c")
                g.node(c, ReleaseNodeStatus.DRAFT, a to "imports[0]")
                g.node(b, ReleaseNodeStatus.DRAFT, c to "imports[0]")
                g.node(a, ReleaseNodeStatus.DRAFT, b to "imports[0]")
                val root = g.node(pipeline(g, "orders"), ReleaseNodeStatus.DRAFT, a to "nodes[0].template")
                GoldenCase("three-node-cycle", g, root)
            },
            {
                // root → shortcut → shared → 3 below (depth 5), then root → long1..3 → shared: 3 + 1 + 3 = 7.
                val g = GoldenGraph()
                val shared = g.node(g.id("shared"))
                g.chain(shared, 3, "below")
                val shortcut = g.node(g.id("shortcut"), ReleaseNodeStatus.DRAFT, shared to "imports[0]")
                val root = g.node(pipeline(g, "orders"), ReleaseNodeStatus.DRAFT, shortcut to "nodes[0].template")
                val long = g.chain(root, 3, "long")
                g.pin(long.last(), shared, "imports[1]")
                GoldenCase("long-path-into-memoised-subtree", g, root, ReleaseBounds(maxDepth = 7))
            },
            {
                val g = GoldenGraph()
                val shared = g.node(g.id("shared"))
                g.chain(shared, 3, "below")
                val shortcut = g.node(g.id("shortcut"), ReleaseNodeStatus.DRAFT, shared to "imports[0]")
                val root = g.node(pipeline(g, "orders"), ReleaseNodeStatus.DRAFT, shortcut to "nodes[0].template")
                val long = g.chain(root, 3, "long")
                g.pin(long.last(), shared, "imports[1]")
                GoldenCase("long-path-into-memoised-subtree-over-depth", g, root, ReleaseBounds(maxDepth = 6))
            },
            {
                val g = GoldenGraph()
                val root = g.node(pipeline(g, "orders"))
                g.chain(root, 32, "c")
                GoldenCase("depth-at-limit", g, root)
            },
            {
                val g = GoldenGraph()
                val root = g.node(pipeline(g, "orders"))
                g.chain(root, 33, "c")
                GoldenCase("depth-over-limit", g, root)
            },
            {
                val g = GoldenGraph()
                val children = (1..4).map { g.node(g.id("t$it")) }
                val root =
                    g.node(
                        pipeline(g, "orders"),
                        ReleaseNodeStatus.DRAFT,
                        *children.map { it to "nodes[${it.entityId}].template" }.toTypedArray(),
                    )
                GoldenCase("versions-at-limit", g, root, ReleaseBounds(maxVersions = 5))
            },
            {
                val g = GoldenGraph()
                val children = (1..4).map { g.node(g.id("t$it")) }
                val root =
                    g.node(
                        pipeline(g, "orders"),
                        ReleaseNodeStatus.DRAFT,
                        *children.map { it to "nodes[${it.entityId}].template" }.toTypedArray(),
                    )
                GoldenCase("versions-over-limit", g, root, ReleaseBounds(maxVersions = 4))
            },
            {
                val g = GoldenGraph()
                val child = g.node(g.id("child"))
                val root =
                    g.node(
                        pipeline(g, "orders"),
                        ReleaseNodeStatus.DRAFT,
                        child to "nodes[0].template",
                        child to "nodes[1].template",
                        child to "nodes[2].template",
                    )
                GoldenCase("edges-at-limit", g, root, ReleaseBounds(maxEdges = 3))
            },
            {
                val g = GoldenGraph()
                val child = g.node(g.id("child"))
                val root =
                    g.node(
                        pipeline(g, "orders"),
                        ReleaseNodeStatus.DRAFT,
                        child to "nodes[0].template",
                        child to "nodes[1].template",
                        child to "nodes[2].template",
                    )
                GoldenCase("edges-over-limit", g, root, ReleaseBounds(maxEdges = 2))
            },
            {
                val g = GoldenGraph()
                val child = g.node(g.id("child"), ReleaseNodeStatus.RELEASED, currentVersion = 1)
                val root = g.node(pipeline(g, "orders"), ReleaseNodeStatus.DRAFT, child to "nodes[0].template")
                GoldenCase("wire-at-limit", g, root, wireBytes = { size -> size })
            },
            {
                val g = GoldenGraph()
                val child = g.node(g.id("child"), ReleaseNodeStatus.RELEASED, currentVersion = 1)
                val root = g.node(pipeline(g, "orders"), ReleaseNodeStatus.DRAFT, child to "nodes[0].template")
                GoldenCase("wire-over-limit", g, root, wireBytes = { size -> size - 1 })
            },
            {
                val g = GoldenGraph()
                val gone = g.id("gone")
                val beyond = g.node(g.id("beyond"))
                val old = g.node(g.id("old"), ReleaseNodeStatus.DISCARDED, beyond to "imports[0]")
                val root =
                    g.node(
                        pipeline(g, "orders"),
                        ReleaseNodeStatus.DRAFT,
                        gone to "nodes[0].template",
                        old to "nodes[1].template",
                        gone to "nodes[2].template",
                    )
                GoldenCase("missing-and-discarded-leaves", g, root)
            },
            {
                val g = GoldenGraph()
                val viz = g.node(g.id("chart", 1, ReleaseFamily.VISUALIZATION), ReleaseNodeStatus.RELEASED)
                val dash =
                    g.node(
                        g.id("board", 1, ReleaseFamily.DASHBOARD),
                        ReleaseNodeStatus.DRAFT,
                        viz to "visualizations[0].visualization",
                    )
                GoldenCase("missing-source-family", g, dash, sources = mapOf(ReleaseFamily.DASHBOARD to g.source()))
            },
            {
                val g = GoldenGraph()
                GoldenCase("unresolved-root", g, pipeline(g, "absent"))
            },
        )
}
