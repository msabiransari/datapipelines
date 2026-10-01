package co.datapipelines.auth

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.server.PathContainer
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.util.pattern.PathPatternParser

/**
 * Matches the visualization test screenshot upload, for the CSRF exemption (#353; auth.md §8.4/§8.6) — the
 * [PromotionRouteMatcher] reasoning, on the second route whose credential is a header and never a cookie.
 *
 * The route's ONE credential is the single-use upload capability in `DP-Upload-Token` (the owner's ruling (b)):
 * a request header no cookie-bearing browser context can forge for another origin, verified by the handler against
 * the run it names. No cookie authenticates anything there — the handler reads no principal and its `PublicPaths`
 * entry makes the route ungoverned — so there is no cookie-authenticated request for the double-submit to protect,
 * and without this exemption Spring's `CsrfFilter` would refuse every agent's upload (a Playwright request carries
 * no `dp_csrf` pair). Matched by method and the exact segment shape the `PublicPaths` entry permits; any other
 * route, and any other verb on this one, keeps the double-submit.
 */
class UploadCapabilityRouteMatcher : RequestMatcher {
    override fun matches(request: HttpServletRequest): Boolean =
        request.method == "POST" && PATTERN.matches(PathContainer.parsePath(request.appPath()))

    private companion object {
        /** The `PublicPaths` entry's pattern — one place spells it; this matcher reads it from there. */
        val PATTERN =
            PathPatternParser.defaultInstance.parse(
                PublicPaths.ENTRIES.single { it.since == "353" && it.pattern.endsWith("/screenshot") }.pattern,
            )
    }
}
