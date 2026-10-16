package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetRepository
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
            InventoryResult.Unavailable("Inventory could not be read.")
        }
    }
}
