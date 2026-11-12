package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.datasources.pooling.HikariConnectionPool
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.atomic.AtomicInteger

/**
 * #463 A.2 — [ReadOnlyConnectionMode] MEASURED on Postgres and MySQL, each on a connection leased
 * from the adapter-built real pool (max size 1). The table in [ReadOnlyConnectionMode] is these
 * runs' outcome, never driver documentation.
 *
 * What the runs established (2026-11-12, pgjdbc 42.7.13 on postgres:16-alpine, Connector/J on
 * mysql:8.4), and why each subject is written the way it is:
 *
 *  - **Postgres:** `setReadOnly(true)` under autocommit enforces NOTHING (an INSERT succeeded);
 *    inside a transaction it is refused by the server with SQLState `25006`. So the mode owns a
 *    transaction for the block, and Postgres is measured in BOTH incoming autocommit states.
 *  - **MySQL:** a plain `INSERT` on a read-only connection is refused by the DRIVER before it is
 *    sent ("Connection is read-only. Queries leading to data modification are not allowed.") —
 *    that is not the database. The write here is therefore `SELECT ro_write(n)`, a stored function
 *    that inserts: the driver lets a SELECT through, and the SERVER refuses it with error `1792`
 *    (`ER_CANT_EXECUTE_IN_READ_ONLY_TRANSACTION`). The same function writes on the baseline.
 */
class ReadOnlyConnectionModeIntegrationTest {
    @Nested
    internal inner class Postgres : ReadOnlyConnectionModeContract() {
        private val pg = SharedPostgres.postgres

        override val subject =
            ReadOnlySubject(
                dialect = Dialect.POSTGRES,
                datasource = pooledOne(Dialect.POSTGRES, "ro_pg", pg.jdbcUrl, pg.username, pg.password),
                raw = { DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password) },
                setup = { },
                write = { c, n -> c.execute("INSERT INTO ro_t VALUES ($n)") },
                refusedByDatabase = { it.sqlState == "25006" },
                entryCall = Call("setReadOnly", true),
                restoreCall = Call("setReadOnly", false),
            )
    }

    @Nested
    internal inner class Mysql : ReadOnlyConnectionModeContract() {
        private val my = SharedMysql.mysql

        override val subject =
            ReadOnlySubject(
                dialect = Dialect.MYSQL,
                datasource = pooledOne(Dialect.MYSQL, "ro_my", my.jdbcUrl, my.username, my.password),
                raw = { DriverManager.getConnection(my.jdbcUrl, my.username, my.password) },
                // As root: with binary logging on, a function that MODIFIES SQL DATA (and is not
                // DETERMINISTIC) is created only under log_bin_trust_function_creators — set on this
                // test container, never declared DETERMINISTIC, which it is not. The test user holds
                // EXECUTE on the database and the function runs as its definer.
                setup = {
                    DriverManager.getConnection(my.jdbcUrl, "root", my.password).use { root ->
                        root.execute("SET GLOBAL log_bin_trust_function_creators = 1")
                        root.execute("DROP FUNCTION IF EXISTS ro_write")
                        root.execute(
                            "CREATE FUNCTION ro_write(v INT) RETURNS INT MODIFIES SQL DATA " +
                                "BEGIN INSERT INTO ro_t VALUES (v); RETURN v; END",
                        )
                    }
                },
                write = { c, n -> c.createStatement().use { s -> s.executeQuery("SELECT ro_write($n)").use { it.next() } } },
                refusedByDatabase = { it.errorCode == MYSQL_READ_ONLY_TRANSACTION },
                entryCall = Call("setReadOnly", true),
                restoreCall = Call("setReadOnly", false),
            )
    }

    private companion object {
        /** `ER_CANT_EXECUTE_IN_READ_ONLY_TRANSACTION` — a server error number, never the driver's. */
        const val MYSQL_READ_ONLY_TRANSACTION = 1792
    }
}

/** A datasource whose pool is the adapter-built real Hikari pool at max size 1. */
internal fun pooledOne(
    dialect: Dialect,
    name: String,
    url: String,
    user: String? = null,
    secret: String? = null,
): Datasource =
    Datasource(
        name = name,
        displayName = name,
        dialect = dialect,
        jdbcUrl = url,
        username = user,
        secret = secret,
        credentialKind = if (user == null) CredentialKind.NONE else CredentialKind.DEFAULT,
        properties = DatasourceProperties(hikari = mapOf("maximumPoolSize" to 1, "minimumIdle" to 1)),
    )

