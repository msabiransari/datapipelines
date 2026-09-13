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
        KINDS.forEach(::requireValidOutputs)
    }

    /** §6.1's context-key shape — an output name becomes a `context_keys` object key. */
    private val OUTPUT_NAME = Regex("[a-z_][a-z0-9_]*")

    /**
     * The D1 output-shape invariant, enforced on every kind at registration (121).
     *
     * A kind is single-output ([CalculatorKind.outputs] empty) and writes its one value
     * through `context_key`, or multi-output (two or more declared outputs, a null
     * [CalculatorKind.output]) and writes its named set through `context_keys`. A ONE-entry
     * set is refused outright: a single named output is a single output, and the way to name
     * it is `context_key` — permitting the shape would give one concept two wire forms.
     *
     * Internal rather than private so `CalculatorRegistryTest` can prove the refusal, not
     * just the invariant's consequence — the registry never holds a bad kind, so a test over
     * the registry alone could not see this check fail.
     */
    internal fun requireValidOutputs(kind: CalculatorKind) {
        require(kind.outputs.size != 1) {
            "Calculator kind '${kind.kind}' declares exactly one named output ('${kind.outputs.single().name}'). " +
                "A one-name set is a single output — declare `output` and let the node name it with `context_key`."
        }
        if (kind.outputs.isEmpty()) return
        require(kind.output == null) {
            "Multi-output calculator kind '${kind.kind}' must declare `output = null`; " +
                "each named output carries its own type."
        }
        val names = kind.outputs.map { it.name }
        require(names.all { OUTPUT_NAME.matches(it) }) {
            "Multi-output calculator kind '${kind.kind}' declares an output name that is not a snake_case " +
                "identifier: ${names.filterNot { OUTPUT_NAME.matches(it) }}."
        }
        require(names.toSet().size == names.size) {
            "Multi-output calculator kind '${kind.kind}' declares a duplicate output name: " +
                "${names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys}."
        }
    }
}
