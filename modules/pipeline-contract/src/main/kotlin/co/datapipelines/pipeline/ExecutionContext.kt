package co.datapipelines.pipeline

/**
 * The single shared render Context of one pipeline execution (pipeline-contract §7).
 *
 * A **runtime** construct: never serialized into the pipeline JSON, constructed fresh per
 * execution from the validated input parameters (§7.1 step 4), and rendered against by every
 * node's template (§2 principle 4). Values are typed Kotlin objects — `LocalDate`,
 * `BigDecimal`, `Boolean`, `Instant` — not the JSON they arrived as, because Freemarker's
 * `?c`, `?string(...)` and comparison operators are what template authors write against.
 *
 * ## What is deliberately absent (§7.3)
 *
 * Upstream node outputs (they are tempdb or external tables, referenced by name in SQL),
 * connection credentials, and execution metadata. A Context that carried credentials would
 * put them one `${...}` away from being rendered into a SQL string and logged.
 *
 * Mutability is for v2 calculators (§7.1 step 5, §18) — the one extension point the spec
 * names. v1 writes it once and reads it thereafter.
 */
class ExecutionContext(
    initial: Map<String, Any?> = emptyMap(),
) {
    private val values = LinkedHashMap<String, Any?>(initial)

    /** The Context as the template engine consumes it (§7.4). */
    fun asMap(): Map<String, Any?> = values.toMap()

    operator fun get(key: String): Any? = values[key]

    operator fun contains(key: String): Boolean = values.containsKey(key)

    /** The declared keys, in declaration order. */
    val keys: Set<String> get() = values.keys.toSet()

    /**
     * Adds or replaces a key — the v2 calculator hook (§7.1 step 5).
     *
     * Returns `this` so a calculator chain reads as one expression.
     */
    fun put(
        key: String,
        value: Any?,
    ): ExecutionContext {
        values[key] = value
        return this
    }

    override fun toString(): String = "ExecutionContext(keys=${values.keys})"
}
