package co.datapipelines.visualization

/**
 * The scale policy of a mark (design record 2026-11-14-js-rendered-charts §9 G2, "The layer policy"; §4.4 G4 and G8):
 * whether a value axis drawn for the mark always holds zero, and whether it admits a log scale. Written once, as a table
 * by mark: a [ChartType] names the row of its marks ([ChartType.policy]), and a shape inside a layer object (an
 * [ChannelNode.ObjectOf] whose `layer` names the member holding the layer's mark) takes the row of that layer's mark,
 * so one type with layers admits a log scale on a line layer and refuses it on an area layer. A type lane names one of
 * these rows or adds one; it adds no line of [ChartSaveRules].
 */
enum class ChartMarkPolicy(
    val wire: String,
    val alwaysZero: Boolean,
    val logAdmitted: Boolean,
) {
    /** A bar grows from zero: its value axis always holds zero; a log value axis is admitted (a bar grows from its lower bound). */
    BAR("bar", alwaysZero = true, logAdmitted = true),

    /** A line's value axis may leave zero out; log admitted. */
    LINE("line", alwaysZero = false, logAdmitted = true),

    /** An area is filled down to zero: the axis always holds zero, so a log scale (which has no zero) is refused. */
    AREA("area", alwaysZero = true, logAdmitted = false),

    /** A point marks a position: zero free; log admitted. */
    POINT("point", alwaysZero = false, logAdmitted = true),
    ;

    companion object {
        /** The row of [mark]; a mark no row names is a descriptor error (the type's schema refuses an unknown layer mark first). */
        fun of(mark: String): ChartMarkPolicy =
            entries.firstOrNull { it.wire == mark } ?: throw IllegalArgumentException("no mark policy for the layer mark $mark")
    }
}
