package co.datapipelines.visualization

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.JsonNode

/**
 * A dashboard as an author submits it (the spec's §3.2, Dashboards §2.2): its folder-path [name] and its
 * versioned [body]; the name is outside the body, as for a visualization. [DashboardReader] is the only way in.
 */
data class DashboardDocument(
    val name: String,
    val body: DashboardBody,
)

/**
 * The versioned dashboard artifact (the spec's §3.2, field for field). Object names — every visualization
 * occurrence, group, action and action control — and the pinned set's parameter names share ONE namespace
 * (D15); `type` is mandatory on every object and matches the list it sits in.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DashboardBody(
    @field:JsonProperty("display_name") @get:JsonProperty("display_name") @param:JsonProperty("display_name")
    val displayName: String,
    @field:JsonProperty("description") @get:JsonProperty("description") @param:JsonProperty("description")
    val description: String? = null,
    /** At most one pinned set release (D40); absent for a dashboard without parameters. */
    @field:JsonProperty("parameter_set") @get:JsonProperty("parameter_set") @param:JsonProperty("parameter_set")
    val parameterSet: ArtifactRef? = null,
    @field:JsonProperty("sources") @get:JsonProperty("sources") @param:JsonProperty("sources")
    val sources: List<DashboardSource> = emptyList(),
    @field:JsonProperty("visualizations") @get:JsonProperty("visualizations") @param:JsonProperty("visualizations")
    val visualizations: List<VisualizationOccurrence>,
    @field:JsonProperty("groups") @get:JsonProperty("groups") @param:JsonProperty("groups")
    val groups: List<DashboardGroup> = emptyList(),
    @field:JsonProperty("actions") @get:JsonProperty("actions") @param:JsonProperty("actions")
    val actions: List<DashboardAction> = emptyList(),
    @field:JsonProperty("action_controls") @get:JsonProperty("action_controls") @param:JsonProperty("action_controls")
    val actionControls: List<ActionControl> = emptyList(),
    /** Parameter → the groups its change makes stale (D41); an absent parameter is unscoped. */
    @field:JsonProperty("parameter_scopes") @get:JsonProperty("parameter_scopes") @param:JsonProperty("parameter_scopes")
    val parameterScopes: Map<String, List<String>> = emptyMap(),
    @field:JsonProperty("parameter_state") @get:JsonProperty("parameter_state") @param:JsonProperty("parameter_state")
    val parameterState: ParameterStateOverrides? = null,
    /** Source → pipeline parameter → the literal the pipeline receives whatever the control showed (D23). */
    @field:JsonProperty("outgoing_overrides") @get:JsonProperty("outgoing_overrides") @param:JsonProperty("outgoing_overrides")
    val outgoingOverrides: Map<String, Map<String, LiteralValue>> = emptyMap(),
    @field:JsonProperty("layout") @get:JsonProperty("layout") @param:JsonProperty("layout")
    val layout: DashboardLayout,
    @field:JsonProperty("timeouts") @get:JsonProperty("timeouts") @param:JsonProperty("timeouts")
    val timeouts: DashboardTimeouts? = null,
)

/** One source: a pinned RELEASED pipeline version (D1) and how each of its parameters is bound. */
data class DashboardSource(
    @field:JsonProperty("name") @get:JsonProperty("name") @param:JsonProperty("name")
    val name: String,
    @field:JsonProperty("pipeline") @get:JsonProperty("pipeline") @param:JsonProperty("pipeline")
    val pipeline: ArtifactRef,
    @field:JsonProperty("parameters") @get:JsonProperty("parameters") @param:JsonProperty("parameters")
    val parameters: Map<String, ParameterBinding> = emptyMap(),
)

/** A pipeline parameter bound EITHER to a set parameter (`{parameter}`) OR to a literal (`{value}`) — never both. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ParameterBinding(
    @field:JsonProperty("parameter") @get:JsonProperty("parameter") @param:JsonProperty("parameter")
    val parameter: String? = null,
    /** The literal, coerced by the pipeline's own binder at refresh. */
    @field:JsonProperty("value") @get:JsonProperty("value") @param:JsonProperty("value")
    val value: JsonNode? = null,
)

/** One outgoing-override literal (D23). */
data class LiteralValue(
    @field:JsonProperty("value") @get:JsonProperty("value") @param:JsonProperty("value")
    val value: JsonNode,
)

