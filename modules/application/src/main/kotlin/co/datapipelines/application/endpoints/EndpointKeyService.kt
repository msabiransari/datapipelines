package co.datapipelines.application.endpoints

import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.IssuedApiKey
import co.datapipelines.auth.Scope
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import java.time.Instant
import java.util.UUID

/**
 * Minting an API key together with its endpoint bindings (design §5.2/§6) — the cross-aggregate
 * half of key issuance, because a key lives in `auth` and a binding lives in the endpoint
 * registry, and an `endpoint` key without its bindings is a credential that authorises nothing.
 *
 * ## Why one call and not two
 *
 * `POST /api/v1/auth/api-keys` returns the plaintext key **exactly once**. If bindings were a
 * second request, a failure between the two would leave the operator holding a secret they can
 * never use and cannot re-read — they would have to revoke and start again, and the useless key
 * would sit in the table looking valid. So the bindings are part of issuance, and they are
 * validated BEFORE the key is minted: a bad path costs nothing, where a bad path after the mint
 * costs a wasted credential.
 *
 * ## Scopes on a scopeless kind
 *
 * An `endpoint` key — and, since 091, a `server` key — is issued with NO scopes, and asking for
 * some is refused rather than quietly dropped. A caller who writes
 * `{"kind": "endpoint", "scopes": ["admin"]}` has a mental model this surface must correct out
 * loud — silently ignoring it would leave them believing the key carries admin. The mirror rule
 * holds for bindings: every kind but `endpoint` refuses them, so a kind added later cannot fall
 * through and write binding rows nothing will ever read.
 */
class EndpointKeyService(
    private val apiKeys: ApiKeyService,
    private val bindings: EndpointKeyBindingRepository,
    private val audit: AuditEventSink,
) {
    /**
     * Issues a key of [kind], binding it at every path in [bindingPaths].
     *
     * @throws DatapipelinesException `endpoint.path_invalid` for a malformed binding path, or
     *   `endpoint.key_kind_refused` when the kind and the other arguments contradict each other.
     */
    @Suppress("LongParameterList") // the issuance contract, mirroring ApiKeyService.issue
    fun issue(
        principal: AuthenticatedPrincipal,
        name: String,
        scopes: Set<Scope>,
        kind: ApiKeyKind,
        bindingPaths: List<String>,
        expiresAt: Instant?,
    ): IssuedApiKey {
        val workspaceId = principal.requireWorkspace().id
        val normalized = bindingPaths.map(::normalizeBinding)

        // Bindings belong to exactly ONE kind. Stated as "not endpoint" rather than "is user"
        // so a kind added later (091's `server` was) cannot fall through and silently WRITE
        // binding rows that nothing would ever consult.
        if (kind != ApiKeyKind.ENDPOINT && normalized.isNotEmpty()) {
            throw refused(
                "Endpoint bindings belong to a key of kind 'endpoint'; a ${kind.wire} key is authorised by " +
                    "${authorityOf(kind)}. Mint it with \"kind\": \"endpoint\", or drop the bindings.",
            )
        }
        if (kind in ApiKeyKind.SCOPELESS && scopes.isNotEmpty()) {
            throw refused(
                "A ${kind.wire} key carries no scopes — its authority is ${authorityOf(kind)}. " +
                    "Remove \"scopes\" from the request.",
            )
        }
        // Not a refusal: an endpoint key with no bindings is legal and authorises nothing, which
        // is a coherent thing to mint (bind it later). It is worth an audit detail, not an error.

        val issued =
            apiKeys.issue(
                issuer = principal,
                ownerId = principal.userId,
                name = name,
                scopes = scopes,
                creatorScopes = principal.scopes,
                workspaceId = workspaceId,
                expiresAt = expiresAt,
                kind = kind,
            )

        normalized.forEach { prefix ->
            bindings.insert(
                EndpointKeyBinding(
                    pathPrefix = prefix,
                    apiKeyId = issued.record.id,
                    workspaceId = workspaceId,
                    createdBy = principal.userId,
                    createdAt = Instant.now(),
                ),
            )
            audit.log(
                event = AUDIT_BOUND,
                userId = principal.userId,
                keyId = issued.record.id,
                details = mapOf("path_prefix" to prefix, "workspace_id" to workspaceId.toString()),
            )
        }
        return issued
    }

    /** Binds an existing key at [pathPrefix] (§6). Idempotent. */
    fun bind(
        principal: AuthenticatedPrincipal,
        apiKeyId: String,
        pathPrefix: String,
    ): Boolean {
        val prefix = normalizeBinding(pathPrefix)
        val workspaceId = principal.requireWorkspace().id
        val added =
            bindings.insert(
                EndpointKeyBinding(prefix, apiKeyId, workspaceId, principal.userId, Instant.now()),
            )
        audit.log(
            event = AUDIT_BOUND,
            userId = principal.userId,
            keyId = apiKeyId,
            details = mapOf("path_prefix" to prefix, "workspace_id" to workspaceId.toString(), "created" to added),
        )
        return added
    }

    /** Unbinds a key from one node (§6). */
    fun unbind(
        principal: AuthenticatedPrincipal,
        apiKeyId: String,
        pathPrefix: String,
    ): Boolean {
        val prefix = normalizeBinding(pathPrefix)
        val removed = bindings.delete(prefix, apiKeyId)
        audit.log(
            event = AUDIT_UNBOUND,
            userId = principal.userId,
            keyId = apiKeyId,
            details = mapOf("path_prefix" to prefix, "removed" to removed),
        )
        return removed
    }

    /**
     * A binding path as it is stored: a leading `/`, no trailing slash, and — apart from the root
     * — a legal §4.1 path.
     *
     * The root `/` is a legal binding and deliberately not a legal endpoint: binding at `/`
     * authorises the whole tree, which is a real operator intent, while publishing AT `/` is not
     * an endpoint anyone can name.
     */
    private fun normalizeBinding(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed == "/" || trimmed.isEmpty()) return "/"
        val candidate = "/" + trimmed.trim('/')
        EndpointPath.parse(candidate).getOrElse {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Endpoint.PATH_INVALID,
                message = "Binding path '$raw' is not a legal endpoint path: ${it.message}",
                details = mapOf("path_prefix" to raw),
            )
        }
        return candidate
    }

    /** What decides a kind's authority, for a refusal that tells the caller what to do instead. */
    private fun authorityOf(kind: ApiKeyKind): String =
        when (kind) {
            ApiKeyKind.USER -> "its scopes"
            ApiKeyKind.ENDPOINT -> "its bindings"
            ApiKeyKind.SERVER -> "the promotion route family it opens"
        }

    private fun refused(message: String) =
        DatapipelinesException(
            code = PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED,
            message = message,
            details = emptyMap(),
        )

    private companion object {
        const val AUDIT_BOUND = "endpoint.key_bound"
        const val AUDIT_UNBOUND = "endpoint.key_unbound"
    }
}
