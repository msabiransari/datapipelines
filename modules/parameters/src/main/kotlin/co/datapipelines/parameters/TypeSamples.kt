package co.datapipelines.parameters

import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.ParameterBinder
import co.datapipelines.typesystem.LogicalType

/**
 * "Type-appropriate sample values" for a save-time dry render (templates §7.2's rule; the record's §4
 * step 5) — read from the ONE table pipelines use, `ParameterBinder.sampleContext()`, never a second
 * copy: a sample that differed between a pipeline's dry render and a set's would make the same
 * template pass one and fail the other. The binder's table is lane A's and is only read here.
 */
internal object TypeSamples {
    private val samples: Map<LogicalType, Any?> =
        LogicalType.entries
            .filter { it != LogicalType.NULL }
            .associateWith { type -> ParameterBinder(mapOf(KEY to Parameter(type = type))).sampleContext()[KEY] }

    fun of(type: LogicalType): Any? = samples[type]

    private const val KEY = "sample"
}
