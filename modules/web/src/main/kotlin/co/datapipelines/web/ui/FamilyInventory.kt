package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.ChartInventory
import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.visualization.DashboardRepository
import co.datapipelines.visualization.VisualizationRepository
import org.slf4j.LoggerFactory
import java.util.UUID

/** Unknown inventory is distinct from a successfully read empty workspace. */
sealed interface InventoryResult {
    data class Available(
        val inventory: Inventory,
    ) : InventoryResult

    data class Unavailable(
        val reason: String,
    ) : InventoryResult
}

/** #598a — the overview's chart group, or why it is unknown; an unknown chart is never drawn as zeros. */
sealed interface ChartsResult {
    data class Available(
        val charts: ChartInventory,
    ) : ChartsResult

    data class Unavailable(
        val reason: String,
    ) : ChartsResult
}

/**
 * Injectable family inventory for #556's overview handlers, which resolve the principal's [LensedView].
 *
 * Buckets use admitted versions: draft only (DRAFT, no RELEASED), released only (RELEASED, no DRAFT),
 * released with draft changes (both), discarded only (all DISCARDED). The last bucket deliberately
 * widens the unrestricted live catalog's lifecycle predicate, as specified by shell design §8.
 * It has no catalog drill-down today. Narrowed lenses admit only releases; hidden drafts and
 * discarded versions never affect a count. Each call reads one SQL snapshot and retains no cache.
 */
class FamilyInventory(
    private val pipelines: PipelineRepository,
    private val templates: TemplateRepository,
    private val dashboards: DashboardRepository,
    private val visualizations: VisualizationRepository,
    private val parameterSets: ParameterSetRepository,
) {
    private val log = LoggerFactory.getLogger(FamilyInventory::class.java)

    /** Fails closed before reading when the lens is unavailable; read failures never become zeros. */
    @Suppress("TooGenericExceptionCaught") // this read boundary also contains failures deriving a lazy lens
    fun forFamily(
        family: NavigationFamily,
        workspaceId: UUID,
        view: LensedView,
    ): InventoryResult {
        view.unavailable?.let { return InventoryResult.Unavailable(it.reason) }
        return try {
            val inventory =
                when (family) {
                    NavigationFamily.PIPELINES -> pipelines.inventory(workspaceId, view.pipelines)
                    NavigationFamily.TEMPLATES -> templates.inventory(workspaceId, view.templates)
                    NavigationFamily.DASHBOARDS -> dashboards.inventory(workspaceId, view.dashboards)
                    NavigationFamily.VISUALIZATIONS -> visualizations.inventory(workspaceId, view.visualizations)
                    NavigationFamily.PARAMETER_SETS -> parameterSets.inventory(workspaceId, view.parameterSets)
                }
            InventoryResult.Available(inventory)
        } catch (failure: Exception) {
            log.warn("inventory unavailable for workspace {} family {}: {}", workspaceId, family, failure.toString())
            InventoryResult.Unavailable(UNREADABLE)
        }
    }

    /**
     * #598a — the lifecycle and maturity charts from ONE statement, under the same rules as
     * [forFamily]: an unavailable lens fails closed before any read, and a read failure (a lazy
     * lens's included) is [ChartsResult.Unavailable], never zeros. Only Pipelines builds these
     * charts this round; any other family answers [NOT_CHARTED] without a read and nothing calls
     * it for one (`OverviewPage.Family.charts`), until #599 wires the four siblings' repositories.
     */
    @Suppress("TooGenericExceptionCaught") // the same read boundary as forFamily's
    fun chartsFor(
        family: NavigationFamily,
        workspaceId: UUID,
        view: LensedView,
    ): ChartsResult {
        view.unavailable?.let { return ChartsResult.Unavailable(it.reason) }
        if (family != NavigationFamily.PIPELINES) return ChartsResult.Unavailable(NOT_CHARTED)
        return try {
            ChartsResult.Available(pipelines.charts(workspaceId, view.pipelines))
        } catch (failure: Exception) {
            log.warn("charts unavailable for workspace {} family {}: {}", workspaceId, family, failure.toString())
            ChartsResult.Unavailable(UNREADABLE)
        }
    }

    companion object {
        /** The one reason a failed read gives — the same words for the counts and the charts. */
        const val UNREADABLE = "Inventory could not be read."
        const val NOT_CHARTED = "These charts are not built for this family yet."
    }
}
