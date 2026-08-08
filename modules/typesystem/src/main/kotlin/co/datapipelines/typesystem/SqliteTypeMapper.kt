package co.datapipelines.typesystem

/**
 * SQLite source → canonical (type-system.md §5.7).
 *
 * SQLite is **dynamically typed**: a column has a type *affinity* derived from the
 * declared type string, not a fixed type. So this mapper reads the declared type name
 * and ignores the JDBC code entirely — the driver's code is itself derived from the
 * same string, and going back to the source of truth is one guess fewer.
 *
 * Affinity is decided by SQLite's own substring rules, **in this order**:
 * `INT` → `CHAR`/`CLOB`/`TEXT` → `BLOB`/none → `REAL`/`FLOA`/`DOUB` → NUMERIC.
 * The order is load-bearing: a column declared `POINT` contains `INT` and therefore has
 * INTEGER affinity — surprising, and correct.
 *
 * ## The two BLOB rows are not in conflict (§5.7)
 *
 * The discriminator is the declared string, not the affinity:
 *  - declared type **containing** `BLOB` → canonical `BINARY` (an explicit declaration
 *    is a real byte column);
 *  - **no declared type at all** (`CREATE TABLE t (c)`), which SQLite also gives BLOB
 *    affinity → canonical `STRING`. Untyped SQLite columns hold text in practice, and
 *    base64-encoding text as `BINARY` is the more damaging error.
 *
 * ## Temporal columns (§5.7 policy)
 *
 * SQLite has no native temporal types, and the storage convention varies per database
 * (ISO text, epoch seconds, epoch millis, Julian day). v1 maps every temporal-looking
 * declaration to `STRING` and attempts **no** heuristic parsing; authors who know their
 * convention `CAST` in the template. This check runs before the affinity chain, since
 * `DATE`/`DATETIME`/`TIMESTAMP` would otherwise land in NUMERIC affinity.
 *
 * This mapper never returns `null`: every declared string, including the empty one,
 * has an answer, so the §8.2 fallback is unreachable for SQLite.
 */
object SqliteTypeMapper : DialectTypeMapper() {
    private val TEXT_TOKENS = listOf("CHAR", "CLOB", "TEXT")
    private val TEMPORAL_TOKENS = listOf("DATE", "TIME")

    override fun mapRecognized(
        sqlType: Int,
        precision: Int,
        scale: Int,
        typeName: String,
    ): LogicalTypeMapping {
        val declared = typeName.trim().uppercase()
        return when {
            // No declaration to trust → conservative text fallback, NOT BINARY.
            declared.isEmpty() -> AS_STRING

            isTemporal(declared) -> AS_STRING

            declared.contains("INT") -> AS_INTEGER

            TEXT_TOKENS.any { declared.contains(it) } -> AS_STRING

            declared.contains("BLOB") -> AS_BINARY

            // REAL affinity and NUMERIC affinity alike: DECIMAL(15), scale omitted.
            // NUMERIC is deliberately conservative — the column may hold either.
            else -> APPROXIMATE_DOUBLE
        }
    }

    private fun isTemporal(declared: String): Boolean = TEMPORAL_TOKENS.any { declared.contains(it) }
}
