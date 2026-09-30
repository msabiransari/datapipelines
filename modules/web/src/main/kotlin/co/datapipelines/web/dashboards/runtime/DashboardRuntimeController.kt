package co.datapipelines.web.dashboards.runtime

import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.PagedData
import co.datapipelines.web.api.currentPrincipal
import com.fasterxml.jackson.databind.node.ObjectNode
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

/**
 * The dashboard RUNTIME and refresh routes (rest-api.md §23; the implementation spec's §6.2, §8) — six handlers, every
 * one `dashboard.execute` (D50: that one row authorizes the delegated act; abort adds OWN or `execution.cancel_all`,
 * and reading others' refreshes `execution.read_all`, judged in [DashboardRuntime]). Nothing here is public.
 *
 * ## Thin
 * Each handler reads its body whole (a malformed body is the family's 400 before any lookup), passes the principal and
 * the ids to [DashboardRuntime], and shapes the answer. The controller names no repository: every read goes through
 * the runtime, which reads through the caller's lens (a hidden dashboard is the family's 404).
 *
 * ## The stream
 * `POST …/runtime/visualizations` produces the execution stream's framing (`event:`, `id:`, `data:`, heartbeats).
 * `produces` lists `application/json` beside `text/event-stream` (gate C, B6) so a pre-stream refusal — the 429 with
 * its `Retry-After`, a 409, a 400 — renders the §4.2 envelope for a client that sent only `Accept: text/event-stream`.
 *
 * ## Roles (roles design §4.9)
 * viewer ✓ · author ✓ · promoter ✓ through the LENS · workspace admin ✓ · super admin ✓ · `api_caller` and
 * `promotion_receiver` ✗; no MCP placement — no tool executes a dashboard until the `dashboard` key kind (L5).
 */
@RestController
@RequestMapping("/api/v1/dashboards")
class DashboardRuntimeController(
    private val runtime: DashboardRuntime,
) {
    /** §8.1 — the runtime configuration the client boots from, pinned by its `configuration_id`. */
    @GetMapping("/{id}/runtime/config")
    @RequiredScope(Permission.DASHBOARD_EXECUTE)
    fun config(
        @PathVariable id: UUID,
    ): ApiResponse<ObjectNode> = ApiResponse.of(runtime.config(currentPrincipal(), id))

    /** §8.2 — evaluate the pinned set against the submitted selections (every parameter, hidden and disabled included). */
    @PostMapping("/{id}/runtime/parameters")
    @RequiredScope(Permission.DASHBOARD_EXECUTE)
    fun parameters(
        @PathVariable id: UUID,
        @RequestBody body: String,
    ): ApiResponse<ObjectNode> {
        val request = RuntimeRequests.parameters(body)
        return ApiResponse.of(runtime.parameters(currentPrincipal(), id, request))
    }

    /** §8.3 — one refresh, streamed. Admission is decided BEFORE the stream opens: a full instance is a plain 429 with `Retry-After`. */
    @PostMapping(
        "/{id}/runtime/visualizations",
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE],
    )
    @RequiredScope(Permission.DASHBOARD_EXECUTE)
    fun refresh(
        @PathVariable id: UUID,
        @RequestBody body: String,
        response: HttpServletResponse,
    ): SseEmitter {
        val request = RuntimeRequests.refresh(body)
        try {
            return runtime.startRefresh(currentPrincipal(), id, request)
        } catch (e: DatapipelinesException) {
            if (e.code ==
                DashboardErrorCodes.REFRESH_SATURATED
            ) {
                response.setHeader(HttpHeaders.RETRY_AFTER, runtime.retryAfterSeconds().toString())
            }
            throw e
        }
    }

    /** §8.4 — abort a RUNNING refresh; answers 202 without waiting. */
    @PostMapping("/{id}/runtime/refreshes/{refresh_id}/abort")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiredScope(Permission.DASHBOARD_EXECUTE)
    fun abort(
        @PathVariable id: UUID,
        @PathVariable("refresh_id") refreshId: UUID,
        @RequestBody body: String,
    ): ApiResponse<Map<String, Any?>> {
        val request = RuntimeRequests.abort(body)
        runtime.abort(currentPrincipal(), id, refreshId, request)
        return ApiResponse.of(mapOf("refresh_id" to refreshId.toString(), "status" to "abort_requested"))
    }

    /** The caller's own refreshes of the dashboard, newest first — every refresh with `execution.read_all`. */
    @GetMapping("/{id}/refreshes")
    @RequiredScope(Permission.DASHBOARD_EXECUTE)
    fun list(
        @PathVariable id: UUID,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<PagedData<ObjectNode>> = ApiResponse.of(runtime.listRefreshes(currentPrincipal(), id, offset, limit))

    @GetMapping("/{id}/refreshes/{refresh_id}")
    @RequiredScope(Permission.DASHBOARD_EXECUTE)
    fun get(
        @PathVariable id: UUID,
        @PathVariable("refresh_id") refreshId: UUID,
    ): ApiResponse<ObjectNode> = ApiResponse.of(runtime.getRefresh(currentPrincipal(), id, refreshId))
}
