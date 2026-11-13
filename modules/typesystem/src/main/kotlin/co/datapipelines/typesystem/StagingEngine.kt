package co.datapipelines.typesystem

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * The tempdb engine a pipeline stages through (enums.md §7; pipeline-contract §5.1
 * `settings.tempdb.engine`; staging.md §3.1 factory dispatch).
 *
 * ## Why this type lives in `typesystem`
 *
 * Three modules need it: `pipeline-contract` (the `settings.tempdb.engine` wire value),
 * `staging` (the `StagingFactory.create(executionId, engine)` dispatch) and `dag` (which passes
 * the pipeline's engine to the factory). module-structure.md §4.2 forbids `staging` from
 * depending on `pipeline-contract`, so v1 declared the enum twice and had `dag` map between
 * the two — bounded while there was one constant. enums.md §7 set the rule for the second
 * engine: move the enum to the one lower layer both share, exactly as [Dialect] resolved the
 * same problem. `DUCKDB` (dp-lake P1, #795) is that second engine, so there is now one
 * declaration and no mapping.
 *
 * Wire values are UPPER and coincide with the constant names, but the `@JsonValue` mapping is
 * explicit per the enums.md serialization convention.
 */
enum class StagingEngine(
    @JsonValue val wire: String,
) {
    /** In-memory H2 (staging.md §3). The default when `settings.tempdb` is omitted. */
    H2("H2"),

    /**
     * A file-backed DuckDB database per execution (staging.md §8.5, dp-lake P1): bounded by
     * DuckDB's `memory_limit`, its spill bounded by a scratch reservation, its configuration
     * locked before author SQL runs.
     */
    DUCKDB("DUCKDB"),
    ;

    /**
     * The SQL dialect templates targeting `source: "tempdb"` must be written in.
     *
     * Derived, not hard-coded: pipeline-contract §12.6 originally pinned H2 and SPEC-REVIEW 2.1.8
     * replaced that with "the dialect of the engine declared in `settings.tempdb.engine`", so the
     * DuckDB engine makes DuckDB templates valid with no second edit. Exhaustive — a new engine
     * is a compile error here.
     */
    val dialect: Dialect
        get() =
            when (this) {
                H2 -> Dialect.H2
                DUCKDB -> Dialect.DUCKDB
            }

    companion object {
        val WIRE_VALUES: List<String> = entries.map { it.wire }

        fun fromWireOrNull(value: String?): StagingEngine? = entries.firstOrNull { it.wire == value }

        @JsonCreator
        @JvmStatic
        fun fromWire(value: String): StagingEngine =
            fromWireOrNull(value)
                ?: throw IllegalArgumentException("Unknown StagingEngine: ${value.truncateForError()}")
    }
}