internal fun Connection.execute(sql: String) {
    createStatement().use { it.execute(sql) }
}

/** One planted JDBC call: [method] on the connection (or `execute` on its statement) with first argument [arg]. */
internal data class Call(
    val method: String,
    val arg: Any?,
)

/** One dialect measured `DATABASE`. */
internal class ReadOnlySubject(
    val dialect: Dialect,
    val datasource: Datasource,
    /** An independent connection, outside the pool, for setup and visibility reads. */
    val raw: () -> Connection,
    /** Per-test setup after `ro_t` exists (MySQL's writing function). */
    val setup: () -> Unit,
    /** A write the DATABASE must refuse inside the mode; inserts [Int] into `ro_t` outside it. */
    val write: (Connection, Int) -> Unit,
    /** True when the refusal is the database's own (SQLState / error code), not the driver's. */
    val refusedByDatabase: (SQLException) -> Boolean,
    /** The mechanism's entry call and its restore — the planted faults (f1–f3). */
    val entryCall: Call,
    val restoreCall: Call,
    /** For a session-statement mechanism: reads the mechanism's own setting, and the statement turning it on. */
    val setting: ((Connection) -> Boolean)? = null,
    val settingOn: String? = null,
    /** False where the pinned driver refuses `setReadOnly` outright (SQLite). */
    val driverSetsReadOnly: Boolean = true,
)

/**
 * The A.2 properties and lifecycle cases, identical for every dialect measured `DATABASE`.
 *
 * (1) baseline: the table and a row are committed and a write succeeds before any mode; (2) inside
 * [ReadOnlyConnectionMode.within] a write is refused BY THE DATABASE and, in an independent call, a
 * SELECT returns its rows; (3a) on the STILL-OPEN lease, before `close()`, `isReadOnly`, autocommit
 * (and the mechanism's own setting) equal their captured values and a write succeeds and is visible
 * after commit — checked before close because HikariCP 6.3.3's `ProxyConnection.close` resets dirty
 * `readOnly`/`autoCommit` and would repair a deleted restore; (3b) after close, the re-lease is the
 * SAME physical connection, writable.
 */
internal abstract class ReadOnlyConnectionModeContract {
    abstract val subject: ReadOnlySubject

    private lateinit var pool: ConnectionPool
    private val values = AtomicInteger(100)

    @BeforeEach
    fun freshTableAndPool() {
        subject.raw().use { c ->
            c.execute("DROP TABLE IF EXISTS ro_t")
            c.execute("CREATE TABLE ro_t (n INTEGER)")
            c.execute("INSERT INTO ro_t VALUES (1)")
            if (!c.autoCommit) c.commit()
        }
        subject.setup()
        pool = ConnectionPoolManager.buildHikariPool(subject.datasource)
    }

    @AfterEach
    fun closePool() {
        pool.close()
    }

    @Test
    fun `the table names DATABASE for this dialect`() {
        ReadOnlyConnectionMode.enforcementFor(subject.dialect) shouldBe ReadOnlyConnectionMode.Enforcement.DATABASE
    }

    @Test
    fun `(1)(2)(3) a SELECT inside returns its rows, and the lease is restored open and after re-lease`() {
        val lease = pool.leaseConnection()
        val physical = physicalOf(lease)
        val captured = State.of(lease, subject)
        withClue("(1) baseline: a write succeeds before the mode") { writeAndSee(lease) }

        val rows = within(lease) { lease.count() }

        rows shouldBe 2L
        assertRestoredOpen(lease, captured)
        lease.close()
        assertSameAndWritableAfterRelease(physical, captured)
    }

    @Test
    fun `(2)(3) a write inside is refused by the database - the driver's own exception - and the lease is restored`() {
        val lease = pool.leaseConnection()
        val physical = physicalOf(lease)
        val captured = State.of(lease, subject)
        writeAndSee(lease)

        val (thrown, inner) = refusedWrite(lease)

        thrown shouldBeSameInstanceAs inner
        println(
            "READONLY-MEASURE dialect=${subject.dialect} refusal=${thrown.javaClass.name} " +
                "sql_state=${thrown.sqlState} code=${thrown.errorCode}",
        )
        withClue("refused by the database (${thrown.sqlState}/${thrown.errorCode}: ${thrown.message})") {
            subject.refusedByDatabase(thrown) shouldBe true
        }
        assertRestoredOpen(lease, captured)
        lease.close()
        assertSameAndWritableAfterRelease(physical, captured)
    }

