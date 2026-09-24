package co.datapipelines.web.ui

import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseBody

/**
 * The caller's own MCP key — the login-minted `user` key for the ACTIVE workspace (D16,
 * 179) — as the two partial surfaces the top bar drives.
 *
 * This controller used to mint and revoke keys of every kind for the API console. R3 moved
 * minting to the login hook (`user` keys) and to the admin `/api-keys` page (`endpoint`
 * keys), and what is left HERE is deliberately narrow: show the secret once per click, and
 * delete-to-rotate. Both are `VIEW_OWN_MCP_KEY` — every role, own key only, the workspace
 * resolved from the request context so no payload chooses a target.
 *
 * The secret is served by [secret] and NOWHERE ELSE: it is fetched on the copy click
 * (shell.js), never rendered into a page. Since #213 the serve is ONE-SHOT: the open destroys
 * the sealed copy in the same statement, so a second click answers 404 — and a key minted
 * before R3 never carried one. The bar's copy button is gone for both (the chip swaps itself
 * out through [chip] after a successful copy) and the prefix says "delete and sign in again"
 * instead.
 */
@Controller
class ApiKeysPartialController(
    private val apiKeyService: ApiKeyService,
    private val apiKeyRepository: ApiKeyRepository,
) {
    /**
     * The caller's own MCP key's plaintext, as `text/plain` for the copy handler. 404 when
     * there is nothing copyable — no live key in the active workspace, or one whose copy was
     * already read (#213: the first successful GET is the last). A 404 discloses nothing here
     * that the chip did not already show: the caller is the key's owner, asking about their
     * own credential.
     *
     * A GET with a side effect, deliberately: the open AND the clear are one SQL statement
     * behind it (owner-scoped, idempotent-after-first-call — a retried GET is a 404, never a
     * second reveal), the response is `no-store`, and the verb is what the chip's existing
     * copy fetch already makes.
     *
     * **Fetch-metadata guard (7a/213 merge review).** `dp_session` is `SameSite=Lax`, and Lax
     * cookies ride a cross-site top-level GET navigation — so without this guard a hostile page
     * could navigate a signed-in user here and DESTROY their one copy (it could never read the
     * body; the harm is a forced rotation). A browser stamps every request with
     * `Sec-Fetch-Site` / `Sec-Fetch-Mode`: the chip's copy fetch is `same-origin` + `cors`, a
     * cross-site link or `window.open` is `cross-site` + `navigate`, the address bar is `none`
     * + `navigate`. Anything that is not a same-origin, non-navigation request is refused 403
     * BEFORE the open, so the copy survives it. A client that sends neither header is not a
     * browser and cannot carry the user's session cookie from someone else's page.
     */
    @GetMapping("/partials/mcp-key/secret", produces = [MediaType.TEXT_PLAIN_VALUE])
    @RequiredScope(Permission.MCP_KEY_OWN)
    @ResponseBody
    fun secret(
        @RequestHeader(name = SEC_FETCH_SITE, required = false) fetchSite: String? = null,
        @RequestHeader(name = SEC_FETCH_MODE, required = false) fetchMode: String? = null,
    ): ResponseEntity<String> {
        if (!isSameOriginFetch(fetchSite, fetchMode)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        val principal = requirePrincipal()
        val workspace = principal.workspace ?: return ResponseEntity.notFound().build()
        val plaintext = apiKeyService.openOwnMcpKey(principal.userId, workspace.id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity
            .ok()
            // The one response in the product whose body IS a credential: never cacheable,
            // anywhere along the way.
            .header("Cache-Control", "no-store")
            .body(plaintext)
    }

    /**
     * The chip re-rendered from the server's CURRENT state (#213): after a successful copy the
     * sealed secret is gone, so the swapped chip comes back with `copyable = false` and no Copy
     * button — the server, not the click handler, decides when the chip stops offering the key.
     * The copy fetch drives this through `htmx.ajax` (shell.js), the same fragment and target
     * delete-to-rotate swaps.
     */
    @GetMapping("/partials/mcp-key/chip")
    @RequiredScope(Permission.MCP_KEY_OWN)
    fun chip(model: Model): String {
        val principal = requirePrincipal()
        val key = principal.workspace?.let { apiKeyRepository.findLiveUserKey(principal.userId, it.id) }
        model.addAttribute("mcpKey", key?.let(McpKeyChip::of))
        RoleModel.stamp(model, principal)
        return "partials/mcp-key-chip"
    }

    /**
     * Delete-to-rotate (D16): revokes the caller's own MCP key in the active workspace; the
     * next login or switch mints a fresh one. The response re-renders the top-bar chip,
     * which now shows the "no key — sign in again" state.
     */
    @DeleteMapping("/partials/mcp-key")
    @RequiredScope(Permission.MCP_KEY_OWN)
    fun rotate(model: Model): String {
        val principal = requirePrincipal()
        principal.workspace?.let { workspace ->
            apiKeyRepository.findLiveUserKey(principal.userId, workspace.id)?.let { key ->
                apiKeyService.revoke(key.id, principal.userId)
            }
        }
        model.addAttribute("mcpKey", null)
        RoleModel.stamp(model, principal)
        return "partials/mcp-key-chip"
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
