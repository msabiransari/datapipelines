package co.datapipelines.auth

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.web.header.HeaderWriter
import org.springframework.security.web.header.writers.CacheControlHeadersWriter
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
 * ## The cache headers, and the one path that does not get them (#1023)
 * The framework's cache writer (`Cache-Control: no-cache, no-store, max-age=0, must-revalidate`, `Pragma: no-cache`,
 * `Expires: 0`) stays on every answer — pages, API answers, partials, errors, every static file — except a font file:
 * a request the web module's font handler answers, under [FONT_PATH_PREFIX]. The owner's ruling of 2026-11-16 lets a
 * browser keep a font file for one year, and the handler states that rule in its own `Cache-Control`. That is not
 * enough on its own: [SecurityConfig] writes these headers EAGERLY (before the handler runs, so an SSE worker cannot
 * race the container's header map), so the writer's own skip ("the answer already has a `Cache-Control`") never sees
 * the handler's value, and its `Pragma: no-cache` stays beside it. Chromium reads `Pragma: no-cache` as
 * `Cache-Control: no-cache` (its `immutable` override is behind a feature that is off by default), so it re-checked
 * every font on every page load. Hence [cacheControlWriter]: the same framework writer, behind a matcher that is
 * false on a font file and true everywhere else.
 *
 * Why a font file may lose them: the files are public, the same for every visitor, carry no user data, and never
 * change under a name already served (the web module's `FontPathLedgerTest`). The matcher is the risk: one wider than
 * the font handler would make another answer storable. It is the handler's own path, by the chain's own path rule
 * (the path inside the application, after the firewall refused encoded and dot-segment forms): a GET or HEAD of the
 * prefix followed by a name that is not a directory. A path that only starts with similar text keeps all three headers;
 * `SecurityHeadersTest` pins both sides. A name under the prefix that does not exist is answered 404 by the web
 * module's not-found envelope with none of the three (the same answer for every caller, no user data;
 * `StaticCacheHeadersE2eTest` pins it).
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

    /**
     * The font files' path inside the application (#1023, class KDoc): the ONE spelling, which the web module's font
     * handler also maps and serves (`StaticAssetCacheConfig`), so the chain's matcher and the handler cannot drift.
     */
    const val FONT_PATH_PREFIX = "/vendor/fonts/"

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

    /** The methods a resource handler answers; any other method on a font path is refused, and keeps the headers. */
    private val FONT_FILE_METHODS = setOf("GET", "HEAD")

    /**
     * True for a request the font handler answers: a GET or HEAD of [FONT_PATH_PREFIX] followed by a name that is not a
     * directory (a path ending in a slash). `/vendor/fonts` itself, the prefix's own directory, a longer segment, the
     * prefix under another path and any other method are false.
     */
    fun isFontFile(request: HttpServletRequest): Boolean {
        val path = request.appPath()
        return request.method in FONT_FILE_METHODS &&
            path.startsWith(FONT_PATH_PREFIX) &&
            path.length > FONT_PATH_PREFIX.length &&
            !path.endsWith("/")
    }

    /**
     * The chain's cache writer: the framework's own (it writes the three headers unless the answer already has one of
     * them, or is a 304), on every request except a font file (class KDoc).
     */
    fun cacheControlWriter(): HeaderWriter =
        DelegatingRequestMatcherHeaderWriter(RequestMatcher { !isFontFile(it) }, CacheControlHeadersWriter())

    /** The CSP header writer, in the order [SecurityConfig] registers them. */
    fun cspWriters(): List<HeaderWriter> {
        val everythingButSitemap = RequestMatcher { !isUnpolicedRoute(it) }
        return listOf(
            DelegatingRequestMatcherHeaderWriter(everythingButSitemap, StaticHeadersWriter(CSP_HEADER, CSP_POLICY)),
        )
    }
}
