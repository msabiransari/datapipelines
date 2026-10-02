package co.datapipelines.application.dashboards

import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineNameGrammar
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.DashboardService
import java.time.Instant
import java.util.UUID

/**
 * Binding a `dashboard` key to folders of the dashboard name space (L5, auth.md §7.7) —
 * `EndpointKeyService`'s bind/unbind half, for the kind whose bindings live on a FOLDER of the
 * dashboard NAME space instead of the published endpoint tree.
 *
 * The mould is copied faithfully and the deltas are stated where they exist:
 *
 * - **No issuance half.** A `dashboard` key is minted WITHOUT bindings (the endpoint key's
 *   plaintext-once reasoning does not apply: its bindings are edited on the key's card after
 *   mint, under `dashboard.key.bind`, exactly the Keys page's per-key editor). A request that
 *   sends endpoint bindings for a dashboard key is refused by `EndpointKeyService` — the
 *   "every kind but `endpoint` refuses them" branch.
 * - **The grammar is the name grammar's PREFIX form** (`PipelineNameGrammar.matchesPrefix`,
 *   1–9 segments) plus the root `/` — a folder of the same tree the dashboards live in, never
 *   a second spelling of it.
 * - **`requireInsideWorkspace` walks the workspace's DASHBOARD names** (#191's rule): a
 *   prefix is refused unless it is the root or a folder at or above a dashboard the caller's
 *   workspace actually has, and the refusal names only the caller's own tree.
 *
 * The BINDINGS are the key's lens: [DashboardKeyAuthorizer] walks them per request, and an
 * unbound key is admitted to the runtime surface and served NOTHING.
 */
class DashboardKeyService(
    private val bindings: DashboardKeyBindingRepository,
    private val audit: AuditEventSink,
    /** The dashboards of a workspace — what a binding of that workspace may name (#191). */
    private val dashboards: DashboardService,
) {
    /**
     * Binds an existing `dashboard` key at [namePrefix] (§7.7). Idempotent.
     *
     * @throws DatapipelinesException `dashboard.binding.path_invalid` for a malformed prefix or
     *   one that names no folder of the caller's own tree.
     */
    fun bind(
        principal: AuthenticatedPrincipal,
        apiKeyId: String,
        namePrefix: String,
    ): Boolean {
        val prefix = normalizeBinding(namePrefix)
        val workspaceId = principal.requireWorkspace().id
        requireInsideWorkspace(prefix, workspaceId)
        val added =
            bindings.insert(
                DashboardKeyBinding(prefix, apiKeyId, workspaceId, principal.userId, Instant.now()),
            )
        audit.log(
            event = AUDIT_BOUND,
            userId = principal.userId,
            keyId = apiKeyId,
            details = mapOf("name_prefix" to prefix, "workspace_id" to workspaceId.toString(), "created" to added),
        )
        return added
    }

    /** Unbinds a key from one folder (§7.7). */
    fun unbind(
        principal: AuthenticatedPrincipal,
        apiKeyId: String,
        namePrefix: String,
    ): Boolean {
        val prefix = normalizeBinding(namePrefix)
        val removed = bindings.delete(prefix, apiKeyId, principal.requireWorkspace().id)
        audit.log(
            event = AUDIT_UNBOUND,
            userId = principal.userId,
            keyId = apiKeyId,
            details = mapOf("name_prefix" to prefix, "removed" to removed),
        )
        return removed
    }

    /**
     * The dashboard names a `dashboard_viewer` principal may be served — the key's LENS as a
     * [co.datapipelines.pipeline.ReadLens.Only] name set, built per request by the R-EP2 walk
     * ([DashboardKeyAuthorizer]) over the workspace's bindings and the workspace's dashboard
     * names. An EMPTY set is the unbound key's answer: the lens admits nothing, fail closed —
     * "unbound = unservable" is the lens answering nothing.
     *
     * Deliberately an EXPANSION to names at resolve time and not a new `ReadLens` variant: the
     * walk is pure and table-tested (`DashboardKeyAuthorizerTest`), `ReadLens` stays a value
     * with no key identity in it, and every consumer keeps its existing `admits(name)` shape.
     */
    fun admittedNames(
        workspaceId: UUID,
        apiKeyId: String,
        dashboardNames: Collection<String>,
    ): Set<String> {
        val bindings = this.bindings.findByWorkspace(workspaceId)
        val authorizer = DashboardKeyAuthorizer()
        return dashboardNames
            .filter { name ->
                authorizer.authorize(name, workspaceId, apiKeyId, bindings) == DashboardKeyAuthorizer.Decision.Allowed
            }.toSet()
    }

    /**
     * A binding prefix as it is stored: the exact folder text, no trailing slash, or the root.
     * Grammar: the name grammar's PREFIX form (`PipelineNameGrammar.matchesPrefix`, 1–9
     * segments) — a folder of the tree dashboard names live in — or the root `/` (binding the
     * workspace's whole dashboard tree; the spec §2.2 allows it, and the endpoint bindings'
     * root-binding reasoning carries over: the workspace predicate at serve time keeps a root
     * binding inside its own workspace).
     */
    private fun normalizeBinding(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed == "/" || trimmed.isEmpty()) return ROOT
        val candidate = trimmed.trim('/')
        if (!PipelineNameGrammar.matchesPrefix(candidate)) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Dashboard.BINDING_PATH_INVALID,
                message = "Binding folder '$raw' is not a legal dashboard folder: ${PipelineNameGrammar.DESCRIPTION}",
                details = mapOf("name_prefix" to raw),
            )
        }
        return candidate
    }

    /**
     * A binding of [workspaceId] may name the root `/` or a folder at or above a dashboard that
     * workspace actually has (#191). Anything else is refused with the bindings' validation code
     * and a message that names ONLY the caller's own tree — whether some other workspace has
     * dashboards at the prefix is exactly what the refusal must not reveal (§11A.1).
     */
    private fun requireInsideWorkspace(
        prefix: String,
        workspaceId: UUID,
    ) {
        if (prefix == ROOT) return
        val names = dashboards.currentVersions(workspaceId).map { it.name }
        val inside = names.any { name -> prefix in DashboardKeyAuthorizer.ancestors(name) }
        if (!inside) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Dashboard.BINDING_PATH_INVALID,
                message =
                    "No dashboard of your workspace lies at or under '$prefix'. " +
                        "Bind the key at a folder of your own dashboards, or at the root.",
                details = mapOf("name_prefix" to prefix),
            )
        }
    }

    companion object {
        /** The binding pair's audit events (enums.md §15) — `DashboardAuditEvents.ALL` holds them to the doc. */
        const val AUDIT_BOUND = "dashboard.key_bound"
        const val AUDIT_UNBOUND = "dashboard.key_unbound"
        private const val ROOT = "/"
    }
}
