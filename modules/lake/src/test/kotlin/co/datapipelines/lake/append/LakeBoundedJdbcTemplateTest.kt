package co.datapipelines.lake.append

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.CallableStatementCreator
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLFeatureNotSupportedException
import java.sql.Statement
import java.util.concurrent.Executor
import javax.sql.DataSource

/**
 * #858 — the managed lake runtime's statements carry the same two bounds as the house helper (`BoundedJdbc`, #837),
 * through the PRODUCTION factory `LakeAppendRuntime.statements`: every statement Spring creates on the template — a plain
 * `Statement`, a `PreparedStatement` (with and without generated keys, single and batched) and a `CallableStatement` —
 * gets `queryTimeout` 5 and runs on a connection whose network timeout is 10 000 ms when it executes. The numbers are
 * literals, never read back from the constants under test. That a paused server then ends a settlement statement at 15 s
 * on the real scheduler is `LakeSettlementMetadataBoundsE2eTest`'s; that the bound rides a transaction's connection and the
 * pool restores its own value is `LakeBoundedJdbcPostgresIntegrationTest`'s.
 */
class LakeBoundedJdbcTemplateTest {
    private val recording = Recording()
    private val jdbc = LakeAppendRuntime.statements(recording.dataSource)

    @Test
    fun `every statement shape Spring creates carries the five second bound and runs under the ten second socket bound`() {
        val named = NamedParameterJdbcTemplate(jdbc)

        jdbc.execute("SELECT 1")
        jdbc.queryForList("SELECT 2")
        jdbc.update("UPDATE t SET a = ?", 1)
        jdbc.queryForObject("SELECT ?", Int::class.java, 3)
        named.update("UPDATE t SET a = :a", mapOf("a" to 4))
        named.batchUpdate("UPDATE t SET a = :a", arrayOf(MapSqlParameterSource("a", 5), MapSqlParameterSource("a", 6)))
        named.update("INSERT INTO t (a) VALUES (:a)", MapSqlParameterSource("a", 7), GeneratedKeyHolder())
        jdbc.call(CallableStatementCreator { it.prepareCall("{call p()}") }, emptyList())

        withClue("statements: ${recording.shapes()}") {
            recording.shapes() shouldContainExactly
                listOf("statement", "statement", "prepared", "prepared", "prepared", "prepared", "prepared-keys", "callable")
            recording.queryTimeouts().toSet() shouldBe setOf(STATEMENT_BOUND)
            recording.socketAtExecution().toSet() shouldBe setOf(SOCKET_MILLIS)
            // One network-timeout call per statement: every statement re-applies it, not only the first on a connection.
            recording.networkCalls() shouldBe List(recording.shapes().size) { SOCKET_MILLIS }
        }
        // Non-vacuity: the recorder saw statements at all — an empty recording would pass every set above.
        recording.shapes().size shouldBeGreaterThan 0
    }

    @Test
    fun `the template is the runtime's DataSource itself - never a wrapper that would leave the caller's transaction`() {
        jdbc.dataSource shouldBeSameInstanceAs recording.dataSource
        jdbc.queryTimeout shouldBe STATEMENT_BOUND
    }

    @Test
    fun `the driver is handed an executor that runs inline - no thread is spent on a bound`() {
        val executor = slot<Executor>()
        every { recording.connection.setNetworkTimeout(capture(executor), any()) } returns Unit

        jdbc.update("UPDATE t SET a = 1")

        var ran = false
        executor.captured.execute { ran = true }
        ran shouldBe true
    }

    @Test
    fun `a driver that refuses the network timeout fails the statement before it runs - no silently unbounded fallback`() {
        every { recording.connection.setNetworkTimeout(any(), any()) } throws SQLFeatureNotSupportedException("no network timeout")

        shouldThrow<DataAccessException> { jdbc.update("UPDATE t SET a = ?", 1) }

        withClue("the statement must not execute without its socket bound") { recording.executions() shouldBe 0 }
        recording.shapes() shouldContainExactly listOf("prepared")
    }

    /**
     * One relaxed connection whose statements record their shape, the `queryTimeout` they were given and the network
     * timeout in force on the connection when they executed.
     */
    private class Recording {
        private val shapes = mutableListOf<String>()
        private val timeouts = mutableListOf<IntArray>()
        private val sockets = mutableListOf<Int>()
        private val calls = mutableListOf<Int>()
        private val network = intArrayOf(NO_SOCKET)

        val connection: Connection = mockk(relaxed = true)
        val dataSource: DataSource = mockk { every { connection } returns this@Recording.connection }

        init {
            every { connection.setNetworkTimeout(any(), any()) } answers {
                network[0] = secondArg()
                calls += secondArg<Int>()
            }
            every { connection.networkTimeout } answers { network[0] }
            every { connection.createStatement() } answers { record("statement", mockk<Statement>(relaxed = true)) }
            every { connection.prepareStatement(any<String>()) } answers { record("prepared", mockk<PreparedStatement>(relaxed = true)) }
            every { connection.prepareStatement(any<String>(), any<Int>()) } answers {
                record("prepared-keys", mockk<PreparedStatement>(relaxed = true))
            }
            every { connection.prepareCall(any<String>()) } answers { record("callable", mockk<CallableStatement>(relaxed = true)) }
            every { connection.metaData.supportsBatchUpdates() } returns true
        }

        private fun <S : Statement> record(
            shape: String,
            statement: S,
        ): S {
            val timeout = intArrayOf(UNBOUNDED)
            shapes += shape
            timeouts += timeout
            every { statement.connection } returns connection
            every { statement.setQueryTimeout(any()) } answers { timeout[0] = firstArg() }
            every { statement.execute(any<String>()) } answers { executed(false) }
            every { statement.executeQuery(any<String>()) } answers { executed(emptyRows()) }
            // No further results: a relaxed 0 would keep Spring's result loop of a call going forever.
            every { statement.updateCount } returns -1
            every { statement.moreResults } returns false
            if (statement is PreparedStatement) {
                every { statement.executeUpdate() } answers { executed(0) }
                every { statement.executeQuery() } answers { executed(oneRow()) }
                every { statement.executeBatch() } answers { executed(intArrayOf(0, 0)) }
                every { statement.generatedKeys } returns emptyRows()
            }
            if (statement is CallableStatement) every { statement.execute() } answers { executed(false) }
            return statement
        }

        private fun <T> executed(answer: T): T {
            sockets += network[0]
            return answer
        }

        private fun emptyRows(): ResultSet = mockk(relaxed = true) { every { next() } returns false }

        private fun oneRow(): ResultSet {
            var rows = 1
            return mockk(relaxed = true) {
                every { next() } answers { rows-- > 0 }
                every { metaData.columnCount } returns 1
                every { getObject(1, Int::class.javaObjectType) } returns 3
                every { getInt(1) } returns 3
            }
        }

        fun shapes(): List<String> = shapes.toList()

        fun queryTimeouts(): List<Int> = timeouts.map { it[0] }

        fun socketAtExecution(): List<Int> = sockets.toList()

        fun networkCalls(): List<Int> = calls.toList()

        fun executions(): Int = sockets.size
    }

    private companion object {
        /** The runtime's statement bound, a literal. */
        const val STATEMENT_BOUND = 5

        /** #858: the bound plus five seconds, in milliseconds — the house helper's contract, a literal. */
        const val SOCKET_MILLIS = 10_000

        const val UNBOUNDED = -1
        const val NO_SOCKET = 0
    }
}
