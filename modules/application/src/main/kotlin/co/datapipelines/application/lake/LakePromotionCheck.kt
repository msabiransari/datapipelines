package co.datapipelines.application.lake

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.databind.JsonNode

/**
 * The receiver's promotion-time lake check (#778, slice D-E; dp-lake design record §7 (1), versioning §10.5): after a
 * batch's pipelines and parameter sets LANDED — inside the batch's one transaction, so the import-time scan
 * ([LakeReferenceScanService], through the release seam) has just indexed each of them against THIS deployment's
 * registry — every resolved reference of every landed version is asked [LakeTableAvailability]; any table not available
 * refuses the WHOLE batch with `pipeline.promotion.lake_table_unavailable` (409), reported once, and the transaction
 * rolls back: the receiver is left byte-unchanged.
 *
 * What it cannot see it admits (slice D-F, the owner's 2026-11-02 ratification): an unresolved position, a PARTIAL
 * version's unrendered branches and an UNSCANNABLE site write no resolved row, and the runtime gate
 * (`lake.table.unavailable`) stays the defence for them.
 *
 * The refusal names only what the promoter already sees: their own content's names, versions and node or selector ids,
 * the datasource NAME and the table's dotted name. `target` is not set here — the sender's relay supplies it
 * (`PromotionTargetClient` overlays the receiver's details on its own `target`).
 */
class LakePromotionCheck(
    private val index: LakeReferenceIndexRepository,
    private val availability: LakeTableAvailability,
    private val maxReported: Int = DEFAULT_MAX_REPORTED,
) {
    /** One version the batch landed, with its display name. */
    data class Landed(
        val version: ScannedVersion,
        val name: String,
    )

    /** Throws the catalogued refusal when any [landed] version reads an unavailable lake table. */
    fun refuseUnavailable(
        landed: List<Landed>,
        routes: PromotionRoutes,
    ) {
        val unavailable =
            landed.flatMap { entry ->
                index
                    .references(entry.version)
                    .mapNotNull { reference -> reference.resolved?.let { reference to it } }
                    .distinctBy { (reference, table) -> Triple(reference.site, reference.datasourceName, table.dotted.lowercase()) }
                    .mapNotNull { (reference, table) ->
                        val state = availability.stateOf(reference.datasourceName, table)
                        if (state.available) null else row(entry, reference, table, state, routes)
                    }
            }
        if (unavailable.isEmpty()) return
        val tables = unavailable.map { it["table"] }.distinct()
        throw DatapipelinesException(
            PipelineErrorCodes.Versioning.PROMOTION_LAKE_TABLE_UNAVAILABLE,
            "The batch reads ${tables.size} lake table(s) this deployment has not registered " +
                "(${tables.take(MAX_NAMED_IN_MESSAGE).joinToString(", ")}${if (tables.size > MAX_NAMED_IN_MESSAGE) ", …" else ""}). " +
                "Register them on this deployment first; nothing was applied.",
            mapOf(
                "unavailable" to unavailable.take(maxReported),
                "unavailable_truncated" to (unavailable.size > maxReported),
            ),
        )
    }

    private fun row(
        entry: Landed,
        reference: LakeTableReference,
        table: LakeTableIdentity,
        state: LakeTableState,
        routes: PromotionRoutes,
    ): Map<String, Any?> =
        buildMap {
            put("family", entry.version.family.wire)
            put("name", entry.name)
            put("version", entry.version.version)
            reference.nodeId?.let { put("node_id", it) }
            reference.selector?.let { put("selector", it) }
            put("datasource", reference.datasourceName)
            put("table", table.dotted)
            put("state", state.wire)
            put("via", routes.pathsTo(entry.version.family.wire, entry.name, entry.version.version))
        }

    companion object {
        /** `details.unavailable` is bounded; `unavailable_truncated` says when it was cut. */
        const val DEFAULT_MAX_REPORTED = 50
        private const val MAX_NAMED_IN_MESSAGE = 5
    }
}

/**
 * Who in a promotion batch pins whom — the `via` of a refusal (slice D-E: "the paths name the dashboard → pipeline
 * chain the way the blockers shape does"). Built from the batch's own JSON: every member (a pipeline, a parameter set,
 * a visualization, a dashboard) is identified by its top-level `name` and `version`, and every object field named
 * `pipeline`, `parameter_set` or `visualization` inside it that carries a string `name` and an integral `version` is a
 * pin (a parent pipeline's PIPELINE node, a dashboard's source or layout, a visualization's source). Templates are not
 * members: a template is never the thing that reads a table.
 */
class PromotionRoutes private constructor(
    private val incoming: Map<Ref, List<Ref>>,
) {
    /** A batch member's identity, spelled `family:name@version` in a path. */
    private data class Ref(
        val family: String,
        val name: String,
        val version: Int,
    ) {
        override fun toString() = "$family:$name@$version"
    }

    /**
     * The chains of batch members that pin the member ([family], [name], [version]), each outermost-first and joined
     * with ` > `, ending at its immediate pinner; at most [MAX_PATHS]. Empty when nothing in the batch pins it (it is
     * a root the promoter selected).
     */
    fun pathsTo(
        family: String,
        name: String,
        version: Int,
    ): List<String> {
        val paths = ArrayList<String>()

        fun climb(
            at: Ref,
            below: List<Ref>,
        ) {
            if (paths.size >= MAX_PATHS) return
            val parents = incoming[at].orEmpty().filter { it !in below && it != at }
            if (parents.isEmpty()) {
                if (below.isNotEmpty()) paths += below.joinToString(" > ")
                return
            }
            parents.forEach { parent -> climb(parent, listOf(parent) + below) }
        }
        climb(Ref(family, name, version), emptyList())
        return paths
    }

    companion object {
        /** A refusal row names at most this many routes (the blockers' `MAX_PATHS_PER_NODE`). */
        const val MAX_PATHS = 8

        private val PIN_FIELDS = setOf("pipeline", "parameter_set", "visualization")

        /** The routes of a batch, from its member lists keyed by family wire word. */
        fun of(members: Map<String, List<JsonNode>>): PromotionRoutes {
            val incoming = HashMap<Ref, MutableList<Ref>>()
            members.forEach { (family, entries) ->
                entries.forEach { entry ->
                    val self = refOf(family, entry) ?: return@forEach
                    pinsOf(entry)
                        .filter { it != self }
                        .distinct()
                        .forEach { pinned -> incoming.getOrPut(pinned) { ArrayList() }.add(self) }
                }
            }
            return PromotionRoutes(incoming)
        }

        private fun refOf(
            family: String,
            node: JsonNode,
        ): Ref? {
            val name = node.path("name").takeIf { it.isTextual }?.asText() ?: return null
            val version = node.path("version").takeIf { it.isIntegralNumber }?.asInt() ?: return null
            return Ref(family, name, version)
        }

        private fun pinsOf(root: JsonNode): List<Ref> {
            val pins = ArrayList<Ref>()

            fun walk(node: JsonNode) {
                when {
                    node.isArray -> {
                        node.forEach(::walk)
                    }

                    node.isObject -> {
                        node.properties().forEach { (field, child) ->
                            if (field in PIN_FIELDS && child.isObject) refOf(field, child)?.let(pins::add)
                            walk(child)
                        }
                    }
                }
            }
            walk(root)
            return pins
        }
    }
}
