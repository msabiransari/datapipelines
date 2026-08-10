package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.sql.Connection

/**
 * Shared probe for the per-dialect connectivity integration tests: build a real pool through the
 * dialect adapter, lease a live connection, run a query, and map the resulting column with that
 * adapter's `typeMapper` (the type-system.md §5 table for the dialect).
 *
 * Extracted so the MSSQL case — which needs its own architecture gate, see
 * [MssqlConnectivityIntegrationTest] — exercises the identical code path as Postgres and MySQL
 * rather than a second, subtly different copy.
 */
internal object DialectProbe {
    /** Leases a real connection, runs [integerQuery], and maps its single column via the adapter. */
    fun verifyIntegerColumn(
        datasource: Datasource,
        integerQuery: String,
    ) {
        val adapter = DialectAdapters.forDialect(datasource.dialect)
        val mapped =
            ConnectionPoolManager.buildHikariPool(datasource).use { pool ->
                pool.leaseConnection().use { connection -> firstColumnType(connection, integerQuery, adapter) }
            }
        // An integer column maps into the exact-integer family (type-system §5).
        listOf(LogicalType.INTEGER, LogicalType.BIGINTEGER) shouldContain mapped
    }

    /** Runs [query], asserts it returned the integer 1, and maps column 1 through [adapter]. */
    private fun firstColumnType(
        connection: Connection,
        query: String,
        adapter: DialectAdapter,
    ): LogicalType =
        connection.createStatement().use { statement ->
            statement.executeQuery(query).use { rs ->
                rs.next() shouldBe true
                rs.getInt(1) shouldBe 1
                val meta = rs.metaData
                adapter.typeMapper
                    .map(
                        sqlType = meta.getColumnType(1),
                        precision = meta.getPrecision(1),
                        scale = meta.getScale(1),
                        typeName = meta.getColumnTypeName(1),
                    ).type
            }
        }
}
