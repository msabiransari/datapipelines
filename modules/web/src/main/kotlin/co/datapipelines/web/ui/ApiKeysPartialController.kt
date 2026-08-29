package co.datapipelines.web.ui

import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Instant
import java.time.temporal.ChronoUnit

@Controller
class ApiKeysPartialController(
    private val apiKeyService: ApiKeyService,
    private val apiKeyRepository: ApiKeyRepository,
) {
    @PostMapping("/partials/api-keys")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_OWN_API_KEYS)
    fun create(
        @RequestParam name: String,
        @RequestParam(required = false) scopes: String?,
        @RequestParam(required = false) expiryDays: Int?,
        model: Model,
    ): String {
        val principal = requirePrincipal()
        val requestedScopes =
            scopes
                ?.split(",")
                ?.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
                ?.map { Scope.fromWire(it) }
                ?.toSet()
                .orEmpty()
        val expiresAt =
            if (expiryDays != null && expiryDays > 0) {
                Instant.now().plus(expiryDays.toLong(), ChronoUnit.DAYS)
            } else {
                null
            }
        val issued =
            apiKeyService.issue(
                ownerId = principal.userId,
                name = name,
                scopes = requestedScopes,
                creatorScopes = principal.scopes,
                workspaceId = principal.requireWorkspace().id,
                expiresAt = expiresAt,
            )
        val keys = apiKeyRepository.findByUser(principal.userId)
        model.addAttribute("key", issued.plaintext)
        model.addAttribute("keyId", issued.record.id)
        model.addAttribute("keyName", issued.record.name)
        model.addAttribute("keys", keys)
        return "partials/api-key-created"
    }

    @DeleteMapping("/partials/api-keys/{keyId}")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_OWN_API_KEYS)
    fun revoke(
        @PathVariable keyId: String,
    ): ResponseEntity<String> {
        val principal = requirePrincipal()
        apiKeyService.revoke(keyId, principal.userId)
        val keys = apiKeyRepository.findByUser(principal.userId)
        val html = buildKeyTableRows(keys)
        return ResponseEntity
            .status(HttpStatus.OK)
            .header("HX-Trigger", "keyRevoked")
            .body(html)
    }

    private fun buildKeyTableRows(keys: List<co.datapipelines.auth.ApiKey>): String {
        if (keys.isEmpty()) {
            return EMPTY_ROW_HTML
        }
        return keys.joinToString("") { key ->
            val scopes = key.scopes.joinToString(", ") { it.wire }
            val lastUsed =
                key.lastUsedAt
                    ?.toString()
                    ?.take(TIMESTAMP_TRIM)
                    ?.replace("T", " ") ?: "never"
            val revoked = if (key.isRevoked) " (revoked)" else ""
            val createdAt =
                key.createdAt
                    .toString()
                    .take(TIMESTAMP_TRIM)
                    .replace("T", " ")
            buildKeyRow(key, createdAt, lastUsed, scopes, revoked)
        }
    }

    private fun buildKeyRow(
        key: co.datapipelines.auth.ApiKey,
        createdAt: String,
        lastUsed: String,
        scopes: String,
        revoked: String,
    ): String {
        val revokeBtn =
            if (!key.isRevoked) {
                """<button class="ds-button ds-button-ghost ds-button-sm" """ +
                    """hx-delete="/partials/api-keys/${key.id}" """ +
                    """hx-target="#keys-table-body" hx-confirm="Revoke this key?" """ +
                    """style="color:var(--accent-danger)">Revoke</button>"""
            } else {
                ""
            }
        return """<tr>
          <td style="padding:var(--gap-xs);font-family:var(--font-mono)">${key.name}$revoked</td>
          <td style="padding:var(--gap-xs);color:var(--text-secondary)">$createdAt</td>
          <td style="padding:var(--gap-xs);color:var(--text-secondary)">$lastUsed</td>
          <td style="padding:var(--gap-xs)">$scopes</td>
          <td style="padding:var(--gap-xs)">$revokeBtn</td>
        </tr>"""
    }

    private fun requirePrincipal(): AuthenticatedPrincipal =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
            ?: error("No authenticated principal")

    private companion object {
        const val TIMESTAMP_TRIM = 19
        const val EMPTY_ROW_HTML =
            """<tr><td colspan="5" style="padding:var(--gap-md);""" +
                """text-align:center;color:var(--text-secondary);font-size:var(--text-sm)">""" +
                "No API keys found</td></tr>"
    }
}
