package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.typesystem.Dialect
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.DriverManager
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

/**
 * A [ConnectionPool] that hands out fresh connections to [url] — the shared fake behind the
 * introspection tests' "same named in-memory DB" leases (SchemaIntrospectorTest's seams).
 */
internal class JdbcUrlPool(
    url: String,
    override val name: String = "pooled",
) : ConnectionPool {
    private val connect: () -> Connection = { DriverManager.getConnection(url) }

    override fun leaseConnection(): Connection = connect()

    override fun close() = Unit
}

/** [JdbcUrlPool] plus a lease counter — proves the one-lease snapshot protocol. */
internal class CountingPool(
    url: String,
    override val name: String = "counted",
) : ConnectionPool {
    private val delegate = JdbcUrlPool(url)

    var leases = 0
        private set

    override fun leaseConnection(): Connection {
        leases++
        return delegate.leaseConnection()
    }

    override fun close() = Unit
}

/**
 * An [SchemaIntrospector] whose registry hands out ONE connection carrying the given mocked
 * [DatabaseMetaData]. Returns (introspector, datasource name). [connectionSetup] stubs
 * connection-level reads the operation under test consults (getSchema/getCatalog).
 */
internal fun introspectorOver(
    dialect: Dialect,
    meta: DatabaseMetaData,
    connectionSetup: (Connection) -> Unit = {},
): Pair<SchemaIntrospector, String> {
    val ds = Fixtures.forDialect(dialect)
    val connection = mockk<Connection>()
    every { connection.metaData } returns meta
    every { connection.close() } returns Unit
    connectionSetup(connection)
    val registry = mockk<DatasourceRegistry>()
    every { registry.get(ds.name) } returns ds
    every { registry.poolFor(ds) } returns
        object : ConnectionPool {
            override val name: String = ds.name

            override fun leaseConnection(): Connection = connection

            override fun close() = Unit
        }
    return SchemaIntrospector(registry) to ds.name
}
