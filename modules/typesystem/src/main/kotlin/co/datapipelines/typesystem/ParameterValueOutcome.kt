package co.datapipelines.typesystem

/**
 * [ParameterValueValidator]'s answer for one wire value (parameter-engine record P28).
 *
 * Three answers, not two: [Unsupplied] is neither acceptance nor refusal. A `null` or absent
 * value — and `[]` for a `MULTI` — is *nothing chosen* (P25), and what nothing means is the
 * CALLER's to decide: a pipeline applies the declaration's `default` and then its existing
 * `required` refusal; the parameter engine walks its selection priority (P26). One policy for
 * every caller, no per-caller flag.
 */
sealed interface ParameterValueOutcome {
    /**
     * The typed value — a [ParameterCoercion] value for `SINGLE`, a `List` of them in wire order
     * for `MULTI`.
     */
    data class Accepted(
        val value: Any,
    ) : ParameterValueOutcome

    /** Nothing was supplied; neither validated nor refused — the caller resolves it. */
    data object Unsupplied : ParameterValueOutcome

    /** The value breaks a rule; nothing about it was rounded, trimmed or clamped (P19). */
    data class Refused(
        val refusal: ParameterValueRefusal,
    ) : ParameterValueOutcome
}

/**
 * Which rule a value broke — each caller maps it to its own family's code
 * (`pipeline.execution.*` for a pipeline, `parameter.evaluate.*` for the engine). [wire] is the
 * engine's code suffix.
 */
enum class ParameterValueRule(
    val wire: String,
) {
    /** Wrong wire form, not coercible, not an array for a `MULTI`, a null or duplicate member. */
    INVALID_VALUE_TYPE("invalid_value_type"),

    /** A declared constraint, precision or scale refused it; the refusal's `reason` names which. */
    CONSTRAINT_VIOLATION("constraint_violation"),

    /** Nothing resolved for a required parameter — raised by a caller, after its own resolution. */
    REQUIRED_MISSING("required_missing"),
}

/**
 * One refusal. [message] is safe to echo (bounded, control characters replaced) and names no
 * parameter — the caller prefixes its own path. [reason] is `details.reason` for a
 * [ParameterValueRule.CONSTRAINT_VIOLATION]: `min`, `max`, `min_length`, `max_length`, `pattern`,
 * `pattern_budget`, `scale` or `precision`.
 */
data class ParameterValueRefusal(
    val rule: ParameterValueRule,
    val message: String,
    val reason: String? = null,
)

/** What a declaration itself got wrong, found at save by [ParameterValueValidator.checkDeclaration]. */
enum class DeclarationRule(
    val wire: String,
) {
    /** A constraint on a type it does not apply to (`pattern` on an `INTEGER`). */
    CONSTRAINT_NOT_APPLICABLE("constraint_not_applicable"),

    /** A malformed constraint: a bound not in the parameter's type, `min > max`, a negative length. */
    CONSTRAINT_INVALID("constraint_invalid"),

    /** A `pattern` that is too long, does not compile, or uses a construct the regex budget refuses. */
    PATTERN_INVALID("pattern_invalid"),
}

/**
 * One declaration problem: the [constraint] key it concerns, a safe [message], and
 * `details.reason` — for [DeclarationRule.CONSTRAINT_INVALID] `bound_type`,
 * `min_greater_than_max`, `negative_length` or `min_length_greater_than_max_length`; for
 * [DeclarationRule.PATTERN_INVALID] `too_long`, `syntax` or `unsafe_construct`.
 */
data class DeclarationProblem(
    val rule: DeclarationRule,
    val constraint: String,
    val message: String,
    val reason: String? = null,
)
