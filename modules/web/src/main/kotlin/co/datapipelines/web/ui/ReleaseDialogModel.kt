package co.datapipelines.web.ui

import co.datapipelines.pipeline.release.ClosureNode
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleasePrerequisite

/**
 * The shared release dialog's view model (#876 §F.2; record §8 release treatment, §12; the 2026-10-13 release-dependencies
 * design §9): ONE row per plan node — the closure holds each identity once, so a draft shared by several branches is one
 * row — and one text row per datasource prerequisite. Built from a [ReleasePreview] and nothing else; the family dialogs
 * add their own facts (checks, evidence, warnings) beside it.
 *
 * Row kinds by §8: the [Kind.ROOT] (released by the action, no input); a selectable DRAFT ([Kind.DRAFT] — a checkbox,
 * checked and required, posting its `node_id` as `include_dependencies`); RELEASED ([Kind.RELEASED] — a text chip); MISSING
 * or DISCARDED ([Kind.BLOCKING] — blocking text, no input). A prerequisite is never an input and never a graph node.
 *
 * [submittable] is the one boolean the fragment reads to enable its submit — a courtesy: the planner refuses a stale
 * `tree_hash` or an unticked draft and the coordinator re-checks every prerequisite under the guard (A11).
 */
data class ReleaseDialogModel(
    /** The root's family wire key (`pipeline`, `template`, …). */
    val family: String,
    /** Every plan node in plan order, the root first. Empty when [refusal] is set. */
    val rows: List<Row>,
    val prerequisites: List<PrerequisiteRow>,
    /** The plan's edges as given — `path` is the native internal path, never parsed as a pointer. */
    val edges: List<Edge>,
    /** The plan's digest as THIS read computed it; posted back as `tree_hash`. */
    val treeHash: String?,
    val complete: Boolean,
    /** The planner refused to plan at all (a cycle, a bound, the metadata deadline): the message, and no form. */
    val refusal: String? = null,
) {
    enum class Kind { ROOT, DRAFT, RELEASED, BLOCKING }

    data class Row(
        /** The plan's opaque `node_id` — the value a DRAFT checkbox posts. */
        val nodeId: String,
        val family: String,
        val familyLabel: String,
        val name: String,
        val version: Int,
        /** The plan status word: DRAFT, RELEASED, MISSING or DISCARDED. */
        val status: String,
        val kind: Kind,
        /** A family's own remark on this row (the pipeline's "also pinned by N other draft pipelines"), or null. */
        val note: String? = null,
    ) {
        val label: String get() = "$name@$version"

        /** The row's `data-release-row`: root, draft, released or blocking. */
        val kindWire: String get() = kind.name.lowercase()
        val versionLabel: String get() = "v$version"
    }

    data class PrerequisiteRow(
        val name: String,
        val available: Boolean,
        /** Known only when [available]; null is "unknown" and is never rendered as either word. */
        val readonly: Boolean?,
    ) {
        /** `available` or `unavailable` — the row's `data-status`. */
        val status: String get() = if (available) "available" else "unavailable"

        val text: String
            get() =
                when {
                    !available -> "Datasource $name is not available"
                    readonly == true -> "Datasource $name — available, read-only"
                    readonly == false -> "Datasource $name — available, writable"
                    else -> "Datasource $name — available"
                }
    }

    data class Edge(
        val from: String,
        val to: String,
        val path: String,
    )

    val root: Row? get() = rows.firstOrNull { it.kind == Kind.ROOT }

    /** The rows below the root, in plan order. */
    val dependencies: List<Row> get() = rows.filter { it.kind != Kind.ROOT }

    val drafts: List<Row> get() = rows.filter { it.kind == Kind.DRAFT }

    val blocking: List<Row> get() = rows.filter { it.kind == Kind.BLOCKING }

    val unavailable: List<PrerequisiteRow> get() = prerequisites.filterNot { it.available }

    /**
     * The plan is `complete` AND carries a `tree_hash` AND no node is MISSING or DISCARDED AND every prerequisite is
     * available. A complete closure with a valid hash and one unavailable prerequisite is NOT submittable.
     */
    val submittable: Boolean
        get() = refusal == null && complete && treeHash != null && blocking.isEmpty() && unavailable.isEmpty()

    companion object {
        /**
         * The model of [preview]; [note] adds a family's remark to a node's row. Each prerequisite outcome becomes a text
         * row, whatever the closure holds.
         */
        fun of(
            preview: ReleasePreview,
            note: (ClosureNode) -> String? = { null },
        ): ReleaseDialogModel {
            val plan = preview.plan
            val closure = plan.closure
            return ReleaseDialogModel(
                family = closure.root.family.wire,
                rows = closure.nodes.map { node -> row(node, kindOf(node, isRoot = node.identity == closure.root), note(node)) },
                prerequisites = prerequisiteRows(preview.prerequisites),
                edges = closure.edges.map { Edge(closure.node(it.from).nodeId, closure.node(it.to).nodeId, it.path) },
                treeHash = plan.treeHash,
                complete = plan.complete,
            )
        }

        /** The planner refused to plan [family]'s root: [message] (server-authored, rendered as text) and nothing to submit. */
        fun refused(
            family: ReleaseFamily,
            message: String,
        ): ReleaseDialogModel = ReleaseDialogModel(family.wire, emptyList(), emptyList(), emptyList(), null, false, message)

        /** One text row per prerequisite outcome; an unavailable one's `readonly` stays unknown whatever it carried. */
        private fun prerequisiteRows(prerequisites: List<ReleasePrerequisite>): List<PrerequisiteRow> =
            prerequisites.map { PrerequisiteRow(it.name, it.available, if (it.available) it.readonly else null) }

        private fun kindOf(
            node: ClosureNode,
            isRoot: Boolean,
        ): Kind =
            when {
                isRoot -> Kind.ROOT
                node.status == ReleaseNodeStatus.DRAFT -> Kind.DRAFT
                node.status == ReleaseNodeStatus.RELEASED -> Kind.RELEASED
                else -> Kind.BLOCKING
            }

        private fun row(
            node: ClosureNode,
            kind: Kind,
            note: String?,
        ) = Row(
            nodeId = node.nodeId,
            family = node.identity.family.wire,
            familyLabel = familyLabel(node.identity.family),
            name = node.name,
            version = node.identity.version,
            status = node.status.name,
            kind = kind,
            note = note,
        )

        /** The family words the dependency renderer uses (tree-view.js FAMILY_LABELS). */
        fun familyLabel(family: ReleaseFamily): String =
            when (family) {
                ReleaseFamily.PIPELINE -> "Pipeline"
                ReleaseFamily.TEMPLATE -> "Template"
                ReleaseFamily.PARAMETER_SET -> "Parameter set"
                ReleaseFamily.VISUALIZATION -> "Visualization"
                ReleaseFamily.DASHBOARD -> "Dashboard"
                ReleaseFamily.ENDPOINT -> "API"
            }
    }
}
