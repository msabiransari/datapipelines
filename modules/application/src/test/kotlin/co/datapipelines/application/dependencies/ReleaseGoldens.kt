package co.datapipelines.application.dependencies

import co.datapipelines.application.TestRepoFiles
import co.datapipelines.pipeline.release.ReleaseClosure
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.TreeHash
import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/**
 * The release golden files (#868 section A): what one release walk of a [GoldenCase] answered — the closure (every node,
 * every edge with its path, the depth, the dependency-first order, the `TreeHash` canonical string and digest) or the
 * refusal (code and details) — as canonical, pretty-printed JSON under [DIR].
 *
 * The SAME rendering reads the old `ReleaseGraph` (the generator) and the neutral walk behind the release adapter (the
 * parity test), so a byte difference between the two is a behavior difference, never a formatting one.
 */
internal object ReleaseGoldens {
    /** Source directory of the goldens — read and written in place, never through a processed-resources copy. */
    const val DIR = "modules/application/src/test/resources/dependencies/release-goldens"

    /**
     * Writing is allowed only with this environment variable set to `1`, and only for a golden that does not exist yet.
     * An environment variable rather than a system property: Gradle hands the client's environment to the test worker,
     * while a `-D` on the command line never reaches it (no build-file edit is in this lane's fence).
     */
    const val WRITE_FLAG = "DP_WRITE_RELEASE_GOLDENS"

    private val writer =
        ObjectMapper().writer(
            DefaultPrettyPrinter().withObjectIndenter(DefaultIndenter("  ", "\n")).withArrayIndenter(DefaultIndenter("  ", "\n")),
        )

    fun file(case: String): File = File(TestRepoFiles.root, "$DIR/$case.json")

    /** The canonical rendering of [case] walked by [walk] — a closure, a refusal, or an unresolved root. */
    fun render(
        case: GoldenCase,
        walk: (ReleaseNodeIdentity) -> ReleaseClosure?,
    ): String {
        val bounds = case.bounds
        val document = linkedMapOf<String, Any?>("case" to case.name)
        document["bounds"] =
            linkedMapOf(
                "max_depth" to bounds.maxDepth,
                "max_versions" to bounds.maxVersions,
                "max_edges" to bounds.maxEdges,
                "max_blockers" to bounds.maxBlockers,
                "max_wire_bytes" to bounds.maxWireBytes,
            )
        val closure =
            try {
                walk(case.root)
            } catch (refused: DatapipelinesException) {
                document["outcome"] = "refused"
                document["refusal"] = refusal(refused)
                null
            }
        when {
            "refusal" in document -> Unit
            closure == null -> document["outcome"] = "unresolved_root"
            else -> closureFields(document, case, closure)
        }
        return writer.writeValueAsString(document) + "\n"
    }

    private fun closureFields(
        document: MutableMap<String, Any?>,
        case: GoldenCase,
        closure: ReleaseClosure,
    ) {
        document["outcome"] = "closure"
        document["nodes"] =
            closure.nodes.map { node ->
                linkedMapOf(
                    "node_id" to node.nodeId,
                    "family" to node.identity.family.wire,
                    "entity_id" to node.identity.entityId,
                    "version" to node.identity.version,
                    "name" to node.name,
                    "status" to node.status.name,
                    "body_hash" to node.bodyHash,
                    "current_version" to node.currentVersion,
                    "entity_uuid" to node.entityUuid?.toString(),
                )
            }
        val ids = closure.nodes.associate { it.identity to it.nodeId }
        document["edges"] = closure.edges.map { linkedMapOf("from" to ids[it.from], "to" to ids[it.to], "path" to it.path) }
        document["depth"] = closure.depth
        document["dependency_first"] = closure.dependencyFirst.map { it.nodeId }
        val canonical = TreeHash.canonical(closure)
        document["canonical"] = canonical
        val wire = case.wireBytes?.invoke(canonical.toByteArray(Charsets.UTF_8).size) ?: case.bounds.maxWireBytes
        document["wire_bound"] = wire
        try {
            document["digest"] = TreeHash.of(closure, wire)
        } catch (refused: DatapipelinesException) {
            document["digest_refusal"] = refusal(refused)
        }
    }

    private fun refusal(refused: DatapipelinesException): Map<String, Any?> =
        linkedMapOf("code" to refused.code, "message" to refused.message, "details" to refused.details)
}
