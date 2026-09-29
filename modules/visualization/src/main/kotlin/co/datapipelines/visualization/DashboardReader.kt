package co.datapipelines.visualization

import co.datapipelines.parameters.ParametersKey
import com.fasterxml.jackson.databind.JsonNode

/**
 * The ONE way a dashboard document gets in from JSON — the [VisualizationReader] discipline over the spec's
 * §3.2 tree: an explicit key table per level; an unknown key, a wrong JSON type, a missing key, an
 * out-of-vocabulary literal or an object whose `type` does not match its list is
 * `dashboard.validation.body_invalid` naming the path (`details.reason` `unknown_key` / `wrong_type` /
 * `missing` / `not_allowed` / `wrong_object_type`); a parameter binding naming both or neither of
 * `parameter`/`value`, and a placement naming both or neither of `group`/`region`, is `body_invalid` /
 * `ambiguous`. The name's absence is `name_invalid`. `visualizations` is bounded by
 * `datapipelines.visualization.max-visualizations-per-dashboard` BEFORE its members are walked.
 *
 * Every rule that needs the whole document or another aggregate is [DashboardValidator]'s.
 */
class DashboardReader(
    private val config: VisualizationConfig = VisualizationConfig(),
) {
    /** Reads [tree] into a [DashboardDocument], or reports every shape problem at once. */
    fun read(tree: JsonNode): ReadOutcome<DashboardDocument> {
        val failures = ArtifactFailures(DashboardErrorCodes.BODY_INVALID)
        DashboardScan(config, failures).document(tree)
        if (!failures.isEmpty) return ReadOutcome.Refused(failures.toResult())
        return when (val bound = DocumentBinding.bind(tree, DashboardBody::class.java, DashboardErrorCodes.BODY_INVALID)) {
            is ReadOutcome.Read -> ReadOutcome.Read(DashboardDocument(bound.document.first, bound.document.second))
            is ReadOutcome.Refused -> bound
        }
    }

    /** [read] that throws [ArtifactValidationException] instead of returning a refusal. */
    fun readOrThrow(tree: JsonNode): DashboardDocument =
        when (val outcome = read(tree)) {
            is ReadOutcome.Read -> outcome.document
            is ReadOutcome.Refused -> throw ArtifactValidationException(outcome.result, DashboardErrorCodes.BODY_INVALID)
        }

    companion object {
        /** `details.reason` for an object whose `type` is not its list's. */
        const val REASON_WRONG_OBJECT_TYPE = "wrong_object_type"

        /** `details.reason` for a binding or placement naming both or neither of its alternatives. */
        const val REASON_AMBIGUOUS = "ambiguous"

        /**
         * A source binds a parameter set's parameters, and no set declares more than the parameters module's ceiling
         * (`max-parameters-per-set`'s maximum) — a wider map is refused before its members are walked, so the scope
         * pass over a dashboard stays inputs + bindings.
         */
        val MAX_SOURCE_PARAMETERS: Int = checkNotNull(ParametersKey.MAX_PARAMETERS_PER_SET.max).toInt()

        /** The keys of each level — the binding's own properties, pinned equal by `DashboardReaderTest`. */
        val DOCUMENT_KEYS: Set<String> =
            setOf(
                "name",
                "display_name",
                "description",
                "parameter_set",
                "sources",
                "visualizations",
                "groups",
                "actions",
                "action_controls",
                "parameter_scopes",
                "parameter_state",
                "outgoing_overrides",
                "layout",
                "timeouts",
            )
        val SOURCE_KEYS: Set<String> = setOf("name", "pipeline", "parameters")
        val PARAMETER_BINDING_KEYS: Set<String> = setOf("parameter", "value")
        val OCCURRENCE_KEYS: Set<String> = setOf("name", "type", "visualization", "inputs", "timeout_seconds")
        val INPUT_MAPPING_KEYS: Set<String> = setOf("source")
        val GROUP_KEYS: Set<String> = setOf("name", "type", "members")
        val ACTION_KEYS: Set<String> = setOf("name", "type", "scope", "targets", "initial")
        val ACTION_CONTROL_KEYS: Set<String> = setOf("name", "type", "action", "label", "parameter")
        val PARAMETER_STATE_KEYS: Set<String> = setOf("dashboard", "parameters")
        val STATE_OVERRIDE_KEYS: Set<String> = setOf("visible", "enabled")
        val LITERAL_KEYS: Set<String> = setOf("value")
        val LAYOUT_KEYS: Set<String> = setOf("parameter_set", "parameter_placements", "grid", "columns", "breakpoint_px")
        val SET_PLACEMENT_KEYS: Set<String> = setOf("position")
        val PLACEMENT_KEYS: Set<String> = setOf("group", "region")
        val GRID_ITEM_KEYS: Set<String> = setOf("name", "x", "y", "w", "h")
        val TIMEOUT_KEYS: Set<String> = setOf("refresh_seconds")
    }
}

