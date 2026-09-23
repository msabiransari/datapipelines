package co.datapipelines.web.ui

import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
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
     * copy fetch already makes. A POST would buy no CSRF cover the session's SameSite cookie
     * and the owner-scope do not already give.
     */
    @GetMapping("/partials/mcp-key/secret", produces = [MediaType.TEXT_PLAIN_VALUE])
    @RequiredScope(ScopeMatrix.RestOperation.VIEW_OWN_MCP_KEY)
    @ResponseBody
    fun secret(): ResponseEntity<String> {
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
    @RequiredScope(ScopeMatrix.RestOperation.VIEW_OWN_MCP_KEY)
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
    @RequiredScope(ScopeMatrix.RestOperation.VIEW_OWN_MCP_KEY)
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
}
