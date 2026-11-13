package co.datapipelines.lake

import co.datapipelines.lake.append.LakeBoundedJdbcTemplate
import org.apache.iceberg.jdbc.JdbcClientPool
import org.apache.iceberg.jdbc.UncheckedSQLException
import org.postgresql.Driver
import org.postgresql.PGProperty
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties

/**
 * The catalog's connection pool under ONE lease (§8a "How the token reaches Iceberg's connection"; E1 #774
 * `FencedPool`). Three departures from Iceberg's stock [JdbcClientPool]:
 *
 * 1. **Every connection carries the lease.** [newClient] opens a DEDICATED connection through `DriverManager` and
 *    sets `dp.lease_token`, `dp.operation_id`, `lock_timeout` and `statement_timeout` as SESSION settings
 *    (`set_config(…, false)`), which the V53 fence trigger reads. Session scope, not the brief's transaction-local
 *    form (owner ruling 2026-11-02): Iceberg 1.12.0's JDBC code never opens a transaction, so a transaction-local
 *    setting would vanish before the library's own statement ran. Session scope is safe because the pool is
 *    private to one lease holder and is closed with its catalog when the lease is released — no connection
 *    outlives its lease, and DP's own Hikari pool never sees these settings (it never hands out these
 *    connections).
 * 2. **No reconnect-and-replay.** [isConnectionException] answers false, so `ClientPoolImpl.run` never reconnects
 *    and re-runs the action after a lost connection. A replayed commit UPDATE whose first attempt DID commit
 *    reports a false `CommitFailedException`, and the library then deletes the committed files (E1 F3, case A).
 *    The lost connection instead surfaces once, as [LakeCatalogException.OutcomeUnknown].
 * 3. **Every read is bounded, from the first byte (#878).** The server-side `statement_timeout` cannot end a
 *    statement on a server that accepts and never answers: no cancel reaches it, and nothing in Iceberg sets a JDBC
 *    `queryTimeout`. So every connection is opened with pgjdbc's `socketTimeout` = [statementTimeoutSeconds] +
 *    [LakeBoundedJdbcTemplate.SOCKET_SLACK_SECONDS] on a COPY of the connection properties — the one source of the
 *    bound. The driver puts it on the stream right after the TCP connect, before the SSL/startup/authentication
 *    reads (pgjdbc `ConnectionFactoryImpl.tryConnect`), so it bounds the handshake, `SESSION_SQL` and every later
 *    read. The returned connection's `getNetworkTimeout()` is READ BACK before `SESSION_SQL` runs: a connection whose
 *    bound differs, or whose read-back throws, is closed and refused — an unbounded connection is never returned.
 *    A read that outlives the bound ends with SQLState `08006` on an established session, or `08001` while the
 *    connection is being opened; both are class `08`, an unknown outcome, never replayed.
 *
 * The pool refuses, at construction and before any connection is opened, a [statementTimeoutSeconds] whose bound
 * does not fit JDBC's `int` milliseconds, and a catalog URL whose driver parameters would unbound the connection
 * ([UNBOUNDING_RULES]). The connection properties carry the catalog database's user and password; they are never
 * logged, and no refusal names the URL.
 */
