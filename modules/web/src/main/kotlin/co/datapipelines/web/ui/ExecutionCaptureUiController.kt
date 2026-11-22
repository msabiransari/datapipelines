package co.datapipelines.web.ui

import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.config.EndpointsProperties
import co.datapipelines.web.endpoints.ExecutionCaptureController.Companion.requireCaptureSession
import co.datapipelines.web.endpoints.ExecutionCaptureService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Duration
import java.util.UUID

/**
 * ui-screens §4.18c/§4.18d — the API console's runtime capture card and the private diagnostic view (#1043). Both are
 * workspace-administrator surfaces under a session-only permission; the card's writes are htmx POSTs carrying the
 * session's CSRF token, and each re-renders the card (a refusal is shown in it, the card's state re-read).
 */
@Controller
class ExecutionCaptureUiController(
    private val captures: ExecutionCaptureService,
    private val properties: EndpointsProperties,
    private val themeResolver: ThemeResolver,
) : ShellPage {
    @GetMapping("/partials/endpoints/{id}/capture")
    @RequiredScope(Permission.EXECUTION_CAPTURE_MANAGE)
    fun card(
        model: Model,
        response: HttpServletResponse,
        @PathVariable id: UUID,
    ): String = render(model, response, id, null)

    @PostMapping("/partials/endpoints/{id}/capture")
    @RequiredScope(Permission.EXECUTION_CAPTURE_MANAGE)
    fun start(
        model: Model,
        response: HttpServletResponse,
        @PathVariable id: UUID,
        @RequestParam(name = "correlation_id", required = false) correlationId: String?,
    ): String {
        val principal = requireCaptureSession()
        val refusal = refusalOf { captures.start(principal, principal.requireWorkspace().id, id, correlationId) }
        return render(model, response, id, refusal)
    }

    @PostMapping("/partials/endpoints/{id}/capture/{captureId}/stop")
    @RequiredScope(Permission.EXECUTION_CAPTURE_MANAGE)
    fun stop(
        model: Model,
        response: HttpServletResponse,
        @PathVariable id: UUID,
        @PathVariable captureId: UUID,
    ): String {
        val principal = requireCaptureSession()
        val refusal = refusalOf { captures.stop(principal, principal.requireWorkspace().id, id, captureId) }
        return render(model, response, id, refusal)
    }

    @GetMapping("/executions/{id}/capture")
    @RequiredScope(Permission.EXECUTION_CAPTURE_READ)
    fun detail(
        model: Model,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @PathVariable id: UUID,
    ): String {
        val principal = requireCaptureSession()
        // Refusals (404 never captured / other workspace, 410 expired) surface through the global handlers.
        val detail = captures.inspect(principal, principal.requireWorkspace().id, id)
        noStore(response)
        model.addAttribute("detail", detail)
        RoleModel.stamp(model, principal)
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        return "executions/capture"
    }

    private fun render(
        model: Model,
        response: HttpServletResponse,
        id: UUID,
        refusal: String?,
    ): String {
        val principal = requireCaptureSession()
        val overview = captures.overview(principal.requireWorkspace().id, id)
        noStore(response)
        model.addAttribute("cap", overview)
        model.addAttribute("captureError", refusal)
        model.addAttribute("remaining", overview.active?.let { remainingText(Duration.between(overview.now, it.deadlineAt)) })
        model.addAttribute("windowMinutes", Duration.ofSeconds(properties.captureWindowSeconds.toLong()).toMinutes().coerceAtLeast(1))
        model.addAttribute("retentionHours", properties.captureDetailRetentionHours)
        return "partials/endpoint-capture :: card"
    }

    private fun refusalOf(action: () -> Unit): String? =
        try {
            action()
            null
        } catch (e: DatapipelinesException) {
            // A catalogued refusal (409 already active, 400 malformed filter, 404) is shown in the re-rendered card.
            e.message
        }

    private companion object {
        fun noStore(response: HttpServletResponse) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().headerValue)
        }

        /** "14 min" / "45 s" — the console's remaining-time label. */
        fun remainingText(left: Duration): String =
            if (left.toMinutes() >= 1) "${left.toMinutes()} min" else "${left.seconds.coerceAtLeast(0)} s"
    }
}
