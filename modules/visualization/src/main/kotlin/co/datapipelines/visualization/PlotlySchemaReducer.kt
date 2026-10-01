package co.datapipelines.visualization

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.security.MessageDigest

/**
 * The Plotly plot-schema's deterministic reduction (the spec's §11.3 step 1): the upstream
 * `dist/plot-schema.json` of the EXACT vendored release down to the supported traces' attribute trees,
 * the layout attributes and the Plotly config attributes. Every prose and default field is dropped —
 * only what validation READS survives (`valType`, `values`, `freeLength`, `arrayOk`, `role`, and the
 * recursed `items` of compound and info-array attributes), keys sorted, so the output is byte-stable
 * for a given input. No timestamp: two runs over one source produce one file.
 *
 * The upstream source is CHECKED IN (`modules/visualization/schema/plotly/`, its PROVENANCE.md beside
 * it); the Gradle task `reducePlotlySchema` runs [main] over it and rewrites the committed resource —
 * a clean, offline build never fetches anything. `PlotlySchemaProvenanceTest` proves the committed
 * resource IS this function's output over the committed input, and that the recorded hashes still hold.
 */
object PlotlySchemaReducer {
    /** The supported traces (the vendored bundles', `RendererConfigValidators.PLOTLY_TRACES`, kept in step). */
    val SUPPORTED_TRACES: List<String> = RendererConfigValidators.PLOTLY_TRACES

    /** The attribute fields validation reads; copied verbatim. */
    private val KEPT_FIELDS: Set<String> = setOf("valType", "values", "freeLength", "arrayOk", "role")

    /** The prose and edit metadata dropped by name wherever they appear. */
    private val DROPPED_FIELDS: Set<String> = setOf("description", "dflt", "editType", "anim", "impliedEdits")

    /**
     * Reduces [source]. Fails when a supported trace is missing from the source — a release that changes
     * the schema's shape must fail HERE, loudly, not silently shrink what production validates against.
     */
    fun reduce(
        source: JsonNode,
        upstreamSha256: String,
        upstreamCommit: String,
        plotlyVersion: String,
    ): ObjectNode {
        val traces = source.path("traces")
        SUPPORTED_TRACES.forEach { trace ->
            check(traces.has(trace) && traces.path(trace).has("attributes")) {
                "the source schema has no attribute tree for trace '$trace'; the vendored release changed shape"
            }
        }
        val out = ArtifactJson.mapper.createObjectNode()
        out.put("plotly_version", plotlyVersion)
        out.put("source_sha256", upstreamSha256)
        out.put("upstream_commit", upstreamCommit)
        out.set<ObjectNode>("traces", container(SUPPORTED_TRACES.associateWith { traces.path(it).path("attributes") }))
        out.set<ObjectNode>("layout", container(mapOf("layoutAttributes" to source.path("layout").path("layoutAttributes"))))
        out.set<ObjectNode>("config", container(source.path("config")))
        return out
    }

    /** One attribute container: sorted children, each reduced. */
    private fun container(children: Map<String, JsonNode>): ObjectNode {
        val out = ArtifactJson.mapper.createObjectNode()
        children.entries.sortedBy { it.key }.forEach { (name, node) -> out.set<JsonNode>(name, attribute(node)) }
        return out
    }

    private fun container(node: JsonNode): ObjectNode {
        val children =
            node
                .fieldNames()
                .asSequence()
                .map { it to node.path(it) }
                .toMap()
        return container(children)
    }

    /** One attribute: the kept fields verbatim, object children recursed (nested containers, `items`). */
    private fun attribute(node: JsonNode): JsonNode {
        check(node.isObject) { "an attribute is an object; the vendored release changed shape" }
        val out = ArtifactJson.mapper.createObjectNode()
        node
            .properties()
            .asSequence()
            .filter { (key, _) -> !key.startsWith("_") && key !in DROPPED_FIELDS }
            .sortedBy { it.key }
            .forEach { (key, value) ->
                when {
                    key in KEPT_FIELDS -> {
                        out.set<JsonNode>(key, value)
                    }

                    value.isObject -> {
                        out.set<JsonNode>(key, attribute(value))
                    }

                    // An attribute container with no attribute fields of its own (e.g. an empty items map).
                    value.isArray && value.size() > 0 && value.first().isObject -> {
                        val reduced = ArtifactJson.mapper.createArrayNode()
                        value.forEach { reduced.add(attribute(it)) }
                        out.set<JsonNode>(key, reduced)
                    }
                }
            }
        return out
    }
}

/** The vendored release's facts, beside the checked-in source's PROVENANCE.md — the task's inputs. */
const val PROVENANCE_COMMIT = "0aabc3c5cc4f1d91fd6dd3846c5353de1beb0885"
const val PROVENANCE_VERSION = "4.1.1"

/** The Gradle task's entry point: regenerate the committed resource from the checked-in upstream source. */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: reducePlotlySchema <input plot-schema.json> <output plot-schema-reduced.json>" }
    val input = File(args[0])
    val output = File(args[1])
    val source = ArtifactJson.mapper.readTree(input)
    val reduced =
        PlotlySchemaReducer.reduce(
            source,
            upstreamSha256 = MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { "%02x".format(it) },
            upstreamCommit = PROVENANCE_COMMIT,
            plotlyVersion = PROVENANCE_VERSION,
        )
    output.parentFile?.mkdirs()
    output.writeText(ArtifactJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(reduced) + "\n")
    println("reduced ${input.length()} bytes -> ${output.length()} bytes")
}
