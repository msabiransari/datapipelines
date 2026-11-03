package co.datapipelines.auth

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.web.util.matcher.RequestMatcher

/**
 * Matches a logout posted from the app's own page (#782, auth.md §8.4) — the one route whose
 * stale-token refusal must not strand the person: the owner's option (a) is that a logout with a
 * stale or mismatched `dp_csrf` token, posted by a tab that outlived the session's re-mint, still
 * clears `dp_session` and lands on `/login` instead of answering the raw JSON 403.
 *
 * The route's control is the browser's own attestation, not the double-submit: Fetch Metadata
 * (`Sec-Fetch-Site`) is set BY THE BROWSER from the request's actual initiating context, and a
 * hostile cross-site context cannot forge it — a cross-site form post arrives as `cross-site`,
 * and a same-site subdomain attacker as `same-site`; neither matches. Only the app's own origin
 * yields `same-origin`, so the only requests this exempts are ones the person's own page posted,
 * and the session such a request could clear is the one its own `dp_session` already carries —
 * no authority is created. The consequence is intended: this route's logout succeeds on any
 * token state from a same-origin page, which is exactly what a stale tab's Log out must do.
 *
 * A request WITHOUT the header — an old browser, or a non-browser client — does not match and
 * keeps the double-submit as before; so does every other path and verb. This matcher is the
 * CSRF-exemption form of the reasoning the other three matchers share: the exemption follows a
 * credential the hostile context cannot supply, here the browser's own attestation.
 */
class LogoutSameOriginMatcher : RequestMatcher {
    override fun matches(request: HttpServletRequest): Boolean =
        request.method == "POST" &&
            request.appPath() == LOGOUT_PATH &&
            request.getHeader(FETCH_METADATA_HEADER)?.trim()?.equals(SAME_ORIGIN, ignoreCase = true) == true

    private companion object {
        const val LOGOUT_PATH = "/logout"
        const val FETCH_METADATA_HEADER = "Sec-Fetch-Site"
        const val SAME_ORIGIN = "same-origin"
    }
}
