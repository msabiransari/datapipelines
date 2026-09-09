package co.datapipelines.web.pipelines

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.currentPrincipal
import java.util.UUID

/**
 * The 101 lifecycle verbs' shared discipline (versioning §7): purge, discard, restore,
 * entity purge and manual switch are **human verbs** (D4 family) — every endpoint carries
 * `MUTATE_PIPELINES_TEMPLATES` AND requires an interactive session; an API key, however
 * scoped, is refused. There is deliberately no MCP tool for any of them.
 *
 * The session check follows [PromotionUiController.requireSessionPrincipal]'s shape but
 * refuses with `auth.session.required` — the credential is wrong for the verb, which is an
 * auth-domain answer rather than a workspace one.
 */
object LifecycleVerbs {
    /** The interactive-session gate; every 101 endpoint calls this first. */
    fun requireSession(): AuthenticatedPrincipal =
        currentPrincipal().also {
            if (it.authMethod != AuthMethod.OIDC) {
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Auth.SESSION_REQUIRED,
                    message =
                        "This action is a human action and requires an interactive session — " +
                            "API keys cannot purge, discard, restore or switch versions.",
                    details = mapOf("auth_method" to it.authMethod.name),
                )
            }
        }

    /**
     * Emits a lifecycle audit event (enums.md §15's version-lifecycle table). `details` is
     * redaction-bound like every other sink caller — ids, versions and counts only.
     */
    fun audit(
        sink: AuditEventSink,
        event: String,
        principal: AuthenticatedPrincipal,
        workspaceId: UUID,
        details: Map<String, Any?>,
    ) {
        sink.log(
            event = event,
            userId = principal.userId,
            keyId = principal.keyId,
            details = details + ("workspace_id" to workspaceId.toString()),
        )
    }

    /** enums.md §15 — the pipeline-side lifecycle events; the template twins live beside them. */
    const val AUDIT_VERSION_DISCARDED = "pipeline.version.discarded"
    const val AUDIT_VERSION_RESTORED = "pipeline.version.restored"
    const val AUDIT_VERSION_PURGED = "pipeline.version.purged"
    const val AUDIT_ENTITY_PURGED = "pipeline.purged"
    const val AUDIT_CURRENT_SWITCHED = "pipeline.current_switched"

    /** enums.md §15 — the template twins. */
    const val TEMPLATE_AUDIT_VERSION_DISCARDED = "template.version.discarded"
    const val TEMPLATE_AUDIT_VERSION_RESTORED = "template.version.restored"
    const val TEMPLATE_AUDIT_VERSION_PURGED = "template.version.purged"
    const val TEMPLATE_AUDIT_ENTITY_PURGED = "template.purged"
    const val TEMPLATE_AUDIT_CURRENT_SWITCHED = "template.current_switched"
}
