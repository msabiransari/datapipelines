package co.datapipelines.parameters

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DeleteResult
import co.datapipelines.datasources.TestResult
import co.datapipelines.datasources.ValidationResult
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.typesystem.Dialect
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lane C's CUSTOMER database: the module's own Postgres container ([ParametersTestDb]) again, in a
 * schema of its own (`sel`), registered as the datasource `warehouse` (POSTGRES — the fixtures'
 * dialect). The country → state → city data the record's §5.2 scenarios walk, plus the shapes the
 * row-invariant and type suites need.
 */
internal object CustomerDb {
    const val WAREHOUSE = "warehouse"

    fun datasource(
        name: String = WAREHOUSE,
        jdbcUrl: String = ParametersTestDb.jdbcUrl,
        queryTimeoutSeconds: Int? = null,
    ): Datasource =
        Datasource(
            name = name,
            displayName = name,
            dialect = Dialect.POSTGRES,
            jdbcUrl = jdbcUrl,
            username = ParametersTestDb.username,
            secret = ParametersTestDb.password,
            queryTimeoutSeconds = queryTimeoutSeconds,
        )

    /** (Re)creates the `sel` schema and its rows — idempotent, every suite calls it. */
    fun seed() {
        ParametersTestDb.jdbc.jdbcTemplate.execute(
            """
            DROP SCHEMA IF EXISTS sel CASCADE;
            CREATE SCHEMA sel;
            CREATE TABLE sel.country (code TEXT PRIMARY KEY, name TEXT NOT NULL, is_home BOOLEAN NOT NULL);
            INSERT INTO sel.country VALUES ('USA', 'United States', TRUE), ('CAN', 'Canada', FALSE), ('AAA', 'Emptyland', FALSE);
            CREATE TABLE sel.state (country_code TEXT NOT NULL, code TEXT PRIMARY KEY, name TEXT NOT NULL);
            INSERT INTO sel.state VALUES
              ('USA', 'NY', 'New York'), ('USA', 'NJ', 'New Jersey'), ('USA', 'CA', 'California'),
              ('CAN', 'ON', 'Ontario'), ('CAN', 'QC', 'Quebec');
            CREATE TABLE sel.city (state_code TEXT NOT NULL, name TEXT NOT NULL, population BIGINT NOT NULL);
            INSERT INTO sel.city VALUES
              ('NY', 'New York', 8800000), ('NY', 'Buffalo', 278000), ('NJ', 'Newark', 311000),
              ('CA', 'Los Angeles', 3900000), ('ON', 'Toronto', 2800000), ('QC', 'Montreal', 1760000);
            CREATE TABLE sel.orders (region TEXT NOT NULL, amount NUMERIC(6,2) NOT NULL, placed_at TIMESTAMPTZ NOT NULL);
            INSERT INTO sel.orders VALUES
              ('NY', 12.50, '2026-09-01T10:00:00Z'), ('ON', 99.99, '2026-09-02T11:30:00Z'), ('CA', 5.00, '2026-09-03T09:15:00Z');
            """.trimIndent(),
        )
    }
}

/**
 * A REAL-pool [DatasourceRegistry] over [CustomerDb]: the production [ConnectionPoolManager] builds
 * HikariCP pools for the registered datasources, visibility is an explicit grant set per workspace
 * (revocable mid-test — record §12's revoked-grant row), and every live read and every lease is
 * COUNTED so a suite can print the queries one evaluate costs (record §11). Everything the runner
 * never calls throws.
 */
internal class CustomerRegistry(
    vararg datasources: Datasource,
) : DatasourceRegistry,
    AutoCloseable {
    private val registered = ConcurrentHashMap<String, Datasource>()
    private val grants = ConcurrentHashMap<String, MutableSet<UUID>>()
    private val pools = ConnectionPoolManager()

    /** `getVisibleLive` calls — one per distinct datasource per evaluate (the resolver's memo). */
    val liveReads = AtomicInteger()

    /** Connections leased — one per selector statement that reached the database. */
    val leases = AtomicInteger()

    init {
        datasources.forEach { register(it) }
    }

    fun register(
        datasource: Datasource,
        vararg workspaces: UUID = arrayOf(ParametersTestDb.WORKSPACE),
    ) {
        registered[datasource.name] = datasource
        grants[datasource.name] = ConcurrentHashMap.newKeySet<UUID>().also { it.addAll(workspaces) }
    }

    fun revoke(
        name: String,
        workspace: UUID,
    ) {
        grants[name]?.remove(workspace)
    }

    override fun getVisibleLive(
        name: String,
        workspaceId: UUID,
    ): Datasource? {
        liveReads.incrementAndGet()
        return registered[name]?.takeIf { workspaceId in grants[name].orEmpty() }
    }

    override fun getVisible(
        name: String,
        workspaceId: UUID,
    ): Datasource? = error("the runner reads datasources LIVE (P31) — never through the cached getVisible")

    override fun get(name: String): Datasource? = error("the runner never uses the unscoped read")

    override fun getLive(name: String): Datasource? = error("the runner never uses the unscoped live read")

    override fun poolFor(datasource: Datasource): ConnectionPool {
        val pool = pools.poolFor(datasource)
        return object : ConnectionPool by pool {
            override fun leaseConnection(): Connection {
                leases.incrementAndGet()
                return pool.leaseConnection()
            }
        }
    }

    override fun close() = pools.close()

    override fun list(dialect: Dialect?): List<Datasource> = unused()

    override fun isReadonlyLive(name: String): Boolean? = unused()

    override fun exists(name: String): Boolean = unused()

    override fun save(
        datasource: Datasource,
        actor: UUID,
    ): Datasource = unused()

    override fun validate(datasource: Datasource): ValidationResult = unused()

    override fun delete(name: String): DeleteResult = unused()

    override fun testConnection(name: String): TestResult? = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("not used by the selector runtime")
}
