package co.datapipelines.web.ui

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/**
 * #407 — feeds the layout's ok-code block the release flash's names, consumed ONCE.
 *
 * The layout's flash bin (§4.13) renders `?ok=` codes into toasts on every screen; only
 * `released_with_templates` can name the cascaded templates, and only from [ReleaseFlash]'s
 * one-shot, session-held list — read off THIS request's `ok` code, never off any parameter
 * that could carry text. A request without the code pays one map lookup; a request with the
 * code and nothing (or nothing qualifying) held gets `null` and the layout renders the
 * generic sentence.
 */
@ControllerAdvice(annotations = [Controller::class])
class ReleaseFlashAdvice(
    private val flash: ReleaseFlash,
) {
    @ModelAttribute("releasedFlashMessage")
    fun releasedFlashMessage(request: HttpServletRequest): String? {
        if (request.parameterMap["ok"]?.firstOrNull() != CODE) return null
        val session = request.getSession(false) ?: return null
        return flash.consumeMessage(request, session)
    }

    companion object {
        /** The one ok code the names ride behind — the release POST's own redirect target. */
        const val CODE = "released_with_templates"
    }
}
