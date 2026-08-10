package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import java.util.concurrent.CopyOnWriteArrayList

/** Shared datasource builders for the module's unit tests. */
internal object Fixtures {
    /** A valid H2 datasource — H2 is bundled, so its driver is always on the test classpath. */
    fun h2(
        name: String = "test_h2",
        jdbcUrl: String = "jdbc:h2:mem:$name",
        password: String? = "secret",
        queryTimeoutSeconds: Int? = null,
        properties: DatasourceProperties = DatasourceProperties(),
    ): Datasource =
        Datasource(
            name = name,
            displayName = "Test H2",
            description = "A fixture datasource.",
            dialect = Dialect.H2,
            jdbcUrl = jdbcUrl,
            username = "sa",
            password = password,
            queryTimeoutSeconds = queryTimeoutSeconds,
            properties = properties,
        )

    fun postgres(
        name: String = "pg_test",
        jdbcUrl: String = "jdbc:postgresql://db.internal:5432/app",
        properties: DatasourceProperties = DatasourceProperties(),
    ): Datasource =
        Datasource(
            name = name,
            displayName = "Test PG",
            dialect = Dialect.POSTGRES,
            jdbcUrl = jdbcUrl,
            username = "app",
            password = "secret",
            properties = properties,
        )

    /**
     * A well-formed datasource for **any** dialect, so a per-dialect rule can be exercised across
     * the whole enum without seven near-identical builders. The URLs are the shape each driver
     * documents; nothing here connects.
     */
    fun forDialect(
        dialect: Dialect,
        name: String = "ds_${dialect.wire.lowercase()}",
        properties: DatasourceProperties = DatasourceProperties(),
    ): Datasource =
        Datasource(
            name = name,
            displayName = "Test ${dialect.wire}",
            dialect = dialect,
            jdbcUrl = urlFor(dialect, name),
            username = "app",
            password = "secret",
            properties = properties,
        )

    fun urlFor(
        dialect: Dialect,
        name: String = "t",
    ): String =
        when (dialect) {
            Dialect.POSTGRES -> "jdbc:postgresql://db.internal:5432/app"
            Dialect.ORACLE -> "jdbc:oracle:thin:@//db.internal:1521/svc"
            Dialect.MSSQL -> "jdbc:sqlserver://db.internal:1433;databaseName=app"
            Dialect.MYSQL -> "jdbc:mysql://db.internal:3306/app"
            Dialect.H2 -> "jdbc:h2:mem:$name"
            Dialect.DUCKDB -> "jdbc:duckdb::memory:"
            Dialect.SQLITE -> "jdbc:sqlite::memory:"
        }
}

/**
 * A [DatasourceAuditSink] that keeps what it was given, so a test can assert the §7.4 contract —
 * *which* events fire, how many times, and with what actor — instead of only that the code ran.
 */
internal class RecordingAuditSink : DatasourceAuditSink {
    private val recorded = CopyOnWriteArrayList<DatasourceAuditEvent>()

    override fun record(event: DatasourceAuditEvent) {
        recorded += event
    }

    val events: List<DatasourceAuditEvent> get() = recorded.toList()

    fun eventNames(): List<String> = events.map { it.event }

    fun countOf(event: String): Int = events.count { it.event == event }
}
