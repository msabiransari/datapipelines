package co.datapipelines.web.ui

import co.datapipelines.web.ui.site.CONTACT_EMAIL
import co.datapipelines.web.ui.site.GITHUB_STARS
import co.datapipelines.web.ui.site.isPublicOrigin
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/**
 * 119 §C.1 — one model attribute for every controller: **is this request being served on
 * the public site's own origin?** The nav's last item is `/login` on every deployment, but
 * its LABEL is a fact about which deployment is serving it: on `datapipelines.co` a
 * visitor is invited to *try the live demo*; on a customer's own host the same route is
 * how their people sign in. The decision is the canonical tag's (one origin is the
 * published site) applied to a label — and it lives HERE, beside the other per-request
 * advice, so no site controller can forget it and the label cannot disagree with the
 * host it is served on.
 *
 * The offline render (`SitePageRenderer`) bypasses Spring MVC entirely, so it computes
 * the same attribute from its mock request's server name — the static export passes the
 * public origin's host, which is how the exported site reads "Try the live demo".
 */
@ControllerAdvice(annotations = [Controller::class])
class SiteOriginAdvice {
    @ModelAttribute("publicOrigin")
    fun publicOrigin(request: HttpServletRequest): Boolean = isPublicOrigin(request.serverName)

    // The header badge and the footer's Contact line render on every page that uses the
    // site chrome — including the public docs views, whose controller never calls
    // PublicPage.render — so the constants ride here beside the origin decision.
    @ModelAttribute("githubStars")
    fun githubStars(): Int = GITHUB_STARS

    @ModelAttribute("contactEmail")
    fun contactEmail(): String = CONTACT_EMAIL
}
