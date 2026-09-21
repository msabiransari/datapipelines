package co.datapipelines.datasources

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.sql.SQLException

/**
 * The 123 §A permission-denied classification ([isPermissionDenied]): each dialect's shape is
 * pinned (Postgres 42501; MySQL 1142/1143 and MSSQL 229/230 under 42000, discriminated on the
 * VENDOR CODE; Oracle ORA-01031 as 72000/1031), the deliberately ambiguous ORA-00942 stays
 * OUT, and the walk sees the shape anywhere on the cause/nextException chain.
 */
class SqlProbePermissionDeniedTest {
    @Test
    fun `postgres insufficient_privilege is classified`() {
        SQLException("ERROR: permission denied for table orders", "42501").isPermissionDenied() shouldBe true
    }

    @Test
    fun `mysql and mssql permission codes are classified under 42000 - the vendor code discriminates`() {
        assertAll(
            { SQLException("SELECT command denied to user 'app' for table 'orders'", "42000", 1142).isPermissionDenied() shouldBe true },
            { SQLException("SELECT command denied to user 'app' for column 'salary'", "42000", 1143).isPermissionDenied() shouldBe true },
            { SQLException("The SELECT permission was denied on the object 'orders'", "42000", 229).isPermissionDenied() shouldBe true },
            { SQLException("The SELECT permission was denied on the column 'salary'", "42000", 230).isPermissionDenied() shouldBe true },
            // 42000 alone is a syntax-error state, not permission — no vendor code, no classification.
            { SQLException("You have an error in your SQL syntax", "42000", 1064).isPermissionDenied() shouldBe false },
        )
    }

    @Test
    fun `oracle ORA-01031 is classified and ORA-00942 is deliberately not`() {
        assertAll(
            { SQLException("ORA-01031: insufficient privileges", "72000", 1031).isPermissionDenied() shouldBe true },
            // Ambiguous between absent and invisible — stays on the not-found path (123 §A).
            { SQLException("ORA-00942: table or view does not exist", "42000", 942).isPermissionDenied() shouldBe false },
        )
    }

    @Test
    fun `h2 admin-rights-required is classified - the de-privileged in-process shape`() {
        // H2 2.3.232's refusal of a host-reaching function to a non-admin session — its
        // SQLState IS the vendor code (186).
        SQLException("Admin rights are required for this operation", "90040", 90040).isPermissionDenied() shouldBe true
    }

    @Test
    fun `unrelated failures are not classified`() {
        assertAll(
            { SQLException("ERROR: relation \"orders\" does not exist", "42P01").isPermissionDenied() shouldBe false },
            { SQLException("canceling statement due to statement timeout", "57014").isPermissionDenied() shouldBe false },
            { SQLException("Connection refused", "08001").isPermissionDenied() shouldBe false },
            { SQLException("Table \"ORDERS\" not found", "42S02", 42102).isPermissionDenied() shouldBe false },
        )
    }

    @Test
    fun `the shape is found anywhere on the cause and nextException chains`() {
        assertAll(
            {
                SQLException("batch failed", "HY000", 0, SQLException("permission denied", "42501"))
                    .isPermissionDenied() shouldBe true
            },
            {
                SQLException("wrapper", null, 0)
                    .apply { initCause(SQLException("ORA-01031: insufficient privileges", "72000", 1031)) }
                    .isPermissionDenied() shouldBe true
            },
        )
    }
}
