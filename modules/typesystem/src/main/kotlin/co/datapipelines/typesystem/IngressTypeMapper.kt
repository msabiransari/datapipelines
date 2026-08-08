package co.datapipelines.typesystem

import java.sql.Types

/**
 * The canonical decision for one source column: its logical type plus the precision
 * and scale that belong in the envelope (type-system.md §11.2).
 *
 * `null` precision / scale mean **omitted**, and both omissions are meaningful — see
 * [ColumnSchema].
 */
data class LogicalTypeMapping(
    val type: LogicalType,
    val precision: Int? = null,
    val scale: Int? = null,
) {
    /** Attaches a column name (and optional nullability) to produce the envelope entry. */
    fun toColumnSchema(
        name: String,
        nullable: Boolean? = null,
    ): ColumnSchema = ColumnSchema(name = name, type = type, precision = precision, scale = scale, nullable = nullable)
}

/**
 * One mapped column: its descriptor plus any warnings the mapping raised.
 *
 * Warnings are per-column and never fatal (§8.2). The list is normally empty; it holds
 * at most one entry today, but the shape is a list because the envelope's `warnings`
 * array is a flat concatenation across all columns.
 */
data class MappedColumn(
    val column: ColumnSchema,
    val warnings: List<TypeMappingWarning> = emptyList(),
)

/**
 * Maps a source dialect's JDBC column metadata to canonical types (type-system.md §5).
 *
 * One implementation per dialect, each realising exactly one §5.x table. Mappings are
 * **by type and precision, never by value** (§2 principle 4): the wire format of a
 * column is decided once, from metadata, and cannot vary row to row.
 *
 * Implementations never throw for unrecognized input — see [FallbackTypeMapper] and
 * the §8.2 contract restated on [DialectTypeMapper].
 */
interface IngressTypeMapper {
    /**
     * The §11.2 signature: metadata in, canonical decision out.
     *
     * An unrecognized source type resolves to [LogicalType.STRING] rather than failing.
     * Use [mapColumn] when the caller needs the §8.2 warning too — this method alone
     * cannot report one, because a warning names a column and this signature has none.
     */
    fun map(
        sqlType: Int,
        precision: Int,
        scale: Int,
        typeName: String,
    ): LogicalTypeMapping

    /**
     * Maps one named column, returning the envelope descriptor together with any
     * warnings (§8.2). This is the method result-set readers should call: it is the
     * only one that can satisfy "exactly one warning per affected column".
     */
    fun mapColumn(
        name: String,
        sqlType: Int,
        precision: Int,
        scale: Int,
        typeName: String,
        nullable: Boolean? = null,
    ): MappedColumn
}

/**
 * Shared behavior for the seven per-dialect mappers.
 *
 * Subclasses implement [mapRecognized] — their own §5.x table and nothing else — and
 * this class supplies the two rules that are identical for every dialect:
 *
 *  1. **§8.1, all-NULL columns.** A driver-reported `java.sql.Types.NULL` yields
 *     canonical [LogicalType.NULL] before any dialect table is consulted.
 *  2. **§8.2, unknown types.** A `null` from [mapRecognized] resolves to
 *     [LogicalType.STRING] and, through [mapColumn], exactly one
 *     `type_mapping.unknown_source_type` warning. It never throws, and the execution
 *     never fails.
 *
 * Splitting "recognized?" from "what do we do about it?" is what lets the fallback be
 * observable: [map] alone cannot tell a caller whether the table matched, so the
 * distinction has to live somewhere the warning path can see it.
 */
abstract class DialectTypeMapper : IngressTypeMapper {
    /**
     * This dialect's §5.x table.
     *
     * @return the canonical decision, or `null` when the table has no row for this
     *   input — which is the signal that the §8.2 fallback applies. Implementations
     *   must not throw and must not substitute their own fallback.
     */
    protected abstract fun mapRecognized(
        sqlType: Int,
        precision: Int,
        scale: Int,
        typeName: String,
    ): LogicalTypeMapping?

    /**
     * This dialect's name-keyed lookup table, exposed so a test can generate one
     * reachability case **per key** instead of hand-transcribing the list.
     *
     * A key is only useful if dispatch can actually reach it: a key that is not in
     * [normalizeTypeName] form (stray case, a trailing `(p,s)`) or one shadowed by an
     * earlier branch is dead weight that silently maps its type to the §8.2 fallback.
     * Hand-written cases cannot catch that class of defect, because the same typo
     * appears in both the table and the test.
     *
     * Empty for dialects that dispatch on codes alone.
     */
    internal open val recognizedTypeNames: Map<String, LogicalTypeMapping> get() = emptyMap()

    /** This dialect's code-keyed lookup table. Same purpose as [recognizedTypeNames]. */
    internal open val recognizedTypeCodes: Map<Int, LogicalTypeMapping> get() = emptyMap()

    /**
     * A warning to attach to a column this dialect *did* recognize.
     *
     * Exists for MSSQL `sql_variant` (§5.3/§10.5), which maps cleanly to `STRING` yet
     * still owes the author a warning. Default: no warning.
     */
    protected open fun warningForRecognized(
        name: String,
        sqlType: Int,
        typeName: String,
    ): TypeMappingWarning? = null

