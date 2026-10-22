package co.datapipelines.web.ui

import jakarta.servlet.http.HttpServletRequest
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
 *
 * Selection rule (#652): `assignableTypes = [ShellPage::class]` — a `@RestController`
 * never implements [ShellPage], so this advice does not run for JSON handlers at all.
 *
 * The one-shot consumption, spelled out: [releasedFlashMessage] returns null unless the
 * request carries `?ok=released_with_templates`; only then does [ReleaseFlash.consumeMessage]
 * run — it reads the held names AND REMOVES the session attribute first, and only then
 * checks the pipeline id. So a JSON request without the parameter never consumed anything,
 * and one crafted WITH the parameter would have eaten the next shell render's flash. Since
 * #652 neither is reachable: JSON handlers never reach this advice.
 */
@ControllerAdvice(assignableTypes = [ShellPage::class])
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
