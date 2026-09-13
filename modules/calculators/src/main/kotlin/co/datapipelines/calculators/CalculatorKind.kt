package co.datapipelines.calculators

import co.datapipelines.typesystem.LogicalType

/**
 * One entry of the calculator catalog — a **pure, total function** from typed inputs to one
 * typed value ([calculators design §0.3/§0.4](../../../../../../../docs/superpowers/specs/2026-09-04-calculators-design.md),
 * user-facing catalog in [calculators.md](../../../../../../../docs/calculators.md)).
 *
 * ## What "pure" is enforced to mean
 *
 * A kind reads its [inputs] and nothing else: no clock, no locale, no database, no configuration.
 * "Today" is not a kind's to know — it arrives as `$current_date`, which the executor put in the
 * Context at execution start; the organisation's fiscal year arrives as `$org_fiscal_start_date`.
 * That is why the draft's per-kind `config` block is gone (§0.4): a kind is a function, not a
 * form, and everything that used to be config is an input the author can override per pipeline.
 *
 * The property is enforced by the module's build row (`:modules:calculators` may depend only on
 * `typesystem`) and by `CalculatorPurityTest`, not by this comment.
 *
 * ## Evaluation contract
 *
 * [evaluate] receives every declared input by name, already coerced to the Kotlin type its
 * [CalculatorInput.type] names ([CalculatorValues] documents the mapping). An absent optional
 * input arrives as `null` and the kind applies its documented default. Any refusal — an
 * unparseable format string, an unknown unit, a division by zero — is a
 * [CalculatorEvaluationException] naming the input, which the executor turns into
 * `pipeline.node.calculator_failed`. A kind never throws anything else, and never returns a
 * value whose type contradicts [output] — or, for a multi-output kind, a map whose key set is
 * not exactly [outputs]'s names.
 */
interface CalculatorKind {
    /** The registry key an author writes as `"kind"`. Stable forever; the catalog is additive. */
    val kind: String

    /** Human-readable name for the editor's card and the MCP catalog. */
    val displayName: String

    /** One sentence: what the kind computes, in the terms an author thinks in. */
    val description: String

    /**
     * The everyday phrases the kind answers — "last quarter", "month to date", "days between" —
     * lower-case, and kept to what the kind literally computes (120, owner ruling R2).
     *
     * They are the lookup path for a relative phrase in a question: an agent matches the
     * question's words against `phrases` across the catalog and picks the kind that fits, rather
     * than being told by the skill what any phrase means (R1 — the skill never enumerates
     * interpretations). They are time and arithmetic words only, never dataset vocabulary, and a
     * kind with nothing to say is a catalog defect the drift test fails on: every kind lists at
     * least one.
     */
    val phrases: List<String>

    /** The declared inputs, in the order the catalog documents them. */
    val inputs: List<CalculatorInput>

    /**
     * The canonical type of the value written to the node's `context_key` — or **null** for the
     * three kinds whose output type is whatever their input's was (`coalesce`, `if_null`,
     * `map`). Null reads as `ANY` in the catalog and in `calculators_list`.
     *
     * Null for a **multi-output** kind too ([outputs] non-empty): the kind then has no single
     * output type, and each declared output carries its own. The pair distinguishes the two
     * nulls — [outputs] empty with a null [output] is an ANY-output single kind; [outputs]
     * non-empty is a multi kind.
     */
    val output: LogicalType?

    /**
     * The named output set of a **multi-output** kind (121, owner ruling 2026-09-12: the
     * general mechanism, not a period special case) — empty for every single-output kind.
     *
     * The invariant [CalculatorRegistry] enforces at registration and `CalculatorRegistryTest`
     * re-asserts over every kind: empty here means the kind writes ONE value to the node's
     * `context_key` through [output], exactly as it always has; two or more entries means the
     * kind writes a named set, the node maps each name to a context key through its
     * `context_keys` block, and [output] is null. A one-entry set is refused — a single named
     * output IS a single output, named by `context_key` like any other. Names are snake_case
     * identifiers, unique within the kind, because they are what the node's `context_keys`
     * object keys must equal.
     */
    val outputs: List<CalculatorOutput>

    /** One worked example — the row `calculators.md` prints and `calculators_get` returns. */
    val example: CalculatorExample

