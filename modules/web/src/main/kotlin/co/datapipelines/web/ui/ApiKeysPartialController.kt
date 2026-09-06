package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.EndpointKeyService
import co.datapipelines.auth.ApiKeyKind
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
    /** §7.7 — issuance that also writes bindings; the same service `POST /api/v1/auth/api-keys` uses. */
    private val endpointKeys: EndpointKeyService,
) {
    @PostMapping("/partials/api-keys")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_OWN_API_KEYS)
    fun create(
        @RequestParam name: String,
        @RequestParam(required = false) scopes: String?,
        @RequestParam(required = false) expiryDays: Int?,
        // §7.7 — the two endpoint-key fields. Both optional and both absent by default, so the
        // form means exactly what it meant before 074 for every operator who ignores them.
        @RequestParam(required = false) kind: String?,
        @RequestParam(required = false) bindings: String?,
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
        val requestedKind =
            kind?.trim()?.takeIf { it.isNotEmpty() }?.let {
                ApiKeyKind.fromWireOrNull(it)
                    ?: throw IllegalArgumentException("Unknown key kind '$it'")
            } ?: ApiKeyKind.DEFAULT
        val bindingPaths =
            bindings
                ?.split(",")
                ?.mapNotNull { it.trim().takeIf { path -> path.isNotEmpty() } }
                .orEmpty()
        // Through the SAME service the REST surface calls, so the two entry points cannot
        // diverge on what a kind means or on when a binding is written.
        val issued =
            endpointKeys.issue(
                principal = principal,
                name = name,
                scopes = requestedScopes,
                kind = requestedKind,
                bindingPaths = bindingPaths,
                expiresAt = expiresAt,
            )
        val keys = apiKeyRepository.findByUser(principal.userId)
        model.addAttribute("key", issued.plaintext)
        model.addAttribute("keyId", issued.record.id)
        model.addAttribute("keyName", issued.record.name)
        model.addAttribute("keys", keys)
        // The create response refreshes the whole table out-of-band (E2): this flag is
        // what puts hx-swap-oob on the keysTable fragment root for THIS render only.
        model.addAttribute("oob", true)
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
        // The rebuilt rows stay the primary swap; the toast rides along out-of-band
        // (Shape A, §5.1). No HX-Trigger: keyRevoked never had a listener anywhere.
        val html = buildKeyTableRows(keys)
        return ResponseEntity
            .status(HttpStatus.OK)
            .body(html + ToastHtml.oob("success", "API key revoked", "The key can no longer authenticate."))
    }

    private fun buildKeyTableRows(keys: List<co.datapipelines.auth.ApiKey>): String {
        if (keys.isEmpty()) {
            return EMPTY_ROW_HTML
        }
        return keys.joinToString("") { key ->
            val scopeBadges =
                key.scopes.joinToString("") { """<span class="ds-badge ds-badge-default">${it.wire}</span>""" }
            val lastUsed =
                key.lastUsedAt
                    ?.toString()
                    ?.take(TIMESTAMP_TRIM)
                    ?.replace("T", " ") ?: "never"
            val createdAt =
                key.createdAt
                    .toString()
                    .take(TIMESTAMP_TRIM)
                    .replace("T", " ")
            buildKeyRow(key, createdAt, lastUsed, scopeBadges)
        }
    }

    /**
     * One row, byte-for-byte the shape of the template's `keyRows` fragment
     * (settings/api-keys.html): plain `<td>`s — the padding lives in `.ds-table`'s CSS —
     * the revoked marker and scope chips as `ds-badge`s, and the action cell `class="num"`.
     * The 029 treatment of the admin-users builder: a template-rendered row and a
     * revoke-rebuilt row must be indistinguishable in the same table (034 E2). The name
     * is escaped like every other Kotlin-built cell — `th:text` does the same job on the
     * template side.
     */
    private fun buildKeyRow(
        key: co.datapipelines.auth.ApiKey,
        createdAt: String,
        lastUsed: String,
        scopeBadges: String,
    ): String {
        val revokeBtn =
            if (!key.isRevoked) {
                """<button class="ds-button ds-button-ghost ds-button-sm u-danger" """ +
                    """hx-delete="/partials/api-keys/${key.id}" """ +
                    """hx-target="#keys-table-body" hx-confirm="Revoke this key?">Revoke</button>"""
            } else {
                ""
            }
        val revokedBadge = if (key.isRevoked) """ <span class="ds-badge ds-badge-danger">revoked</span>""" else ""

        // §7.7 — an endpoint key behaves nothing like a user key, so the table has to say
        // which it is. Rendered inside the name cell rather than as a new column, so the
        // header is unchanged and this builder stays comparable with the Thymeleaf fragment.
        val kindBadge =
            if (key.isEndpointKey) """ <span class="ds-badge ds-badge-default">endpoint</span>""" else ""
        return """<tr>
          <td><span>${ToastHtml.esc(key.name)}</span>$kindBadge$revokedBadge</td>
          <td>$createdAt</td>
          <td>$lastUsed</td>
          <td>$scopeBadges</td>
          <td class="num">$revokeBtn</td>
        </tr>"""
    }

    private fun requirePrincipal(): AuthenticatedPrincipal =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
            ?: error("No authenticated principal")

    private companion object {
        /** Trims the ISO instant to the template fragment's `yyyy-MM-dd HH:mm` precision. */
        const val TIMESTAMP_TRIM = 16

        /** The template fragment's empty state (`ds-empty`), same markup, for a last-key revoke. */
        const val EMPTY_ROW_HTML =
            """<tr><td colspan="5"><div class="ds-empty">""" +
                """<p class="ds-empty-title">No API keys yet</p>""" +
                """<p class="ds-empty-description">Generate a key to call the REST API.</p>""" +
                """</div></td></tr>"""
    }
}
