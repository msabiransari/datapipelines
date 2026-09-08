package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.EndpointKeyService
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Instant

/**
 * Minting and revoking keys from the API screen (ui-screens.md §4.18, auth.md §7.4/§7.7).
 *
 * ## One service, one row model, one fragment
 *
 * Issuance goes through [EndpointKeyService] — the same call `POST /api/v1/auth/api-keys` makes —
 * so the two entry points cannot diverge on what a kind means or on when a binding is written.
 * Both responses render the SAME Thymeleaf fragments the page renders (`api/console :: keysTable`
 * and `:: keyRows`) from the SAME [ApiKeyRows] model. Before 091 the revoke path hand-built its
 * rows in Kotlin and a parity test kept the two markups "byte-for-byte" alike; deleting the
 * builder deletes that whole class of drift.
 *
 * ## The form's own validation is server-side, always
 *
 * The select elements are rendered from [ApiKeyForm], but nothing here trusts them: the kind,
 * the scope, the expiry and the bindings are all re-resolved against the same source, and every
 * refusal is a catalogued code that [UiExceptionHandler] turns into a §5.1 Shape C toast. A
 * hand-crafted POST gets exactly the answer the form's user would.
 */
@Controller
class ApiKeysPartialController(
    private val apiKeyService: ApiKeyService,
    private val apiKeyRepository: ApiKeyRepository,
    /** §7.7 — issuance that also writes bindings; the same service the REST surface calls. */
    private val endpointKeys: EndpointKeyService,
    private val keyRows: ApiKeyRows,
) {
    /**
     * The form, in the owner's order: Kind → Scope → Name → Expiry → Bindings. The parameters
     * are listed in that order too, so the signature reads as the screen does.
     *
     * `scope` is SINGULAR: scopes are hierarchical (§7.5), so one choice names the whole set a
     * key needs, and a multi-select would let an operator build `{read, admin}`, which is just
     * `admin` written confusingly. The REST surface still takes the list — it has callers that
     * send one.
     */
    @Suppress("LongParameterList") // one parameter per form field; the alternative is a DTO for one call site
    @PostMapping("/partials/api-keys")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_OWN_API_KEYS)
    fun create(
        @RequestParam(required = false) kind: String?,
        @RequestParam(required = false) scope: String?,
        @RequestParam name: String,
        @RequestParam(required = false) expiry: String?,
        @RequestParam(required = false) expiryDate: String?,
        @RequestParam(required = false) bindings: List<String>?,
        model: Model,
    ): String {
        val principal = requirePrincipal()
        val requestedKind =
            kind?.trim()?.takeIf { it.isNotEmpty() }?.let {
                ApiKeyKind.fromWireOrNull(it) ?: throw unknownKind(it)
            } ?: ApiKeyKind.DEFAULT
        // A scope on a scopeless kind is REFUSED, not dropped — by the service, which owns that
        // rule for every surface. Here we only avoid manufacturing one: the form disables the
        // select for those kinds, and a stale value left in a resubmitted form must not become
        // a refusal the user cannot see the cause of.
        val requestedScopes =
            if (requestedKind in ApiKeyKind.SCOPELESS) {
                emptySet()
            } else {
                scope
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { setOf(parseScope(it)) }
                    .orEmpty()
            }
        val expiresAt = ApiKeyForm.resolveExpiry(expiry, expiryDate, Instant.now())
        val bindingPaths =
            bindings
                .orEmpty()
                .flatMap { it.split(',') }
                .mapNotNull { it.trim().takeIf { path -> path.isNotEmpty() } }

        val issued =
            endpointKeys.issue(
                principal = principal,
                name = name.trim(),
                scopes = requestedScopes,
                kind = requestedKind,
                bindingPaths = bindingPaths,
                expiresAt = expiresAt,
            )

        model.addAttribute("key", issued.plaintext)
        model.addAttribute("keyId", issued.record.id)
        model.addAttribute("keyName", issued.record.name)
        model.addAttribute("keyKind", issued.record.kind.wire)
        model.addAttribute(
            "keyScopes",
            issued.record.scopes
                .map { it.wire }
                .sorted(),
        )
        model.addAttribute("keyBindings", bindingPaths)
        model.addAttribute("keyExpires", expiresAt?.let { RelativeTime.absolute(it) })
        model.addAttribute("keys", rows(principal))
        // The create response refreshes the whole table out-of-band (E2): this flag is
        // what puts hx-swap-oob on the keysTable fragment root for THIS render only.
        model.addAttribute("oob", true)
        return "partials/api-key-created"
    }

    @DeleteMapping("/partials/api-keys/{keyId}")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_OWN_API_KEYS)
    fun revoke(
        @PathVariable keyId: String,
        model: Model,
    ): String {
        val principal = requirePrincipal()
        // Owner-scoped in SQL; a key id that is not the caller's revokes nothing and still
        // answers 200, so the response discloses nothing about another user's key ids.
        apiKeyService.revoke(keyId, principal.userId)
        model.addAttribute("keys", rows(principal))
        return "partials/api-keys-rows"
    }

    private fun rows(principal: AuthenticatedPrincipal) =
        keyRows.of(
            apiKeyRepository.findByUser(principal.userId),
            Instant.now(),
        )

    /**
     * One scope wire token, or the same refusal the REST surface gives. Not `Scope.fromWire`
     * directly: its `IllegalArgumentException` would reach the user as a 500, and an unknown
     * value in a select is a 400-shaped problem.
     */
    private fun parseScope(raw: String): Scope =
        Scope.entries.firstOrNull { it.wire == raw.lowercase() }
            ?: throw DatapipelinesException(
                code = PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED,
                message = "Unknown scope '$raw'. Supported: ${Scope.entries.joinToString(", ") { it.wire }}.",
                details = mapOf("reason" to "scope_unknown"),
            )

    private fun unknownKind(raw: String) =
        DatapipelinesException(
            code = PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED,
            message = "Unknown key kind '$raw'. Supported: ${ApiKeyKind.WIRE_VALUES.joinToString(", ")}.",
            details = mapOf("reason" to "kind_unknown"),
        )

    private fun requirePrincipal(): AuthenticatedPrincipal =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
            ?: error("No authenticated principal")
}
