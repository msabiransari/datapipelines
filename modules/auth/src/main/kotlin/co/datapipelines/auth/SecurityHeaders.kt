package co.datapipelines.auth

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.web.header.HeaderWriter
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter
import org.springframework.security.web.header.writers.StaticHeadersWriter
import org.springframework.security.web.util.matcher.RequestMatcher
import java.security.MessageDigest
import java.util.Base64

/**
 * The response headers the app STATES for itself (#188, deployment.md §6.2 / §9) — the
 * values [SecurityConfig]'s `headers { }` block writes on every response, JSON, SSE and
 * HTML alike, and the one place they are spelled.
 *
 * Before 188 nothing here was declared: Spring Security's defaults happened to send
 * `nosniff`, `X-Frame-Options: DENY` and `X-XSS-Protection: 0`, and nothing pinned them —
 * a Spring upgrade could have changed the posture with no test going red. There was no
 * `Content-Security-Policy`, no `Referrer-Policy` and no `Permissions-Policy` at all.
 *
 * ## No `'unsafe-inline'`, no nonce, and — since 195 — no `'unsafe-eval'` anywhere
 * Every script the product runs is a file under `/js` or `/vendor` and every style a file
 * under `/css` or `/vendor`: 188 moved the five executing inline `<script>` blocks and the
 * twenty-one `on*=` handlers the templates carried into the page scripts
 * (`ScriptBlockUtextAuditTest` keeps it that way). A nonce was considered and rejected:
 * with nothing inline left there is nothing for it to permit, an htmx-swapped partial is
 * served under a DIFFERENT response's nonce than the document it lands in, and the static
 * website export (served from S3) has no request to mint one — "a script is a file" is the
 * one rule that holds on every surface. JSON and JSON-LD data blocks are not scripts to
 * CSP (they never execute) and need nothing.
 *
 * ## The one policy, and the retired editor exemption (#195)
 * 188 shipped with one route-scoped exception: `GET /pipelines/{id}/editor` carried
 * `script-src 'self' 'unsafe-eval'` because the STANDARD Alpine build compiles every
 * expression with the `AsyncFunction` constructor — `eval` to CSP (owner ruling
 * 2026-09-21: route-scoped over global). 195 retired it: the editor's page swapped to
 * Alpine's CSP build (`@alpinejs/csp`, the same 3.14.1, vendored at the same path — the
 * vendor-manifest records the tarball), every expression in `pipelines/editor.html` was
 * rewritten as a property path (a getter or no-arg method on the component or a pure
 * module), and THIS object lost `CSP_POLICY_EDITOR` and `isEditorRoute`. One policy
 * covers every policed route; `SecurityHeadersTest` pins the absence of the directive on
 * the editor route too, and the browser suite's zero-violation collector is the live
 * proof the page needs no eval.
 *
 * ## The one stylesheet hash
 * The editor page's Cytoscape 3 injects one `<style>` element at init —
 * [CYTOSCAPE_STYLESHEET], verbatim from the vendored file — so `style-src` admits that
 * ONE sheet by its SHA-256 (a hash source covers a `<style>` element without
 * `'unsafe-hashes'`; nothing else inline is admitted). Since 195 the hash sits in THE
 * policy rather than an editor-only variant: a hash names exactly that sheet and nothing
 * else, so on the routes that never load Cytoscape it permits a style element that never
 * occurs — no route's posture is weakened by carrying it. The test derives the hash from
 * the vendored source, so a Cytoscape bump that changes the sheet goes red rather than
 * blocked.
 *
 * `/sitemap.xml` carries no policy at all: it is an XML data document with no script or
 * style of its own, and the only inline style that ever touches it is the browser's own
 * XML viewer, which a policy can only break.
 *
 * ## What the app deliberately does NOT send
 * No HSTS: the app cannot know it is behind TLS (it sees plain HTTP from the edge), and an
 * HSTS header on a plain-HTTP deployment locks a browser out of it. The edge sets it —
 * deployment.md §6.2 says what the product expects of an edge, and that the edge must not
 * WEAKEN the headers below (a `SAMEORIGIN` override of `X-Frame-Options` is the one
 * documented edge decision; `frame-ancestors 'self'` here says the same thing).
 */
object SecurityHeaders {
    const val CSP_HEADER = "Content-Security-Policy"

    /** `X-Frame-Options` — the product's own stated intent (embedded dashboards on its own origin), not Spring's `DENY`. */
    const val FRAME_OPTIONS = "SAMEORIGIN"

    const val REFERRER_POLICY = "strict-origin-when-cross-origin"

    /** Minimal: the product uses none of these, so no page may ask for them. */
    const val PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=()"

    private const val SCRIPT_SRC_SELF = "script-src 'self'"

    /** The XML data document the browser's own viewer styles — no policy (class KDoc). */
    private const val SITEMAP_PATH = "/sitemap.xml"

    /**
     * The one stylesheet Cytoscape 3 (`static/vendor/cytoscape/cytoscape.min.js`) injects
     * into `<head>` at init, character for character — hashed into `style-src`.
     */
    const val CYTOSCAPE_STYLESHEET = ".__________cytoscape_container { position: relative; }"

    /** `'sha256-<base64>'` of [CYTOSCAPE_STYLESHEET], the CSP hash-source form. */
    val CYTOSCAPE_STYLESHEET_HASH: String = cspHashSource(CYTOSCAPE_STYLESHEET)

    private fun cspHashSource(sheet: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(sheet.toByteArray(Charsets.UTF_8))
        return "'sha256-" + Base64.getEncoder().encodeToString(digest) + "'"
    }

    /**
     * `style-src 'self'` plus [CYTOSCAPE_STYLESHEET_HASH] — the one `<style>` the
     * editor page's Cytoscape injects at init, admitted by hash on every route
     * (class KDoc: on routes that never load Cytoscape it permits nothing that
     * occurs; carrying it keeps ONE policy for every policed route).
     */
    private val STYLE_SRC = "style-src 'self' $CYTOSCAPE_STYLESHEET_HASH"

    /**
     * `img-src` names no external host (#197): the OIDC profile picture
     * (`currentUser.profilePictureUrl`) is proxied through the app's own
     * `GET /avatar` (the stored URL is fetched server-side, from an
     * operator-allowlisted host only), so the browser loads every image from
     * `'self'` or a `data:` icon and a standing `https:` grant — a
     * cross-origin beacon for any user-authored URL that ever reached an
     * `<img src>` — is gone. The picture URL itself never reaches the page.
     */
    private val DIRECTIVES =
        listOf(
            "default-src 'self'",
            SCRIPT_SRC_SELF,
            STYLE_SRC,
            "img-src 'self' data:",
            "font-src 'self'",
            "connect-src 'self'",
            "frame-ancestors 'self'",
            "base-uri 'self'",
            "form-action 'self'",
            "object-src 'none'",
        )

    /** The policy every policed response carries — the one policy (195). */
    val CSP_POLICY: String = DIRECTIVES.joinToString("; ")

    /** True for the one route that gets no policy (the sitemap — class KDoc). */
    fun isUnpolicedRoute(request: HttpServletRequest): Boolean = request.appPath() == SITEMAP_PATH

    /** The CSP header writer, in the order [SecurityConfig] registers them. */
    fun cspWriters(): List<HeaderWriter> {
        val everythingButSitemap = RequestMatcher { !isUnpolicedRoute(it) }
        return listOf(
            DelegatingRequestMatcherHeaderWriter(everythingButSitemap, StaticHeadersWriter(CSP_HEADER, CSP_POLICY)),
        )
    }
}
