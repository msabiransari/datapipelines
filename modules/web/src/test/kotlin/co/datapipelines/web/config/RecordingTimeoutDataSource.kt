package co.datapipelines.web.config

import co.datapipelines.auth.User
import co.datapipelines.auth.UserService
import co.datapipelines.datasources.DatasourceGrantRepository
import co.datapipelines.datasources.ManagedLakeSync
import co.datapipelines.lake.LakeBootCheck
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.definitions.LakeApplyRepository
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * The `@Scheduled` jobs' statement fixtures, shared by `ScheduledJobsStatementBoundsTest` (#326, #636, #818b A: each
 * statement carries its bound) and `ReaperDelayDerivationTest` (#818c: the bounds sum to the published reaper delay).
 *
 * Hands out relaxed JDBC doubles and records, per prepared statement, its SQL, the `queryTimeout` set on it
 * ([UNBOUNDED] when none was) and — #837 — the network (socket read) timeout in force on the connection it was prepared
 * on when it was executed ([NO_SOCKET_BOUND] when none was set). A query answers one row: one column, the database
 * clock for the audit cutoff, a `(table, column)` pair for the keys purge's FK list, an `id` for the housekeeping sweeps' `RETURNING id`, and the
 * managed lake's namespace for its catalog read. An update answers 0 rows, so a batched loop ends after one batch.
 */
internal class RecordingTimeoutDataSource(
    private val lakeName: String = SCHEDULED_JOBS_TEST_LAKE,
) : DataSource by mockk(relaxed = true) {
    private val statements = mutableListOf<Pair<String, IntArray>>()

    /** Per statement, parallel to [statements]: the connection's network timeout when the statement ran. */
    private val sockets = mutableListOf<IntArray>()

    /** Every `setNetworkTimeout` call on any connection handed out, in order, in milliseconds. */
    private val networkCalls = mutableListOf<Int>()

    override fun getConnection(): Connection {
        val network = intArrayOf(NO_SOCKET_BOUND)
        val connection = mockk<Connection>(relaxed = true)
        every { connection.prepareStatement(any<String>()) } answers { statement(firstArg(), connection, network) }
        every { connection.setNetworkTimeout(any(), any()) } answers {
            network[0] = secondArg()
            networkCalls += secondArg<Int>()
        }
        every { connection.networkTimeout } answers { network[0] }
        return connection
    }

    private fun statement(
        sql: String,
        owner: Connection,
        network: IntArray,
    ): PreparedStatement {
        val timeout = intArrayOf(UNBOUNDED)
        val socket = intArrayOf(NO_SOCKET_BOUND)
        statements += sql to timeout
        sockets += socket
        return mockk(relaxed = true) {
            every { setQueryTimeout(any()) } answers { timeout[0] = firstArg() }
            every { connection } returns owner
            every { executeUpdate() } answers {
                socket[0] = network[0]
                0
            }
            every { executeQuery() } answers {
                socket[0] = network[0]
                oneRow()
            }
        }
    }

    private fun oneRow(): ResultSet {
        var rows = 1
        return mockk(relaxed = true) {
            every { next() } answers { rows-- > 0 }
            every { metaData.columnCount } returns 1
            every { getObject(1, OffsetDateTime::class.java) } returns OffsetDateTime.now()
            every { getString("referencing_table") } returns "executions"
            every { getString("referencing_column") } returns "executed_by"
            every { getObject("id", UUID::class.java) } returns UUID.randomUUID()
            every { getArray("namespace").array } returns arrayOf(lakeName, "sales")
        }
    }

    /** The one statement whose SQL starts with [prefix] (after trimming); its recorded timeout. */
    fun timeoutOf(prefix: String): Int {
        val matching = statements.filter { (sql, _) -> sql.trim().startsWith(prefix) }
        withClue("statements sent: ${firstLines()}") {
            matching.shouldNotBeEmpty()
            matching.map { it.second[0] }.toSet().size shouldBe 1
        }
        return matching.first().second[0]
    }

    fun everyTimeout(): Set<Int> = statements.map { it.second[0] }.toSet()

    /**
     * #837 — the network timeout, in milliseconds, in force on the connection of the one statement whose SQL starts with
     * [prefix] when it ran ([NO_SOCKET_BOUND] when none was set).
     */
    fun socketTimeoutOf(prefix: String): Int {
        val matching = statements.indices.filter { statements[it].first.trim().startsWith(prefix) }
        withClue("statements sent: ${firstLines()}") {
            matching.shouldNotBeEmpty()
            matching.map { sockets[it][0] }.toSet().size shouldBe 1
        }
        return sockets[matching.first()][0]
    }

    /** #837 — every statement's network timeout when it ran, as a set (milliseconds). */
    fun everySocketTimeout(): Set<Int> = sockets.map { it[0] }.toSet()

    /** #837 — every `setNetworkTimeout` call on a connection handed out, in order (milliseconds). */
    fun networkTimeoutCalls(): List<Int> = networkCalls.toList()

    /** Every statement's recorded timeout, in the order the statements were prepared. */
    fun timeouts(): List<Int> = statements.map { it.second[0] }

    /** Every statement's first SQL line, in order — the clue a count assertion prints. */
    fun firstLines(): List<String> =
        statements.map {
            it.first
                .trim()
                .lineSequence()
                .first()
        }

    fun statementCount(): Int = statements.size

    fun clear() {
        statements.clear()
        sockets.clear()
        networkCalls.clear()
    }

    companion object {
        /** No `setQueryTimeout` call. */
        const val UNBOUNDED = 0

        /** No `setNetworkTimeout` call: the driver's own 0, an unbounded socket read. */
        const val NO_SOCKET_BOUND = 0
    }
}

/** The managed lake datasource's name in these fixtures. */
internal const val SCHEDULED_JOBS_TEST_LAKE = "lake"

/** An enabled lake, enough for the registrar's boot and the freshness tick's catalog. */
internal val SCHEDULED_JOBS_TEST_LAKE_PROPERTIES =
    LakeProperties(
        enabled = true,
        storage =
            LakeProperties.Storage(
                bucket = "dp-lake",
                region = "us-east-1",
                endpoint = "http://minio:9000",
                pathStyle = true,
            ),
    )

/** The production registrar over [jdbc], its other collaborators answering the boot. */
internal fun scheduledJobsRegistrar(
    jdbc: NamedParameterJdbcTemplate,
    lakeProperties: LakeProperties = SCHEDULED_JOBS_TEST_LAKE_PROPERTIES,
): ManagedLakeDatasourceRegistrar {
    val actor = UUID.randomUUID()
    return ManagedLakeDatasourceRegistrar(
        lakeProperties = beanProviderOf(lakeProperties),
        registry = mockk { every { syncManagedLake(any(), actor) } returns ManagedLakeSync.UNCHANGED },
        grants = DatasourceGrantRepository(jdbc),
        applyRepository = StaticListableBeanFactory().getBeanProvider(LakeApplyRepository::class.java),
        userService = mockk<UserService> { every { provisionSystemActor() } returns mockk<User> { every { id } returns actor } },
        bootCheck = LakeCatalogStartupCheck(StaticListableBeanFactory().getBeanProvider(LakeBootCheck::class.java)),
    )
}

/** A single-bean [ObjectProvider], as the application context hands one out. */
internal inline fun <reified T : Any> beanProviderOf(bean: T): ObjectProvider<T> =
    StaticListableBeanFactory(mapOf("bean" to bean)).getBeanProvider(T::class.java)
