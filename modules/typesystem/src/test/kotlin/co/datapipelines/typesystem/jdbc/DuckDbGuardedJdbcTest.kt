package co.datapipelines.typesystem.jdbc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.h2.jdbc.JdbcConnection
import org.h2.jdbc.JdbcDatabaseMetaData
import org.h2.jdbc.JdbcPreparedStatement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.Statement
import java.util.UUID

/**
 * Every JDBC entry point of a [guardedForAuthorSql] connection that takes SQL text reaches
 * [DuckDbAuthorSqlGate] before the driver sees the text, and nothing reachable from it yields the
 * driver's own connection, statement or metadata. One case per overload: a decorator method that
 * forgot the gate would be the hole, and only a test that calls THAT overload can see it.
 *
 * The decorators are driver-neutral `java.sql`, so the delegate here is the H2 driver this module
 * already tests against (no DuckDB driver in `typesystem`). Each refusal must carry the gate's
 * SQLState `42501`: H2 would refuse the text too, but as a syntax error (`42001`/`42S02`), so a
 * driver refusal cannot pass for the gate's.
 */
class DuckDbGuardedJdbcTest {
    private lateinit var raw: Connection
    private lateinit var guarded: Connection

    private val refused = "SELECT * FROM query('SELECT 1')"

    @BeforeEach
    fun open() {
        raw = DriverManager.getConnection("jdbc:h2:mem:guard_${UUID.randomUUID()}")
        guarded = raw.guardedForAuthorSql()
    }

    @AfterEach
    fun close() {
        guarded.close()
    }

    private fun gated(
        route: String,
        call: () -> Any?,
    ) {
        withClue(route) { shouldThrow<SQLException> { call() }.sqlState shouldBe DuckDbAuthorSqlGate.SQL_STATE }
    }

    /** Every `Statement` method that takes new SQL text, applied to [statement]. */
    private fun everyTextEntryPoint(
        kind: String,
        statement: Statement,
    ) {
        val keys = Statement.RETURN_GENERATED_KEYS
        val indexes = intArrayOf(1)
        val names = arrayOf("a")
        gated("$kind.execute") { statement.execute(refused) }
        gated("$kind.execute(keys)") { statement.execute(refused, keys) }
        gated("$kind.execute(indexes)") { statement.execute(refused, indexes) }
        gated("$kind.execute(names)") { statement.execute(refused, names) }
        gated("$kind.executeQuery") { statement.executeQuery(refused) }
        gated("$kind.executeUpdate") { statement.executeUpdate(refused) }
        gated("$kind.executeUpdate(keys)") { statement.executeUpdate(refused, keys) }
        gated("$kind.executeUpdate(indexes)") { statement.executeUpdate(refused, indexes) }
        gated("$kind.executeUpdate(names)") { statement.executeUpdate(refused, names) }
        gated("$kind.executeLargeUpdate") { statement.executeLargeUpdate(refused) }
        gated("$kind.executeLargeUpdate(keys)") { statement.executeLargeUpdate(refused, keys) }
        gated("$kind.executeLargeUpdate(indexes)") { statement.executeLargeUpdate(refused, indexes) }
        gated("$kind.executeLargeUpdate(names)") { statement.executeLargeUpdate(refused, names) }
        gated("$kind.addBatch") { statement.addBatch(refused) }
    }

    @Test
    fun `every statement overload gates new text, whichever createStatement form made it`() {
        guarded.createStatement().use { everyTextEntryPoint("createStatement()", it) }
        guarded.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).use {
            everyTextEntryPoint("createStatement(type, concurrency)", it)
        }
        guarded.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT).use {
            everyTextEntryPoint("createStatement(type, concurrency, holdability)", it)
        }
    }

    @Test
    fun `every prepareStatement overload gates its text before the driver prepares it`() {
        val forward = ResultSet.TYPE_FORWARD_ONLY
        val readOnly = ResultSet.CONCUR_READ_ONLY
        gated("prepareStatement") { guarded.prepareStatement(refused) }
        gated("prepareStatement(keys)") { guarded.prepareStatement(refused, Statement.RETURN_GENERATED_KEYS) }
        gated("prepareStatement(indexes)") { guarded.prepareStatement(refused, intArrayOf(1)) }
        gated("prepareStatement(names)") { guarded.prepareStatement(refused, arrayOf("a")) }
        gated("prepareStatement(type, concurrency)") { guarded.prepareStatement(refused, forward, readOnly) }
        gated("prepareStatement(type, concurrency, holdability)") {
            guarded.prepareStatement(refused, forward, readOnly, ResultSet.CLOSE_CURSORS_AT_COMMIT)
        }
    }

    @Test
    fun `a prepared statement of allowed text still gates any new text passed to it`() {
        guarded.prepareStatement("SELECT 1").use { prepared ->
            everyTextEntryPoint("prepared", prepared)
            prepared.executeQuery().use { rs ->
                rs.next().shouldBeTrue()
                rs.getInt(1) shouldBe 1
            }
        }
    }

    @Test
    fun `allowed text runs on every route`() {
        guarded.createStatement().use { st ->
            st.execute("CREATE TABLE t(query_count INT)").shouldBeFalse()
            st.executeUpdate("INSERT INTO t VALUES (1)") shouldBe 1
            st.executeQuery("SELECT query_count FROM t").use { rs ->
                rs.next().shouldBeTrue()
                rs.getInt(1) shouldBe 1
            }
        }
    }

    @Test
    fun `no callable statement exists on any overload`() {
        shouldThrow<SQLFeatureNotSupportedException> { guarded.prepareCall("SELECT 1") }
        shouldThrow<SQLFeatureNotSupportedException> {
            guarded.prepareCall("SELECT 1", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
        }
        shouldThrow<SQLFeatureNotSupportedException> {
            guarded.prepareCall("SELECT 1", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT)
        }
    }

    @Test
    fun `nothing reachable unwraps to the driver, and every back-reference is the guarded connection`() {
        guarded.isWrapperFor(JdbcConnection::class.java).shouldBeFalse()
        shouldThrow<SQLException> { guarded.unwrap(JdbcConnection::class.java) }
        guarded.unwrap(Connection::class.java) shouldBeSameInstanceAs guarded

        guarded.createStatement().use { st ->
            st.connection shouldBeSameInstanceAs guarded
            st.isWrapperFor(JdbcPreparedStatement::class.java).shouldBeFalse()
            shouldThrow<SQLException> { st.unwrap(JdbcPreparedStatement::class.java) }
            st.unwrap(Statement::class.java) shouldBeSameInstanceAs st
        }
        guarded.prepareStatement("SELECT 1").use { ps ->
            ps.connection shouldBeSameInstanceAs guarded
            ps.isWrapperFor(JdbcPreparedStatement::class.java).shouldBeFalse()
            shouldThrow<SQLException> { ps.unwrap(JdbcPreparedStatement::class.java) }
            ps.unwrap(Statement::class.java) shouldBeSameInstanceAs ps
        }
        val meta = guarded.metaData
        meta.connection shouldBeSameInstanceAs guarded
        meta.isWrapperFor(JdbcDatabaseMetaData::class.java).shouldBeFalse()
        shouldThrow<SQLException> { meta.unwrap(JdbcDatabaseMetaData::class.java) }
    }

    @Test
    fun `guarding is idempotent and closing the guard closes the delegate`() {
        guarded.guardedForAuthorSql() shouldBeSameInstanceAs guarded
        guarded.close()
        raw.isClosed.shouldBeTrue()
    }
}
