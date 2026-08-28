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
        introspectionIncludeSchemas: List<String> = emptyList(),
        isReadonly: Boolean = false,
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
            introspectionIncludeSchemas = introspectionIncludeSchemas,
            isReadonly = isReadonly,
        )

    fun postgres(
        name: String = "pg_test",
        jdbcUrl: String = "jdbc:postgresql://db.internal:5432/app",
        properties: DatasourceProperties = DatasourceProperties(),
        introspectionIncludeSchemas: List<String> = emptyList(),
    ): Datasource =
        Datasource(
            name = name,
            displayName = "Test PG",
            dialect = Dialect.POSTGRES,
            jdbcUrl = jdbcUrl,
            username = "app",
            password = "secret",
            properties = properties,
            introspectionIncludeSchemas = introspectionIncludeSchemas,
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
        introspectionIncludeSchemas: List<String> = emptyList(),
    ): Datasource =
        Datasource(
            name = name,
            displayName = "Test ${dialect.wire}",
            dialect = dialect,
            jdbcUrl = urlFor(dialect, name),
            username = "app",
            password = "secret",
            properties = properties,
            introspectionIncludeSchemas = introspectionIncludeSchemas,
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

/**
 * A [SchemaIntrospector] whose registry hands out ONE connection carrying the given mocked
 * [DatabaseMetaData]. Returns (introspector, datasource name). [connectionSetup] stubs
 * connection-level reads the operation under test consults (getSchema/getCatalog).
 *
 * The connection defaults to a KNOWN current schema (`getSchema()` → "public",
 * `getCatalog()` → "app") so unfiltered tables()/columns() reads take the current-schema
 * default like a healthy datasource — a test exercising the unknown-current-schema guard
 * overrides either stub via [connectionSetup] (the later recording wins).
 */
internal fun introspectorOver(
    dialect: Dialect,
    meta: DatabaseMetaData,
    introspectionIncludeSchemas: List<String> = emptyList(),
    connectionSetup: (Connection) -> Unit = {},
): Pair<SchemaIntrospector, String> {
    val ds = Fixtures.forDialect(dialect, introspectionIncludeSchemas = introspectionIncludeSchemas)
    val connection = mockk<Connection>()
    every { connection.metaData } returns meta
    every { connection.close() } returns Unit
    every { connection.schema } returns "public"
    every { connection.catalog } returns "app"
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

/**
 * A one-row `getTables` [java.sql.ResultSet] — the single (schema, name, type) row the
 * tables walk reports, with [schemaColumn] selecting the dialect's vocabulary
 * (TABLE_SCHEM by default; TABLE_CAT for catalog-routing drivers). The hand-copied stanza
 * this replaces had 12+ copies across three modules (R5 F8); each module keeps its OWN
 * small builder — no cross-module coupling.
 */
internal fun tablesResultSet(
    schema: String?,
    name: String,
    type: String = "TABLE",
    schemaColumn: String = "TABLE_SCHEM",
): java.sql.ResultSet {
    val rs = mockk<java.sql.ResultSet>(relaxed = true)
    every { rs.next() } returns true andThen false
    every { rs.getString(schemaColumn) } returns schema
    every { rs.getString("TABLE_NAME") } returns name
    every { rs.getString("TABLE_TYPE") } returns type
    return rs
}
