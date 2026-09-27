package co.datapipelines.parameters

import co.datapipelines.pipeline.DatasourceRegistry
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateDryRenderer
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
) {
    @Suppress("ReturnCount") // one early answer per refused pin shape
    fun check(
        workspaceId: UUID,
        index: Int,
        parameter: ParameterDefinition,
        byName: Map<String, ParameterDefinition>,
        failures: ParameterSetFailures,
    ) {
        val source = parameter.source ?: return
        val ref = source.template ?: return
        val datasource = source.datasource ?: return
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
        val pin = mapOf("template_id" to ref.id.safeEcho(), "template_version" to ref.version)
        when (val lookup = templates.lookup(workspaceId, ref)) {
            TemplateLookup.TemplateNotFound -> {
                failures.add(ParameterErrorCodes.TEMPLATE_NOT_FOUND, "$at.template", "Template '${ref.id.safeEcho()}' does not exist.", pin)
            }

            TemplateLookup.VersionNotFound -> {
                failures.add(
                    ParameterErrorCodes.TEMPLATE_VERSION_NOT_FOUND,
                    "$at.template",
                    "Template '${ref.id.safeEcho()}' has no version ${ref.version}.",
                    pin,
                )
            }

            is TemplateLookup.Found -> {
                if (templateStatuses.statusOf(workspaceId, ref.id, ref.version) == PipelineVersionStatus.DISCARDED) {
                    failures.add(
                        ParameterErrorCodes.TEMPLATE_VERSION_NOT_FOUND,
                        "$at.template",
                        "Template '${ref.id.safeEcho()}' version ${ref.version} is DISCARDED; pin a live version.",
                        pin + ("reason" to "discarded"),
                    )
                    return
                }
                if (lookup.type != TemplateType.SQL) {
                    failures.add(
                        ParameterErrorCodes.TEMPLATE_TYPE_MISMATCH,
                        "$at.template",
                        "A selector template is type 'sql'; this one is '${lookup.type.wire}'.",
                        pin + ("type" to lookup.type.wire),
                    )
                    return
                }
                if (facts != null && lookup.dialect != facts.dialect) {
                    failures.add(
                        ParameterErrorCodes.TEMPLATE_DIALECT_MISMATCH,
                        "$at.template",
                        "The template's dialect (${lookup.dialect?.wire}) is not the datasource's (${facts.dialect.wire}).",
                        pin + mapOf("template_dialect" to lookup.dialect?.wire, "datasource_dialect" to facts.dialect.wire),
                    )
                }
                interpolation(workspaceId, at, parameter, failures)
                binds(workspaceId, at, parameter, byName, failures)
            }
        }
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
