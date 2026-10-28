package co.datapipelines.parameters

import co.datapipelines.pipeline.DatasourceRegistry
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.TemplateIds
import co.datapipelines.pipeline.TemplateLookup
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.typesystem.ParameterCardinality
import java.util.UUID

/**
 * Record §4 step 4 — a template-backed source's pin and binds (P22, P30): the pin resolves (a
 * DISCARDED version included in `template_version_not_found`), is `type = 'sql'`, its dialect is the
 * datasource's, the datasource is visible, no parent is interpolated (`${}` — the existing
 * `template.validation.parameter_interpolated`), and every `:bind` resolves by namespace. Every
 * collaborator is a `pipeline-contract` port; split from [ParameterSetValidator] along the record's
 * own step line, and re-run on a stored body at release and import ([ParameterSetValidator.revalidateSources]).
 */
internal class PinRules(
    private val templates: TemplateDryRenderer,
    private val templateStatuses: TemplateVersionStatuses,
    private val datasources: DatasourceRegistry,
    /** Names a selector template may bind without a dependency: the org and platform tiers, minus `execution_id` (P30). */
    private val tierKeys: Set<String>,
    /**
     * Whether a pinned template is registered at all (#737, the #733 port): a restore tells `template_not_found` from
     * `template_version_not_found` by the entity row. The default answers "not registered" for every pin, so an unwired
     * construction reports `template_not_found`, never a false "known" (fail closed).
     */
    private val templateIds: TemplateIds = TemplateIds { _, _ -> false },
) {
    fun check(
        workspaceId: UUID,
        index: Int,
        parameter: ParameterDefinition,
        byName: Map<String, ParameterDefinition>,
        failures: ParameterSetFailures,
    ) {
        if (checkMetadata(workspaceId, index, parameter, failures)) {
            val at = "parameters[$index].source"
            interpolation(workspaceId, at, parameter, failures)
            binds(workspaceId, at, parameter, byName, failures)
        }
    }

    /** Local existence/status/type/dialect only; safe while holding the metadata writer guard. */
    @Suppress("ReturnCount") // one early answer per refused pin shape
    fun checkMetadata(
        workspaceId: UUID,
        index: Int,
        parameter: ParameterDefinition,
        failures: ParameterSetFailures,
        requireLiveStatus: Boolean = false,
    ): Boolean {
        val ref = parameter.source?.template ?: return false
        val datasource = parameter.source.datasource ?: return false
        val at = "parameters[$index].source"
        val facts = datasources.describe(datasource, workspaceId)
        if (facts == null) {
            failures.add(
                ParameterErrorCodes.DATASOURCE_NOT_FOUND,
                "$at.datasource",
                "No datasource named '${datasource.safeEcho()}' is visible from this workspace.",
                mapOf("datasource" to datasource.safeEcho()),
            )
        }
        if (requireLiveStatus && !livePin(workspaceId, index, ref, failures)) return false
        val pin = mapOf("template_id" to ref.id.safeEcho(), "template_version" to ref.version)
        return when (val lookup = templates.lookup(workspaceId, ref)) {
            TemplateLookup.TemplateNotFound -> {
                failures.add(ParameterErrorCodes.TEMPLATE_NOT_FOUND, "$at.template", "Template '${ref.id.safeEcho()}' does not exist.", pin)
                false
            }

            TemplateLookup.VersionNotFound -> {
                missingVersion(at, ref, failures)
                false
            }

            is TemplateLookup.Found -> {
                if (templateStatuses.statusOf(workspaceId, ref.id, ref.version) == PipelineVersionStatus.DISCARDED) {
                    failures.add(
                        ParameterErrorCodes.TEMPLATE_VERSION_NOT_FOUND,
                        "$at.template",
                        "Template '${ref.id.safeEcho()}' version ${ref.version} is DISCARDED; pin a live version.",
                        pin + ("reason" to "discarded"),
                    )
                    return false
                }
                if (lookup.type != TemplateType.SQL) {
                    failures.add(
                        ParameterErrorCodes.TEMPLATE_TYPE_MISMATCH,
                        "$at.template",
                        "A selector template is type 'sql'; this one is '${lookup.type.wire}'.",
                        pin + ("type" to lookup.type.wire),
                    )
                    return false
                }
                if (facts != null && lookup.dialect != facts.dialect) {
                    failures.add(
                        ParameterErrorCodes.TEMPLATE_DIALECT_MISMATCH,
                        "$at.template",
                        "The template's dialect (${lookup.dialect?.wire}) is not the datasource's (${facts.dialect.wire}).",
                        pin + mapOf("template_dialect" to lookup.dialect?.wire, "datasource_dialect" to facts.dialect.wire),
                    )
                }
                true
            }
        }
    }

    /**
     * A RESTORE's recheck of one selector pin (#517 round three, record §12.1 RULED 2026-10-25): existence and status
     * only, read from the uncached status port, so the registry cache cannot pass a pin discarded meanwhile. Never the
     * datasource, type or dialect: a rule that tightened since the version was released is not a dangling pin. Reports a
     * missing or DISCARDED pin; answers the pin's live status, or null when it has none. A missing pin is told apart from a
     * missing template by the ENTITY row ([TemplateIds]) — no version number stands in for it, since a preserved-number
     * import or a draft purge leaves a template without version 1 (#733, #737).
     */
    fun restorePin(
        workspaceId: UUID,
        index: Int,
        parameter: ParameterDefinition,
        failures: ParameterSetFailures,
    ): PipelineVersionStatus? {
        val ref = parameter.source?.template ?: return null
        val at = "parameters[$index].source"
        val pin = mapOf("template_id" to ref.id.safeEcho(), "template_version" to ref.version)
        val status = templateStatuses.statusOf(workspaceId, ref.id, ref.version)
        when {
            status == PipelineVersionStatus.DISCARDED -> {
                failures.add(
                    ParameterErrorCodes.TEMPLATE_VERSION_NOT_FOUND,
                    "$at.template",
                    "Template '${ref.id.safeEcho()}' version ${ref.version} is DISCARDED; a restore lands RELEASED and " +
                        "may pin only a live version.",
                    pin + ("reason" to "discarded"),
                )
            }

            status == null && templateIds.isRegistered(workspaceId, ref.id) -> {
                missingVersion(at, ref, failures)
            }

            status == null -> {
                failures.add(ParameterErrorCodes.TEMPLATE_NOT_FOUND, "$at.template", "Template '${ref.id.safeEcho()}' does not exist.", pin)
            }
        }
        return status.takeUnless { it == PipelineVersionStatus.DISCARDED }
    }

    private fun missingVersion(
        at: String,
        ref: co.datapipelines.pipeline.TemplateRef,
        failures: ParameterSetFailures,
    ) {
        failures.add(
            ParameterErrorCodes.TEMPLATE_VERSION_NOT_FOUND,
            "$at.template",
            "Template '${ref.id.safeEcho()}' has no version ${ref.version}.",
            mapOf("template_id" to ref.id.safeEcho(), "template_version" to ref.version),
        )
    }

    private fun livePin(
        workspaceId: UUID,
        index: Int,
        ref: co.datapipelines.pipeline.TemplateRef,
        failures: ParameterSetFailures,
    ): Boolean {
        if (templateStatuses.statusOf(workspaceId, ref.id, ref.version) in
            setOf(PipelineVersionStatus.DRAFT, PipelineVersionStatus.RELEASED)
        ) {
            return true
        }
        failures.add(
            ParameterErrorCodes.TEMPLATE_VERSION_NOT_FOUND,
            "parameters[$index].source.template",
            "The pinned template version is missing or discarded; pin a live version.",
            mapOf("template_id" to ref.id.safeEcho(), "template_version" to ref.version),
        )
        return false
    }

    /** §4 step 4: a parent (or a tier key) inside `${}` is refused with the existing template code (042 B2's rule). */
    private fun interpolation(
        workspaceId: UUID,
        at: String,
        parameter: ParameterDefinition,
        failures: ParameterSetFailures,
    ) {
        val ref = checkNotNull(parameter.source?.template)
        templates.interpolatedParameters(workspaceId, ref, parameter.dependsOn.toSet() + tierKeys).forEach { name ->
            failures.add(
                PipelineErrorCodes.Template.PARAMETER_INTERPOLATED,
                "$at.template",
                "The template interpolates '${name.safeEcho()}' with \${}; a parent is a VALUE — bind it as :${name.safeEcho()}.",
                mapOf("parameter" to name.safeEcho(), "template_id" to ref.id.safeEcho()),
            )
        }
    }

    /** P30: a `:name` resolves against the set's parameters (then it must be a dependency), else the tiers, else it is undeclared. */
    private fun binds(
        workspaceId: UUID,
        at: String,
        parameter: ParameterDefinition,
        byName: Map<String, ParameterDefinition>,
        failures: ParameterSetFailures,
    ) {
        val ref = checkNotNull(parameter.source?.template)
        templates.boundParameters(workspaceId, ref).forEach { bind ->
            val countOf = bind.removeSuffix(COUNT_SUFFIX).takeIf { bind.endsWith(COUNT_SUFFIX) }
            val reason =
                when {
                    SLICE_BIND.matches(bind) -> {
                        "slice"
                    }

                    bind in byName -> {
                        if (bind in parameter.dependsOn) null else "not_a_dependency"
                    }

                    countOf != null && byName[countOf]?.cardinality == ParameterCardinality.MULTI -> {
                        if (countOf in parameter.dependsOn) null else "not_a_dependency"
                    }

                    bind in tierKeys -> {
                        null
                    }

                    else -> {
                        "unknown"
                    }
                }
            if (reason != null) {
                failures.add(
                    ParameterErrorCodes.BIND_UNDECLARED,
                    "$at.template",
                    "The template binds :${bind.safeEcho()}, which is ${BIND_REASONS.getValue(reason)}.",
                    mapOf("bind" to bind.safeEcho(), "reason" to reason, "template_id" to ref.id.safeEcho()),
                )
            }
        }
    }

    private companion object {
        val BIND_REASONS =
            mapOf(
                "slice" to "the in_list macro's own bind — never written by hand (P29)",
                "not_a_dependency" to "a parameter of this set that is not in depends_on — add it there",
                "unknown" to "neither a parameter of this set nor an org/platform key (execution_id is absent here)",
            )
    }
}
