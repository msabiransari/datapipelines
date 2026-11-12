package co.datapipelines.web.ui

import co.datapipelines.web.ui.ReleaseDialogModel.Kind

/**
 * #876 §F.2 — hand-built [ReleaseDialogModel]s for the release dialogs' RENDER tests (no planner, no database): a
 * root row, the dependency rows a case needs, and the prerequisite rows. The model's own rules (which status is which
 * kind, when it is submittable) are pinned by `ReleaseDialogModelTest` over a real `ReleasePreview`; here only the
 * markup is under test.
 */
internal object ReleaseDialogFixtures {
    const val TREE_HASH = "th-render-1"

    fun root(
        family: String,
        name: String,
        version: Int,
    ) = row(Kind.ROOT, family, name, version, "DRAFT")

    fun draft(
        family: String,
        name: String,
        version: Int,
        note: String? = null,
    ) = row(Kind.DRAFT, family, name, version, "DRAFT", note)

    fun released(
        family: String,
        name: String,
        version: Int,
    ) = row(Kind.RELEASED, family, name, version, "RELEASED")

    fun blocking(
        family: String,
        name: String,
        version: Int,
        status: String = "MISSING",
    ) = row(Kind.BLOCKING, family, name, version, status)

    /** A plan of [root] and [dependencies], each dependency an edge from the root; complete, hashed. */
    fun plan(
        root: ReleaseDialogModel.Row,
        vararg dependencies: ReleaseDialogModel.Row,
        prerequisites: List<ReleaseDialogModel.PrerequisiteRow> = emptyList(),
    ): ReleaseDialogModel =
        ReleaseDialogModel(
            family = root.family,
            rows = listOf(root) + dependencies,
            prerequisites = prerequisites,
            edges = dependencies.mapIndexed { i, dep -> ReleaseDialogModel.Edge(root.nodeId, dep.nodeId, "/pins/$i") },
            treeHash = TREE_HASH,
            complete = true,
        )

    private fun row(
        kind: Kind,
        family: String,
        name: String,
        version: Int,
        status: String,
        note: String? = null,
    ) = ReleaseDialogModel.Row(
        nodeId = "n-$family-${name.replace('/', '-')}-$version",
        family = family,
        familyLabel = FAMILY_LABELS.getValue(family),
        name = name,
        version = version,
        status = status,
        kind = kind,
        note = note,
    )

    private val FAMILY_LABELS =
        mapOf(
            "pipeline" to "Pipeline",
            "template" to "Template",
            "parameter_set" to "Parameter set",
            "visualization" to "Visualization",
            "dashboard" to "Dashboard",
        )
}
