package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.pipeline.LifecycleBucket
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.ReadLens

/**
 * The family facts an overview names. Parameter sets' route is `parameter-sets`. [charts] is
 * the inventory card the family carries: since #599 all five are lifecycle homes (#598a/b,
 * #638: the strip, the activity columns and the release history from one chart read).
 *
 * #670 (owner ruling 2026-10-24, option (a)) promoted this enum to the top level out of
 * [OverviewPage], where it was nested as `Family`, so the page model's file stays under the
 * 300-line convention; [OverviewPage] stays the page model, and its nested data classes stay nested.
 */
enum class OverviewFamily(
    val navigation: NavigationFamily,
    val title: String,
    val singular: String,
    val plural: String,
    val offered: List<LifecycleBucket>,
    val guided: Boolean,
    val icon: String,
    val charts: OverviewStacked.ChartSet,
) {
    PIPELINES(
        NavigationFamily.PIPELINES,
        "Pipelines",
        "pipeline",
        "pipelines",
        CatalogLifecycle.PIPELINES,
        guided = true,
        icon = "workflow",
        charts = OverviewStacked.ChartSet.LIFECYCLE,
    ),
    TEMPLATES(
        NavigationFamily.TEMPLATES,
        "Templates",
        "template",
        "templates",
        CatalogLifecycle.TEMPLATES,
        guided = true,
        icon = "code",
        charts = OverviewStacked.ChartSet.LIFECYCLE,
    ),
    DASHBOARDS(
        NavigationFamily.DASHBOARDS,
        "Dashboards",
        "dashboard",
        "dashboards",
        CatalogLifecycle.DASHBOARDS,
        guided = true,
        icon = "boxes",
        charts = OverviewStacked.ChartSet.LIFECYCLE,
    ),
    VISUALIZATIONS(
        NavigationFamily.VISUALIZATIONS,
        "Visualizations",
        "visualization",
        "visualizations",
        CatalogLifecycle.VISUALIZATIONS,
        guided = true,
        icon = "chart-line",
        charts = OverviewStacked.ChartSet.LIFECYCLE,
    ),
    PARAMETER_SETS(
        NavigationFamily.PARAMETER_SETS,
        "Parameter Sets",
        "parameter set",
        "parameter sets",
        CatalogLifecycle.PARAMETER_SETS,
        guided = true,
        icon = "sliders-horizontal",
        charts = OverviewStacked.ChartSet.LIFECYCLE,
    ),
    ;

    val catalogHref: String get() = "/${navigation.route}"

    /** This family's arm of the caller's view — the lens [FamilyInventory] reads it through. */
    fun lens(view: LensedView): ReadLens =
        when (this) {
            PIPELINES -> view.pipelines
            TEMPLATES -> view.templates
            DASHBOARDS -> view.dashboards
            VISUALIZATIONS -> view.visualizations
            PARAMETER_SETS -> view.parameterSets
        }

    /** The catalog filtered to [bucket], or null when this family's catalog does not offer it. */
    fun filtered(bucket: LifecycleBucket): String? = if (bucket in offered) "$catalogHref?lifecycle=${bucket.wire}" else null

    fun count(value: Long): String = "$value ${if (value == 1L) singular else plural}"
}