/** One walk over one dashboard document; [document] is the whole API. */
@Suppress("TooManyFunctions") // one function per level of the spec's §3.2 tree, deliberately flat
private class DashboardScan(
    private val config: VisualizationConfig,
    private val failures: ArtifactFailures,
) {
    private val scan = JsonScan(failures, DashboardErrorCodes.BODY_INVALID)

    fun document(tree: JsonNode) {
        if (!tree.isObject) {
            scan.wrongType("", "object", tree)
            return
        }
        scan.unknownKeys(tree, DashboardReader.DOCUMENT_KEYS, "")
        scan.requiredText(tree, "name", "name", DashboardErrorCodes.NAME_INVALID)
        scan.requiredText(tree, "display_name", "display_name")
        scan.optionalText(tree, "description", "description")
        scan.present(tree, "parameter_set")?.let { scan.ref(it, "parameter_set") }
        list(tree, "sources", required = false) { node, path -> source(node, path) }
        occurrences(tree)
        list(tree, "groups", required = false) { node, path -> group(node, path) }
        list(tree, "actions", required = false) { node, path -> action(node, path) }
        list(tree, "action_controls", required = false) { node, path -> actionControl(node, path) }
        scan.objectAt(tree, "parameter_scopes", "parameter_scopes", required = false)?.let(::parameterScopes)
        scan.objectAt(tree, "parameter_state", "parameter_state", required = false)?.let(::parameterState)
        scan.objectAt(tree, "outgoing_overrides", "outgoing_overrides", required = false)?.let(::outgoingOverrides)
        scan.objectAt(tree, "layout", "layout", required = true)?.let(::layout)
        scan.objectAt(tree, "timeouts", "timeouts", required = false)?.let { timeouts ->
            scan.unknownKeys(timeouts, DashboardReader.TIMEOUT_KEYS, "timeouts")
            scan.optionalInt(timeouts, "refresh_seconds", "timeouts.refresh_seconds")
        }
    }

    private fun occurrences(tree: JsonNode) {
        val occurrences = scan.arrayAt(tree, "visualizations", "visualizations", required = true) ?: return
        if (occurrences.size() > config.maxVisualizationsPerDashboard) {
            scan.tooMany(
                "visualizations",
                occurrences.size(),
                VisualizationKey.MAX_VISUALIZATIONS_PER_DASHBOARD,
                config.maxVisualizationsPerDashboard,
            )
            return
        }
        occurrences.forEachIndexed { index, node -> objectOf(node, "visualizations[$index]") { occurrence(it, "visualizations[$index]") } }
    }

    private fun source(
        node: JsonNode,
        path: String,
    ) {
        scan.unknownKeys(node, DashboardReader.SOURCE_KEYS, path)
        scan.requiredText(node, "name", "$path.name")
        scan.present(node, "pipeline")?.let { scan.ref(it, "$path.pipeline") } ?: scan.missing("$path.pipeline")
        scan.objectAt(node, "parameters", "$path.parameters", required = false)?.let { parameters ->
            if (parameters.size() > DashboardReader.MAX_SOURCE_PARAMETERS) {
                scan.tooMany(
                    "$path.parameters",
                    parameters.size(),
                    ParametersKey.MAX_PARAMETERS_PER_SET.path,
                    DashboardReader.MAX_SOURCE_PARAMETERS,
                )
            } else {
                parameters.properties().forEach { (name, binding) ->
                    objectOf(binding, "$path.parameters.$name") { parameterBinding(it, "$path.parameters.$name") }
                }
            }
        }
    }

    private fun parameterBinding(
        node: JsonNode,
        path: String,
    ) {
        scan.unknownKeys(node, DashboardReader.PARAMETER_BINDING_KEYS, path)
        scan.optionalText(node, "parameter", "$path.parameter")
        scan.present(node, "value")?.let { scan.depthWithin(it, "$path.value", JsonScan.MAX_LITERAL_DEPTH) }
        exactlyOne(node, "parameter", "value", path)
    }

    private fun occurrence(
        node: JsonNode,
        path: String,
    ) {
        scan.unknownKeys(node, DashboardReader.OCCURRENCE_KEYS, path)
        scan.requiredText(node, "name", "$path.name")
        objectType(node, path, DashboardObjectType.VISUALIZATION)
        scan.present(node, "visualization")?.let { scan.ref(it, "$path.visualization") } ?: scan.missing("$path.visualization")
        scan.objectAt(node, "inputs", "$path.inputs", required = false)?.let { inputs ->
            if (inputs.size() > config.maxInputsPerVisualization) {
                scan.tooMany("$path.inputs", inputs.size(), VisualizationKey.MAX_INPUTS_PER_VISUALIZATION, config.maxInputsPerVisualization)
            } else {
                inputs.properties().forEach { (input, mapping) ->
                    objectOf(mapping, "$path.inputs.$input") {
                        scan.unknownKeys(it, DashboardReader.INPUT_MAPPING_KEYS, "$path.inputs.$input")
                        scan.requiredText(it, "source", "$path.inputs.$input.source")
                    }
                }
            }
        }
        scan.optionalInt(node, "timeout_seconds", "$path.timeout_seconds")
    }

    private fun group(
        node: JsonNode,
        path: String,
    ) {
        scan.unknownKeys(node, DashboardReader.GROUP_KEYS, path)
        scan.requiredText(node, "name", "$path.name")
        objectType(node, path, DashboardObjectType.GROUP)
        scan.arrayAt(node, "members", "$path.members", required = false)?.let { scan.stringElements(it, "$path.members") }
    }

    private fun action(
        node: JsonNode,
        path: String,
    ) {
        scan.unknownKeys(node, DashboardReader.ACTION_KEYS, path)
        scan.requiredText(node, "name", "$path.name")
        objectType(node, path, DashboardObjectType.REFRESH)
        scan.literal(node, "scope", "$path.scope", ActionScope.WIRE_VALUES, required = true)
        scan.arrayAt(node, "targets", "$path.targets", required = false)?.let { scan.stringElements(it, "$path.targets") }
        scan.optionalBoolean(node, "initial", "$path.initial")
    }

    private fun actionControl(
        node: JsonNode,
        path: String,
    ) {
        scan.unknownKeys(node, DashboardReader.ACTION_CONTROL_KEYS, path)
        scan.requiredText(node, "name", "$path.name")
        objectType(node, path, DashboardObjectType.ACTION_CONTROL)
        scan.requiredText(node, "action", "$path.action")
        scan.optionalText(node, "label", "$path.label")
        scan.optionalText(node, "parameter", "$path.parameter")
    }

    private fun parameterScopes(node: JsonNode) {
        node.properties().forEach { (parameter, groups) ->
            if (!groups.isArray) {
                scan.wrongType(
                    "parameter_scopes.$parameter",
                    "array",
                    groups,
                )
            } else {
                scan.stringElements(groups, "parameter_scopes.$parameter")
            }
        }
    }

    private fun parameterState(node: JsonNode) {
        scan.unknownKeys(node, DashboardReader.PARAMETER_STATE_KEYS, "parameter_state")
        scan
            .objectAt(
                node,
                "dashboard",
                "parameter_state.dashboard",
                required = false,
            )?.let { stateOverride(it, "parameter_state.dashboard") }
        scan.objectAt(node, "parameters", "parameter_state.parameters", required = false)?.properties()?.forEach { (name, state) ->
            objectOf(state, "parameter_state.parameters.$name") { stateOverride(it, "parameter_state.parameters.$name") }
        }
    }

    private fun stateOverride(
        node: JsonNode,
        path: String,
    ) {
        scan.unknownKeys(node, DashboardReader.STATE_OVERRIDE_KEYS, path)
        scan.literal(node, "visible", "$path.visible", StateSetting.WIRE_VALUES)
        scan.literal(node, "enabled", "$path.enabled", StateSetting.WIRE_VALUES)
    }

    private fun outgoingOverrides(node: JsonNode) {
        node.properties().forEach { (source, parameters) ->
            objectOf(parameters, "outgoing_overrides.$source") { byParameter ->
                byParameter.properties().forEach { (parameter, literal) ->
                    val path = "outgoing_overrides.$source.$parameter"
                    objectOf(literal, path) {
                        scan.unknownKeys(it, DashboardReader.LITERAL_KEYS, path)
                        val value = scan.present(it, "value")
                        if (value ==
                            null
                        ) {
                            scan.missing("$path.value")
                        } else {
                            scan.depthWithin(value, "$path.value", JsonScan.MAX_LITERAL_DEPTH)
                        }
                    }
                }
            }
        }
    }

    private fun layout(node: JsonNode) {
        scan.unknownKeys(node, DashboardReader.LAYOUT_KEYS, "layout")
        scan.objectAt(node, "parameter_set", "layout.parameter_set", required = false)?.let {
            scan.unknownKeys(it, DashboardReader.SET_PLACEMENT_KEYS, "layout.parameter_set")
            scan.literal(it, "position", "layout.parameter_set.position", LayoutPosition.WIRE_VALUES, required = true)
        }
        val placements = scan.objectAt(node, "parameter_placements", "layout.parameter_placements", required = false)
        placements?.properties()?.forEach { (name, placement) ->
            val path = "layout.parameter_placements.$name"
            objectOf(placement, path) {
                scan.unknownKeys(it, DashboardReader.PLACEMENT_KEYS, path)
                scan.optionalText(it, "group", "$path.group")
                scan.literal(it, "region", "$path.region", LayoutPosition.WIRE_VALUES)
                exactlyOne(it, "group", "region", path)
            }
        }
        scan.arrayAt(node, "grid", "layout.grid", required = false)?.forEachIndexed { index, item ->
            val path = "layout.grid[$index]"
            objectOf(item, path) {
                scan.unknownKeys(it, DashboardReader.GRID_ITEM_KEYS, path)
                scan.requiredText(it, "name", "$path.name")
                listOf("x", "y", "w", "h").forEach { key -> scan.requiredInt(it, key, "$path.$key") }
            }
        }
        scan.optionalInt(node, "columns", "layout.columns")
        scan.optionalInt(node, "breakpoint_px", "layout.breakpoint_px")
    }

    // ---- shared shapes -----------------------------------------------------------------------------------

    /** An optional (or required) array of objects at [key], each walked by [walk]. */
    private fun list(
        tree: JsonNode,
        key: String,
        required: Boolean,
        walk: (JsonNode, String) -> Unit,
    ) {
        scan.arrayAt(tree, key, key, required)?.forEachIndexed {
            index,
            node,
            ->
            objectOf(node, "$key[$index]") { walk(it, "$key[$index]") }
        }
    }

    private inline fun objectOf(
        node: JsonNode,
        path: String,
        walk: (JsonNode) -> Unit,
    ) {
        if (node.isObject) walk(node) else scan.wrongType(path, "object", node)
    }

    /** `type` is mandatory and must be [expected] — the list the object sits in decides it (the spec's §3.2). */
    private fun objectType(
        node: JsonNode,
        path: String,
        expected: DashboardObjectType,
    ) {
        val type = scan.present(node, "type")
        when {
            type == null -> {
                scan.missing("$path.type")
            }

            !type.isTextual -> {
                scan.wrongType("$path.type", "string", type)
            }

            type.asText() != expected.wire -> {
                failures.add(
                    DashboardErrorCodes.BODY_INVALID,
                    "$path.type",
                    "An object in this list has type '${expected.wire}'; got '${type.asText().safeEcho()}'.",
                    mapOf("reason" to DashboardReader.REASON_WRONG_OBJECT_TYPE, "allowed" to listOf(expected.wire)),
                )
            }
        }
    }

    /** Exactly one of [first] / [second] is present (null ≡ absent). */
    private fun exactlyOne(
        node: JsonNode,
        first: String,
        second: String,
        path: String,
    ) {
        val count = listOf(first, second).count { scan.present(node, it) != null }
        if (count != 1) {
            failures.add(
                DashboardErrorCodes.BODY_INVALID,
                path,
                "Exactly one of '$first' and '$second' is required here; got $count.",
                mapOf("reason" to DashboardReader.REASON_AMBIGUOUS, "allowed" to listOf(first, second)),
            )
        }
    }
}
