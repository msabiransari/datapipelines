package co.datapipelines.executor

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

/**
 * How [NodeRunner] executes a [SqlBindTranslator.BoundSql] (042 C1): prepared and bound when the
 * rendered SQL uses `:name` parameters, otherwise created and executed exactly as the runner
 * created statements before that round, so parameterless templates keep their statement
 * semantics byte-for-byte — including multi-statement author SQL, which a prepared statement
 * would refuse.
 *
 * Its own object for the reason [SourceStreaming] and [CalculatorNodeRuns] are: the runner is
 * at detekt's size ceiling, and these five functions are self-contained JDBC shape adapters
 * with no dependency on the runner's state.
 */
internal object BoundStatements {
    /**
     * The statement [bound] executes on [connection] (042 C1): prepared and bound when the
     * rendered SQL uses `:name` parameters, otherwise created exactly as this class created
     * statements before the round. Parameterless templates therefore keep their existing
     * statement semantics byte-for-byte — including multi-statement author SQL, which a
     * prepared statement would refuse.
     */
    fun statementFor(
        connection: Connection,
        bound: SqlBindTranslator.BoundSql,
        resultSetType: Int = ResultSet.TYPE_FORWARD_ONLY,
        resultSetConcurrency: Int = ResultSet.CONCUR_READ_ONLY,
    ): Statement =
        if (bound.hasBindParameters) {
            connection
                .prepareStatement(bound.sql, resultSetType, resultSetConcurrency)
                .also { SqlBindTranslator.bind(it, bound.bindValues) }
        } else {
            connection.createStatement(resultSetType, resultSetConcurrency)
        }

    /** `executeQuery` in the shape [bound] needs — a prepared statement already carries its SQL. */
    fun query(
        statement: Statement,
        bound: SqlBindTranslator.BoundSql,
    ): ResultSet =
        if (bound.hasBindParameters) {
            (statement as PreparedStatement).executeQuery()
        } else {
            statement.executeQuery(bound.sql)
        }

    /** `executeUpdate` in the shape [bound] needs. */
    fun update(
        statement: Statement,
        bound: SqlBindTranslator.BoundSql,
    ): Int =
        if (bound.hasBindParameters) {
            (statement as PreparedStatement).executeUpdate()
        } else {
            statement.executeUpdate(bound.sql)
        }

    /** `execute` in the shape [bound] needs. */
    fun executeOf(
        statement: Statement,
        bound: SqlBindTranslator.BoundSql,
    ): Boolean =
        if (bound.hasBindParameters) {
            (statement as PreparedStatement).execute()
        } else {
            statement.execute(bound.sql)
        }

    /** DDL reports success, not rows (§6.3.3). */
    fun executeDdl(
        statement: Statement,
        bound: SqlBindTranslator.BoundSql,
    ): Long {
        executeOf(statement, bound)
        return 0L
    }
}