    /**
     * Evaluates the kind. [values] holds every declared input by name; optional inputs the author
     * omitted are present and null.
     *
     * The return shape is fixed by [outputs]: a single-output kind returns the one scalar
     * [output] types; a multi-output kind returns a `Map<String, Any?>` whose **key set equals
     * the declared output names** — no missing key, no extra one (`CalculatorPurityTest` asserts
     * the shape for every kind in the registry).
     *
     * @throws CalculatorEvaluationException the inputs are individually well-typed but jointly
     *   unusable — an unknown `unit`, a `format` that does not compile, a zero denominator.
     */
    fun evaluate(values: Map<String, Any?>): Any?
}

/**
 * One declared input of a kind.
 *
 * `$name` in a node's `inputs` object is a **reference** to a Context key; anything else is a
 * literal typed against [type] (§0.3). So `"fiscal_start": "$org_fiscal_start_date"` reads the
 * deployment's setting and `"fiscal_start": "09-15"` pins this pipeline's own — the same input,
 * two authoring shapes, one type.
 */
data class CalculatorInput(
    val name: String,
    /**
     * The canonical type of each value this input takes, or **null** for an input that accepts
     * any canonical type (`coalesce`'s values, `if_null`'s default, `map`'s pairs). A null type
     * is not a hole in the type system: it is the honest declaration for a kind that does not
     * look at the value, and the validator still refuses a literal whose JSON shape has no
     * canonical reading at all.
     */
    val type: LogicalType?,
    /** What it means, in the terms an author thinks in. */
    val description: String,
    /** False when the author may omit it; the kind then applies [defaultDescription]'s default. */
    val required: Boolean = true,
    /**
     * [Arity.LIST] when the wire value is a JSON **array** of [type] rather than one value.
     *
     * The design's §0.3 term for this is `variadic`, restricted to the last input. That
     * restriction is kept for the one open-ended case it was written for — `coalesce`, whose
     * argument count IS the authoring choice, and where a second list input would be
     * unparseable. Kinds with *fixed-role* lists (`add_business_days`' weekend days and
     * holidays, `map`'s from/to pairs) declare more than one, because each is a named role and
     * nothing about them is ambiguous. `CalculatorRegistryTest` pins both halves of that rule.
     */
    val arity: Arity = Arity.SINGLE,
    /** How the default reads in the catalog — null when [required]. */
    val defaultDescription: String? = null,
) {
    /** The design's `variadic` flag: this input takes a JSON array. */
    val isList: Boolean get() = arity == Arity.LIST

    /** How the type reads in the catalog and in `calculators_list`. */
    val typeName: String get() = type?.wire ?: ANY_TYPE

    companion object {
        /** How a null [type] (or a null [CalculatorKind.output]) is spelled to a reader. */
        const val ANY_TYPE = "ANY"
    }

    enum class Arity {
        /** One value of [CalculatorInput.type]. */
        SINGLE,

        /** A JSON array of [CalculatorInput.type]. */
        LIST,
    }
}

/**
 * One named output of a multi-output kind (121) — one entry of [CalculatorKind.outputs].
 *
 * [name] is the key the kind's `evaluate` result carries and the key a node's `context_keys`
 * object maps FROM: `{"start": "window_start"}` writes the kind's `start` output to the Context
 * as `window_start`. [type] is the output's canonical type, with the same null-means-ANY rule
 * [CalculatorInput.type] has; it is what the validator types the mapped context key with, so a
 * SQL node binding `:window_start` and a calculator referencing `$window_start` are checked
 * against it exactly as they are against a single-output kind's [CalculatorKind.output].
 */
data class CalculatorOutput(
    /** A snake_case identifier, unique within the kind. */
    val name: String,
    /** The canonical type of this output's values, or null when the kind does not pin one. */
    val type: LogicalType?,
    /** What it means, in the terms an author thinks in. */
    val description: String,
)

/** The worked example a catalog row and `calculators_get` both print. */
data class CalculatorExample(
    /** Input values as an author would write them, in `inputs`-object order. */
    val inputs: Map<String, String>,
    /** The value the kind produces for [inputs], rendered as the catalog prints it. */
    val output: String,
)

/**
 * A kind refused the inputs it was given.
 *
 * Deliberately not a `DatapipelinesException`: that type lives in `typesystem` and carries an
 * error CODE, and a code is a contract-layer decision. This module knows what went wrong; the
 * executor knows it is `pipeline.node.calculator_failed` (pipeline-contract §13.4) and attaches
 * the code, the node and the kind. Keeping the split means the catalog stays a library anybody
 * could call, including a future editor preview that has no execution to fail.
 */
class CalculatorEvaluationException(
    /** The input whose value made the evaluation impossible; null when it is the combination. */
    val input: String?,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
