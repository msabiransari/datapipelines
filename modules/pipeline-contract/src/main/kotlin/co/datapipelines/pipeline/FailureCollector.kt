package co.datapipelines.pipeline

/**
 * Accumulates §12 failures across every rule group.
 *
 * Exists so the rule objects cannot be written fail-fast even by accident: they are handed a
 * collector and return `Unit`, so there is no early-return shape that skips later rules.
 * §17.2 makes exhaustiveness normative — "all checks run, all failures collected, returned
 * together" — and a `return@validate` in the middle of a rule chain is exactly how that
 * promise quietly stops being true.
 */
internal class FailureCollector {
    private val failures = mutableListOf<ValidationFailure>()

    fun add(
        code: String,
        path: String,
        message: String,
        details: Map<String, Any?> = emptyMap(),
    ) {
        failures += validationFailure(code, path, message, details)
    }

    fun toResult(): ValidationResult = ValidationResult(failures.toList())
}

/**
 * The single constructor of a [ValidationFailure] in this module, so `path` cannot escape the
 * CF-1/CF-2 reflection rules.
 *
 * `path` embeds identifiers straight from the payload — `parameters.<key>`, and a node index
 * from an array the author sizes — which makes it reflected input exactly like `message` and
 * `details`. Sanitising it here rather than at each call site means a new rule cannot forget:
 * there is one place a `ValidationFailure` is built, and it is this function.
 */
internal fun validationFailure(
    code: String,
    path: String,
    message: String,
    details: Map<String, Any?> = emptyMap(),
): ValidationFailure = ValidationFailure(code, path.truncateForErrorPath(), message, details)

/** `[a-z0-9_]+`, length 1–63 — the identifier rule frozen by §15.1 for names, node ids and tables. */
internal val IDENTIFIER = Regex("^[a-z0-9_]{1,63}$")

/** The reserved `__…__` namespace (§10.1, §12.1). */
internal val RESERVED_NAMESPACE = Regex("^__.*__$")

/** True when [value] is the tempdb literal or sits in the reserved `__…__` namespace. */
internal fun isReservedIdentifier(value: String): Boolean = value == NodeSource.TEMPDB_LITERAL || RESERVED_NAMESPACE.matches(value)