    @Test
    fun `an incoming lease out of autocommit is refused inside and comes back out of autocommit`() {
        val lease = pool.leaseConnection()
        lease.autoCommit = false
        writeAndSee(lease)
        val captured = State.of(lease, subject)

        refusedWrite(lease)
        within(lease) { lease.count() } shouldBe 2L

        captured.autoCommit shouldBe false
        assertRestoredOpen(lease, captured)
        lease.autoCommit = true
        lease.close()
    }

    @Test
    fun `an incoming read-only lease is still read-only after exit`() {
        assumeTrue(subject.driverSetsReadOnly) { "${subject.dialect}'s driver refuses setReadOnly after connect" }
        val lease = pool.leaseConnection()
        lease.isReadOnly = true

        refusedWrite(lease)
        lease.isReadOnly shouldBe true
        within(lease) { lease.count() }
        lease.isReadOnly shouldBe true

        lease.isReadOnly = false
        lease.close()
    }

    @Test
    fun `an incoming lease with the mechanism's setting already on keeps it on, after a normal and a failing block`() {
        val read = subject.setting
        assumeTrue(read != null) { "${subject.dialect}'s mechanism is not a session statement" }
        requireNotNull(read)
        val lease = pool.leaseConnection()
        lease.execute(requireNotNull(subject.settingOn))
        lease.isReadOnly shouldBe false

        within(lease) { lease.count() }
        read(lease) shouldBe true
        shouldThrow<SQLException> { subject.write(lease, values.incrementAndGet()) }

        shouldThrow<IllegalStateException> { within(lease) { error("the block fails") } }
        read(lease) shouldBe true
        shouldThrow<SQLException> { subject.write(lease, values.incrementAndGet()) }
        lease.close()
    }

    @Test
    fun `(f1) an entry step fails and its undo succeeds - the entry failure propagates, the lease is reusable`() {
        val lease = pool.leaseConnection()
        val physical = physicalOf(lease)
        val captured = State.of(lease, subject)

        val thrown = shouldThrow<SQLException> { within(planted(lease, subject.entryCall), discardOf(lease)) { error("unreachable") } }

        thrown.message shouldBe planted(subject.entryCall)
        thrown.suppressed.toList().shouldBeEmpty()
        State.of(lease, subject) shouldBe captured
        lease.close()
        assertSameAndWritableAfterRelease(physical, captured)
    }

    @Test
    fun `(f2) an entry step fails and its undo fails too - the undo failure is suppressed and the connection is disposed`() {
        val lease = pool.leaseConnection()
        val physical = physicalOf(lease)

        val thrown =
            shouldThrow<SQLException> {
                within(planted(lease, subject.entryCall, subject.restoreCall), discardOf(lease)) { error("unreachable") }
            }

        thrown.message shouldBe planted(subject.entryCall)
        thrown.suppressed.map { it.message } shouldContain planted(subject.restoreCall)
        runCatching { lease.close() }
        assertDifferentAndWritable(physical)
    }

    @Test
    fun `(f3) an exit step fails - suppressed onto a failing block, thrown after a clean one, disposed in both`() {
        val first = pool.leaseConnection()
        val firstPhysical = physicalOf(first)
        val failure = IllegalStateException("the block fails")
        val thrown = shouldThrow<IllegalStateException> { within(planted(first, subject.restoreCall), discardOf(first)) { throw failure } }
        thrown shouldBeSameInstanceAs failure
        thrown.suppressed.map { it.message } shouldContain planted(subject.restoreCall)
        runCatching { first.close() }
        val second = assertDifferentAndWritable(firstPhysical)

        val secondPhysical = physicalOf(second)
        val exit = shouldThrow<SQLException> { within(planted(second, subject.restoreCall), discardOf(second)) { second.count() } }
        exit.message shouldBe planted(subject.restoreCall)
        runCatching { second.close() }
        assertDifferentAndWritable(secondPhysical).close()
    }

