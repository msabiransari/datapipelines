package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import java.util.UUID

/**
 * The dashboard rules (the spec's §3.2) over a document the [DashboardReader] bound — every failure collected,
 * one code per rule. It runs whole at save, again at release and — against the pinned dependencies' CURRENT
 * state — at every runtime configuration read (L2), so it reads every dependency through a port:
 * [PipelineReleaseFacts], [ParameterSetFacts] and [VisualizationPins].
 *
 * The namespace (D15): occurrence, group, action and action-control names and the pinned set's parameter
 * names are ONE namespace (`duplicate_name`; an object name outside `[a-z][a-z0-9_]{0,63}` is `duplicate_name`
 * / `grammar`); sources are unique among themselves. Dependencies: a pin that does not exist (or only
 * DISCARDED) is `dependency_not_found`; a source must pin a live read-only (`source_not_read_only`) pipeline version; draft source,
 * set and visualization pins are admitted during authoring — the release
 * requires RELEASED (the D61 cascade covers the visualizations).
 *
 * Layout — the spec's "every visualization occurrence, group and action control appears exactly once" read
 * against its own worked example (which places `overview_group` nowhere and `refresh_button` only by
 * membership): an OCCURRENCE has exactly one grid item; an ACTION CONTROL is placed exactly once — a grid item
 * or a group membership; a GROUP at most once; nothing is a member of two groups or of itself; every grid item
 * fits the 12 columns. Recorded as an interpretation in the L1a handback.
 */
