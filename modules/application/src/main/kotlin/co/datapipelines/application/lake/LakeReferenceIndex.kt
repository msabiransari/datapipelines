package co.datapipelines.application.lake

import co.datapipelines.pipeline.release.ReleaseFamily
import java.util.UUID

/**
 * A scanned version's verdict (metadata-db §4.41 `status`, enums.md §45): what the lake reference index can say about
 * the tables a released version reads (#778, reference-scanner slice D-A/D-F).
 *
 * - [EXACT] — every LAKE site rendered, parsed, and its template's output depends on no value: the index rows are
 *   every table position the version can ever produce.
 * - [PARTIAL] — every LAKE site parsed, but at least one template's output depends on a value (an interpolation, a
 *   branch, a loop, a macro call, an imported library): the rows are the positions of the ONE rendering scanned.
 * - [UNSCANNABLE] — at least one LAKE site did not render, or the engine's parser refused its text (DML, DDL, `PIVOT`,
 *   a syntax error): that site has no rows, never "no references".
 *
 * Ordered weakest-last, so a version's status is the [maxOf] of its sites'.
 */
enum class LakeScanStatus(
    val wire: String,
) {
    EXACT("exact"),
    PARTIAL("partial"),
    UNSCANNABLE("unscannable"),
    ;

    companion object {
        fun fromWire(wire: String): LakeScanStatus =
            entries.firstOrNull { it.wire == wire } ?: error("lake_reference_scans holds an unknown status '$wire'")
    }
}

/** The two families the index scans (metadata-db §4.41 `family`): the release family's own wire word. */
internal val SCANNED_FAMILIES: Set<ReleaseFamily> = setOf(ReleaseFamily.PIPELINE, ReleaseFamily.PARAMETER_SET)

/** One released version's identity in the index — the §4.41 key. */
data class ScannedVersion(
    val workspaceId: UUID,
    val family: ReleaseFamily,
    val entityId: UUID,
    val version: Int,
) {
    init {
        require(family in SCANNED_FAMILIES) { "the lake reference index scans pipelines and parameter sets only, not $family" }
    }
}

/**
 * Why a site is not EXACT (metadata-db §4.41 `reasons_json`): the [site] (`node:<id>` or `selector:<name>`), the
 * classifier's or the scanner's [kind] word, and a bounded [detail] — a template construct's own source spelling or a
 * template reference, NEVER rendered SQL (which may embed §12.6 sample values) and never a parser message (which may
 * quote a token of it).
 */
data class LakeScanReason(
    val site: String,
    val kind: String,
    val detail: String,
)

/** A scan row (metadata-db §4.41) with its references (§4.42). */
data class LakeReferenceScan(
    val version: ScannedVersion,
    val name: String,
    val status: LakeScanStatus,
    val reasons: List<LakeScanReason>,
    val scannerVersion: Int,
    val references: List<LakeTableReference>,
)

/** A lake table's identity in the registry: its namespace segments, outermost first, and its name (metadata-db §4.15). */
data class LakeTableIdentity(
    val namespace: List<String>,
    val table: String,
) {
    init {
        require(namespace.isNotEmpty()) { "a lake table identity has at least one namespace segment" }
    }

    /** The dotted form the refusal names — `nyc.mobility.trips`. */
    val dotted: String get() = (namespace + table).joinToString(".")

    /** DuckDB identifiers match case-insensitively, quoted or not; so does the registry comparison. */
    fun sameAs(other: LakeTableIdentity): Boolean =
        table.equals(other.table, ignoreCase = true) &&
            namespace.size == other.namespace.size &&
            namespace.zip(other.namespace).all { (a, b) -> a.equals(b, ignoreCase = true) }
}

/**
 * One table position of a scanned rendering (metadata-db §4.42). Exactly one of [nodeId] / [selector] is set. [resolved]
 * is the registry identity the written form names (resolution rules: [LakeTableReferenceResolver]), or null when the
 * position names none — a bare name under several namespaces.
 */
data class LakeTableReference(
    val ordinal: Int,
    val nodeId: String?,
    val selector: String?,
    val templateId: String,
    val templateVersion: Int,
    val datasourceName: String,
    val writtenCatalog: String?,
    val writtenSchema: String?,
    val writtenTable: String,
    val resolved: LakeTableIdentity?,
    val timeTravel: Boolean,
    val statementIndex: Int,
) {
    init {
        require((nodeId == null) != (selector == null)) { "a reference site is a node or a selector, exactly one" }
    }

    /** The site word the refusal and the reasons carry. */
    val site: String get() = nodeId?.let { "node:$it" } ?: "selector:$selector"
}
