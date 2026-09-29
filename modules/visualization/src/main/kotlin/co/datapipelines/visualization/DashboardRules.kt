package co.datapipelines.visualization

/** The names a dashboard declares, by kind, with the path each one was declared at (the D15 namespace). */
internal class DashboardNames private constructor(
    val occurrences: Set<String>,
    val groups: Set<String>,
    val actions: Set<String>,
    val controls: Set<String>,
    val parameters: Set<String>,
    val sources: Set<String>,
    /** Every object name and its declaring path, in document order — duplicates included (the namespace rule reads them). */
    val objectPaths: List<Pair<String, String>>,
    /** The pinned set's parameter names — in the namespace, but under their own grammar (`ParameterNameGrammar`). */
    val parameterPaths: List<Pair<String, String>>,
) {
    /** Every name of the one namespace. */
    val all: Set<String> = occurrences + groups + actions + controls + parameters

    /** What a group may hold: occurrences, groups, action controls and set parameters (an action is not placed). */
    val members: Set<String> = occurrences + groups + controls + parameters

    /** What a grid item may position: occurrences, groups and action controls (a parameter is placed by the set's placement). */
    val gridded: Set<String> = occurrences + groups + controls

    companion object {
        fun of(
            body: DashboardBody,
            set: ParameterSetFact?,
        ): DashboardNames {
            val paths = mutableListOf<Pair<String, String>>()
            body.visualizations.forEachIndexed { i, occurrence -> paths += occurrence.name to "visualizations[$i].name" }
            body.groups.forEachIndexed { i, group -> paths += group.name to "groups[$i].name" }
            body.actions.forEachIndexed { i, action -> paths += action.name to "actions[$i].name" }
            body.actionControls.forEachIndexed { i, control -> paths += control.name to "action_controls[$i].name" }
            val parameters = set?.parameters?.map { it.name }.orEmpty()
            return DashboardNames(
                occurrences = body.visualizations.map { it.name }.toSet(),
                groups = body.groups.map { it.name }.toSet(),
                actions = body.actions.map { it.name }.toSet(),
                controls = body.actionControls.map { it.name }.toSet(),
                parameters = parameters.toSet(),
                sources = body.sources.map { it.name }.toSet(),
                objectPaths = paths,
                parameterPaths = parameters.map { it to "parameter_set (parameter '$it')" },
            )
        }
    }
}

/**
 * Which groups consume a parameter (D41, the record's §4.5): a group consumes P when an occurrence inside it —
 * directly or through nested groups — maps an input to a source that binds P, or binds a parameter that depends
 * on P (transitively through the set's `dependents`).
 */
internal class ScopeConsumers(
    private val body: DashboardBody,
    set: ParameterSetFact?,
) {
    private val dependents: Map<String, Set<String>> = set?.parameters?.associate { it.name to it.dependents }.orEmpty()
    private val groupsByName = body.groups.associateBy { it.name }
    private val occurrencesByName = body.visualizations.associateBy { it.name }
    private val sourcesByName = body.sources.associateBy { it.name }

    /** Every group consuming [parameter]. */
    fun groupsConsuming(parameter: String): Set<String> {
        val affected = closure(parameter)
        return body.groups
            .filter { group -> occurrencesIn(group.name).any { consumes(it, affected) } }
            .map { it.name }
            .toSet()
    }

    /** [parameter] and every parameter that depends on it, transitively. */
    private fun closure(parameter: String): Set<String> {
        val seen = mutableSetOf(parameter)
        val pending = ArrayDeque(listOf(parameter))
        while (pending.isNotEmpty()) {
            dependents[pending.removeFirst()].orEmpty().forEach { if (seen.add(it)) pending.addLast(it) }
        }
        return seen
    }

    private fun consumes(
        occurrence: VisualizationOccurrence,
        affected: Set<String>,
    ): Boolean =
        occurrence.inputs.values.any { mapping ->
            sourcesByName[mapping.source]?.parameters?.values?.any { it.parameter in affected } ?: false
        }

    /** The occurrences inside [group], through nested groups — a cycle is walked once (the layout rule refuses it). */
    private fun occurrencesIn(group: String): List<VisualizationOccurrence> {
        val seen = mutableSetOf(group)
        val pending = ArrayDeque(listOf(group))
        val found = mutableListOf<VisualizationOccurrence>()
        while (pending.isNotEmpty()) {
            groupsByName[pending.removeFirst()]?.members?.forEach { member ->
                occurrencesByName[member]?.let(found::add)
                if (member in groupsByName && seen.add(member)) pending.addLast(member)
            }
        }
        return found
    }
}