/** One visualization occurrence — the dashboard-local name of a pinned visualization release (D30, D33). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class VisualizationOccurrence(
    @field:JsonProperty("name") @get:JsonProperty("name") @param:JsonProperty("name")
    val name: String,
    @field:JsonProperty("type") @get:JsonProperty("type") @param:JsonProperty("type")
    val type: DashboardObjectType,
    @field:JsonProperty("visualization") @get:JsonProperty("visualization") @param:JsonProperty("visualization")
    val visualization: ArtifactRef,
    /** Visualization input name → the source that feeds it. */
    @field:JsonProperty("inputs") @get:JsonProperty("inputs") @param:JsonProperty("inputs")
    val inputs: Map<String, InputMapping> = emptyMap(),
    @field:JsonProperty("timeout_seconds") @get:JsonProperty("timeout_seconds") @param:JsonProperty("timeout_seconds")
    val timeoutSeconds: Int? = null,
)

/** A visualization input's source. */
data class InputMapping(
    @field:JsonProperty("source") @get:JsonProperty("source") @param:JsonProperty("source")
    val source: String,
)

/** A dashboard-owned container (D47): composition and action scope, never a name or evaluation scope. */
data class DashboardGroup(
    @field:JsonProperty("name") @get:JsonProperty("name") @param:JsonProperty("name")
    val name: String,
    @field:JsonProperty("type") @get:JsonProperty("type") @param:JsonProperty("type")
    val type: DashboardObjectType,
    @field:JsonProperty("members") @get:JsonProperty("members") @param:JsonProperty("members")
    val members: List<String> = emptyList(),
)

/** A refresh action (D17, D55, D57): its scope, its explicit targets, and whether bootstrap invokes it (R22). */
data class DashboardAction(
    @field:JsonProperty("name") @get:JsonProperty("name") @param:JsonProperty("name")
    val name: String,
    @field:JsonProperty("type") @get:JsonProperty("type") @param:JsonProperty("type")
    val type: DashboardObjectType,
    @field:JsonProperty("scope") @get:JsonProperty("scope") @param:JsonProperty("scope")
    val scope: ActionScope,
    @field:JsonProperty("targets") @get:JsonProperty("targets") @param:JsonProperty("targets")
    val targets: List<String> = emptyList(),
    @field:JsonProperty("initial") @get:JsonProperty("initial") @param:JsonProperty("initial")
    val initial: Boolean = false,
)

/**
 * A control bound to an action (D17, D42). Without [parameter] it is a button — an EXPLICIT action; with
 * [parameter] it binds the committed change of that parameter's control — an AUTOMATIC action, refused on a
 * parent parameter (R2). The key is an L1a addition: the spec names `parent_action_binding` and no field.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ActionControl(
    @field:JsonProperty("name") @get:JsonProperty("name") @param:JsonProperty("name")
    val name: String,
    @field:JsonProperty("type") @get:JsonProperty("type") @param:JsonProperty("type")
    val type: DashboardObjectType,
    @field:JsonProperty("action") @get:JsonProperty("action") @param:JsonProperty("action")
    val action: String,
    @field:JsonProperty("label") @get:JsonProperty("label") @param:JsonProperty("label")
    val label: String? = null,
    @field:JsonProperty("parameter") @get:JsonProperty("parameter") @param:JsonProperty("parameter")
    val parameter: String? = null,
)

/** The server's hide/show and enable/disable overrides (D20): dashboard-wide, then per-parameter exceptions. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ParameterStateOverrides(
    @field:JsonProperty("dashboard") @get:JsonProperty("dashboard") @param:JsonProperty("dashboard")
    val dashboard: StateOverride? = null,
    @field:JsonProperty("parameters") @get:JsonProperty("parameters") @param:JsonProperty("parameters")
    val parameters: Map<String, StateOverride> = emptyMap(),
)

/** One override: each dimension inherits the engine's state or forces it (the record's §4.4). */
data class StateOverride(
    @field:JsonProperty("visible") @get:JsonProperty("visible") @param:JsonProperty("visible")
    val visible: StateSetting = StateSetting.INHERIT,
    @field:JsonProperty("enabled") @get:JsonProperty("enabled") @param:JsonProperty("enabled")
    val enabled: StateSetting = StateSetting.INHERIT,
)

