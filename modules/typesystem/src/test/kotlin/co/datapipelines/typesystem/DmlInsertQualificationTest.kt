package co.datapipelines.typesystem

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins the qualified-dialect set for a DML `insert` (pipeline-contract §4.4): only the dialects
 * whose pinned driver was measured to return the named key column. A dialect that flips without
 * its real-driver evidence (#1036 owns MySQL, SQL Server and Oracle) turns this red.
 */
class DmlInsertQualificationTest {
    @Test
    fun `only the measured dialects are qualified for an insert`() {
        Dialect.entries.filter { DmlInsertQualification.isQualified(it) }.toSet() shouldBe
            setOf(Dialect.H2, Dialect.DUCKDB, Dialect.POSTGRES)
    }

    @Test
    fun `sqlite, the unmeasured vendors and the lake stay refused`() {
        listOf(Dialect.SQLITE, Dialect.MYSQL, Dialect.MSSQL, Dialect.ORACLE, Dialect.LAKE)
            .map { DmlInsertQualification.isQualified(it) } shouldBe listOf(false, false, false, false, false)
    }
}
