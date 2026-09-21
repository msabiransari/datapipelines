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
 * ## The policy has no `'unsafe-inline'`, no nonce and no `'unsafe-eval'`
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
 * ## The one exception: the pipeline editor's `'unsafe-eval'` and one stylesheet hash
 * `pipelines/editor.html` is the only page that loads Alpine.js, and Alpine 3.14.1 compiles
 * every `x-*` expression with the `AsyncFunction` constructor — `eval` to CSP. Porting the
 * editor's 131 expressions to the CSP build of Alpine is its own lane (#195); until then
 * the route [isEditorRoute] names alone carries `script-src 'self' 'unsafe-eval'` (owner ruling 2026-09-21:
 * route-scoped exemption over a global relaxation), pinned by `SecurityHeadersTest`.
 * The same page's Cytoscape 3 injects one `<style>` element at init —
 * [CYTOSCAPE_STYLESHEET], verbatim from the vendored file — so the editor's `style-src`
 * admits that ONE sheet by its SHA-256 (a hash source covers a `<style>` element without
 * `'unsafe-hashes'`; nothing else inline is admitted). The test derives the hash from the
 * vendored source, so a Cytoscape bump that changes the sheet goes red rather than blocked.
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

    /**
     * The route that carries [CSP_POLICY_EDITOR] — the pipeline editor's full-document GET
     * (`PipelineEditorController`), matched on its exact three-segment shape so no other
     * route under `/pipelines` inherits the relaxation. Class-level KDoc says why.
     */
    private val EDITOR_PATH = Regex("^/pipelines/[^/]+/editor$")

    private const val SCRIPT_SRC_SELF = "script-src 'self'"
    private const val STYLE_SRC_SELF = "style-src 'self'"

    /** The XML data document the browser's own viewer styles — no policy (class KDoc). */
    private const val SITEMAP_PATH = "/sitemap.xml"

    /**
     * The one stylesheet Cytoscape 3 (`static/vendor/cytoscape/cytoscape.min.js`) injects
     * into `<head>` at init, character for character — hashed into the editor's `style-src`.
     */
    const val CYTOSCAPE_STYLESHEET = ".__________cytoscape_container { position: relative; }"

    /** `'sha256-<base64>'` of [CYTOSCAPE_STYLESHEET], the CSP hash-source form. */
    val CYTOSCAPE_STYLESHEET_HASH: String = cspHashSource(CYTOSCAPE_STYLESHEET)

    private fun cspHashSource(sheet: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(sheet.toByteArray(Charsets.UTF_8))
        return "'sha256-" + Base64.getEncoder().encodeToString(digest) + "'"
    }

    /**
     * `img-src` admits `https:` for one reason: the OIDC profile picture
     * (`currentUser.profilePictureUrl`, an identity provider's host) — everything else the
     * product renders is `'self'` or a `data:` icon.
     */
    private val DIRECTIVES =
        listOf(
            "default-src 'self'",
            SCRIPT_SRC_SELF,
            STYLE_SRC_SELF,
            "img-src 'self' data: https:",
            "font-src 'self'",
            "connect-src 'self'",
            "frame-ancestors 'self'",
            "base-uri 'self'",
            "form-action 'self'",
            "object-src 'none'",
        )

    /** The policy every response carries but the editor's. */
    val CSP_POLICY: String = DIRECTIVES.joinToString("; ")

    /**
     * [CSP_POLICY] with `'unsafe-eval'` on `script-src` and Cytoscape's sheet hash on
     * `style-src` — nothing else differs. See the class KDoc.
     */
    val CSP_POLICY_EDITOR: String =
        DIRECTIVES.joinToString("; ") {
            when (it) {
                SCRIPT_SRC_SELF -> "$it 'unsafe-eval'"
                STYLE_SRC_SELF -> "$it $CYTOSCAPE_STYLESHEET_HASH"
                else -> it
            }
        }

    /** True for the one route that gets [CSP_POLICY_EDITOR]. */
    fun isEditorRoute(request: HttpServletRequest): Boolean = EDITOR_PATH.matches(request.appPath())

    /** True for the one route that gets no policy (the sitemap — class KDoc). */
    fun isUnpolicedRoute(request: HttpServletRequest): Boolean = request.appPath() == SITEMAP_PATH

    /** The CSP header writers, in the order [SecurityConfig] registers them. */
    fun cspWriters(): List<HeaderWriter> {
        val editor = RequestMatcher { isEditorRoute(it) }
        val everythingElse = RequestMatcher { !isEditorRoute(it) && !isUnpolicedRoute(it) }
        return listOf(
            DelegatingRequestMatcherHeaderWriter(editor, StaticHeadersWriter(CSP_HEADER, CSP_POLICY_EDITOR)),
            DelegatingRequestMatcherHeaderWriter(everythingElse, StaticHeadersWriter(CSP_HEADER, CSP_POLICY)),
        )
    }
}
