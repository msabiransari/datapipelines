package co.datapipelines.staging

import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.TypeMappingWarning
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Types
import java.util.UUID

/**
 * §3.2's source-dialect rule: `stage` maps source columns through **the source dialect's**
 * mapper, never through H2's, and surfaces that mapping's §8.2 warnings on [StageResult].
 *
 * The defect this pins: mapping source metadata with `H2IngressMapper` reads another dialect's
 * JDBC codes as if they were H2's. Oracle's `DATE` (JDBC code 91) carries a time-of-day and is
 * canonically a `TIMESTAMP`; H2's code 91 is a plain `DATE`. Under the old code an Oracle
 * source's `DATE` column was created as an H2 `DATE` and the time component was **dropped at
 * insert** — before egress re-derivation could ever see it, with no warning anywhere.
 *
 * Source cursors here are mocks with zero rows: the assertion is about the schema the metadata
 * produces, and a mock is the only way to present Oracle/MSSQL metadata without those servers.
 */
class H2StagingSourceDialectTest {
    private val staging = H2StagingFactory(H2StagingProperties()).create(UUID.randomUUID())

    @AfterEach
    fun tearDown() = staging.close()

    @Test
    fun `an Oracle DATE stages as canonical TIMESTAMP, not as H2's DATE`() {
        val oracle =
            runBlocking {
                staging.stage(sourceResultSet(SourceColumn("HIRED", Types.DATE, typeName = "DATE")), "stg_oracle", Dialect.ORACLE)
            }
        val h2 =
            runBlocking {
                staging.stage(sourceResultSet(SourceColumn("HIRED", Types.DATE, typeName = "DATE")), "stg_h2", Dialect.H2)
            }

        // Identical metadata, different dialect → different canonical type. This is the whole
        // point of the parameter: it must not be possible for both to agree.
        oracle.columns.single().type shouldBe LogicalType.TIMESTAMP
        h2.columns.single().type shouldBe LogicalType.DATE
    }

    @Test
    fun `a MySQL multi-bit column stages as BINARY, not as H2's BOOLEAN`() {
        val bitString = SourceColumn("FLAGS", Types.BIT, precision = 8, typeName = "BIT")
        val mysql = runBlocking { staging.stage(sourceResultSet(bitString), "stg_mysql", Dialect.MYSQL) }

        mysql.columns.single().type shouldBe LogicalType.BINARY
    }

    @Test
    fun `a clean mapping reports no warnings`() {
        val result =
            runBlocking {
                staging.stage(sourceResultSet(SourceColumn("ID", Types.INTEGER, typeName = "int4")), "stg_clean", Dialect.POSTGRES)
            }

        result.warnings.shouldBeEmpty()
        result.columns.single().type shouldBe LogicalType.INTEGER
    }

    @Test
    fun `an unknown source type falls back to STRING and surfaces one warning naming the column`() {
        val result =
            runBlocking {
                staging.stage(
                    sourceResultSet(SourceColumn("ODDBALL", Types.JAVA_OBJECT, typeName = "mystery_type")),
                    "stg_unknown",
                    Dialect.POSTGRES,
                )
            }

        // Non-fatal: the node completes, the table exists, the author gets told (§8.2).
        result.rowsStaged shouldBe 0L
        result.columns.single().type shouldBe LogicalType.STRING
        val warning = result.warnings.single()
        warning.code shouldBe TypeMappingWarning.UNKNOWN_SOURCE_TYPE
        warning.column shouldBe "ODDBALL"
        warning.sourceType shouldBe "mystery_type"
        staging.readFromStaging { tableCount(it) } shouldBe 1
    }

    @Test
    fun `a recognized-but-lossy MSSQL sql_variant still warns`() {
        val result =
            runBlocking {
                staging.stage(
                    sourceResultSet(SourceColumn("ANYTHING", Types.OTHER, typeName = "sql_variant")),
                    "stg_variant",
                    Dialect.MSSQL,
                )
            }

        result.columns.single().type shouldBe LogicalType.STRING
        result.warnings.single().code shouldBe TypeMappingWarning.SQL_VARIANT
        result.warnings.single().column shouldBe "ANYTHING"
    }

    @Test
    fun `warnings are one per affected column, flattened in column order`() {
        val result =
            runBlocking {
                staging.stage(
                    sourceResultSet(
                        SourceColumn("FIRST", Types.JAVA_OBJECT, typeName = "mystery_a"),
                        SourceColumn("PLAIN", Types.INTEGER, typeName = "int4"),
                        SourceColumn("LAST", Types.JAVA_OBJECT, typeName = "mystery_b"),
                    ),
                    "stg_multi",
                    Dialect.POSTGRES,
                )
            }

        result.warnings.map { it.column } shouldBe listOf("FIRST", "LAST")
        result.warnings.map { it.sourceType } shouldBe listOf("mystery_a", "mystery_b")
    }

    @Test
    fun `a hostile-length source type name is bounded before it rides out on a warning`() {
        // `sourceType` is reflected text from a foreign driver's metadata that reaches the UI
        // (ST-SEC-2). An unbounded one is a payload budget nobody set.
        val hostile = "x".repeat(5_000)
        val result =
            runBlocking {
                staging.stage(
                    sourceResultSet(SourceColumn("ODD", Types.JAVA_OBJECT, typeName = hostile)),
                    "stg_longtype",
                    Dialect.POSTGRES,
                )
            }

        val warning = result.warnings.single()
        val sourceType = warning.sourceType ?: ""
        sourceType.length shouldBe BOUNDED_SOURCE_TYPE_LENGTH
        sourceType.endsWith("…") shouldBe true
    }

    /** One column's worth of source `ResultSetMetaData`, as a dialect's driver would report it. */
    private data class SourceColumn(
        val label: String,
        val sqlType: Int,
        val precision: Int = 0,
        val scale: Int = 0,
        val typeName: String,
    )

    /** A zero-row source cursor whose metadata is exactly [columns]. */
    private fun sourceResultSet(vararg columns: SourceColumn): ResultSet {
        val meta =
            mockk<ResultSetMetaData> {
                every { columnCount } returns columns.size
                columns.forEachIndexed { i, column ->
                    every { getColumnLabel(i + 1) } returns column.label
                    every { getColumnType(i + 1) } returns column.sqlType
                    every { getPrecision(i + 1) } returns column.precision
                    every { getScale(i + 1) } returns column.scale
                    every { getColumnTypeName(i + 1) } returns column.typeName
                }
            }
        return mockk {
            every { metaData } returns meta
            every { next() } returns false
        }
    }

    private companion object {
        /** 64 retained characters plus the single-character ellipsis the bound appends. */
        const val BOUNDED_SOURCE_TYPE_LENGTH = 65
    }
}
