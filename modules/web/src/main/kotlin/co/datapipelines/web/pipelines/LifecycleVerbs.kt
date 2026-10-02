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

    /**
     * enums.md §15 — the release itself (T187). Versioning D4 makes the release the one human
     * step, so the record of WHO made it and THROUGH WHAT (`via`: session or key id) is the
     * audit's whole point; 101 audited the discard family and left this one out.
     */
    const val AUDIT_VERSION_RELEASED = "pipeline.version.released"

    /** enums.md §15 — the template twins. */
    const val TEMPLATE_AUDIT_VERSION_DISCARDED = "template.version.discarded"
    const val TEMPLATE_AUDIT_VERSION_RESTORED = "template.version.restored"
    const val TEMPLATE_AUDIT_VERSION_PURGED = "template.version.purged"
    const val TEMPLATE_AUDIT_ENTITY_PURGED = "template.purged"
    const val TEMPLATE_AUDIT_CURRENT_SWITCHED = "template.current_switched"
    const val TEMPLATE_AUDIT_VERSION_RELEASED = "template.version.released"

    /**
     * enums.md §15 — the five human verbs' audit events for one FAMILY, generalised by prefix
     * (#332) rather than duplicated as a constants block per family: the parameter-set,
     * visualization and dashboard families emit the pipelines' five by name substitution, plus
     * the family's own `version.released` (the [releaseDetails]/[auditRelease] twin, §A of the
     * lane brief — the T187 gap would otherwise be recreated per family). Held to the doc by the
     * family spec-drift guards, each with the five-event non-vacuity floor.
     */
    data class FamilyAuditEvents(
        private val prefix: String,
    ) {
        val versionDiscarded: String get() = "$prefix.version.discarded"
        val versionRestored: String get() = "$prefix.version.restored"
        val versionPurged: String get() = "$prefix.version.purged"
        val entityPurged: String get() = "$prefix.purged"
        val currentSwitched: String get() = "$prefix.current_switched"
        val versionReleased: String get() = "$prefix.version.released"

        /** Every event name the family's surfaces can emit — the drift guards' code side. */
        val all: Set<String>
            get() =
                setOf(
                    versionDiscarded,
                    versionRestored,
                    versionPurged,
                    entityPurged,
                    currentSwitched,
                    versionReleased,
                )
    }

    /** The three families' event sets (#332); the wire values are enums.md §15's rows. */
    val PARAMETER_SET_EVENTS = FamilyAuditEvents("parameter_set")
    val VISUALIZATION_EVENTS = FamilyAuditEvents("visualization")
    val DASHBOARD_EVENTS = FamilyAuditEvents("dashboard")

    /**
     * The release a family verb's cascade rode on (#332): which family release ordered a cascaded
     * template or visualization release — the [CascadeSource] question with the source key spelled
     * per family (`cascade_from_parameter_set_id`, `cascade_from_visualization_id`,
     * `cascade_from_dashboard_id`).
     */
    data class FamilyCascade(
        val idKey: String,
        val id: UUID,
        val version: Int,
    ) {
        /** The `details` keys the cascaded row carries. */
        val provenance: Pair<String, String> get() = "cascade_from_$idKey" to id.toString()
    }

    /** The `via` detail every release event carries — the D4 question is "was it a person". */
    fun via(principal: AuthenticatedPrincipal): String = if (principal.keyId != null) "api_key" else "session"

    /**
     * The shared `pipeline.version.released` details (T187 + 140 + 142): ids, name, version,
     * `via` — when the release-check gate was overridden, `checks_overridden` and
     * `override_reason` — and `templates_released`, the `{template_id, version}` list the
     * release cascaded to (empty when nothing did). One constructor so the REST surface and
     * the dialog cannot drift on what an audited release records (enums.md §15).
     */
    fun releaseDetails(
        principal: AuthenticatedPrincipal,
        pipelineId: UUID,
        released: co.datapipelines.pipeline.PipelineReleaseService.Released,
    ): Map<String, Any?> =
        buildMap {
            put("pipeline_id", pipelineId.toString())
            put("pipeline_name", released.record.name)
            put("version", released.version.version)
            put("via", via(principal))
            if (released.checksOverridden.isNotEmpty()) {
                put("checks_overridden", released.checksOverridden)
                put("override_reason", released.checksOverrideReason)
            }
            put("templates_released", released.templatesReleased.map { mapOf("template_id" to it.id, "version" to it.version) })
        }

    /**
     * The shared `template.version.released` details (enums.md §15): `template_id`,
     * `version`, `via` — and, for a release the pipeline cascade made (142),
     * `cascade_from_pipeline_id` and `cascade_from_version`, so "who released template X v2
     * and why" reads off the template's own event alone. The same constructor serves the
     * direct verb (REST, the template dialog) and the cascade, so the two cannot drift.
     */
    fun templateReleaseDetails(
        principal: AuthenticatedPrincipal,
        templateId: String,
        version: Int,
        cascadeFrom: CascadeSource? = null,
    ): Map<String, Any?> =
        buildMap {
            put("template_id", templateId)
            put("version", version)
            put("via", via(principal))
            if (cascadeFrom != null) {
                put("cascade_from_pipeline_id", cascadeFrom.pipelineId.toString())
                put("cascade_from_version", cascadeFrom.version)
            }
        }

    /** The pipeline release a cascaded template release rode on (142). */
    data class CascadeSource(
        val pipelineId: UUID,
        val version: Int,
    )

    /**
     * The shared `<family>.version.released` details (#332): the artifact's id and name, the
     * released version, `via`, and the cascade the release carried — `templates_released`
     * (`{template_id, version}`) for the set and visualization families, `visualizations_released`
     * (`{name, version}`) for the dashboard family — an empty list when nothing cascaded. One
     * constructor so the three controllers cannot drift on what an audited family release records.
     */
    fun familyReleaseDetails(
        principal: AuthenticatedPrincipal,
        identity: FamilyIdentity,
        cascadeKey: String,
        cascade: List<Map<String, Any?>>,
    ): Map<String, Any?> =
        buildMap {
            put(identity.idKey, identity.id.toString())
            put(identity.nameKey, identity.name)
            put("version", identity.version)
            put("via", via(principal))
            put(cascadeKey, cascade)
        }

    /**
     * The audited artifact's identity as the released event names it (#332): the `details` keys
     * (`parameter_set_id`/`parameter_set_name`, the visualization and dashboard twins) beside the
     * values, so the three controllers cannot drift on the row's shape.
     */
    data class FamilyIdentity(
        val idKey: String,
        val nameKey: String,
        val id: UUID,
        val name: String,
        val version: Int,
    )

    /**
     * The shared cascaded-release details (#332): the released member's own id (where the member
     * carries one — templates do, dashboard-cascaded visualizations are ArtifactRef-shaped and
     * name only), version and `via`, plus the [FamilyCascade] provenance — "who released X v2 and
     * why" reads off the member's own row, the 142 rule for every family.
     */
    fun cascadedReleaseDetails(
        principal: AuthenticatedPrincipal,
        idKey: String?,
        id: String?,
        version: Int,
        cascade: FamilyCascade,
    ): Map<String, Any?> =
        buildMap {
            if (idKey != null && id != null) put(idKey, id)
            put("version", version)
            put("via", via(principal))
            put(cascade.provenance.first, cascade.provenance.second)
            put("cascade_from_version", cascade.version)
        }

    /**
     * Audits a pipeline release on any surface (142): one `template.version.released` per
     * cascaded template FIRST — the order the writes happened in — then the pipeline's own
     * `pipeline.version.released` naming them. Called only after the service returned, i.e.
     * after the one transaction committed; a refusal anywhere threw before this and logs
     * nothing, which is the audit half of the cascade's atomicity.
     */
    fun auditRelease(
        sink: AuditEventSink,
        principal: AuthenticatedPrincipal,
        workspaceId: UUID,
        pipelineId: UUID,
        released: co.datapipelines.pipeline.PipelineReleaseService.Released,
    ) {
        val source = CascadeSource(pipelineId, released.version.version)
        released.templatesReleased.forEach { template ->
            audit(
                sink,
                TEMPLATE_AUDIT_VERSION_RELEASED,
                principal,
                workspaceId,
                templateReleaseDetails(principal, template.id, template.version, source),
            )
        }
        audit(sink, AUDIT_VERSION_RELEASED, principal, workspaceId, releaseDetails(principal, pipelineId, released))
    }
}
