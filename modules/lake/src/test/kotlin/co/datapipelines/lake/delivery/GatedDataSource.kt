package co.datapipelines.lake.delivery

import org.springframework.jdbc.datasource.DelegatingDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource

/**
 * Records the SQL text of every statement executed through this source, and runs a gate the first time a statement
 * whose text starts with a named prefix is about to execute (B.7): the gate sits between the JDBC timeout being applied
 * and the statement reaching the server, so a gate that sleeps models a statement that took that long inside its bound,
 * and a gate that takes a row lock on another connection makes the real statement block on the server, under its real
 * timer.
 */
internal class GatedDataSource(
    private val target: DataSource,
) : DelegatingDataSource(target) {
    val executed = CopyOnWriteArrayList<String>()
    private val gates = ConcurrentHashMap<String, () -> Unit>()

    fun reset() = executed.clear()

    /** Runs [action] once, on the executing thread, before the first statement starting with [prefix]. */
    fun gate(
        prefix: String,
        action: () -> Unit,
    ) {
        gates[prefix] = action
    }

    override fun getConnection(): Connection = connection(target.connection)

    private fun connection(raw: Connection): Connection =
        proxy(Connection::class.java) { method, args ->
            val result = invoke(raw, method, args)
            when (method.name) {
                "prepareStatement" -> statement(result as PreparedStatement, PreparedStatement::class.java, args?.get(0) as String)
                "createStatement" -> statement(result as Statement, Statement::class.java, null)
                else -> result
            }
        }

    private fun <S : Statement> statement(
        raw: S,
        type: Class<S>,
        prepared: String?,
    ): S =
        proxy(type) { method, args ->
            if (method.name.startsWith("execute")) {
                val text = prepared ?: (args?.firstOrNull() as? String ?: method.name)
                executed += text
                gates.keys.firstOrNull { text.startsWith(it) }?.let { gates.remove(it)?.invoke() }
            }
            invoke(raw, method, args)
        }

    private fun <T> proxy(
        type: Class<T>,
        handler: (Method, Array<Any?>?) -> Any?,
    ): T = type.cast(Proxy.newProxyInstance(javaClass.classLoader, arrayOf(type)) { _, method, args -> handler(method, args) })

    private fun invoke(
        raw: Any,
        method: Method,
        args: Array<Any?>?,
    ): Any? =
        try {
            method.invoke(raw, *(args ?: emptyArray()))
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
}
