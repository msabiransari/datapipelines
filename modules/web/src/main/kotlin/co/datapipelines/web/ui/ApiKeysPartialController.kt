package co.datapipelines.web.ui

import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

/**
 * The ONE-TIME read of a key's sealed plaintext (auth.md §7.4, #213 show-once) — keys v2
 * edition. The login mint and the top-bar chip it served are retired (A15): what remains is
 * the show-once mechanism itself, now serving the KEYS PAGE.
 *
 * No new key is ever minted with a sealed copy — a key created on the page or over REST
 * returns its plaintext in the create response exactly once (§7.4). The sealed copies still
 * in flight are the login-minted keys the V37 migration converted (keys v2 A2): their
 * creators may read the copy once, from the key's row on the Keys page, and the key is
 * hash-only from then on.
 *
 * The secret is served by [secret] and NOWHERE ELSE: it is fetched on the copy click
 * (shell.js), never rendered into a page. The serve is ONE-SHOT: the open destroys the
 * sealed copy in the same statement, so a second click answers 404 — and a key that never
 * carried one always answers 404.
 */
@Controller
class ApiKeysPartialController(
    private val apiKeyService: ApiKeyService,
) {
    /**
     * The key [keyId]'s plaintext, as `text/plain` for the copy handler — the caller must be
     * its CREATOR. 404 when there is nothing copyable: not the creator's key, no sealed copy,
     * or one whose copy was already read (#213: the first successful GET is the last). A 404
     * discloses nothing: the caller is asking about a key the server answers only to its
     * creator, and every other case looks the same.
     *
     * A GET with a side effect, deliberately: the open AND the clear are one SQL statement
     * behind it (creator-scoped, idempotent-after-first-call — a retried GET is a 404, never a
     * second reveal), the response is `no-store`, and the verb is what the page's existing
     * copy fetch already makes.
     *
     * **Fetch-metadata guard (7a/213 merge review).** `dp_session` is `SameSite=Lax`, and Lax
     * cookies ride a cross-site top-level GET navigation — so without this guard a hostile page
     * could navigate a signed-in user here and DESTROY their one copy (it could never read the
     * body; the harm is a forced rotation). A browser stamps every request with
     * `Sec-Fetch-Site` / `Sec-Fetch-Mode`: the page's copy fetch is `same-origin` + `cors`, a
     * cross-site link or `window.open` is `cross-site` + `navigate`, the address bar is `none`
     * + `navigate`. Anything that is not a same-origin, non-navigation request is refused 403
     * BEFORE the open, so the copy survives it. A client that sends neither header is not a
     * browser and cannot carry the user's session cookie from someone else's page.
     */
    @GetMapping("/partials/mcp-key/secret", produces = [MediaType.TEXT_PLAIN_VALUE])
    @RequiredScope(Permission.MCP_KEY_OWN)
    @ResponseBody
    fun secret(
        @RequestParam("key") keyId: String,
        @RequestHeader(name = SEC_FETCH_SITE, required = false) fetchSite: String? = null,
        @RequestHeader(name = SEC_FETCH_MODE, required = false) fetchMode: String? = null,
    ): ResponseEntity<String> {
        if (!isSameOriginFetch(fetchSite, fetchMode)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        val principal = requirePrincipal()
        if (principal.workspace == null) return ResponseEntity.notFound().build<String>()
        val plaintext = apiKeyService.openSealedSecret(keyId, principal.userId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity
            .ok()
            // The one response in the product whose body IS a credential: never cacheable,
            // anywhere along the way.
            .header("Cache-Control", "no-store")
            .body(plaintext)
    }

    private fun requirePrincipal(): AuthenticatedPrincipal =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
            ?: error("No authenticated principal")

    private companion object {
        const val SEC_FETCH_SITE = "Sec-Fetch-Site"
        const val SEC_FETCH_MODE = "Sec-Fetch-Mode"

        /**
         * True when the request may open the one-shot secret: no fetch metadata at all (not a
         * browser), or a same-origin request that is not a navigation.
         */
        fun isSameOriginFetch(
            fetchSite: String?,
            fetchMode: String?,
        ): Boolean {
            val crossSite = fetchSite != null && !fetchSite.equals("same-origin", ignoreCase = true)
            val navigation = fetchMode != null && fetchMode.equals("navigate", ignoreCase = true)
            return !crossSite && !navigation
        }
    }
}
