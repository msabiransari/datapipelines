package co.datapipelines.staging

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID

/**
 * A throwaway in-memory H2 database standing in for a **source** datasource. It produces the
 * live JDBC `ResultSet`s that [Staging.stage] streams from, so staging tests exercise the real
 * `ResultSet` → H2 path (not a mock) end to end. Distinct JDBC URL per instance, so it never
 * collides with the per-execution staging databases under test.
 */
internal class SourceDb : AutoCloseable {
    private val connection: Connection =
        DriverManager.getConnection("jdbc:h2:mem:src_${UUID.randomUUID()};MODE=PostgreSQL", "sa", "")

    fun exec(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    /**
     * Returns a live cursor. The backing `Statement` is intentionally left open for the source
     * database's lifetime (until [close]); tests consume the cursor before then.
     */
    fun query(sql: String): ResultSet = connection.createStatement().executeQuery(sql)

    override fun close() {
        connection.close()
    }
}
