package co.datapipelines.web.navigation

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.NavigationRequest
import co.datapipelines.pipeline.NavigationRow
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.templates.TemplateService
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.currentPrincipal
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** Literal first-party read surfaces; permission and credential admission retain the existing matrix. */
@RestController
@Suppress("LongParameterList", "TooManyFunctions") // five sources, two literal permission-bearing handlers per source
class TreeController(
    private val pipelines: PipelineService,
    private val templates: TemplateService,
    private val dashboards: DashboardService,
    private val visualizations: VisualizationService,
    private val parameters: ParameterSetService,
    private val lens: PromoterLens,
) {
    private val projection = TreeProjection()

    /** Every immediate folder and leaf, bounded only per transport page. */
    @GetMapping("/api/v1/pipelines/tree")
    @RequiredScope(Permission.PIPELINE_READ)
    fun pipelinesTree(
        @RequestParam(defaultValue = "") root: String,
        @RequestParam(required = false) parent: String?,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<ApiResponse<TreePage>> = answer(NavigationFamily.PIPELINES, root, parent ?: root, null, cursor)

    /** Every literal name match with its ancestors; description is deliberately excluded. */
    @GetMapping("/api/v1/pipelines/tree/search")
    @RequiredScope(Permission.PIPELINE_READ)
    fun pipelinesSearch(
        @RequestParam(defaultValue = "") root: String,
        @RequestParam q: String,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<ApiResponse<TreePage>> = answer(NavigationFamily.PIPELINES, root, root, q, cursor)

    /** Every immediate folder and leaf, bounded only per transport page. */
    @GetMapping("/api/v1/templates/tree")
    @RequiredScope(Permission.TEMPLATE_READ)
    fun templatesTree(
        @RequestParam(defaultValue = "") root: String,
        @RequestParam(required = false) parent: String?,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<ApiResponse<TreePage>> = answer(NavigationFamily.TEMPLATES, root, parent ?: root, null, cursor)

    /** Every literal name match with its ancestors; description is deliberately excluded. */
    @GetMapping("/api/v1/templates/tree/search")
    @RequiredScope(Permission.TEMPLATE_READ)
    fun templatesSearch(
        @RequestParam(defaultValue = "") root: String,
        @RequestParam q: String,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<ApiResponse<TreePage>> = answer(NavigationFamily.TEMPLATES, root, root, q, cursor)

    /** Every immediate folder and leaf, bounded only per transport page. */
    @GetMapping("/api/v1/dashboards/tree")
    @RequiredScope(Permission.DASHBOARD_READ)
    fun dashboardsTree(
        @RequestParam(defaultValue = "") root: String,
        @RequestParam(required = false) parent: String?,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<ApiResponse<TreePage>> = answer(NavigationFamily.DASHBOARDS, root, parent ?: root, null, cursor)

    /** Every literal name match with its ancestors; description is deliberately excluded. */
    @GetMapping("/api/v1/dashboards/tree/search")
    @RequiredScope(Permission.DASHBOARD_READ)
    fun dashboardsSearch(
        @RequestParam(defaultValue = "") root: String,
        @RequestParam q: String,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<ApiResponse<TreePage>> = answer(NavigationFamily.DASHBOARDS, root, root, q, cursor)

    /** Every immediate folder and leaf, bounded only per transport page. */
    @GetMapping("/api/v1/visualizations/tree")
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun visualizationsTree(
        @RequestParam(defaultValue = "") root: String,
        @RequestParam(required = false) parent: String?,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<ApiResponse<TreePage>> = answer(NavigationFamily.VISUALIZATIONS, root, parent ?: root, null, cursor)

    /** Every literal name match with its ancestors; description is deliberately excluded. */
    @GetMapping("/api/v1/visualizations/tree/search")
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun visualizationsSearch(
        @RequestParam(defaultValue = "") root: String,
        @RequestParam q: String,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<ApiResponse<TreePage>> = answer(NavigationFamily.VISUALIZATIONS, root, root, q, cursor)

    /** Every immediate folder and leaf, bounded only per transport page. */
    @GetMapping("/api/v1/parameter-sets/tree")
    @RequiredScope(Permission.PARAMETER_SET_READ)
    fun parameterSetsTree(
        @RequestParam(defaultValue = "") root: String,
        @RequestParam(required = false) parent: String?,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<ApiResponse<TreePage>> = answer(NavigationFamily.PARAMETER_SETS, root, parent ?: root, null, cursor)

    /** Every literal name match with its ancestors; description is deliberately excluded. */
    @GetMapping("/api/v1/parameter-sets/tree/search")
    @RequiredScope(Permission.PARAMETER_SET_READ)
    fun parameterSetsSearch(
        @RequestParam(defaultValue = "") root: String,
        @RequestParam q: String,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<ApiResponse<TreePage>> = answer(NavigationFamily.PARAMETER_SETS, root, root, q, cursor)

    private fun answer(
        family: NavigationFamily,
        root: String,
        parent: String,
        query: String?,
        cursor: String?,
    ): ResponseEntity<ApiResponse<TreePage>> {
        val principal = currentPrincipal()
        if (principal.authMethod != AuthMethod.OIDC) {
            throw DatapipelinesException(PipelineErrorCodes.Auth.SESSION_REQUIRED, "Navigation requires a signed-in session")
        }
        val workspace = principal.requireWorkspace().id
        val view = lens.viewFor(principal)
        if (view.unavailable != null) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Navigation view is unavailable; retry")
        val admitted =
            when (family) {
                NavigationFamily.PIPELINES -> view.pipelines
                NavigationFamily.TEMPLATES -> view.templates
                NavigationFamily.DASHBOARDS -> view.dashboards
                NavigationFamily.VISUALIZATIONS -> view.visualizations
                NavigationFamily.PARAMETER_SETS -> view.parameterSets
            }
        val load: (NavigationRequest) -> List<NavigationRow> =
            when (family) {
                NavigationFamily.PIPELINES -> pipelines::navigation
                NavigationFamily.TEMPLATES -> templates::navigation
                NavigationFamily.DASHBOARDS -> dashboards::navigation
                NavigationFamily.VISUALIZATIONS -> visualizations::navigation
                NavigationFamily.PARAMETER_SETS -> parameters::navigation
            }
        val page = projection.page(family, workspace, principal.userId, admitted, root, parent, query, cursor, load)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.of(page))
    }
}
