package co.datapipelines.web.ui

import co.datapipelines.web.ui.site.ADVISORY_URL
import co.datapipelines.web.ui.site.CONTACT_EMAIL
import co.datapipelines.web.ui.site.DISCUSSIONS_URL
import co.datapipelines.web.ui.site.GITHUB_STARS
import co.datapipelines.web.ui.site.RELEASE_STAGE
import co.datapipelines.web.ui.site.RELEASE_STAGE_CTA
import co.datapipelines.web.ui.site.REPORT_PROBLEM_URL
import co.datapipelines.web.ui.site.REPO_URL
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

    // 127 — the beta line (hero and site footer) and the "Report a problem" bug-form URL
    // (the avatar menu, the docs index header) ride the same advice, so site chrome and
    // app chrome read one constant and no template types a URL.
    @ModelAttribute("releaseStage")
    fun releaseStage(): String = RELEASE_STAGE

    @ModelAttribute("releaseStageCta")
    fun releaseStageCta(): String = RELEASE_STAGE_CTA

    @ModelAttribute("reportProblemUrl")
    fun reportProblemUrl(): String = REPORT_PROBLEM_URL

    // 145 — the site footer's source, support and private vulnerability-report links render
    // from the same constants (SitePages), never typed into the layout.
    @ModelAttribute("repoUrl")
    fun repoUrl(): String = REPO_URL

    @ModelAttribute("discussionsUrl")
    fun discussionsUrl(): String = DISCUSSIONS_URL

    @ModelAttribute("advisoryUrl")
    fun advisoryUrl(): String = ADVISORY_URL
}