    // ------------------------------------------------------------------ helpers

    private fun <T> within(
        lease: Connection,
        block: () -> T,
    ): T = within(lease, discardOf(lease), block)

    private fun <T> within(
        connection: Connection,
        discard: (Connection) -> Unit,
        block: () -> T,
    ): T = ReadOnlyConnectionMode.within(connection, subject.dialect, discard, block = block)

    /** The pool's own discard of the REAL lease, whatever wrapper the mode was handed. */
    private fun discardOf(lease: Connection): (Connection) -> Unit = { pool.discard(lease) }

    private fun refusedWrite(lease: Connection): Pair<SQLException, SQLException?> {
        var inner: SQLException? = null
        val thrown =
            shouldThrow<SQLException> {
                within(lease) {
                    try {
                        subject.write(lease, values.incrementAndGet())
                    } catch (e: SQLException) {
                        inner = e
                        throw e
                    }
                }
            }
        withClue("the write was refused by the database, not by the mode") { subject.refusedByDatabase(thrown) shouldBe true }
        return thrown to inner
    }

    /** Writes a fresh value through [lease], commits if out of autocommit, and reads it back independently. */
    private fun writeAndSee(lease: Connection) {
        val value = values.incrementAndGet()
        subject.write(lease, value)
        if (!lease.autoCommit) lease.commit()
        subject.raw().use { it.countOf(value) } shouldBe 1L
    }

    private fun assertRestoredOpen(
        lease: Connection,
        captured: State,
    ) {
        withClue("(3a) the still-open lease is in its captured state before close") { State.of(lease, subject) shouldBe captured }
        withClue("(3a) the still-open lease writes, visibly after commit") { writeAndSee(lease) }
    }

    private fun assertSameAndWritableAfterRelease(
        physical: Connection,
        captured: State,
    ) {
        pool.leaseConnection().use { again ->
            withClue("(3b) the re-lease is the same physical connection") { physicalOf(again) shouldBeSameInstanceAs physical }
            again.isReadOnly shouldBe false
            again.autoCommit shouldBe true
            captured.autoCommit shouldBe true
            writeAndSee(again)
        }
    }

    private fun assertDifferentAndWritable(disposed: Connection): Connection {
        val next = pool.leaseConnection()
        withClue("the disposed connection is never lent again") { physicalOf(next) shouldNotBeSameInstanceAs disposed }
        next.isReadOnly shouldBe false
        writeAndSee(next)
        return next
    }

    private fun physicalOf(lease: Connection): Connection =
        (pool as HikariConnectionPool).poolConnectionOf(lease).unwrap(Connection::class.java)

    private fun Connection.count(): Long =
        createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM ro_t").use {
                it.next()
                it.getLong(1)
            }
        }

    private fun Connection.countOf(value: Int): Long =
        createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM ro_t WHERE n = $value").use {
                it.next()
                it.getLong(1)
            }
        }

    /** What the mode must restore: the JDBC flags, and the mechanism's own setting where it has one. */
    data class State(
        val autoCommit: Boolean,
        val readOnly: Boolean,
        val setting: Boolean?,
    ) {
        companion object {
            fun of(
                c: Connection,
                subject: ReadOnlySubject,
            ) = State(c.autoCommit, c.isReadOnly, subject.setting?.invoke(c))
        }
    }

    companion object {
        fun planted(call: Call): String = "planted fault: ${call.method}(${call.arg})"

        /**
         * A delegating [Connection] (a `java.lang.reflect.Proxy`) that throws on each of [calls] —
         * a connection method by name and first argument, or `execute(sql)` on a statement it creates.
         */
        fun planted(
            real: Connection,
            vararg calls: Call,
        ): Connection = proxy(Connection::class.java, real, calls.toSet())

        private fun <T : Any> proxy(
            type: Class<T>,
            real: T,
            calls: Set<Call>,
        ): T {
            val handler =
                java.lang.reflect.InvocationHandler { _, method, args ->
                    val call = Call(method.name, args?.firstOrNull())
                    if (call in calls) throw SQLException(planted(call))
                    val result =
                        try {
                            method.invoke(real, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                    if (result is Statement && method.name == "createStatement") proxy(Statement::class.java, result, calls) else result
                }
            return type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler))
        }
    }
}