/** The layout rules (the spec's §3.2 `layout`, §17's 12 columns) — see [DashboardValidator]'s KDoc for the placement reading. */
internal class DashboardLayoutRules(
    private val body: DashboardBody,
    private val names: DashboardNames,
    private val failures: ArtifactFailures,
) {
    private val layout = body.layout

    fun check() {
        if (layout.columns != DashboardLayout.GRID_COLUMNS) {
            invalid("layout.columns", "columns", "The grid has ${DashboardLayout.GRID_COLUMNS} columns.")
        }
        layout.breakpointPx?.let {
            if (it !in
                1..MAX_BREAKPOINT_PX
            ) {
                invalid("layout.breakpoint_px", "breakpoint", "breakpoint_px is 1..$MAX_BREAKPOINT_PX.")
            }
        }
        val gridCount = grid()
        val memberCount = memberships()
        placements(gridCount, memberCount)
        parameterPlacements()
    }

    /** Validates the grid items; answers how often each name is gridded. */
    private fun grid(): Map<String, Int> {
        val count = mutableMapOf<String, Int>()
        layout.grid.forEachIndexed { index, item ->
            val path = "layout.grid[$index]"
            when {
                item.name !in names.all -> {
                    unknownObject("$path.name", item.name, "placeable object", failures)
                }

                item.name !in names.gridded -> {
                    invalid(
                        "$path.name",
                        "not_placeable",
                        "'${item.name.safeEcho()}' is not placed by the grid.",
                    )
                }
            }
            count.merge(item.name, 1, Int::plus)
            if ((count[item.name] ?: 0) > 1) invalid("$path.name", "duplicate", "'${item.name.safeEcho()}' has two grid items.")
            if (!item.fits(layout.columns)) {
                invalid(
                    path,
                    "geometry",
                    "A grid item sits inside ${layout.columns} columns: x ≥ 0, y ≥ 0, w ≥ 1, h ≥ 1, x + w ≤ ${layout.columns}.",
                )
            }
        }
        return count
    }

    /** Validates the group memberships; answers how often each name is a member. */
    private fun memberships(): Map<String, Int> {
        val count = mutableMapOf<String, Int>()
        body.groups.forEachIndexed { index, group ->
            group.members.forEachIndexed { at, member ->
                val path = "groups[$index].members[$at]"
                if (member in names.all && member !in names.members) invalid(path, "not_placeable", "An action is not a group member.")
                count.merge(member, 1, Int::plus)
                if ((count[member] ?: 0) == 2) invalid(path, "multiple_parents", "'${member.safeEcho()}' is a member of two groups.")
            }
        }
        cycles()
        return count
    }

    private fun cycles() {
        val children = body.groups.associate { group -> group.name to group.members.filter { it in names.groups } }
        body.groups.forEachIndexed { index, group ->
            val seen = mutableSetOf<String>()
            val pending = ArrayDeque(children[group.name].orEmpty())
            while (pending.isNotEmpty()) {
                val next = pending.removeFirst()
                if (next == group.name) {
                    invalid("groups[$index].members", "cycle", "Group '${group.name.safeEcho()}' contains itself.")
                    return@forEachIndexed
                }
                if (seen.add(next)) pending.addAll(children[next].orEmpty())
            }
        }
    }

    /**
     * Where each object is placed — "gridded" and "has a parent" count ONCE each: a second grid item and a second
     * parent are their own refusals (`duplicate`, `multiple_parents`), never a second report of this one.
     */
    private fun placements(
        grid: Map<String, Int>,
        members: Map<String, Int>,
    ) {
        fun placed(name: String): Int = (if ((grid[name] ?: 0) > 0) 1 else 0) + (if ((members[name] ?: 0) > 0) 1 else 0)
        body.visualizations.forEachIndexed { index, occurrence ->
            if ((grid[occurrence.name] ?: 0) == 0) {
                invalid("visualizations[$index]", "unplaced", "Occurrence '${occurrence.name.safeEcho()}' has no grid item.")
            }
        }
        body.actionControls.forEachIndexed { index, control ->
            when (placed(control.name)) {
                0 -> {
                    invalid(
                        "action_controls[$index]",
                        "unplaced",
                        "Control '${control.name.safeEcho()}' is neither gridded nor a group member.",
                    )
                }

                1 -> {
                    // placed exactly once — the rule holds
                }

                else -> {
                    invalid("action_controls[$index]", "placed_twice", "Control '${control.name.safeEcho()}' is placed twice.")
                }
            }
        }
        body.groups.forEachIndexed { index, group ->
            if (placed(group.name) > 1) {
                invalid("groups[$index]", "placed_twice", "Group '${group.name.safeEcho()}' is placed twice.")
            }
        }
    }

    private fun parameterPlacements() {
        layout.parameterPlacements.forEach { (parameter, placement) ->
            val path = "layout.parameter_placements.$parameter"
            if (parameter !in names.parameters) unknownObject(path, parameter, "parameter", failures)
            placement.group?.let { if (it !in names.groups) unknownObject("$path.group", it, "group", failures) }
        }
    }

    private fun invalid(
        path: String,
        reason: String,
        message: String,
    ) = failures.add(DashboardErrorCodes.LAYOUT_INVALID, path, message, mapOf("reason" to reason))

    /** Inside the grid: a non-negative origin, a positive size, the right edge within [columns]. */
    private fun GridItem.fits(columns: Int): Boolean = x >= 0 && y >= 0 && w >= 1 && h >= 1 && x + w <= columns

    private companion object {
        const val MAX_BREAKPOINT_PX = 10_000
    }
}
