package co.datapipelines.application.endpoints

import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.IssuedApiKey
import co.datapipelines.auth.KeyRole
import co.datapipelines.pipeline.AuthoringGuard
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
 * ## A key's role follows its kind
 *
 * Since #215 slice (b) a key carries a ROLE, never scopes (record PK8): an `endpoint` key is an
 * `api_caller`, a `server` key a `promotion_receiver`, and nothing else is offered (A1, the
 * database CHECK). A request may name the role; one that names a role its kind cannot hold is
 * refused rather than quietly corrected — a caller who writes `{"kind": "endpoint", "role":
 * "promotion_receiver"}` has a mental model this surface must correct out loud. The mirror rule
 * holds for bindings: every kind but `endpoint` refuses them, so a kind added later cannot fall
 * through and write binding rows nothing will ever read.
 */
class EndpointKeyService(
    private val apiKeys: ApiKeyService,
    private val bindings: EndpointKeyBindingRepository,
    private val audit: AuditEventSink,
    /** The published paths of a workspace — what a binding of that workspace may name (#191). */
    private val publishedEndpoints: PublishedEndpointRepository,
    /** #1111 — the posture an explicit `allow: true` requires (authoring on); no default, so a surface cannot forget it. */
    private val authoring: AuthoringGuard,
    /** #1111 — keys the deployment manages itself (the demo public key), never elevated. */
    private val managedKeys: ManagedEndpointKeys,
) {
    /**
     * Issues a key of [kind], binding it at every path in [bindingPaths].
     *
     * @throws DatapipelinesException `endpoint.path_invalid` for a malformed binding path, or
     *   `endpoint.key_kind_refused` when the kind and the other arguments contradict each other
     *   (keys v2: an `mcp` key is refused with bindings, and without a member `role`).
     */
    @Suppress("LongParameterList", "ThrowsCount") // the issuance contract; each refusal has its own catalogued code
    fun issue(
        principal: AuthenticatedPrincipal,
        name: String,
        role: KeyRole?,
        kind: ApiKeyKind,
        bindingPaths: List<String>,
        expiresAt: Instant?,
    ): IssuedApiKey = issueInternal(principal, name, role, kind, bindingPaths, expiresAt, plaintext = null)

    /**
     * The issuance contract with the credential SUPPLIED (#224) — the bootstrap seeder's mint of
     * the demo workspace's public `api_caller` key from its configured plaintext. Every guard of
     * [issue] runs unchanged; the plaintext reaches [ApiKeyService]'s supplied-credential mint,
     * which owns the §7.1 shape gate. This overload and the `ApiKeyService` one are the lane's
     * listed signature changes — both exist for exactly one caller, the bootstrap seeder.
     */
    @Suppress("LongParameterList", "ThrowsCount") // the issuance contract; each refusal has its own catalogued code
    fun issue(
        principal: AuthenticatedPrincipal,
        name: String,
        role: KeyRole?,
        kind: ApiKeyKind,
        bindingPaths: List<String>,
        expiresAt: Instant?,
        plaintext: String,
    ): IssuedApiKey = issueInternal(principal, name, role, kind, bindingPaths, expiresAt, plaintext)

    /**
     * The shared funnel: [plaintext] null = the random mint, else the supplied credential.
     *
     * The issuance contract: every guard and refusal is its own step, each with a catalogued
     * code; splitting the funnel hides the order the rules run in.
     */
    @Suppress("LongParameterList", "ThrowsCount", "LongMethod")
    private fun issueInternal(
        principal: AuthenticatedPrincipal,
        name: String,
        role: KeyRole?,
        kind: ApiKeyKind,
        bindingPaths: List<String>,
        expiresAt: Instant?,
        plaintext: String?,
    ): IssuedApiKey {
        val workspaceId = principal.requireWorkspace().id
        val normalized = bindingPaths.map(::normalizeBinding)

        // Keys v2 (A15): every kind is minted on demand, on the Keys page or over REST — the
        // login mint is gone. Bindings belong to exactly ONE kind, stated as "not endpoint"
        // so a kind added later cannot fall through and silently WRITE binding rows that
        // nothing would ever consult.
        if (kind != ApiKeyKind.ENDPOINT && normalized.isNotEmpty()) {
            throw refused(
                "Endpoint bindings belong to a key of kind 'endpoint'; a ${kind.wire} key is authorised by " +
                    "${authorityOf(kind)}. Mint it with \"kind\": \"endpoint\", or drop the bindings.",
            )
        }
        // The kind/role contract (keys v2 A13): an `mcp` key's member role is REQUIRED — a
        // live mcp key without one is a row the database CHECK refuses — and an `endpoint` or
        // `server` key's role is its kind's, so a request that names anything else is refused
        // rather than quietly corrected.
        when (kind) {
            ApiKeyKind.MCP -> {
                if (role == null || !role.isMemberKeyRole) {
                    throw refused(
                        "An mcp key carries a member role — one of " +
                            KeyRole.MEMBER_KEY_ROLES.joinToString(", ") { it.wire } +
                            " (keys v2: viewer is never a key role). Name \"role\".",
                    )
                }
            }

            else -> {
                if (role != null && role != KeyRole.forKind(kind)) {
                    throw refused(
                        "A ${kind.wire} key's role is ${KeyRole.forKind(kind)?.wire}; '${role.wire}' is not offered for it. " +
                            "Drop \"role\", or name the kind that role belongs to.",
                    )
                }
            }
        }
        // Not a refusal: an endpoint key with no bindings is legal and authorises nothing, which
        // is a coherent thing to mint (bind it later). It is worth an audit detail, not an error.

        // #191 — every requested prefix is checked BEFORE the key is minted, for the same
        // ordering reason the kind checks above run first where they do: a refusal must cost
        // nothing, and the D16 refusal stays the FIRST word so it never depends on which other
        // argument would also have failed.
        normalized.forEach { requireInsideWorkspace(it, workspaceId) }

        val issued =
            if (plaintext == null) {
                apiKeys.issue(
                    issuer = principal,
                    name = name,
                    workspaceId = workspaceId,
                    expiresAt = expiresAt,
                    kind = kind,
                    role = role,
                )
            } else {
                apiKeys.issue(
                    issuer = principal,
                    name = name,
                    workspaceId = workspaceId,
                    expiresAt = expiresAt,
                    kind = kind,
                    role = role,
                    plaintext = plaintext,
                )
            }

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

    /**
     * Binds an existing key at [pathPrefix] (§6) — a GET grant at a tree node, or with [method] a mutation grant on
     * exactly ONE route entity (#1037). Idempotent.
     *
     * #191: a GET prefix must lie at or above a path the caller's OWN workspace publishes. A workspace that publishes
     * nothing at or under the prefix is refused exactly like one that cannot name it — the message says nothing about
     * anyone else's tree.
     *
     * A mutation grant names the whole route — the prefix IS the entity's full pattern, and the entity must exist at
     * that (path, method) in the caller's workspace: the grant stores its id, so it can never widen to a sibling
     * method or survive a delete-and-recreate at the same pattern (published-mutation-apis §2).
     */
    fun bind(
        principal: AuthenticatedPrincipal,
        apiKeyId: String,
        pathPrefix: String,
        method: EndpointMethod = EndpointMethod.GET,
    ): Boolean {
        val prefix = normalizeBinding(pathPrefix)
        val workspaceId = principal.requireWorkspace().id
        val endpointId: UUID?
        if (method == EndpointMethod.GET) {
            requireInsideWorkspace(prefix, workspaceId)
            endpointId = null
        } else {
            // The mutation grant's target: one entity of this method at this exact path in the caller's workspace.
            // A disabled entity may still be granted (enabling it later is the operator's next step, and the serve
            // path 404s a disabled route regardless of grants) — the same posture a GET node binding has toward the
            // entities beneath it.
            endpointId =
                publishedEndpoints.findByPath(prefix, method)?.takeIf { it.workspaceId == workspaceId }?.id
                    ?: throw DatapipelinesException(
                        code = PipelineErrorCodes.Endpoint.PATH_INVALID,
                        message =
                            "Your workspace publishes no ${method.name} route at '$prefix'. Grant a method on a route " +
                                "you publish — a mutation grant names one route, not a tree node.",
                        details = mapOf("path_prefix" to prefix, "method" to method.name),
                    )
        }
        val added =
            bindings.insert(
                EndpointKeyBinding(prefix, apiKeyId, workspaceId, principal.userId, Instant.now(), method, endpointId),
            )
        audit.log(
            event = AUDIT_BOUND,
            userId = principal.userId,
            keyId = apiKeyId,
            details =
                mapOf(
                    "path_prefix" to prefix,
                    "workspace_id" to workspaceId.toString(),
                    "method" to method.name,
                    "created" to added,
                ),
        )
        return added
    }

    /**
     * Sets the namespace-writes opt-in (#1111; auth.md §7.7) on [apiKeyId]'s GET binding at [pathPrefix] and answers
     * the stored state the one statement produced ([EndpointKeyBindingRepository.setNamespaceWrites]).
     *
     * `allow = true` creates or upgrades the GET node binding. It is refused BEFORE any write when this deployment's
     * authoring capability is off (`409 endpoint.authoring_disabled`), for a key the deployment manages itself
     * (`403 endpoint.key_kind_refused`, `details.reason = "managed_key"`), and — the #191 rule of every creating bind —
     * at a node outside the caller's own published tree (`400 endpoint.path_invalid`). A namespace needs a published
     * descendant at setup, never the future route it will admit.
     *
     * `allow = false` clears the marker on an existing binding and creates nothing at an absent node; it is permitted
     * with authoring off and without the tree check, so an operator can always retire a retained opt-in, including one
     * whose last route has gone.
     */
    fun setNamespaceWrites(
        principal: AuthenticatedPrincipal,
        apiKeyId: String,
        pathPrefix: String,
        allow: Boolean,
    ): EndpointKeyBindingRepository.NamespaceWritesState {
        val prefix = normalizeBinding(pathPrefix)
        val workspaceId = principal.requireWorkspace().id
        if (allow) {
            if (!authoring.developmentPosture) {
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Endpoint.AUTHORING_DISABLED,
                    message =
                        "This deployment has authoring disabled (${AuthoringGuard.CONFIG_KEY}=false), so a key cannot be " +
                            "allowed namespace writes here. Grant the exact routes instead; \"allow\": false and unbinding " +
                            "stay available.",
                    details = mapOf("path_prefix" to prefix, "config_key" to AuthoringGuard.CONFIG_KEY),
                )
            }
            if (managedKeys.isManaged(apiKeyId, workspaceId)) {
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED,
                    message =
                        "This key is managed by the deployment (the demo workspace's public read key) and is never " +
                            "allowed namespace writes. Mint a dedicated endpoint key for development.",
                    details = mapOf("reason" to REASON_MANAGED_KEY),
                )
            }
            requireInsideWorkspace(prefix, workspaceId)
        }
        val state = bindings.setNamespaceWrites(prefix, apiKeyId, workspaceId, principal.userId, allow)
        audit.log(
            event = AUDIT_NAMESPACE_WRITES,
            userId = principal.userId,
            keyId = apiKeyId,
            details =
                mapOf(
                    "path_prefix" to prefix,
                    "workspace_id" to workspaceId.toString(),
                    "method" to EndpointMethod.GET.name,
                    "requested" to allow,
                    "allow" to state.allow,
                    "bound" to state.bound,
                    "created" to state.created,
                ),
        )
        return state
    }

    /**
     * Unbinds a key from one grant (§6) — the GET node, or with [method] the mutation grant on that route. Unbinding the
     * GET node also removes its namespace-writes opt-in (#1111): the marker lives on that row.
     */
    fun unbind(
        principal: AuthenticatedPrincipal,
        apiKeyId: String,
        pathPrefix: String,
        method: EndpointMethod = EndpointMethod.GET,
    ): Boolean {
        val prefix = normalizeBinding(pathPrefix)
        val removed = bindings.delete(prefix, apiKeyId, principal.requireWorkspace().id, method)
        audit.log(
            event = AUDIT_UNBOUND,
            userId = principal.userId,
            keyId = apiKeyId,
            details = mapOf("path_prefix" to prefix, "method" to method.name, "removed" to removed),
        )
        return removed
    }

    /**
     * A binding path as it is stored: a leading `/`, no trailing slash, the one `/api` prefix
     * stripped, and — apart from the root — legal §4.1 segments.
     *
     * The root `/` is a legal binding and deliberately not a legal endpoint: binding at `/`
     * authorises the whole tree, which is a real operator intent, while publishing AT `/` is not
     * an endpoint anyone can name. The endpoint SHAPE rules (three segments, a literal category
     * and version — R-EP5) do not apply here either: a binding names a tree NODE, and `/nyc`
     * authorising everything beneath it is the common case.
     */
    private fun normalizeBinding(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed == "/" || trimmed.isEmpty()) return "/"
        val candidate = "/" + trimmed.trim('/')
        return EndpointPath
            .parseTreeNode(candidate)
            .getOrElse {
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Endpoint.PATH_INVALID,
                    message = "Binding path '$raw' is not a legal endpoint path: ${it.message}",
                    details = mapOf("path_prefix" to raw),
                )
            }.pattern
    }

    /**
     * A binding of [workspaceId] may name the root `/` or a node at or above a path that
     * workspace actually publishes (#191). Anything else is refused with the bindings'
     * validation code and a message that names ONLY the caller's own tree: whether some other
     * workspace publishes at the prefix is exactly what the refusal must not reveal — the same
     * non-disclosure rule the serve path's 404 follows (auth.md §11A.1).
     *
     * The root stays legal (it always was, and `ApiKeyForm.bindingNodes` offers it first), but
     * since #191's serve-time rule it only ever exercises bindings of the serving key's own
     * workspace — binding at `/` confers no reach into anyone else's tree.
     *
     * The comparison runs over [EndpointPath.ancestors] of each published PATTERN, so a binding
     * at a literal node above a `{variable}` leaf (`/nyc` for `/nyc/v1/revenue/{borough}`) is
     * accepted — the same relationship the authorizer walks at serve time.
     */
    private fun requireInsideWorkspace(
        prefix: String,
        workspaceId: UUID,
    ) {
        if (prefix == ROOT) return
        val published = publishedEndpoints.findByWorkspace(workspaceId)
        val inside = published.any { endpoint -> prefix in EndpointPath.ancestors(endpoint.pathPattern) }
        if (!inside) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Endpoint.PATH_INVALID,
                message =
                    "No published path of your workspace lies at or under '$prefix'. " +
                        "Bind the key at a node of your own published tree, or at the root.",
                details = mapOf("path_prefix" to prefix),
            )
        }
    }

    /** What decides a kind's authority, for a refusal that tells the caller what to do instead. */
    private fun authorityOf(kind: ApiKeyKind): String =
        when (kind) {
            ApiKeyKind.MCP -> "its role in its workspace, over /mcp"
            ApiKeyKind.ENDPOINT -> "its bindings"
            ApiKeyKind.SERVER -> "the promotion route family it opens"
            ApiKeyKind.DASHBOARD -> "its dashboard folder bindings, over the runtime routes"
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
        const val AUDIT_NAMESPACE_WRITES = "endpoint.key_namespace_writes"
        const val REASON_MANAGED_KEY = "managed_key"
        const val ROOT = "/"
    }
}
