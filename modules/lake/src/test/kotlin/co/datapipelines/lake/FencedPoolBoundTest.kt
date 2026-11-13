package co.datapipelines.lake

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.apache.iceberg.jdbc.UncheckedSQLException
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #878 A over a RECORDING connector (no database): every connection the fenced pool returns carries the socket bound
 * `(statement-timeout-seconds + 5) × 1000` ms, put on the driver's properties before the connection opens and read back
 * before `SESSION_SQL` runs; a connection whose read-back differs or throws is closed and never returned; a statement
 * bound outside `1..2,147,478` and a catalog URL that would unbound the connection are refused before any connection
 * is opened. The real driver honouring the property is `FencedPoolPausedServerIntegrationTest`'s.
 */
class FencedPoolBoundTest {
    @Test
    fun `every connection the pool returns carries the derived socket bound, read back before SESSION_SQL`() {
        val connector = RecordingConnector()
        val callerProperties = CatalogDatabase(URL, "dp", PASSWORD).connectionProperties()
        val pool = pool(connector, statementTimeoutSeconds = 30, properties = callerProperties)
        // Nested runs hold both pool slots at once, so the pool opens two connections.
        pool.run { outer -> pool.run { inner -> listOf(outer, inner) } }
        pool.close()
        withClue("non-vacuity: connections opened") { connector.opened.size shouldBeGreaterThan 1 }
        connector.opened.forEach { opened ->
            opened.properties.getProperty("socketTimeout") shouldBe "35"
            opened.networkTimeoutReads shouldBe 1
            opened.events shouldContainExactly listOf("getNetworkTimeout=35000", "SESSION_SQL", "close")
        }
        // The bound is put on a copy: the caller's properties carry no second value.
        callerProperties.getProperty("socketTimeout").shouldBeNull()
    }

    @Test
    fun `a connection whose read-back differs is closed and never returned, and SESSION_SQL never runs on it`() {
        val connector = RecordingConnector(networkTimeout = { 0 })
        val actions = mutableListOf<Connection>()
        val failure = shouldThrow<UncheckedSQLException> { pool(connector).run { actions += it } }
        failure.message.orEmpty() shouldContain "35000"
        actions.shouldBeEmpty()
        connector.opened.single().events shouldContainExactly listOf("getNetworkTimeout=0", "close")
    }

    @Test
    fun `a connection whose read-back throws is closed and never returned, and SESSION_SQL never runs on it`() {
        val connector = RecordingConnector(networkTimeout = { throw SQLException("refused by the driver", "0A000") })
        val actions = mutableListOf<Connection>()
        val failure = shouldThrow<UncheckedSQLException> { pool(connector).run { actions += it } }
        (failure.cause as SQLException).sqlState shouldBe "0A000"
        actions.shouldBeEmpty()
        connector.opened.single().events shouldContainExactly listOf("getNetworkTimeout threw", "close")
    }

    @Test
    fun `the largest admitted statement bound gives the largest int bound`() {
        val connector = RecordingConnector()
        pool(connector, statementTimeoutSeconds = 2_147_478).run { it }
        val opened = connector.opened.single()
        opened.properties.getProperty("socketTimeout") shouldBe "2147483"
        opened.events.first() shouldBe "getNetworkTimeout=2147483000"
    }

    @Test
    fun `a statement bound outside 1 to 2147478 is refused before any connection opens`() {
        listOf(2_147_479L, Long.MAX_VALUE, 0L, -1L).forEach { seconds ->
            val connector = RecordingConnector()
            val failure = shouldThrow<IllegalArgumentException> { pool(connector, statementTimeoutSeconds = seconds).run { it } }
            withClue("statement bound $seconds") {
                failure.message.orEmpty() shouldContain "datapipelines.lake.catalog.statement-timeout-seconds"
                failure.message.orEmpty() shouldContain "1..2147478"
                failure.message.orEmpty() shouldContain "was $seconds"
                connector.opened.size shouldBe 0
            }
        }
    }

