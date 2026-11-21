package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.DmlInsertQualification
import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The qualification matrix's shape (#1036): which engines the transaction covers, what a combined
 * status means, and that the insert rows never disagree with the insert authority the executor's
 * per-node backstop reads ([DmlInsertQualification]). The real-driver evidence behind each row is
 * `LocalTransactionDialectQualificationTest` in modules/dag.
 */
class LocalWriteQualificationTest {
    @Test
    fun `the four transactional engines are in scope and the embedded engines and LAKE are not`() {
        Dialect.entries.filter(LocalWriteQualification::inScope).toSet() shouldBe
            setOf(Dialect.POSTGRES, Dialect.MYSQL, Dialect.MSSQL, Dialect.ORACLE)
        listOf(Dialect.H2, Dialect.SQLITE, Dialect.DUCKDB, Dialect.LAKE).flatMap(LocalWriteQualification::rowsFor).shouldBeEmpty()
    }

    @Test
    fun `every in-scope engine records every operation and both insert key modes on its default storage`() {
        Dialect.entries.filter(LocalWriteQualification::inScope).forEach { dialect ->
            LocalWriteOperation.entries.forEach { operation ->
                val keyModes = LocalWriteQualification.rows(dialect, operation).map { it.keyMode }.toSet()
                val expected =
                    if (operation ==
                        LocalWriteOperation.INSERT
                    ) {
                        setOf(KeyMode.GENERATED, KeyMode.SUPPLIED)
                    } else {
                        setOf(KeyMode.NONE)
                    }
                keyModes shouldBe expected
            }
        }
    }

    @Test
    fun `PostgreSQL MySQL and SQL Server qualify the transaction, Oracle is unavailable and is not qualified`() {
        Dialect.entries.filter(LocalWriteQualification::transactionQualified).toSet() shouldBe
            setOf(Dialect.POSTGRES, Dialect.MYSQL, Dialect.MSSQL)
        LocalWriteOperation.entries.map { LocalWriteQualification.status(Dialect.ORACLE, it) }.toSet() shouldBe
            setOf(QualificationStatus.UNAVAILABLE)
    }

    @Test
    fun `an insert is qualified only when both key modes are, and an out-of-scope engine reads refused`() {
        LocalWriteQualification.status(Dialect.POSTGRES, LocalWriteOperation.INSERT) shouldBe QualificationStatus.QUALIFIED
        LocalWriteQualification.status(Dialect.MYSQL, LocalWriteOperation.INSERT) shouldBe QualificationStatus.REFUSED
        LocalWriteQualification.status(Dialect.MSSQL, LocalWriteOperation.INSERT) shouldBe QualificationStatus.REFUSED
        LocalWriteQualification.status(Dialect.H2, LocalWriteOperation.UPDATE) shouldBe QualificationStatus.REFUSED
    }

    @Test
    fun `the insert rows agree with the per-node insert authority on every in-scope engine`() {
        Dialect.entries.filter(LocalWriteQualification::inScope).forEach { dialect ->
            (LocalWriteQualification.status(dialect, LocalWriteOperation.INSERT) == QualificationStatus.QUALIFIED) shouldBe
                DmlInsertQualification.isQualified(dialect)
        }
    }

    @Test
    fun `PostgreSQL qualifies exactly the measured key types, each in both key modes`() {
        LocalWriteQualification.qualifiedKeyTypes(Dialect.POSTGRES) shouldContainExactly
            listOf(LogicalType.INTEGER, LogicalType.BIGINTEGER, LogicalType.STRING)
        listOf(LogicalType.INTEGER, LogicalType.BIGINTEGER, LogicalType.STRING).forEach { type ->
            LocalWriteQualification
                .rowsFor(Dialect.POSTGRES)
                .filter { it.operation == LocalWriteOperation.INSERT && it.keyType == type }
                .map { it.keyMode to it.status } shouldContainExactly
                listOf(
                    KeyMode.GENERATED to QualificationStatus.QUALIFIED,
                    KeyMode.SUPPLIED to QualificationStatus.QUALIFIED,
                )
        }
    }

    @Test
    fun `a key type no row measured is refused, never admitted under another type's evidence`() {
        val measured = setOf(LogicalType.INTEGER, LogicalType.BIGINTEGER, LogicalType.STRING)
        LogicalType.entries.filterNot { it in measured }.forEach { type ->
            LocalWriteQualification.insertStatus(Dialect.POSTGRES, type) shouldBe QualificationStatus.REFUSED
        }
    }

    @Test
    fun `a qualified row covers its own key type only, so removing one type's rows refuses that type`() {
        // The covering rule, on the matrix as shipped: STRING and BIGINTEGER are separate rows.
        val postgres = LocalWriteQualification.rowsFor(Dialect.POSTGRES).filter { it.operation == LocalWriteOperation.INSERT }
        postgres.map { it.keyType }.toSet() shouldBe setOf(LogicalType.INTEGER, LogicalType.BIGINTEGER, LogicalType.STRING)
        postgres.none { it.keyType == null } shouldBe true
    }

    @Test
    fun `MySQL and SQL Server refuse and Oracle is unavailable for every key type`() {
        LogicalType.entries.forEach { type ->
            LocalWriteQualification.insertStatus(Dialect.MYSQL, type) shouldBe QualificationStatus.REFUSED
            LocalWriteQualification.insertStatus(Dialect.MSSQL, type) shouldBe QualificationStatus.REFUSED
            LocalWriteQualification.insertStatus(Dialect.ORACLE, type) shouldBe QualificationStatus.UNAVAILABLE
        }
        listOf(Dialect.MYSQL, Dialect.MSSQL, Dialect.ORACLE).forEach {
            LocalWriteQualification.qualifiedKeyTypes(it).shouldBeEmpty()
        }
    }

    @Test
    fun `the non-transactional MySQL storage is a recorded limitation the runtime does not read`() {
        val myisam = LocalWriteQualification.rowsFor(Dialect.MYSQL).single { !it.defaultStorage }
        myisam.storage shouldBe "MyISAM"
        myisam.status shouldBe QualificationStatus.REFUSED
        LocalWriteQualification.status(Dialect.MYSQL, LocalWriteOperation.TRANSACTION) shouldBe QualificationStatus.QUALIFIED
    }

    @Test
    fun `reasons name no secret, SQL statement or credential`() {
        LocalWriteQualification.ROWS.forEach { row ->
            listOf("password", "secret", "SELECT ", "INSERT INTO", "jdbc:").forEach { row.reason shouldNotContain it }
        }
    }
}