/** The system layout (D25, D32, R5, R6): the set's region, per-parameter placements, the 12-column grid. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DashboardLayout(
    @field:JsonProperty("parameter_set") @get:JsonProperty("parameter_set") @param:JsonProperty("parameter_set")
    val parameterSet: SetPlacement? = null,
    @field:JsonProperty("parameter_placements") @get:JsonProperty("parameter_placements") @param:JsonProperty("parameter_placements")
    val parameterPlacements: Map<String, ParameterPlacement> = emptyMap(),
    @field:JsonProperty("grid") @get:JsonProperty("grid") @param:JsonProperty("grid")
    val grid: List<GridItem> = emptyList(),
    @field:JsonProperty("columns") @get:JsonProperty("columns") @param:JsonProperty("columns")
    val columns: Int = GRID_COLUMNS,
    /** Below this width every item spans the full width in grid order (the spec's §3.2; 768 when absent). */
    @field:JsonProperty("breakpoint_px") @get:JsonProperty("breakpoint_px") @param:JsonProperty("breakpoint_px")
    val breakpointPx: Int? = null,
) {
    companion object {
        /** The spec's §17: the grid is 12 columns. */
        const val GRID_COLUMNS = 12

        /** The spec's §17: the responsive breakpoint when none is declared. */
        const val DEFAULT_BREAKPOINT_PX = 768
    }
}

/** Where the whole set's controls sit (R6). */
data class SetPlacement(
    @field:JsonProperty("position") @get:JsonProperty("position") @param:JsonProperty("position")
    val position: LayoutPosition,
)

/** One parameter's placement — into a group OR a region, never both (the record's §7.4: move, never duplicate). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ParameterPlacement(
    @field:JsonProperty("group") @get:JsonProperty("group") @param:JsonProperty("group")
    val group: String? = null,
    @field:JsonProperty("region") @get:JsonProperty("region") @param:JsonProperty("region")
    val region: LayoutPosition? = null,
)

/** One grid item in 12-column units. */
data class GridItem(
    @field:JsonProperty("name") @get:JsonProperty("name") @param:JsonProperty("name")
    val name: String,
    @field:JsonProperty("x") @get:JsonProperty("x") @param:JsonProperty("x")
    val x: Int,
    @field:JsonProperty("y") @get:JsonProperty("y") @param:JsonProperty("y")
    val y: Int,
    @field:JsonProperty("w") @get:JsonProperty("w") @param:JsonProperty("w")
    val w: Int,
    @field:JsonProperty("h") @get:JsonProperty("h") @param:JsonProperty("h")
    val h: Int,
)

/** The explicit refresh deadline (the spec's §9.6); the occurrence's own `timeout_seconds` may be earlier. */
data class DashboardTimeouts(
    @field:JsonProperty("refresh_seconds") @get:JsonProperty("refresh_seconds") @param:JsonProperty("refresh_seconds")
    val refreshSeconds: Int? = null,
)

/** The object `type` vocabulary (enums.md §34): each list admits exactly one. */
enum class DashboardObjectType(
    @JsonValue val wire: String,
) {
    VISUALIZATION("visualization"),
    GROUP("group"),
    REFRESH("refresh"),
    ACTION_CONTROL("action_control"),
    ;

    companion object {
        fun fromWire(value: String): DashboardObjectType? = entries.firstOrNull { it.wire == value }
    }
}

/** An action's scope (enums.md §35, D55). */
enum class ActionScope(
    @JsonValue val wire: String,
) {
    ALL("all"),
    TARGETS("targets"),
    ;

    companion object {
        val WIRE_VALUES: List<String> = entries.map { it.wire }
    }
}

/** A parameter-state dimension's setting (enums.md §36, D20). */
enum class StateSetting(
    @JsonValue val wire: String,
) {
    INHERIT("inherit"),
    FORCE_TRUE("force_true"),
    FORCE_FALSE("force_false"),
    ;

    companion object {
        val WIRE_VALUES: List<String> = entries.map { it.wire }
    }
}

/** A layout region (enums.md §37, R6). */
enum class LayoutPosition(
    @JsonValue val wire: String,
) {
    LEFT("left"),
    RIGHT("right"),
    TOP("top"),
    BOTTOM("bottom"),
    ;

    companion object {
        val WIRE_VALUES: List<String> = entries.map { it.wire }
    }
}
