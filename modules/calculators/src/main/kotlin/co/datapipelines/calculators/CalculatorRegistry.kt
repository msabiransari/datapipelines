package co.datapipelines.calculators

/**
 * The calculator catalog — every [CalculatorKind] the deployment ships, by `kind`
 * ([calculators design §0.4](../../../../../../../docs/superpowers/specs/2026-09-04-calculators-design.md)).
 *
 * ## Additive, forever (D4)
 *
 * A `kind` is written into pipeline bodies that are versioned, exported and promoted. Removing
 * one, or changing what one computes, breaks a body that already validated — so the catalog only
 * ever grows, and a changed meaning is a NEW kind with a new name. `CalculatorRegistrySpecDriftTest`
 * holds this object and `docs/calculators.md` to each other in both directions, so a kind cannot
 * ship undocumented and a documented kind cannot be missing.
 */
object CalculatorRegistry {
    /** Every kind, in catalog order: calendar and time first, then numeric, then value. */
    val KINDS: List<CalculatorKind> = DateKinds.ALL + ValueKinds.ALL

    private val byKind: Map<String, CalculatorKind> = KINDS.associateBy { it.kind }

    /** The kind names, in catalog order. */
    val NAMES: List<String> get() = KINDS.map { it.kind }

    /** The kind, or null when the catalog has no such name — the validator's `calculator_unknown`. */
    fun find(kind: String): CalculatorKind? = byKind[kind]

    /** The kind, or a refusal. For call sites that have already validated the name. */
    fun require(kind: String): CalculatorKind = find(kind) ?: throw CalculatorEvaluationException(null, "No calculator kind named '$kind'.")

    init {
        // A duplicate `kind` would silently shadow one implementation with another, and the
        // catalog's whole contract is that a name means one thing forever.
        check(byKind.size == KINDS.size) {
            "Duplicate calculator kind(s): ${KINDS.map { it.kind }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys}"
        }
    }
}
