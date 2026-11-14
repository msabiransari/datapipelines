package co.datapipelines.lake.append

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.Statement

/**
 * #968 O12, the tripwire for #971: plain DuckDB on the pinned driver, no catalog, no store. `ORDER BY` on a `DOUBLE`
 * key returns every `-0.0` as `0.0` (duckdb/duckdb#25417; reproduced on 1.5.5 and 1.5.6), so a floating column is
 * never a sort column (owner ruling 2026-11-13T18:23:58Z; brief 935's definition rule, [AppendOrder.keys]).
 * This asserts today's defect on purpose. Nobody announces the fix to us (the upstream issue is closed): when a driver
 * bump turns this red, the lane it goes red in turns the assertion round (the sign is kept; a regression test from then
 * on) and reports that #971 is ready. Never delete or loosen it to make a bump green.
 */
class FloatingSortKeyTripwireTest {
    @Test
    fun `an ORDER BY on a DOUBLE key still drops the sign of zero, and a plain read keeps it`() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE t AS SELECT * FROM (VALUES ('-0.0'::DOUBLE), ('0.0'::DOUBLE), ('1.0'::DOUBLE)) v(v)")
                withClue("non-vacuity: the stored rows carry one negative zero") { negativeZeros(statement, "SELECT v FROM t") shouldBe 1 }
                withClue(MESSAGE) { negativeZeros(statement, "SELECT v FROM t ORDER BY v ASC NULLS FIRST") shouldBe 0 }
            }
        }
    }

    private fun negativeZeros(
        statement: Statement,
        sql: String,
    ): Int =
        statement.executeQuery(sql).use { rows ->
            generateSequence { if (rows.next()) rows.getDouble(1) else null }
                .count { java.lang.Double.doubleToRawLongBits(it) == NEGATIVE_ZERO_BITS }
        }

    private companion object {
        val NEGATIVE_ZERO_BITS = java.lang.Double.doubleToRawLongBits(-0.0)
        const val MESSAGE =
            "the pinned DuckDB now keeps the sign of zero in a floating sort key: " +
                "the refusal of a floating sort column can be lifted (#971)"
    }
}
