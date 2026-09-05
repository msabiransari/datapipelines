package co.datapipelines.calculators

/**
 * The one renderer both `docs/calculators.md` and `CalculatorRegistrySpecDriftTest` read a row
 * through.
 *
 * The drift guard's strength comes from this being *derived*, not transcribed: the test rebuilds
 * each row's signature and example from the registry and compares the string to the document's
 * cell, so a renamed input, a changed type, an input that became optional, or an example whose
 * answer moved all fail — not just an added or removed `kind`.
 *
 * Deliberately named without the `Test` suffix so the module's `verifyTestsExecuted` guard counts
 * only real test classes — the convention `McpFixtures` and `ConfigSnapshots` follow.
 */
object CatalogFormat {
    /** `` `date` DATE, `mode?` STRING → DATE `` — the signature cell. */
    fun signature(kind: CalculatorKind): String =
        kind.inputs.joinToString(", ") { "`${it.name}${if (it.isList) "[]" else ""}${if (it.required) "" else "?"}` ${it.typeName}" } +
            " → ${kind.output?.wire ?: CalculatorInput.ANY_TYPE}"

    /** `date=2026-08-14, unit=quarter → 2026-07-01` — the example cell. */
    fun example(kind: CalculatorKind): String =
        kind.example.inputs.entries
            .joinToString(", ") { (name, value) -> "$name=$value" } + " → ${kind.example.output}"
}
