package co.datapipelines.web.ui

import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.datasources.DatasourceDialectCounts
import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.web.api.currentPrincipal
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import java.time.Instant
import java.util.UUID

/**
 * #583 — the Datasources family overview (ui-screens.md §4.25): the page the Datasources menu
 * item lands on, in the shell with the datasources browser open.
 *
 * It lives outside [OverviewController] on purpose: the five lifecycle families read their
 * numbers through [FamilyInventory]/[FamilyRecent] under the caller's promoter lens, and a
 * datasource has no lens — its inventory is the workspace's VISIBLE SET (the grant predicate
 * [DatasourceRepository.findAllVisible] applies), read here from the datasources module's
 * repository and built into the same generalized [OverviewPage] frame (one panel, many charts;
 * one recent card, many sections). "Recently added to this workspace" is the GRANT's time —
 * when the datasource became this workspace's — not anyone's modification history.
 *
 * Every number is one SQL snapshot per card ([DatasourceRepository.visibleCounts],
 * [DatasourceRepository.recentGrants]); a failed read says so and shows no number, never a
 * zero (the FamilyInventory rule). Nothing here writes; the §7.6 row is `datasource.read`, the
 * lowest operation whose row admits a metadata read (`ReadFloorTest`).
 */
@Controller
class DatasourceOverviewController(
    private val repository: DatasourceRepository,
    private val themeResolver: ThemeResolver,
) {
    private val log = LoggerFactory.getLogger(DatasourceOverviewController::class.java)

    @GetMapping(PATH)
    @RequiredScope(Permission.DATASOURCE_READ)
    fun overview(
        model: Model,
        request: HttpServletRequest,
    ): String {
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        RoleModel.stamp(model)
        val workspaceId = currentPrincipal().requireWorkspace().id
        val counts = readCounts(workspaceId)
        val now = Instant.now()
        model.addAttribute(
            OverviewController.MODEL,
            OverviewPage(
                family = "datasources",
                title = "Datasources",
                catalogHref = "/datasources",
                lensed = false,
                inventory = counts.panel,
                unavailable = counts.unavailable,
                recent = recentSection(workspaceId, now),
                guided = true,
            ),
        )
        return OverviewController.VIEW
    }

    /** The panel from one counts read: the total tile, connection health, by dialect — or why neither. */
    @Suppress("TooGenericExceptionCaught") // the read boundary FamilyInventory draws
    private fun readCounts(workspaceId: UUID): CountsRead {
        val counts =
            try {
                repository.visibleCounts(workspaceId)
            } catch (failure: Exception) {
                log.warn("datasource overview counts unavailable for workspace {}: {}", workspaceId, failure.toString())
                return CountsRead(null, "Inventory could not be read.")
            }
        val panel =
            OverviewPage.InventoryPanel(
                counts = listOf(totalTile(counts)),
                charts =
                    listOf(
                        healthChart(counts),
                        dialectChart(counts),
                    ),
            )
        return CountsRead(panel, null)
    }

    /** The one tile: every datasource the workspace can see, however it was granted. */
    private fun totalTile(counts: List<DatasourceDialectCounts>): OverviewPage.Count =
        OverviewPage.Count(
            key = "datasources",
            label = "Datasources",
            value = counts.sumOf { it.total },
            help = "Granted to this workspace.",
            href = null,
        )

    /** The last recorded test per datasource — one outcome each, whoever ran it; the buckets never hide a zero. */
    private fun healthChart(counts: List<DatasourceDialectCounts>): OverviewPage.Chart {
        val chart =
            OverviewPage.Chart(
                key = "health",
                caption = "Last connection test",
                bars =
                    listOf(
                        bar("ok", "Connection ok", counts.sumOf { it.testedOk }, "released"),
                        bar("failed", "Connection failed", counts.sumOf { it.testedFailed }, "discarded"),
                        bar("never", "Never tested", counts.sumOf { it.neverTested }, "draft"),
                    ),
                note = "Each datasource carries its latest recorded test — one per datasource, whoever ran it.",
            )
        return chart.withWidths()
    }

    /** One bar per dialect the workspace can see, each filtering the catalog (`/datasources?dialect=`). */
    private fun dialectChart(counts: List<DatasourceDialectCounts>): OverviewPage.Chart {
        val bars =
            counts.map { row ->
                bar(row.dialect.wire, row.dialect.wire, row.total, "mixed", "/datasources?dialect=${row.dialect.wire}")
            }
        val chart =
            OverviewPage.Chart(
                key = "dialect",
                caption = "By dialect",
                bars = bars,
                headings = listOf("Dialect", "Datasources"),
            )
        return chart.withWidths()
    }

    private fun bar(
        key: String,
        name: String,
        value: Long,
        tone: String,
        href: String? = null,
    ): OverviewPage.Bar =
        OverviewPage.Bar(
            key = key,
            name = name,
            value = value,
            width = "0",
            tone = tone,
            label = "$name: $value",
            href = href,
        )

    /** Bar widths are shares of the chart's own tallest bar; an empty or all-zero chart is all zero-width. */
    private fun OverviewPage.Chart.withWidths(): OverviewPage.Chart {
        val tallest = bars.maxOfOrNull { it.value } ?: 0L
        return copy(bars = bars.map { it.copy(width = OverviewPage.width(it.value, tallest)) })
    }

    /** Continue your work's one section: the newest grants to THIS workspace, linking each datasource. */
    @Suppress("TooGenericExceptionCaught") // the read boundary FamilyRecent draws
    private fun recentSection(
        workspaceId: UUID,
        now: Instant,
    ): OverviewPage.RecentPanel =
        try {
            OverviewPage.RecentPanel(
                review = null,
                sections =
                    listOf(
                        OverviewPage.RecentSection(
                            heading = "Recently added to this workspace",
                            note = "Newest first.",
                            entries =
                                repository.recentGrants(workspaceId).map { grant ->
                                    OverviewPage.RecentItem(
                                        name = grant.name,
                                        href = "/datasources/${grant.name}",
                                        datetime = grant.grantedAt.toString(),
                                        ago = RelativeTime.since(grant.grantedAt, now),
                                        absolute = RelativeTime.absolute(grant.grantedAt),
                                        hasDraft = false,
                                    )
                                },
                            unavailable = null,
                            emptyNote = "No datasources have been added to this workspace yet.",
                        ),
                    ),
            )
        } catch (failure: Exception) {
            log.warn("datasource overview recent list unavailable for workspace {}: {}", workspaceId, failure.toString())
            OverviewPage.RecentPanel(
                review = null,
                sections =
                    listOf(
                        OverviewPage.RecentSection(
                            heading = "Recently added to this workspace",
                            note = "Newest first.",
                            entries = null,
                            unavailable = "Recently added could not be read.",
                            emptyNote = null,
                        ),
                    ),
            )
        }

    /** The counts read's two outcomes: the built panel, or the reason it is missing. */
    private data class CountsRead(
        val panel: OverviewPage.InventoryPanel?,
        val unavailable: String?,
    )

    companion object {
        /** The overview path — also the layout's path rule for opening the datasources browser. */
        const val PATH = "/overview/datasources"
    }
}