class DashboardValidator(
    private val pipelines: PipelineReleaseFacts,
    private val sets: ParameterSetFacts,
    private val visualizations: VisualizationPins,
    /**
     * The refresh deadline's cap (the spec's §9.6: `max-refresh-seconds`, 900) —
     * `datapipelines.dashboards.timeouts.max-refresh-seconds`.
     */
    private val maxRefreshSeconds: Int = DEFAULT_MAX_REFRESH_SECONDS,
    /** The most DISTINCT executions one refresh may run (`datapipelines.dashboards.admission.max-executions-per-refresh`, 16). */
    private val maxExecutionsPerRefresh: Int = DEFAULT_MAX_EXECUTIONS_PER_REFRESH,
) {
    /** Every rule over [document]; the document itself when it passes. */
    fun validate(
        workspaceId: UUID,
        document: DashboardDocument,
        requireReleasedSources: Boolean = false,
    ): ArtifactValidation<DashboardDocument> {
        val failures = ArtifactFailures(DashboardErrorCodes.BODY_INVALID)
        val body = document.body
        DocumentRules.name(document.name, DashboardErrorCodes.NAME_INVALID, failures)
        DocumentRules.texts(body.displayName, body.description, DashboardErrorCodes.BODY_INVALID, failures)
        val set = parameterSet(workspaceId, body, failures)
        val names = DashboardNames.of(body, set)
        val graph = GroupGraph(body)
        namespace(body, names, failures)
        val sources = sources(workspaceId, body, set, failures, requireReleasedSources)
        invocations(body, failures)
        occurrences(workspaceId, body, names, sources, failures)
        groups(body, names, failures)
        actions(body, names, failures)
        controls(body, names, set, failures)
        scopes(body, names, set, graph, failures)
        overrides(body, names, sources, failures)
        DashboardLayoutRules(body, names, graph, failures).check()
        timeouts(body, failures)
        val result = failures.toResult()
        return if (result.isValid) ArtifactValidation.Valid(document) else ArtifactValidation.Invalid(result)
    }

    // ---- dependencies ------------------------------------------------------------------------------------

    private fun parameterSet(
        workspaceId: UUID,
        body: DashboardBody,
        failures: ArtifactFailures,
    ): ParameterSetFact? {
        val ref = body.parameterSet ?: return null
        val fact = sets.setOf(workspaceId, ref)?.takeUnless { it.status == PipelineVersionStatus.DISCARDED }
        if (fact == null) dependencyNotFound("parameter_set", "parameter_set", ref, failures)
        return fact
    }

    /** Each source's release facts by source name (a source whose pin failed is absent — no cascade). */
    private fun sources(
        workspaceId: UUID,
        body: DashboardBody,
        set: ParameterSetFact?,
        failures: ArtifactFailures,
        requireReleasedSources: Boolean,
    ): Map<String, PipelineReleaseFact> {
        val facts = mutableMapOf<String, PipelineReleaseFact>()
        val setParameters = set?.parameters?.map { it.name }?.toSet()
        body.sources.forEachIndexed { index, source ->
            val path = "sources[$index]"
            val fact = pipelines.releaseOf(workspaceId, source.pipeline)
            when {
                fact == null || fact.status == PipelineVersionStatus.DISCARDED -> {
                    dependencyNotFound("$path.pipeline", "pipeline", source.pipeline, failures)
                }

                requireReleasedSources && fact.status != PipelineVersionStatus.RELEASED -> {
                    failures.add(
                        DashboardErrorCodes.SOURCE_NOT_RELEASED,
                        "$path.pipeline",
                        "Source '${source.name.safeEcho()}' pins ${source.pipeline.toString().safeEcho()}, which is ${fact.status.name}.",
                        mapOf("source" to source.name.safeEcho(), "status" to fact.status.name),
                    )
                }

                !fact.readOnly -> {
                    failures.add(
                        DashboardErrorCodes.SOURCE_NOT_READ_ONLY,
                        "$path.pipeline",
                        "Source '${source.name.safeEcho()}' pins a pipeline that writes business data or acts externally (D38).",
                        mapOf("source" to source.name.safeEcho()),
                    )
                }
            }
            fact?.let { facts[source.name] = it }
            if (fact != null) sourceParameters(body, source, path, fact, setParameters, failures)
        }
        return facts
    }

    /**
     * The dashboard's DISTINCT executions (spec §18 premise 11): the sources some occurrence reads, collapsed by the
     * refresh's own sharing identity — pinned release plus bindings, the outgoing overrides applied. A refresh reserves
     * one slot per distinct execution and is admitted for at most [maxExecutionsPerRefresh], so a document needing more
     * could be saved and released and then be `dashboard.refresh.saturated` for ever; it is refused here instead.
     *
     * The identity here is decided from the DOCUMENT (a set-parameter binding is its name, a literal its canonical
     * text), the refresh's from the evaluated values, so two bindings that happen to evaluate equal are two here and one
     * there — this count is never below the refresh's, and a document that passes here is admissible on that ground.
     */
    private fun invocations(
        body: DashboardBody,
        failures: ArtifactFailures,
    ) {
        val read = body.visualizations.flatMap { occurrence -> occurrence.inputs.values.map { it.source } }.toSet()
        val distinct =
            body.sources
                .filter { it.name in read }
                .map { staticIdentity(body, it) }
                .toSet()
                .size
        if (distinct > maxExecutionsPerRefresh) {
            failures.add(
                DashboardErrorCodes.TOO_MANY_INVOCATIONS,
                "sources",
                "The dashboard needs $distinct distinct executions per refresh; one refresh may run $maxExecutionsPerRefresh.",
                mapOf("invocations" to distinct, "max" to maxExecutionsPerRefresh),
            )
        }
    }

    private fun staticIdentity(
        body: DashboardBody,
        source: DashboardSource,
    ): String {
        val bound = source.parameters.mapValues { (_, binding) -> binding.parameter?.let { "@$it" } ?: ("=" + canonical(binding.value)) }
        val overridden = body.outgoingOverrides[source.name].orEmpty().mapValues { (_, literal) -> "=" + canonical(literal.value) }
        return source.pipeline.toString() + "#" + (bound + overridden).toSortedMap()
    }

    /** JSON text with object keys sorted at every level — two spellings of one literal are one. */
    private fun canonical(node: com.fasterxml.jackson.databind.JsonNode?): String =
        when {
            node == null -> "null"
            node.isObject -> node.properties().sortedBy { it.key }.joinToString(",", "{", "}") { "\"${it.key}\":" + canonical(it.value) }
            node.isArray -> node.joinToString(",", "[", "]") { canonical(it) }
            else -> node.toString()
        }

    @Suppress("LongParameterList") // one source's bindings judged against its release, the set and the overrides
    private fun sourceParameters(
        body: DashboardBody,
        source: DashboardSource,
        path: String,
        fact: PipelineReleaseFact,
        setParameters: Set<String>?,
        failures: ArtifactFailures,
    ) {
        val declared = fact.parameters.associateBy { it.name }
        source.parameters.forEach { (name, binding) ->
            if (name !in declared) unknownObject("$path.parameters.$name", name, "pipeline_parameter", failures)
            val setParameter = binding.parameter
            if (setParameter != null && (setParameters == null || setParameter !in setParameters)) {
                failures.add(
                    DashboardErrorCodes.PARAMETER_UNBOUND,
                    "$path.parameters.$name.parameter",
                    "'${setParameter.safeEcho()}' is not a parameter of the pinned set.",
                    mapOf("reason" to "set_parameter_unknown", "parameter" to setParameter.safeEcho()),
                )
            }
        }
        val overridden = body.outgoingOverrides[source.name]?.keys.orEmpty()
        fact.parameters.filter { it.required && it.name !in source.parameters && it.name !in overridden }.forEach {
            failures.add(
                DashboardErrorCodes.PARAMETER_UNBOUND,
                "$path.parameters",
                "Required pipeline parameter '${it.name.safeEcho()}' is bound neither to a set parameter nor to a literal.",
                mapOf("reason" to "required", "parameter" to it.name.safeEcho(), "source" to source.name.safeEcho()),
            )
        }
    }

    private fun occurrences(
        workspaceId: UUID,
        body: DashboardBody,
        names: DashboardNames,
        sources: Map<String, PipelineReleaseFact>,
        failures: ArtifactFailures,
    ) {
        body.visualizations.forEachIndexed { index, occurrence ->
            val path = "visualizations[$index]"
            val pin =
                visualizations
                    .pinOf(
                        workspaceId,
                        occurrence.visualization,
                    )?.takeUnless { it.status == PipelineVersionStatus.DISCARDED }
            if (pin == null) dependencyNotFound("$path.visualization", "visualization", occurrence.visualization, failures)
            occurrence.inputs.forEach { (input, mapping) ->
                if (mapping.source !in names.sources) unknownObject("$path.inputs.$input.source", mapping.source, "source", failures)
            }
            occurrence.timeoutSeconds?.let { outOfRange("$path.timeout_seconds", it, failures) }
            pin ?: return@forEachIndexed
            (pin.body.inputs.keys - occurrence.inputs.keys).forEach { missing ->
                inputUnbound("$path.inputs", missing, "unmapped", "Input '$missing' of the visualization is mapped to no source.", failures)
            }
            (occurrence.inputs.keys - pin.body.inputs.keys).forEach { extra ->
                inputUnbound(
                    "$path.inputs.$extra",
                    extra,
                    "unknown_input",
                    "The visualization declares no input '${extra.safeEcho()}'.",
                    failures,
                )
            }
            occurrence.inputs.forEach { (input, mapping) ->
                val contract = pin.body.inputs[input] ?: return@forEach
                val fact = sources[mapping.source] ?: return@forEach
                contractSatisfied("$path.inputs.$input", contract, fact, failures)
            }
        }
    }

    private fun contractSatisfied(
        path: String,
        contract: InputContract,
        fact: PipelineReleaseFact,
        failures: ArtifactFailures,
    ) {
        // A release that does not DECLARE its caller columns (a SQL caller node) is not judged here: the columns are
        // unknown until it runs, and a guess would refuse or admit on nothing (L1b; the runtime checks them, L2).
        val output = (fact.outputColumns ?: return).associateBy { it.name }
        contract.columns.firstOrNull { output[it.name]?.type != it.type }?.let { column ->
            failures.add(
                DashboardErrorCodes.INPUT_CONTRACT_MISMATCH,
                path,
                "The source's output does not supply column '${column.name.safeEcho()}' as ${column.type.wire}.",
                mapOf(
                    "column" to column.name.safeEcho(),
                    "expected" to column.type.wire,
                    "actual" to output[column.name]?.type?.wire,
                ),
            )
        }
    }

    // ---- the objects -------------------------------------------------------------------------------------

    private fun namespace(
        body: DashboardBody,
        names: DashboardNames,
        failures: ArtifactFailures,
    ) {
        // The set's parameters first, so a clash is reported at the OBJECT's path, which the author can rename.
        val seen = names.parameterPaths.toMap(mutableMapOf())
        names.objectPaths.forEach { (name, path) ->
            if (!DocumentRules.OBJECT_NAME.matches(name)) {
                failures.add(
                    DashboardErrorCodes.DUPLICATE_NAME,
                    path,
                    "An object name is [a-z][a-z0-9_], 64 characters.",
                    mapOf("reason" to "grammar", "name" to name.safeEcho()),
                )
            }
            val first = seen.putIfAbsent(name, path)
            if (first != null) duplicate(name, path, first, failures)
        }
        val sources = mutableMapOf<String, String>()
        body.sources.forEachIndexed { index, source ->
            val first = sources.putIfAbsent(source.name, "sources[$index].name")
            if (first != null) duplicate(source.name, "sources[$index].name", first, failures)
        }
    }

    private fun groups(
        body: DashboardBody,
        names: DashboardNames,
        failures: ArtifactFailures,
    ) {
        body.groups.forEachIndexed { index, group ->
            group.members.forEachIndexed { at, member ->
                if (member !in names.all) unknownObject("groups[$index].members[$at]", member, "object", failures)
            }
        }
    }

    private fun actions(
        body: DashboardBody,
        names: DashboardNames,
        failures: ArtifactFailures,
    ) {
        body.actions.forEachIndexed { index, action ->
            val path = "actions[$index]"
            val empty = action.targets.isEmpty()
            if ((action.scope == ActionScope.TARGETS) == empty) {
                failures.add(
                    DashboardErrorCodes.EMPTY_TARGETS,
                    "$path.targets",
                    "scope 'targets' needs a non-empty targets list; scope 'all' takes none (D55).",
                    mapOf("reason" to if (empty) "empty" else "targets_with_all", "action" to action.name.safeEcho()),
                )
            }
            action.targets.forEachIndexed { at, target ->
                when {
                    target !in names.all -> {
                        unknownObject("$path.targets[$at]", target, "visualization", failures)
                    }

                    target !in names.occurrences -> {
                        failures.add(
                            DashboardErrorCodes.TARGET_NOT_VISUALIZATION,
                            "$path.targets[$at]",
                            "'${target.safeEcho()}' is not a visualization occurrence.",
                            mapOf("target" to target.safeEcho()),
                        )
                    }
                }
            }
        }
    }

    private fun controls(
        body: DashboardBody,
        names: DashboardNames,
        set: ParameterSetFact?,
        failures: ArtifactFailures,
    ) {
        val parameters = set?.parameters?.associateBy { it.name }.orEmpty()
        body.actionControls.forEachIndexed { index, control ->
            val path = "action_controls[$index]"
            if (control.action !in names.actions) unknownObject("$path.action", control.action, "action", failures)
            val parameter = control.parameter ?: return@forEachIndexed
            val fact = parameters[parameter]
            when {
                fact == null -> {
                    unknownObject("$path.parameter", parameter, "parameter", failures)
                }

                fact.isParent -> {
                    failures.add(
                        DashboardErrorCodes.PARENT_ACTION_BINDING,
                        "$path.parameter",
                        "'${parameter.safeEcho()}' has dependents — a parent control cannot invoke an action (R2).",
                        mapOf("parameter" to parameter.safeEcho(), "dependents" to fact.dependents.sorted()),
                    )
                }
            }
        }
    }

    @Suppress("LongParameterList") // the document, its names, the set, the group graph and the collector
    private fun scopes(
        body: DashboardBody,
        names: DashboardNames,
        set: ParameterSetFact?,
        graph: GroupGraph,
        failures: ArtifactFailures,
    ) {
        val consumers = ScopeConsumers(body, set, graph)
        body.parameterScopes.forEach { (parameter, groups) ->
            val path = "parameter_scopes.$parameter"
            if (parameter !in names.parameters) unknownObject(path, parameter, "parameter", failures)
            groups.forEachIndexed { at, group -> if (group !in names.groups) unknownObject("$path[$at]", group, "group", failures) }
            (consumers.groupsConsuming(parameter) - groups.toSet()).sorted().forEach { omitted ->
                failures.add(
                    DashboardErrorCodes.SCOPE_OMITS_CONSUMER,
                    path,
                    "Group '$omitted' consumes '${parameter.safeEcho()}' and is not in its declared scope (D41).",
                    mapOf("parameter" to parameter.safeEcho(), "group" to omitted),
                )
            }
        }
        body.parameterState?.parameters?.keys?.forEach { parameter ->
            if (parameter !in names.parameters) unknownObject("parameter_state.parameters.$parameter", parameter, "parameter", failures)
        }
    }

    private fun overrides(
        body: DashboardBody,
        names: DashboardNames,
        sources: Map<String, PipelineReleaseFact>,
        failures: ArtifactFailures,
    ) {
        body.outgoingOverrides.forEach { (source, parameters) ->
            if (source !in names.sources) {
                unknownObject("outgoing_overrides.$source", source, "source", failures)
                return@forEach
            }
            val declared = sources[source]?.parameters?.map { it.name }?.toSet() ?: return@forEach
            parameters.keys.filter { it !in declared }.forEach {
                unknownObject("outgoing_overrides.$source.$it", it, "pipeline_parameter", failures)
            }
        }
    }

    private fun timeouts(
        body: DashboardBody,
        failures: ArtifactFailures,
    ) {
        body.timeouts?.refreshSeconds?.let { outOfRange("timeouts.refresh_seconds", it, failures) }
    }

    // ---- refusals ----------------------------------------------------------------------------------------

    private fun outOfRange(
        path: String,
        seconds: Int,
        failures: ArtifactFailures,
    ) {
        if (seconds !in 1..maxRefreshSeconds) {
            failures.add(
                DashboardErrorCodes.BODY_INVALID,
                path,
                "A timeout is 1..$maxRefreshSeconds seconds (the executor tier's cap).",
                mapOf("reason" to "out_of_range", "min" to 1, "max" to maxRefreshSeconds),
            )
        }
    }

    private fun duplicate(
        name: String,
        path: String,
        first: String,
        failures: ArtifactFailures,
    ) = failures.add(
        DashboardErrorCodes.DUPLICATE_NAME,
        path,
        "'${name.safeEcho()}' is already the name of $first — one namespace per dashboard.",
        mapOf("name" to name.safeEcho(), "first" to first),
    )

    private fun dependencyNotFound(
        path: String,
        kind: String,
        ref: ArtifactRef,
        failures: ArtifactFailures,
    ) = failures.add(
        DashboardErrorCodes.DEPENDENCY_NOT_FOUND,
        path,
        "The pinned $kind ${ref.toString().safeEcho()} does not exist here.",
        mapOf("kind" to kind, "name" to ref.name.safeEcho(), "version" to ref.version),
    )

    @Suppress("LongParameterList") // the refusal's parts
    private fun inputUnbound(
        path: String,
        input: String,
        reason: String,
        message: String,
        failures: ArtifactFailures,
    ) = failures.add(DashboardErrorCodes.INPUT_UNBOUND, path, message, mapOf("reason" to reason, "input" to input.safeEcho()))

    companion object {
        /** The spec's §9.6 cap until L2 binds `datapipelines.dashboards.timeouts.max-refresh-seconds`. */
        const val DEFAULT_MAX_REFRESH_SECONDS = 900

        /** The shipped `max-executions-per-refresh` — the constructor's default for callers that wire no runtime config. */
        const val DEFAULT_MAX_EXECUTIONS_PER_REFRESH = 16
    }
}

/** A reference to nothing: `unknown_object`, naming the kind of thing expected. */
internal fun unknownObject(
    path: String,
    name: String,
    expected: String,
    failures: ArtifactFailures,
) = failures.add(
    DashboardErrorCodes.UNKNOWN_OBJECT,
    path,
    "'${name.safeEcho()}' names no $expected of this dashboard.",
    mapOf("name" to name.safeEcho(), "expected" to expected),
)
