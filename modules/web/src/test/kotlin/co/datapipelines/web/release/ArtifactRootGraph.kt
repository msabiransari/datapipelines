package co.datapipelines.web.release

import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleasePlanRequest

/**
 * The four artifact roots of #873 §C over one [CoordinatorFixture], each with the DRAFT dependencies a plan selects:
 *
 * - parameter set `test/sets/regions` → DRAFT selector template `test/set_source.sql`;
 * - visualization `test/charts/revenue` → DRAFT transform template `test/chart_transform.sql`;
 * - dashboard `test/boards/revenue` → DRAFT source pipeline `test/source` and DRAFT visualization `test/charts/board`,
 *   whose transform template `test/board_transform.sql` is RELEASED;
 * - template `test/root.sql` → DRAFT import `test/lib.sql`.
 */
internal class ArtifactRootGraph(
    val fx: CoordinatorFixture,
) {
    val setTemplate = fx.template("test/set_source.sql")
    val set = fx.artifact(ReleaseFamily.PARAMETER_SET, "test/sets/regions", listOf(setTemplate))
    val chartTemplate = fx.template("test/chart_transform.sql")
    val chart = fx.artifact(ReleaseFamily.VISUALIZATION, "test/charts/revenue", listOf(chartTemplate))
    val boardTemplate = fx.template("test/board_transform.sql", status = ReleaseNodeStatus.RELEASED)
    val boardChart = fx.artifact(ReleaseFamily.VISUALIZATION, "test/charts/board", listOf(boardTemplate))
    val source = fx.pipeline("test/source")
    val board = fx.artifact(ReleaseFamily.DASHBOARD, "test/boards/revenue", listOf(source, boardChart))
    val library = fx.template("test/lib.sql")
    val template = fx.template("test/root.sql", imports = listOf(library))

    fun root(family: ReleaseFamily): ReleaseNodeIdentity =
        when (family) {
            ReleaseFamily.PARAMETER_SET -> set
            ReleaseFamily.VISUALIZATION -> chart
            ReleaseFamily.DASHBOARD -> board
            ReleaseFamily.TEMPLATE -> template
            ReleaseFamily.PIPELINE, ReleaseFamily.ENDPOINT -> error("$family is not an artifact root")
        }

    /** The DRAFT dependencies of [family]'s root, dependency-first — exactly what its plan must select. */
    fun dependencies(family: ReleaseFamily): List<ReleaseNodeIdentity> =
        when (family) {
            ReleaseFamily.PARAMETER_SET -> listOf(setTemplate)
            ReleaseFamily.VISUALIZATION -> listOf(chartTemplate)
            ReleaseFamily.DASHBOARD -> listOf(source, boardChart)
            ReleaseFamily.TEMPLATE -> listOf(library)
            ReleaseFamily.PIPELINE, ReleaseFamily.ENDPOINT -> error("$family is not an artifact root")
        }

    /** The plan a client holding the preview posts: the tree hash and every DRAFT dependency selected. */
    fun plan(family: ReleaseFamily): ReleasePlanRequest {
        val preview = fx.preview(root(family))
        return ReleasePlanRequest(preview.treeHash, preview.ids(*dependencies(family).toTypedArray()))
    }

    /** The root's planned release request. */
    fun planned(family: ReleaseFamily): RootReleaseRequest = fx.rootRequest(root(family), plan(family))
}