    final override fun map(
        sqlType: Int,
        precision: Int,
        scale: Int,
        typeName: String,
    ): LogicalTypeMapping =
        when {
            sqlType == Types.NULL -> {
                ALL_NULL_COLUMN
            }

            else -> {
                mapRecognized(sqlType, precision, scale, typeName)
                    ?: FallbackTypeMapper.map(sqlType, precision, scale, typeName)
            }
        }

    final override fun mapColumn(
        name: String,
        sqlType: Int,
        precision: Int,
        scale: Int,
        typeName: String,
        nullable: Boolean?,
    ): MappedColumn {
        if (sqlType == Types.NULL) {
            return MappedColumn(ALL_NULL_COLUMN.toColumnSchema(name, nullable))
        }
        val recognized =
            mapRecognized(sqlType, precision, scale, typeName)
                ?: return MappedColumn(
                    column = FallbackTypeMapper.map(sqlType, precision, scale, typeName).toColumnSchema(name, nullable),
                    warnings = listOf(TypeMappingWarning.unknownSourceType(name, sourceTypeLabel(sqlType, typeName))),
                )
        return MappedColumn(
            column = recognized.toColumnSchema(name, nullable),
            warnings = listOfNotNull(warningForRecognized(name, sqlType, typeName)),
        )
    }

    // `internal` rather than `protected`: subclasses still reach these unqualified, and
    // the reachability drift guard needs `normalizeTypeName` to check that every lookup
    // key is in the form dispatch can actually produce. Nothing here is public API.
    internal companion object {
        /** §8.1: the driver said `Types.NULL`, so the canonical type is `NULL`. */
        val ALL_NULL_COLUMN = LogicalTypeMapping(LogicalType.NULL)

        /** The parameter-less canonical decisions, shared by every §5.x table. */
        val AS_BOOLEAN = LogicalTypeMapping(LogicalType.BOOLEAN)
        val AS_INTEGER = LogicalTypeMapping(LogicalType.INTEGER)
        val AS_BIGINTEGER = LogicalTypeMapping(LogicalType.BIGINTEGER)
        val AS_STRING = LogicalTypeMapping(LogicalType.STRING)
        val AS_BINARY = LogicalTypeMapping(LogicalType.BINARY)
        val AS_DATE = LogicalTypeMapping(LogicalType.DATE)
        val AS_TIME = LogicalTypeMapping(LogicalType.TIME)
        val AS_TIMESTAMP = LogicalTypeMapping(LogicalType.TIMESTAMP)

        /** §3.4: REAL / float32 — ~7 significant digits, scale omitted. */
        val APPROXIMATE_SINGLE = LogicalTypeMapping(LogicalType.DECIMAL, precision = 7)

        /** §3.4: DOUBLE / float64 — ~15 significant digits, scale omitted. */
        val APPROXIMATE_DOUBLE = LogicalTypeMapping(LogicalType.DECIMAL, precision = 15)

        /** The DECIMAL / BIGDECIMAL split point (§3.3), frozen in v1 (§9.1). */
        const val MAX_DOUBLE_SAFE_PRECISION = 15

        /**
         * Exact numeric: `DECIMAL(p, s)` at or below the §3.3 threshold,
         * `BIGDECIMAL(p, s)` above it.
         *
         * A driver reporting **no** precision (`0`, as PostgreSQL does for an unsized
         * `numeric`) takes the §4 unbounded encoding — `BIGDECIMAL` with the precision
         * key omitted. That is the spec's own shorthand for "the source declares no
         * precision limit", and it is the only safe answer: `precision` is `minimum: 1`
         * in the §7.1 schema, so emitting `DECIMAL(0, s)` would build an invalid
         * descriptor, and inventing a ceiling would lie about the source column.
         */
        fun exactNumeric(
            precision: Int,
            scale: Int,
        ): LogicalTypeMapping =
            when {
                precision <= 0 -> {
                    unboundedNumeric(scale)
                }

                precision <= MAX_DOUBLE_SAFE_PRECISION -> {
                    LogicalTypeMapping(
                        LogicalType.DECIMAL,
                        precision,
                        // Some drivers report a negative scale (Oracle NUMBER(p,-2));
                        // the envelope's `scale` is `minimum: 0` (§7.1), so it floors.
                        scale.coerceAtLeast(0),
                    )
                }

                else -> {
                    LogicalTypeMapping(LogicalType.BIGDECIMAL, precision, scale.coerceAtLeast(0))
                }
            }

        /**
         * §4: an unsized exact numeric whose dialect imposes **no** precision ceiling
         * (today only PostgreSQL `numeric`/`decimal` declared without precision).
         *
         * The precision key is **omitted** — normative shorthand for "unbounded". A
         * synthetic ceiling must never be substituted here: it would be a lie about the
         * source column and would break clients that size local buffers from it.
         * Dialects that *do* define a default (Oracle `NUMBER` → 38) report that default
         * instead and never reach this helper.
         */
        fun unboundedNumeric(scale: Int): LogicalTypeMapping =
            LogicalTypeMapping(LogicalType.BIGDECIMAL, precision = null, scale = scale.coerceAtLeast(0))

        /** Lowercased, parameter-stripped source type name: `"DECIMAL(18,2)"` → `"decimal"`. */
        fun normalizeTypeName(typeName: String): String = typeName.substringBefore('(').trim().lowercase()

        /** The label a §8.2 warning reports as the source type. */
        fun sourceTypeLabel(
            sqlType: Int,
            typeName: String,
        ): String = typeName.ifBlank { "JDBC type $sqlType" }
    }
}
