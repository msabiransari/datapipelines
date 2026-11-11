package co.datapipelines.application.dependencies

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.Collections
import javax.sql.DataSource

/**
 * A [DataSource] that observes the `statement_timeout` each REAL statement executes under (#786 R3): when a prepared
 * statement is about to execute — after `JdbcTemplate` applied its settings, so after the release ceiling's per-statement
 * hook — it reads `SHOW statement_timeout` on the same connection, records it with the statement's SQL in [observed],
 * runs the statement, then calls [after] (a test advances its injected clock there: the statement "took" that long).
 *
 * The ceiling's own bookkeeping statements (they name `statement_timeout`) are neither recorded nor followed by [after].
 * The observation is a read on the statement's own connection; it changes no setting.
 */
class ObservedStatements(
    private val target: DataSource,
    private val after: (sql: String) -> Unit = {},
) {
    /** Every observed statement, in execution order: its SQL and the `statement_timeout` it executed under. */
    val observed: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    /** The timeouts alone, in order. */
    fun timeouts(): List<String> = observed.map { it.second }

    /** The observing data source: the transaction manager, the ceiling and every repository must share THIS one. */
    val dataSource: DataSource =
        proxy(target) { method, args ->
            if (method.name == "getConnection") connection(call(target, method, args) as Connection) else null
        }

    private fun connection(raw: Connection): Connection =
        proxy(raw) { method, args ->
            if (method.name == "prepareStatement") {
                statement(raw, checkNotNull(args)[0] as String, call(raw, method, args) as PreparedStatement)
            } else {
                null
            }
        }

    private fun statement(
        connection: Connection,
        sql: String,
        raw: PreparedStatement,
    ): PreparedStatement =
        proxy(raw) { method, args ->
            if (method.name in EXECUTIONS && "statement_timeout" !in sql) {
                val timeout =
                    connection.createStatement().use { show ->
                        show.executeQuery("SHOW statement_timeout").use { rows ->
                            check(rows.next()) { "SHOW answers one row" }
                            rows.getString(1)
                        }
                    }
                observed += sql to timeout
                try {
                    call(raw, method, args) ?: NULL
                } finally {
                    after(sql)
                }
            } else {
                null
            }
        }

    /** A proxy of [target]'s interface that lets [intercept] answer a call (non-null) or passes it through. */
    private inline fun <reified T : Any> proxy(
        target: T,
        crossinline intercept: (java.lang.reflect.Method, Array<out Any?>?) -> Any?,
    ): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            when (val answer = intercept(method, args)) {
                null -> call(target, method, args)
                NULL -> null
                else -> answer
            }
        } as T

    private fun call(
        target: Any,
        method: java.lang.reflect.Method,
        args: Array<out Any?>?,
    ): Any? =
        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }

    private companion object {
        val EXECUTIONS = setOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate")

        /** An intercepted call that legitimately answered null. */
        val NULL = Any()
    }
}