    @Test
    fun `a catalog URL that would unbound the connection is refused before any connection opens, naming no URL`() {
        val refused =
            mapOf(
                "socketTimeout=0" to "socketTimeout",
                "sslmode=disable&socketTimeout=600" to "socketTimeout",
                "sslResponseTimeout=0" to "sslResponseTimeout",
                "gssResponseTimeout=0" to "gssResponseTimeout",
                "connectTimeout=0" to "connectTimeout",
            )
        refused.forEach { (query, parameter) ->
            val connector = RecordingConnector()
            val url = "$URL?password=$PASSWORD&$query"
            val failure = shouldThrow<IllegalArgumentException> { pool(connector, url = url).run { it } }
            withClue(query) {
                failure.message.orEmpty() shouldContain parameter
                failure.message.orEmpty() shouldContain "spring.datasource.url"
                failure.message.orEmpty() shouldNotContain URL
                failure.message.orEmpty() shouldNotContain PASSWORD
                failure.message.orEmpty() shouldNotContain "600"
                connector.opened.size shouldBe 0
            }
        }
    }

    @Test
    fun `a catalog URL with no parameter or a positive operator wait is admitted and left as written`() {
        listOf("", "?sslResponseTimeout=2000", "?connectTimeout=3").forEach { query ->
            val connector = RecordingConnector()
            pool(connector, url = URL + query).run { it }
            withClue(query) {
                connector.opened.single().url shouldBe URL + query
                connector.opened
                    .single()
                    .properties
                    .getProperty("socketTimeout") shouldBe "35"
            }
        }
    }

    private fun pool(
        connector: RecordingConnector,
        statementTimeoutSeconds: Long = 30,
        url: String = URL,
        properties: Properties = CatalogDatabase(url, "dp", PASSWORD).connectionProperties(),
    ): FencedJdbcClientPool = FencedJdbcClientPool(url, properties, LEASE, 5, statementTimeoutSeconds, 2, connector)

    /** One `connect` call: what the pool handed the driver, and what it then did with the connection, in order. */
    private class Opened(
        val url: String,
        val properties: Properties,
    ) {
        val events = CopyOnWriteArrayList<String>()
        var networkTimeoutReads = 0
    }

    /**
     * The driver double. By default it honours the `socketTimeout` property as pgjdbc does (seconds on the
     * properties, milliseconds from `getNetworkTimeout`); [networkTimeout] overrides the read-back.
     */
    private class RecordingConnector(
        private val networkTimeout: ((Properties) -> Int)? = null,
    ) : FencedJdbcClientPool.CatalogConnector {
        val opened = CopyOnWriteArrayList<Opened>()

        override fun connect(
            url: String,
            properties: Properties,
        ): Connection {
            val record = Opened(url, Properties().apply { putAll(properties) })
            opened += record
            return proxy(Connection::class.java) { method, _ ->
                when (method) {
                    "getNetworkTimeout" -> {
                        record.networkTimeoutReads++
                        val millis =
                            try {
                                networkTimeout?.invoke(properties) ?: (properties.getProperty("socketTimeout", "0").toInt() * MILLIS)
                            } catch (e: SQLException) {
                                record.events += "getNetworkTimeout threw"
                                throw e
                            }
                        record.events += "getNetworkTimeout=$millis"
                        millis
                    }

                    "prepareStatement" -> {
                        record.events += "SESSION_SQL"
                        statement()
                    }

                    "close" -> {
                        record.events += "close"
                    }

                    "isClosed" -> {
                        false
                    }

                    else -> {
                        null
                    }
                }
            }
        }

        private fun statement(): PreparedStatement =
            proxy(PreparedStatement::class.java) { method, _ ->
                when (method) {
                    "executeQuery" -> proxy(ResultSet::class.java) { m, _ -> if (m == "next") true else null }
                    else -> null
                }
            }
    }

    private companion object {
        const val URL = "jdbc:postgresql://catalog.invalid:5432/datapipelines"
        const val PASSWORD = "catalog-password-878"
        const val MILLIS = 1_000
        val LEASE = LeaseHandle(LeaseKind.TABLE, "sales.orders", UUID.randomUUID(), UUID.randomUUID())

        inline fun <reified T> proxy(
            type: Class<T>,
            crossinline answer: (String, Array<out Any?>?) -> Any?,
        ): T =
            type.cast(
                Proxy.newProxyInstance(
                    type.classLoader,
                    arrayOf(type),
                    InvocationHandler { self, method, args ->
                        when (method.name) {
                            "hashCode" -> System.identityHashCode(self)
                            "equals" -> self === args?.firstOrNull()
                            "toString" -> "recording ${type.simpleName}"
                            else -> answer(method.name, args)
                        }
                    },
                ),
            )
    }
}
