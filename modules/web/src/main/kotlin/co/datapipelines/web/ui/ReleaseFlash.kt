package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.TemplateRef
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.servlet.HandlerMapping
import java.util.UUID

/**
 * #407 — the workspace release flash's ONE-SHOT cascade names.
 *
 * When a release cascades (142), its toast names the templates it released with it, as the
 * explorer's Shape A toast used to before #401 retired the pane. Spring's `FlashMap` does
 * not bind to the `HX-Redirect` 200 the dialog's release POST answers (the redirect is
 * client-side htmx navigation, not a 302), so the POST holds the names HERE — derived from
 * [PipelineReleaseService.Released.templatesReleased], the same server facts the audit
 * records — and the next GET of the SAME pipeline carrying `ok=released_with_templates`
 * renders them once ([ReleaseFlashAdvice] consumes). Everything else — a crafted `?ok=`,
 * another pipeline's page, another actor, a minute gone by — renders the layout's generic
 * sentence and nothing else.
 *
 * The held value is keyed to the actor (`userId`) and the pipeline (`pipelineId`), expires
 * after [TTL_MS], and is consumed on first read: nothing lingers in the session to render
 * later, and no template name ever travels in the redirect URL or any request parameter.
 * The message is built here, server-side, from the held list; the template renders it
 * through `partials/toast`'s `th:text`.
 */
class ReleaseFlash {
    /** What one cascading release held, until the next matching GET consumes it. */
    data class Held(
        val pipelineId: UUID,
        val actorId: UUID,
        val atEpochMs: Long,
        val templates: List<String>,
    )

    /**
     * Holds [templates] for [actorId]'s session, bound to [pipelineId]. A release that
     * cascaded nothing holds nothing (the flash stays the generic sentence). A second
     * release replaces whatever was held — the newest release is the one the flash names.
     */
    fun hold(
        session: HttpSession,
        pipelineId: UUID,
        actorId: UUID,
        templates: List<TemplateRef>,
    ) {
        if (templates.isEmpty()) return
        session.setAttribute(
            SESSION_KEY,
            Held(pipelineId, actorId, System.currentTimeMillis(), templates.map { it.key }),
        )
    }

    /**
     * Consumes the held entry and builds the toast sentence — `null` when nothing qualifies:
     * nothing held, the minute spent, another actor's session, or a request whose path does
     * not name the pipeline the release was made on. Consumed even when it does not
     * qualify: the FIRST look at the code eats the entry, so nothing survives to render on
     * a later page.
     */
    fun consumeMessage(
        request: HttpServletRequest,
        session: HttpSession,
    ): String? {
        val held = session.getAttribute(SESSION_KEY) as? Held ?: return null
        session.removeAttribute(SESSION_KEY)
        if (System.currentTimeMillis() - held.atEpochMs > TTL_MS) return null
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
        if (principal == null || principal.userId != held.actorId) return null
        val pathId = (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<*, *>)?.get("id")?.toString()
        if (pathId != held.pipelineId.toString()) return null
        return MESSAGE_PREFIX + held.templates.joinToString(", ") + "."
    }

    companion object {
        /** The session attribute's one key; a release replaces whatever it held. */
        const val SESSION_KEY = "dp.pipeline.release.flash"

        /** The held names' lifetime — a flash is about the release that JUST happened. */
        const val TTL_MS = 60_000L

        /**
         * The sentence's prefix — the wording the explorer's toast used ("Also released:
         * `id@version`, …"), kept; the template list is appended, closed with a full stop.
         */
        const val MESSAGE_PREFIX = "Released. Also released: "
    }
}
