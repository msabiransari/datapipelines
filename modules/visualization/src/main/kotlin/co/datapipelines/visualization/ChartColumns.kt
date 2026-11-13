package co.datapipelines.visualization

import co.datapipelines.pipeline.ScriptContractView
import co.datapipelines.typesystem.LogicalType
import java.util.UUID

/**
 * A chart's `columns` (design record 2026-11-06 §4.1 "Types at render"): `{field: LogicalType}` for every encoded field,
 * the types the runtime core orders categories and aggregates measures by. Read from the SAME contract the save rules
 * read, through the same two functions [VisualizationValidator] uses at save: [inputs] (the one input's columns, no
 * transform) and [output] (the pinned transform's output table). ONE resolver serves every surface that hands a chart
 * occurrence to the runtime: the runtime configuration (`RuntimeViews`, through the dashboard resolver), the capability
 * preview and the workspace Preview tab.
 */
class ChartColumns(
    private val templates: TemplateContractFacts,
) {
    /**
     * The encoded fields' types of [body] in [workspaceId]; empty for a non-chart body, and when the contract cannot be
     * known (several inputs without a transform, or a pin that no longer resolves). Empty is the runtime's "no contract":
     * it orders the way the reference did (two numbers by value, anything else by code point).
     */
    fun of(
        workspaceId: UUID,
        body: VisualizationBody,
    ): Map<String, LogicalType> {
        if (body.renderer.kind != RendererKind.CHART) return emptyMap()
        val transform = body.transform
        val columns =
            if (transform == null) {
                inputs(body)
            } else {
                (templates.pinOf(workspaceId, transform.template) as? TemplatePin.Transform)?.let(::output)
            } ?: return emptyMap()
        val channels = BarChannels.of(body.config)
        val encoded = setOfNotNull(channels.band.field, channels.value.field, channels.color?.field)
        return columns
            .filter { it.name in encoded }
            .sortedBy { it.name }
            .associate { it.name to it.type }
    }

    companion object {
        /** Without a transform, the one input's columns; null when several inputs leave the contract unknowable. */
        fun inputs(body: VisualizationBody): List<InputColumn>? =
            body.inputs.values
                .singleOrNull()
                ?.columns

        /** A transform pin's output contract as columns; empty when the output is not a table. */
        fun output(pin: TemplatePin.Transform): List<InputColumn> =
            (pin.contract.output as? ScriptContractView.Output.Table)?.columns?.map { InputColumn(it.name, it.type, it.nullable) }
                ?: emptyList()
    }
}
