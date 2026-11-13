package co.datapipelines.staging

import co.datapipelines.typesystem.jdbc.DuckDbAuthorSqlGate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.runBlocking
import org.duckdb.DuckDBConnection
import org.duckdb.DuckDBDatabaseMetaData
import org.duckdb.DuckDBPreparedStatement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.Statement

/**
 * Every JDBC entry point of an author lease that takes SQL text reaches [DuckDbAuthorSqlGate]
 * before the driver sees the text, and nothing reachable from the lease yields the driver's own
 * connection, statement or metadata (staging.md §8.5, the temporary gate). One case per overload:
 * a decorator method that forgot the gate would be the hole, and only a test that calls THAT
 * overload can see it. Each refusal must carry the gate's SQLState, so a driver that happens to
 * refuse an overload on its own (unsupported generated keys, say) cannot pass for the gate.
 */
class DuckDbGuardedJdbcTest {
    @TempDir
    lateinit var scratch: Path

    private lateinit var staging: DuckDbStaging

    private val refused = "SELECT * FROM query('SELECT 1')"

    @BeforeEach
    fun open() {
        staging = duckFactory(scratch).open()
    }

    @AfterEach
    fun close() {
        staging.close()
    }

    private fun <T> onLease(block: (Connection) -> T): T = runBlocking { staging.withConnection { block(it) } }

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
        onLease { c ->
            c.createStatement().use { everyTextEntryPoint("createStatement()", it) }
            c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).use {
                everyTextEntryPoint("createStatement(type, concurrency)", it)
            }
            c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT).use {
                everyTextEntryPoint("createStatement(type, concurrency, holdability)", it)
            }
        }
    }

    @Test
    fun `every prepareStatement overload gates its text before the driver prepares it`() {
        val forward = ResultSet.TYPE_FORWARD_ONLY
        val readOnly = ResultSet.CONCUR_READ_ONLY
        onLease { c ->
            gated("prepareStatement") { c.prepareStatement(refused) }
            gated("prepareStatement(keys)") { c.prepareStatement(refused, Statement.RETURN_GENERATED_KEYS) }
            gated("prepareStatement(indexes)") { c.prepareStatement(refused, intArrayOf(1)) }
            gated("prepareStatement(names)") { c.prepareStatement(refused, arrayOf("a")) }
            gated("prepareStatement(type, concurrency)") { c.prepareStatement(refused, forward, readOnly) }
            gated("prepareStatement(type, concurrency, holdability)") {
                c.prepareStatement(refused, forward, readOnly, ResultSet.CLOSE_CURSORS_AT_COMMIT)
            }
        }
    }

    @Test
    fun `a prepared statement of allowed text still gates any new text passed to it`() {
        onLease { c ->
            c.prepareStatement("SELECT 1").use { prepared ->
                everyTextEntryPoint("prepared", prepared)
                // Its own text runs: the gate refused only the new text.
                prepared.executeQuery().use { rs ->
                    rs.next().shouldBeTrue()
                    rs.getInt(1) shouldBe 1
                }
            }
        }
    }

    @Test
    fun `no callable statement exists on any overload`() {
        onLease { c ->
            shouldThrow<SQLFeatureNotSupportedException> { c.prepareCall("SELECT 1") }
            shouldThrow<SQLFeatureNotSupportedException> {
                c.prepareCall("SELECT 1", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
            }
            shouldThrow<SQLFeatureNotSupportedException> {
                c.prepareCall("SELECT 1", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT)
            }
        }
    }

    @Test
    fun `nothing reachable from the lease unwraps to the driver, and every back-reference is the guarded connection`() {
        onLease { c ->
            c.isWrapperFor(DuckDBConnection::class.java).shouldBeFalse()
            shouldThrow<SQLException> { c.unwrap(DuckDBConnection::class.java) }
            c.unwrap(Connection::class.java) shouldBeSameInstanceAs c

            c.createStatement().use { st ->
                st.connection shouldBeSameInstanceAs c
                st.isWrapperFor(DuckDBPreparedStatement::class.java).shouldBeFalse()
                shouldThrow<SQLException> { st.unwrap(DuckDBPreparedStatement::class.java) }
                st.unwrap(Statement::class.java) shouldBeSameInstanceAs st
            }
            c.prepareStatement("SELECT 1").use { ps ->
                ps.connection shouldBeSameInstanceAs c
                ps.isWrapperFor(DuckDBPreparedStatement::class.java).shouldBeFalse()
                shouldThrow<SQLException> { ps.unwrap(DuckDBPreparedStatement::class.java) }
                ps.unwrap(Statement::class.java) shouldBeSameInstanceAs ps
            }
            val meta = c.metaData
            meta.connection shouldBeSameInstanceAs c
            meta.isWrapperFor(DuckDBDatabaseMetaData::class.java).shouldBeFalse()
            shouldThrow<SQLException> { meta.unwrap(DuckDBDatabaseMetaData::class.java) }
        }
    }
}
