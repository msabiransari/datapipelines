package co.datapipelines.parameters

import co.datapipelines.typesystem.ParameterCardinality

/** A label (a parameter's, the set's `display_name`) is 1–120 characters and not blank (record §3.2). */
internal const val MAX_LABEL_CHARS = 120

/** A description is at most 2000 characters (record §3.2). */
internal const val MAX_DESCRIPTION_CHARS = 2000

/** P29's generated count bind, `<name>_count` — a reserved parameter-name suffix (§3.2). */
internal const val COUNT_SUFFIX = "_count"

/** P29's generated slice binds, `<name>__<digits>` — the `in_list` macro's own, reserved (§3.2). */
internal val SLICE_BIND = Regex("^[a-z_][a-z0-9_]*__[0-9]+$")

/** Every refusal about a parameter names it (safely echoed). */
internal fun detailsOf(parameter: ParameterDefinition): Map<String, Any?> = mapOf("parameter" to parameter.name.safeEcho())

/** The declared type as a refusal names it — `STRING`, or `STRING array` for a `MULTI`. */
internal fun describeDeclared(parameter: ParameterDefinition): String =
    if (parameter.cardinality == ParameterCardinality.MULTI) "${parameter.type.wire} array" else parameter.type.wire

/** `base.suffix`, or `base` alone when the suffix is empty. */
internal fun joinPath(
    base: String,
    suffix: String,
): String = if (suffix.isEmpty()) base else "$base.$suffix"

/** A parameter's two expressions as parsed at step 1 — null where absent or refused. */
internal data class ParsedExpressions(
    val hidden: Expr?,
    val disabled: Expr?,
)
