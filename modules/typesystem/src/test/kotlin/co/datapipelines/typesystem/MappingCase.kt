package co.datapipelines.typesystem

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/**
 * One row of a §5.x source-to-canonical mapping table, written the way the spec writes
 * it so a reviewer can diff the test data against the document line by line.
 *
 * [sourceType] is the dialect's own spelling (`int8`, `NUMBER(p,0)`, `sql_variant`) and
 * doubles as the JUnit display name — a failing case names the spec row it came from
 * rather than an index.
 *
 * `precision` / `scale` / `typeName` default to what a driver reports for a type that
 * carries none, so a row only states what actually discriminates it.
 */
data class MappingCase(
    val sourceType: String,
    val sqlType: Int,
    val expected: LogicalTypeMapping,
    val precision: Int = 0,
    val scale: Int = 0,
    val typeName: String = "",
) {
    override fun toString(): String = sourceType
}

/** The column name every `mapColumn` probe uses. */
private const val PROBE_COLUMN = "probe_column"

/**
 * Asserts this row produces the same canonical answer through `mapColumn` as through
 * `map`, for each of the three nullability states.
 *
 * `map` and `mapColumn` are separate code paths — `mapColumn` is the one result-set
 * readers actually call, because it is the only one that can carry a §8.2 warning — and
 * testing only `map` left the recognized branch of `mapColumn` uncovered. Mutation
 * testing proved the gap was real, not theoretical: mutants that doubled the precision
 * written into the descriptor and that dropped `nullable` on the floor both survived the
 * entire suite. This assertion is what kills them, and it does so for every §5.x row
 * rather than a hand-picked few.
 *
 * The nullability sweep covers §7.3's three states in one place: `true`, `false`, and
 * **omitted**, where omitted must stay omitted rather than defaulting to `false`.
 */
fun MappingCase.assertMapColumnMatches(mapper: IngressTypeMapper) {
    listOf(null, true, false).forEach { nullable ->
        val mapped = mapper.mapColumn(PROBE_COLUMN, sqlType, precision, scale, typeName, nullable)
        withClue("$sourceType via mapColumn (nullable=$nullable)") {
            mapped.column shouldBe expected.toColumnSchema(PROBE_COLUMN, nullable)
            mapped.column.nullable shouldBe nullable
        }
    }
}

/** The parameter-less canonical decisions, for terse table rows. */
val INTEGER = LogicalTypeMapping(LogicalType.INTEGER)
val BIGINTEGER = LogicalTypeMapping(LogicalType.BIGINTEGER)
val BOOLEAN = LogicalTypeMapping(LogicalType.BOOLEAN)
val STRING = LogicalTypeMapping(LogicalType.STRING)
val BINARY = LogicalTypeMapping(LogicalType.BINARY)
val DATE = LogicalTypeMapping(LogicalType.DATE)
val TIME = LogicalTypeMapping(LogicalType.TIME)
val TIMESTAMP = LogicalTypeMapping(LogicalType.TIMESTAMP)

/** `DECIMAL(7)` — REAL / float32 origin, scale omitted (§3.4). */
val SINGLE = LogicalTypeMapping(LogicalType.DECIMAL, precision = 7)

/** `DECIMAL(15)` — DOUBLE / float64 origin, scale omitted (§3.4). */
val DOUBLE = LogicalTypeMapping(LogicalType.DECIMAL, precision = 15)

/** `DECIMAL(p, s)` — exact origin at or below the §3.3 threshold. */
fun decimal(
    precision: Int,
    scale: Int,
): LogicalTypeMapping = LogicalTypeMapping(LogicalType.DECIMAL, precision, scale)

/** `BIGDECIMAL(p, s)` — exact origin above the §3.3 threshold. */
fun bigDecimal(
    precision: Int,
    scale: Int,
): LogicalTypeMapping = LogicalTypeMapping(LogicalType.BIGDECIMAL, precision, scale)

/** `BIGDECIMAL` with precision **omitted** — the §4 unbounded encoding. */
fun unbounded(scale: Int = 0): LogicalTypeMapping = LogicalTypeMapping(LogicalType.BIGDECIMAL, precision = null, scale = scale)
