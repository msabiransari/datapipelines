package co.datapipelines.web.dependencies

import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.currentPrincipal
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The eight session-only dependency tree routes (#875 G4; record §11.1, erratum (8)(a): the family is a LITERAL path
 * segment — no `{family}` variable, no ninth route). Each declares ITS family's READ permission, checked by
 * `ScopeInterceptor` before the handler like every other route (key kinds are refused there: these routes are
 * session-only). Every handler is one line: the query is read raw ([DependenciesRequest.parse] owns the §11.1 rules).
 * The answer is never cached (`Cache-Control: no-store`): it is a read of live metadata for one member.
 */
@RestController
class DependenciesController(
    private val trees: DependencyTreeService,
) {
    @GetMapping("/api/v1/dependencies/pipeline")
    @RequiredScope(Permission.PIPELINE_READ)
    fun pipeline(request: HttpServletRequest) = answer(FamilyKey.PIPELINE, request)

    @GetMapping("/api/v1/dependencies/template")
    @RequiredScope(Permission.TEMPLATE_READ)
    fun template(request: HttpServletRequest) = answer(FamilyKey.TEMPLATE, request)

    @GetMapping("/api/v1/dependencies/parameter_set")
    @RequiredScope(Permission.PARAMETER_SET_READ)
    fun parameterSet(request: HttpServletRequest) = answer(FamilyKey.PARAMETER_SET, request)

    @GetMapping("/api/v1/dependencies/visualization")
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun visualization(request: HttpServletRequest) = answer(FamilyKey.VISUALIZATION, request)

    @GetMapping("/api/v1/dependencies/dashboard")
    @RequiredScope(Permission.DASHBOARD_READ)
    fun dashboard(request: HttpServletRequest) = answer(FamilyKey.DASHBOARD, request)

    @GetMapping("/api/v1/dependencies/api_endpoint")
    @RequiredScope(Permission.ENDPOINT_READ)
    fun apiEndpoint(request: HttpServletRequest) = answer(FamilyKey.API_ENDPOINT, request)

    @GetMapping("/api/v1/dependencies/schedule")
    @RequiredScope(Permission.SCHEDULE_READ)
    fun schedule(request: HttpServletRequest) = answer(FamilyKey.SCHEDULE, request)

    @GetMapping("/api/v1/dependencies/datasource")
    @RequiredScope(Permission.DATASOURCE_READ)
    fun datasource(request: HttpServletRequest) = answer(FamilyKey.DATASOURCE, request)

    private fun answer(
        family: FamilyKey,
        request: HttpServletRequest,
    ): ResponseEntity<ApiResponse<DependencyTreeData>> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.of(trees.tree(currentPrincipal(), family, request.queryString)))
}
