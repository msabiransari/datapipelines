package co.datapipelines.executor

import co.datapipelines.datasources.LocalWriteQualification
import co.datapipelines.datasources.QualificationStatus
import co.datapipelines.pipeline.DmlKey
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * #1036 R2 — an insert is admitted by the DECLARED KEY TYPE, not by the dialect alone. On an engine the
 * qualification matrix covers, a key type is admitted only where a matrix row measured it with the
 * pinned driver in both key modes; a type no row covers is refused before the first business
 * statement. The real-driver evidence for each measured PostgreSQL type is
 * `DmlKeyTypeQualificationIntegrationTest` (tests/integration-tests) and the existing INTEGER and
 * BIGINTEGER runs it names (dag-executor.md §16.6).
 */
class DmlKeyTypeAdmissionTest {
    @Test
    fun `every key type the contract admits is admitted on PostgreSQL exactly because the matrix measured it`() {
        DmlKey.SUPPORTED_TYPES.forEach { type ->
            withClue("PostgreSQL $type") {
                DmlAdmission.insertQualified(Dialect.POSTGRES, type) shouldBe
                    (LocalWriteQualification.insertStatus(Dialect.POSTGRES, type) == QualificationStatus.QUALIFIED)
                DmlAdmission.insertQualified(Dialect.POSTGRES, type) shouldBe true
            }
        }
    }

    @Test
    fun `an unmeasured key type is refused on a covered engine even where the dialect returns keys`() {
        // DECIMAL is not a contract key type; the matrix has no row for it, so the admission gate says no.
        DmlAdmission.insertQualified(Dialect.POSTGRES, LogicalType.DECIMAL) shouldBe false
        DmlAdmission.insertQualified(Dialect.POSTGRES, LogicalType.BOOLEAN) shouldBe false
    }

    @Test
    fun `an engine whose inserts are refused refuses every key type`() {
        DmlKey.SUPPORTED_TYPES.forEach { type ->
            listOf(Dialect.MYSQL, Dialect.MSSQL, Dialect.ORACLE).forEach { dialect ->
                withClue("$dialect $type") { DmlAdmission.insertQualified(dialect, type) shouldBe false }
            }
        }
    }

    @Test
    fun `the embedded engines keep their dialect-only authority for every key type`() {
        DmlKey.SUPPORTED_TYPES.forEach { type ->
            listOf(Dialect.H2, Dialect.DUCKDB).forEach { dialect ->
                withClue("$dialect $type") { DmlAdmission.insertQualified(dialect, type) shouldBe true }
            }
            DmlAdmission.insertQualified(Dialect.SQLITE, type) shouldBe false
        }
    }

    @Test
    fun `an invalid key is left to the key rules, not refused as a qualification gap`() {
        DmlAdmission.insertQualified(Dialect.POSTGRES, null) shouldBe true
    }

    @Test
    fun `a refusal for a key type names the type, never a value, and a dialect refusal stays as it was`() {
        val typed = DmlAdmission.unqualified("ins", Dialect.POSTGRES, keyType = LogicalType.STRING)
        typed.code shouldBe PipelineErrorCodes.Node.DML_INSERT_UNQUALIFIED
        typed.message shouldContain "not qualified to return STRING keys"
        typed.details["key_type"] shouldBe "STRING"

        val dialectOnly = DmlAdmission.unqualified("ins", Dialect.MYSQL, keyType = LogicalType.STRING)
        dialectOnly.message shouldContain "not qualified to return inserted keys"
        dialectOnly.message shouldNotContain "STRING"
        dialectOnly.details.containsKey("key_type") shouldBe false
    }
}
