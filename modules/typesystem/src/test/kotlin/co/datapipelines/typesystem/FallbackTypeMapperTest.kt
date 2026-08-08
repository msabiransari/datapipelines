package co.datapipelines.typesystem

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.sql.Types

/**
 * The §8.2 unknown-type contract: **STRING, exactly one warning, never a throw.**
 *
 * The "never throws" half is the part worth testing hardest. An exception here would
 * fail a whole pipeline execution over one exotic column, which is exactly the behavior
 * §8.2 exists to forbid — the author is supposed to see a warning and add a `CAST`.
 */
class FallbackTypeMapperTest {
    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `an unknown JDBC type maps to STRING with exactly one warning`() {
        val mapped = FallbackTypeMapper.mapColumn("weird_column", UNKNOWN_JDBC_TYPE, 0, 0, "pgvector")

        mapped.column shouldBe ColumnSchema("weird_column", LogicalType.STRING)
        mapped.warnings.size shouldBe 1
        with(mapped.warnings.single()) {
            code shouldBe "type_mapping.unknown_source_type"
            column shouldBe "weird_column"
            sourceType shouldBe "pgvector"
            message shouldContain "has no canonical mapping"
            message shouldContain "falling back to STRING"
        }
    }

    @Test
    fun `the warning serializes to the §8-2 payload shape`() {
        val warning = TypeMappingWarning.unknownSourceType("weird_column", "pgvector")
        val json = mapper.readTree(mapper.writeValueAsString(warning))

        json.fieldNames().asSequence().toList() shouldContainExactly
            listOf("code", "message", "column", "source_type")
        json["source_type"].asText() shouldBe "pgvector"
    }

    @Test
    fun `the sql_variant warning omits source_type entirely`() {
        // §10.5's example payload carries no source_type; emitting `null` would add a
        // field the spec's example does not have.
        val json = mapper.readTree(mapper.writeValueAsString(TypeMappingWarning.sqlVariant("mixed_values")))

        json.fieldNames().asSequence().toList() shouldContainExactly listOf("code", "message", "column")
    }

    @Test
    fun `no JDBC type code, however absurd, makes any mapper throw`() {
        // Swept across every dialect and a wide code range rather than spot-checked: the
        // §8.2 guarantee is universal, and one mapper quietly throwing on one exotic code
        // would only surface in production, mid-execution.
        //
        // BOTH entry points are swept. `map` and `mapColumn` are separate code paths, and
        // `map`'s never-throw guarantee previously rested on a single incidental Oracle
        // assertion — so a mapper could have thrown from `map` while `mapColumn` stayed
        // clean, and nothing would have said so.
        val codes = (-1_000..3_000).toList() + listOf(Int.MIN_VALUE, Int.MAX_VALUE)

        Dialect.entries.forEach { dialect ->
            val ingress = TypeMappers.forDialect(dialect)
            codes.forEach { code ->
                ingress.map(code, 0, 0, "whatever_$code")
                ingress.mapColumn("c", code, 0, 0, "whatever_$code")
            }
        }
    }

    @Test
    fun `no precision or scale a driver can report makes any mapper throw`() {
        // The other half of the input space, and the one that actually bit: a driver
        // reporting precision 0 on an exact numeric used to build DECIMAL(0), which
        // violates §7.1's `minimum: 1` and threw out of mapColumn — failing a whole
        // execution over a driver quirk, contra §8.2. Negative and extreme values are
        // included because ResultSetMetaData is not obliged to be sane.
        Dialect.entries.forEach { dialect -> sweepNumericMetadata(TypeMappers.forDialect(dialect)) }
        sweepNumericMetadata(FallbackTypeMapper)
    }

    private fun sweepNumericMetadata(ingress: IngressTypeMapper) {
        NUMERIC_CODES.forEach { code ->
            METADATA_EXTREMES.forEach { precision ->
                METADATA_EXTREMES.forEach { scale ->
                    ingress.map(code, precision, scale, "numeric")
                    ingress.mapColumn("c", code, precision, scale, "numeric")
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Dialect::class)
    fun `an unrecognized type never yields more than one warning for a column`(dialect: Dialect) {
        // "Exactly one" is the contract — a duplicate would show up twice in the response
        // envelope for a single column.
        val mapped = TypeMappers.forDialect(dialect).mapColumn("c", UNKNOWN_JDBC_TYPE, 0, 0, "no_such_type")

        if (dialect == Dialect.SQLITE) {
            // SQLite is the one dialect with no unknown case: §5.7's affinity rules give
            // every declared string — including one nothing else matches — an answer
            // (here NUMERIC affinity), so there is nothing to warn about.
            mapped.warnings shouldBe emptyList()
        } else {
            mapped.warnings.size shouldBe 1
            mapped.column.type shouldBe LogicalType.STRING
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Dialect::class)
    fun `Types-NULL maps to canonical NULL through BOTH entry points`(dialect: Dialect) {
        // §8.1 on the `map` path as well as the `mapColumn` path. The two implement the
        // rule independently — `mapColumn` cannot delegate to `map`, because it must
        // still distinguish "recognized" from "fell back" to decide on a warning — so a
        // §8.1 test that only exercises one of them leaves the other free to drift.
        //
        // Found by mutation: replacing the Types.NULL branch in `map` with STRING
        // survived the entire suite, because every §8.1 assertion happened to go through
        // `mapColumn`. Same defect class as the recognized-path gap, opposite direction.
        val ingress = TypeMappers.forDialect(dialect)

        ingress.map(Types.NULL, 0, 0, "") shouldBe LogicalTypeMapping(LogicalType.NULL)
        // …and the dialect's own type name must not talk it out of the answer.
        ingress.map(Types.NULL, 10, 2, "numeric") shouldBe LogicalTypeMapping(LogicalType.NULL)
        ingress.mapColumn("c", Types.NULL, 0, 0, "").column.type shouldBe LogicalType.NULL
    }

    @Test
    fun `Types-NULL survives the fallback mapper's own map path`() {
        // The unknown-dialect route (§11.2) implements §8.1 separately again.
        FallbackTypeMapper.map(Types.NULL, 0, 0, "anything") shouldBe LogicalTypeMapping(LogicalType.NULL)
    }

    @Test
    fun `a driver-reported NULL type is an answer, not an unknown type`() {
        // §8.1: Types.NULL means "all-NULL column", so it maps to canonical NULL and
        // raises NO warning — even on the fallback path, where everything else does.
        val mapped = FallbackTypeMapper.mapColumn("only_nulls", Types.NULL, 0, 0, "")

        mapped.column.type shouldBe LogicalType.NULL
        mapped.warnings shouldBe emptyList()
    }

    @Test
    fun `a blank type name still produces a usable warning`() {
        // Some drivers report no type name at all; a warning that says `''` helps nobody.
        val mapped = FallbackTypeMapper.mapColumn("c", UNKNOWN_JDBC_TYPE, 0, 0, "")

        mapped.warnings.single().sourceType shouldBe "JDBC type $UNKNOWN_JDBC_TYPE"
    }

    private companion object {
        /** A code no dialect table lists. */
        const val UNKNOWN_JDBC_TYPE = 9999

        /** The codes whose answer depends on the reported precision and scale. */
        val NUMERIC_CODES = listOf(Types.NUMERIC, Types.DECIMAL, Types.FLOAT, Types.REAL, Types.DOUBLE)

        /** `ResultSetMetaData` is not obliged to be sane; neither are these. */
        val METADATA_EXTREMES = listOf(Int.MIN_VALUE, -100, -1, 0, 1, 15, 16, 38, 100_000, Int.MAX_VALUE)
    }
}