class FencedJdbcClientPool internal constructor(
    private val url: String,
    private val connectionProperties: Properties,
    private val lease: LeaseHandle,
    private val lockTimeoutSeconds: Long,
    private val statementTimeoutSeconds: Long,
    poolSize: Int,
    private val connector: CatalogConnector,
) : JdbcClientPool(poolSize, url, emptyMap()) {
    constructor(
        url: String,
        connectionProperties: Properties,
        lease: LeaseHandle,
        lockTimeoutSeconds: Long,
        statementTimeoutSeconds: Long,
        poolSize: Int = DEFAULT_POOL_SIZE,
    ) : this(url, connectionProperties, lease, lockTimeoutSeconds, statementTimeoutSeconds, poolSize, CatalogConnector.DRIVER_MANAGER)

    /** The socket bound every connection must carry, in milliseconds; checked arithmetic, never narrowed. */
    private val socketBoundMillis: Int = socketBoundMillis(statementTimeoutSeconds)

    init {
        refuseUnboundingUrl(url, boundedProperties())
    }

    override fun newClient(): Connection {
        val connection =
            try {
                connector.connect(url, boundedProperties())
            } catch (e: SQLException) {
                throw UncheckedSQLException(e, "Failed to connect to the lake catalog")
            }
        requireBounded(connection)
        try {
            connection.prepareStatement(SESSION_SQL).use { statement ->
                // In SESSION_SQL's order: the token, the operation id, then the two timeouts.
                listOf(lease.token.toString(), lease.operationId.toString(), "${lockTimeoutSeconds}s", "${statementTimeoutSeconds}s")
                    .forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.executeQuery().use { it.next() }
            }
        } catch (e: SQLException) {
            runCatching { connection.close() }
            throw UncheckedSQLException(e, "Failed to set the lake lease on a catalog connection")
        }
        return connection
    }

    /** Never a reconnect-and-replay (§8a, E1 F3) — see the class note. */
    override fun isConnectionException(e: Exception): Boolean = false

    /** A copy of the caller's properties with the socket bound, in seconds (pgjdbc's unit): the bound's one source. */
    private fun boundedProperties(): Properties =
        Properties().apply {
            putAll(connectionProperties)
            setProperty(PGProperty.SOCKET_TIMEOUT.getName(), (socketBoundMillis / MILLIS_PER_SECOND).toString())
        }

    /** The read-back guard: the driver honoured the bound, or the connection is closed and refused. */
    private fun requireBounded(connection: Connection) {
        val observed =
            try {
                connection.networkTimeout
            } catch (e: SQLException) {
                runCatching { connection.close() }
                throw UncheckedSQLException(e, "Failed to read the network timeout of a lake catalog connection")
            }
        if (observed != socketBoundMillis) {
            runCatching { connection.close() }
            throw UncheckedSQLException(
                "A lake catalog connection's network timeout is %s ms, not the derived %s ms; refused",
                observed,
                socketBoundMillis,
            )
        }
    }

    /** Opens one catalog connection; `DriverManager` in production, a recording double in the pool's unit test. */
    internal fun interface CatalogConnector {
        @Throws(SQLException::class)
        fun connect(
            url: String,
            properties: Properties,
        ): Connection

        companion object {
            val DRIVER_MANAGER = CatalogConnector { url, properties -> DriverManager.getConnection(url, properties) }
        }
    }

    companion object {
        /** E1's size: Iceberg holds at most one connection per catalog call, and a lease session is one caller. */
        const val DEFAULT_POOL_SIZE = 2

        /** The four session settings, one round-trip. Parameterised: no lease value is ever interpolated. */
        internal const val SESSION_SQL =
            "SELECT set_config('dp.lease_token', ?, false), set_config('dp.operation_id', ?, false)," +
                " set_config('lock_timeout', ?, false), set_config('statement_timeout', ?, false)"

        /** The key [statementTimeoutSeconds] is bound from (`LakeRules.STATEMENT_TIMEOUT_SECONDS` in `app`). */
        internal const val STATEMENT_TIMEOUT_KEY = "${LakeProperties.PREFIX}.catalog.statement-timeout-seconds"

        /** The key the catalog URL comes from: `LakeAutoConfiguration` passes `DataSourceProperties.determineUrl()`. */
        internal const val URL_KEY = "spring.datasource.url"

        private const val MILLIS_PER_SECOND = 1_000

        /** The largest statement bound whose socket bound, + the slack, still fits JDBC's `int` milliseconds. */
        internal const val MAX_STATEMENT_TIMEOUT_SECONDS =
            Int.MAX_VALUE / MILLIS_PER_SECOND - LakeBoundedJdbcTemplate.SOCKET_SLACK_SECONDS

        /**
         * The driver waits a URL could turn into "no timeout" with a zero: each is judged on its EFFECTIVE value
         * (URL, then the pool's properties, then the driver's default) and must be a positive integer, parsed as the
         * driver parses it (`Integer.parseInt`). A positive
         * value is the operator's and is left alone. `loginTimeout`, `cancelSignalTimeout` and `queryTimeout` bound no
         * handshake read and are not judged.
         */
        internal val UNBOUNDING_RULES: List<PGProperty> =
            listOf(PGProperty.CONNECT_TIMEOUT, PGProperty.SSL_RESPONSE_TIMEOUT, PGProperty.GSS_RESPONSE_TIMEOUT)

        /** `(statementTimeoutSeconds + slack) × 1000`, refused outside `1..MAX_STATEMENT_TIMEOUT_SECONDS`. */
        internal fun socketBoundMillis(statementTimeoutSeconds: Long): Int {
            require(statementTimeoutSeconds in 1..MAX_STATEMENT_TIMEOUT_SECONDS) {
                "$STATEMENT_TIMEOUT_KEY must be in 1..$MAX_STATEMENT_TIMEOUT_SECONDS (its socket bound, " +
                    "+${LakeBoundedJdbcTemplate.SOCKET_SLACK_SECONDS} s, must fit JDBC's int milliseconds), was $statementTimeoutSeconds"
            }
            val seconds = Math.addExact(statementTimeoutSeconds, LakeBoundedJdbcTemplate.SOCKET_SLACK_SECONDS.toLong())
            return Math.toIntExact(Math.multiplyExact(seconds, MILLIS_PER_SECOND.toLong()))
        }

        /**
         * Refuses a catalog URL that would unbound a connection, read with the driver's OWN parser (a URL parameter
         * wins over the properties, `Driver.parseURL`). The messages name the parameter and [URL_KEY] — never the URL,
         * its userinfo or another parameter's value.
         */
        internal fun refuseUnboundingUrl(
            url: String,
            properties: Properties,
        ) {
            val fromUrl =
                requireNotNull(Driver.parseURL(url, null)) {
                    "$URL_KEY is not a PostgreSQL JDBC URL the lake catalog's driver parses"
                }
            require(!fromUrl.containsKey(PGProperty.SOCKET_TIMEOUT.getName())) {
                "$URL_KEY must not carry ${PGProperty.SOCKET_TIMEOUT.getName()}: the lake catalog derives its socket bound " +
                    "from $STATEMENT_TIMEOUT_KEY, and a URL value would replace it"
            }
            val effective = requireNotNull(Driver.parseURL(url, properties)) { "$URL_KEY is not a PostgreSQL JDBC URL" }
            UNBOUNDING_RULES.forEach { property ->
                val number = property.getOrDefault(effective)?.toIntOrNull()
                require(number != null && number > 0) {
                    val shown = number?.let { "is $it" } ?: "is not an integer"
                    "the ${property.getName()} of $URL_KEY $shown; it must be a positive integer (zero means no timeout " +
                        "and would leave a lake catalog connection unbounded)"
                }
            }
        }
    }
}
