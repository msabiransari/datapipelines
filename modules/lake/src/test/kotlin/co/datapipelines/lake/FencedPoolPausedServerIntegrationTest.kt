package co.datapipelines.lake

import co.datapipelines.lake.append.LakeBoundedJdbcTemplate
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Schema
import org.apache.iceberg.inmemory.InMemoryFileIO
import org.apache.iceberg.io.FileIO
import org.apache.iceberg.types.Types
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.postgresql.PGProperty
import java.net.SocketTimeoutException
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * #878 B over the REAL driver (pgjdbc, the version on this classpath) and a `docker pause`d server of the class's OWN
 * container ([PausedCatalogServer]): every catalog connection is bounded by `socketTimeout` = S + 5 s = B, from the first
 * byte.
 *
 * - **Established session, S = [ESTABLISHED_S] s, B = 8 s.** A read (`loadTable`) held on an `ACCESS EXCLUSIVE` lock and
 *   a commit (Iceberg's CAS `UPDATE`) held on a row lock — each confirmed waiting in `pg_stat_activity` — then the
 *   container paused. Nothing in the catalog sets a JDBC `queryTimeout`, so no cancel is sent: the only end is the
 *   socket bound, B after the statement was sent, SQLState `08006`. The commit surfaces once as `OutcomeUnknown`, the
 *   pool opens no further connection, and the session refuses further work.
 * - **Pre-connect, S = [PRE_CONNECT_S] s, B = 6 s.** Paused FIRST, then a session is opened (a paused container's
 *   kernel still accepts the TCP connection). (a) `sslmode=disable`: one wait, B. (b) no `sslmode` (the default
 *   `prefer`): the SSLRequest wait `min(sslResponseTimeout 5 s, B)`, then the driver's own non-TLS attempt, B — 11 s.
 *   Both end `08001`, with exactly one `newClient` call that never returned a connection (so `SESSION_SQL` never ran).
 *   The two windows `[6, 9]` and `[11, 14]` s do not overlap, so the margin cannot hide the second wait.
 * - **Refused URL settings.** On a paused server a URL carrying `socketTimeout=0` or a zero `sslResponseTimeout` is
 *   refused before any connection; without the refusal either open is blocked at the outer deadline.
 *
 * Every red run ends at the case's outer deadline ([Ended.stillBlocked]) and restores the environment in `finally`
 * (unpaused, lock rolled back, operation drained), so it cannot hang the suite.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class FencedPoolPausedServerIntegrationTest {
    private lateinit var server: PausedCatalogServer

    @BeforeAll
    fun boot() {
        server = PausedCatalogServer()
        val ns = server.lease(LeaseKind.NAMESPACE, NAMESPACE)
        LakeCatalogs(properties(DEFAULT_S), server.database(), ::inMemory).open(ns).use { it.createNamespace(NAMESPACE) }
    }

    @AfterAll
    fun stop() = server.close()

    @Test
    fun `a read on a paused server ends at the socket bound with 08006`() {
        val lease = table("paused_read")
        val session = LakeCatalogs(properties(ESTABLISHED_S), server.database(), ::inMemory).open(lease)
        val ended =
            held("LOCK TABLE iceberg_tables IN ACCESS EXCLUSIVE MODE", WAITING_READ) {
                session.loadTable(NAMESPACE, "paused_read")
            }
        session.close()
        report("read", ESTABLISHED_S, ended)
        endsInWindow(ended, bound(ESTABLISHED_S), "08006")
        ended.failure.shouldBeInstanceOf<LakeCatalogException.OutcomeUnknown>()
        freshSessionSucceeds(lease, "paused_read")
    }

    @Test
    fun `a commit on a paused server surfaces once as outcome_unknown, opens no new connection and is never replayed`() {
        val lease = table("paused_commit")
        val counted = CountedCatalogs(properties(ESTABLISHED_S), server.database(), ::inMemory)
        val session = counted.open(lease)
        val table = session.loadTable(NAMESPACE, "paused_commit")
        val connectsBefore = counted.connectCalls.get()
        val revisionsBefore = revisions("paused_commit")
        val ended =
            held("SELECT 1 FROM iceberg_tables WHERE table_name = 'paused_commit' FOR UPDATE", WAITING_UPDATE) {
                session.write { table.updateProperties().set("k", "v").commit() }
            }
        // The session that saw the unknown outcome refuses further work, before it touches the network.
        val refusedAfter = runCatching { session.loadTable(NAMESPACE, "paused_commit") }.exceptionOrNull()
        session.close()
        report("commit", ESTABLISHED_S, ended)
        endsInWindow(ended, bound(ESTABLISHED_S), "08006")
        ended.failure.shouldBeInstanceOf<LakeCatalogException.OutcomeUnknown>()
        withClue("no-replay: the pool opened no connection after the lost one") { counted.connectCalls.get() shouldBe connectsBefore }
        refusedAfter.shouldBeInstanceOf<IllegalStateException>()
        awaitNoCatalogBackend()
        val delta = revisions("paused_commit") - revisionsBefore
        println("878 measured: case=commit revision rows for the interrupted operation=$delta")
        delta shouldBeIn listOf(0L, 1L)
        freshSessionSucceeds(lease, "paused_commit")
    }

    @Test
    fun `pre-connect with sslmode=disable ends at one socket bound with 08001`() {
        preConnect("sslmode=disable", bound(PRE_CONNECT_S))
    }

    @Test
    fun `pre-connect with the default sslmode ends after the SSL wait plus one socket bound with 08001`() {
        preConnect("", minOf(DEFAULT_SSL_RESPONSE_MILLIS, bound(PRE_CONNECT_S)) + bound(PRE_CONNECT_S))
    }

    @Test
    fun `on a paused server a URL socketTimeout or a zero SSL wait is refused before any connection`() {
        listOf("sslmode=disable&socketTimeout=0", "sslResponseTimeout=0").forEach { query ->
            val lease = table("refused_${query.length}")
            val counted = CountedCatalogs(properties(PRE_CONNECT_S), server.database(query), ::inMemory)
            val ended = paused { counted.open(lease).also { it.close() } }
            report("refused url [$query]", PRE_CONNECT_S, ended)
            withClue(query) {
                ended.stillBlocked shouldBe false
                ended.failure.shouldBeInstanceOf<IllegalArgumentException>()
                counted.connectCalls.get() shouldBe 0
            }
        }
    }

    private fun preConnect(
        query: String,
        expectedMillis: Long,
    ) {
        val lease = table("pre_connect_${query.length}")
        val counted = CountedCatalogs(properties(PRE_CONNECT_S), server.database(query), ::inMemory)
        val ended = paused(expectedMillis) { counted.open(lease).also { it.close() } }
        report("pre-connect [$query]", PRE_CONNECT_S, ended)
        endsInWindow(ended, expectedMillis, "08001")
        withClue("one newClient call, and it never returned a connection (SESSION_SQL never sent)") {
            counted.connectCalls.get() shouldBe 1
            counted.connectionsReturned.get() shouldBe 0
        }
    }

    /** Pauses first, then runs [operation]; unpauses in `finally`. */
    private fun paused(
        expectedMillis: Long = bound(PRE_CONNECT_S),
        operation: () -> Any?,
    ): Ended {
        server.pause()
        val running = Running(operation)
        try {
            return running.await(expectedMillis + MARGIN_MILLIS + OUTER_SLACK_MILLIS).also { checkStillPaused(it) }
        } finally {
            server.unpause()
            running.drain()
            println("878 cleanup: unpaused, operation drained")
        }
    }

    /** Holds [lockSql] in an open transaction, runs [operation], pauses once [waiting] sees it blocked. */
    private fun held(
        lockSql: String,
        waiting: String,
        operation: () -> Any?,
    ): Ended {
        server.connect().use { lock ->
            lock.autoCommit = false
            lock.createStatement().use { it.execute(lockSql) }
            var running: Running<Any?>? = null
            try {
                server.connect().use { watcher ->
                    running = Running(operation)
                    awaitTrue("the catalog statement waiting on the lock") { count(watcher, waiting) == 1L }
                    server.pause()
                }
                return requireNotNull(
                    running,
                ).await(bound(ESTABLISHED_S) + MARGIN_MILLIS + OUTER_SLACK_MILLIS).also { checkStillPaused(it) }
            } finally {
                server.unpause()
                runCatching { lock.rollback() }
                running?.drain()
                println("878 cleanup: unpaused, lock rolled back, operation drained")
            }
        }
    }

    /** Observed before the `finally`: the operation ended while the server was STILL paused. */
    private fun checkStillPaused(ended: Ended) {
        withClue("the server was still paused when the operation ended") { server.paused shouldBe true }
        ended.elapsedMillis shouldBeGreaterThanOrEqual 0
    }

    private fun endsInWindow(
        ended: Ended,
        expectedMillis: Long,
        sqlState: String,
    ) {
        withClue("still blocked at the outer deadline (${ended.elapsedMillis} ms): the bound did not end it") {
            ended.stillBlocked shouldBe false
        }
        val chain = generateSequence(ended.failure) { it.cause }.toList()
        withClue("SQLStates in the chain: ${chain.filterIsInstance<SQLException>().map { it.sqlState }}") {
            chain.filterIsInstance<SQLException>().any { it.sqlState == sqlState } shouldBe true
        }
        chain.any { it is SocketTimeoutException } shouldBe true
        withClue("ended at ${ended.elapsedMillis} ms; window [$expectedMillis, ${expectedMillis + MARGIN_MILLIS}]") {
            ended.elapsedMillis shouldBeGreaterThanOrEqual expectedMillis
            ended.elapsedMillis shouldBeLessThanOrEqual expectedMillis + MARGIN_MILLIS
        }
    }

    /** After the environment is restored, a fresh session on the same lease reads and commits. */
    private fun freshSessionSucceeds(
        lease: LeaseHandle,
        name: String,
    ) {
        LakeCatalogs(properties(DEFAULT_S), server.database(), ::inMemory).open(lease).use { session ->
            session.write {
                session
                    .loadTable(NAMESPACE, name)
                    .updateProperties()
                    .set("after", "1")
                    .commit()
            }
        }
    }

    private fun table(name: String): LeaseHandle {
        val lease = server.lease(LeaseKind.TABLE, LakeNames.tableLeaseName(NAMESPACE, name))
        LakeCatalogs(properties(DEFAULT_S), server.database(), ::inMemory).open(lease).use {
            it.createTable(NAMESPACE, name, UUID.randomUUID(), SCHEMA, PartitionSpec.unpartitioned(), 1L)
        }
        return lease
    }

    private fun revisions(table: String): Long =
        server.jdbc.queryForObject("SELECT count(*) FROM lake_table_revisions WHERE table_name = ?", Long::class.java, table) ?: -1L

    /** The interrupted backend has finished its statement (committed or cancelled) and gone. */
    private fun awaitNoCatalogBackend() =
        server.connect().use { watcher ->
            awaitTrue("no catalog backend left") { count(watcher, "application_name = '${CatalogDatabase.APPLICATION_NAME}'") == 0L }
        }

    private fun count(
        watcher: Connection,
        where: String,
    ): Long =
        watcher.createStatement().use { s ->
            s.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE $where").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private fun report(
        case: String,
        statementSeconds: Long,
        ended: Ended,
    ) {
        val states = generateSequence(ended.failure) { it.cause }.filterIsInstance<SQLException>().map { it.sqlState }.toList()
        println(
            "878 measured: case=$case statement-timeout-seconds=$statementSeconds bound=${bound(statementSeconds)}ms " +
                "elapsed=${ended.elapsedMillis}ms stillBlocked=${ended.stillBlocked} failure=${ended.failure?.javaClass?.simpleName} " +
                "sqlStates=$states margin=${MARGIN_MILLIS}ms",
        )
    }

    private companion object {
        const val NAMESPACE = "sales"
        const val DEFAULT_S = 30L
        const val ESTABLISHED_S = 3L
        const val PRE_CONNECT_S = 1L

        /** pgjdbc's `sslResponseTimeout` default (PGProperty.SSL_RESPONSE_TIMEOUT). */
        val DEFAULT_SSL_RESPONSE_MILLIS = requireNotNull(PGProperty.SSL_RESPONSE_TIMEOUT.defaultValue).toLong()

        /** Declared margin past the expected end: thread hand-off, the docker pause call, JVM scheduling. */
        const val MARGIN_MILLIS = 3_000L

        /** The outer deadline sits past the window, so a late end is measured (red at the window), not hidden. */
        const val OUTER_SLACK_MILLIS = 2_000L
        const val WAIT_SECONDS = 15L

        val WAITING_READ =
            "application_name = '${CatalogDatabase.APPLICATION_NAME}' AND wait_event_type = 'Lock' AND query ILIKE 'SELECT%iceberg_tables%'"
        val WAITING_UPDATE =
            "application_name = '${CatalogDatabase.APPLICATION_NAME}' AND wait_event_type = 'Lock' AND query ILIKE 'UPDATE%iceberg_tables%'"
        val SCHEMA = Schema(Types.NestedField.required(1, "id", Types.LongType.get()))

        fun bound(statementSeconds: Long): Long = (statementSeconds + LakeBoundedJdbcTemplate.SOCKET_SLACK_SECONDS) * 1_000

        fun properties(statementSeconds: Long): LakeProperties =
            LakeProperties(
                enabled = true,
                storage = LakeProperties.Storage(bucket = "in-memory"),
                catalog = LakeProperties.Catalog(statementTimeoutSeconds = statementSeconds),
            )

        fun inMemory(props: Map<String, String>): FileIO = InMemoryFileIO().apply { initialize(props) }

        fun awaitTrue(
            what: String,
            condition: () -> Boolean,
        ) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
            while (!condition()) {
                check(System.nanoTime() < until) { "timed out waiting until $what" }
                Thread.sleep(20)
            }
        }
    }
}
