package co.datapipelines.pipeline

import co.datapipelines.typesystem.ParameterValueLimits
import co.datapipelines.typesystem.ParameterValueValidator

/**
 * The shared `ParameterValueValidator` (typesystem, parameter-engine record P28) as every
 * pipeline surface uses it — [ParameterBinder] at execute (the REST execute API,
 * `pipelines_execute`, release checks, schedules, and a published endpoint through its request
 * validator) and [ParameterRules] at save — so the value a save accepted as a default is judged
 * by the same rules a supplied value is.
 *
 * Pipelines take the default [ParameterValueLimits]: the record's 100,000-read regex budget and
 * NO default `max_length` — a `STRING` parameter without a declared `max_length` stays unbounded,
 * as it was before #194 (a published endpoint's 4 KB value cap is its own, and unchanged). The
 * parameter engine binds its own limits to `datapipelines.parameters.*`.
 */
internal object PipelineParameterValidator {
    val validator: ParameterValueValidator = ParameterValueValidator(ParameterValueLimits())
}
