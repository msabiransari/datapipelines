package co.datapipelines.datasources

import com.zaxxer.hikari.HikariDataSource
import java.sql.SQLException

/**
 * 109 §A — the registration pre-flight: prove a CANDIDATE lake table readable BEFORE its row is
 * stored, so a bad location (a wrong prefix, a file that is not Parquet) is refused at
 * registration with the engine's own error text instead of being discovered at connect.
 *
 * The proof runs on a scratch single-connection pool built through the datasource's real
 * adapter ([DialectAdapter.buildHikariConfig] — the probe's §8.1 shape: `maximumPoolSize = 1`,
 * a bounded connection timeout, no construction-time fail-fast), so the check sees exactly the
 * extensions, secret and engine limits the real pool's connections get. On it: the plan's
 * prelude (the namespace's ATTACH / CREATE SCHEMA and any Iceberg extension the format needs),
 * the candidate's own `CREATE VIEW`, and a `SELECT * … LIMIT 1` through that view — the view
 * alone is not proof, because DuckDB plans lazily and a missing file surfaces at the SCAN.
 *
 * Returns null when the table reads; otherwise the deepest engine message, bounded to
 * [MAX_ERROR_CHARS]. Never throws for engine trouble (the probe's failure-as-data rule); a
 * failure to even build the scratch pool IS the answer — the table cannot be read on a
 * datasource whose own connect fails.
 */
object LakeTablePreflight {
    /** Upper bound on returned engine text (the ErrorCodeMapper.MAX_MESSAGE_CHARS value). */
    const val MAX_ERROR_CHARS = 2000

    private const val PROBE_CONNECTION_TIMEOUT_MS = 10_000L

    /** Null = the candidate table is readable on [datasource]; non-null = the refusal's reason. */
    @Suppress("TooGenericExceptionCaught") // the probe's rule: a driver fault arrives as SQLException OR RuntimeException
    fun check(
        datasource: Datasource,
        table: LakeRegisteredTable,
        duckdbExtensionDirectory: String? = null,
    ): String? {
        val adapter = DialectAdapters.forDialect(datasource.dialect, duckdbExtensionDirectory)
        val plan = LakeViewStatements.planForTables(listOf(table), adapter, duckdbExtensionDirectory)
        val view = plan.views.single()
        // The SQL-emission boundary's refusal (an unmappable namespace, an unutterable
        // location) needs no engine round-trip — it IS the pre-flight answer.
        if (view.sql == null) return view.emissionError?.take(MAX_ERROR_CHARS)
        val config =
            adapter.buildHikariConfig(datasource).apply {
                maximumPoolSize = 1
                connectionTimeout = PROBE_CONNECTION_TIMEOUT_MS
                initializationFailTimeout = -1
                poolName = "ds-lake-preflight-${datasource.name}"
            }
        return try {
            HikariDataSource(config).use { pool ->
                pool.connection.use { connection -> readCandidate(connection, plan, view, table, adapter) }
            }
            null
        } catch (e: SQLException) {
            deepestThrowableMessage(e).take(MAX_ERROR_CHARS)
        } catch (e: RuntimeException) {
            deepestThrowableMessage(e).take(MAX_ERROR_CHARS)
        }
    }

    /**
     * The proof itself on [connection]: the plan's prelude, the candidate's view, and a one-row
     * scan through it (the view alone is not proof — DuckDB plans lazily and a missing file
     * surfaces at the SCAN). A separate function so the closing-over-use nesting stays readable.
     */
    private fun readCandidate(
        connection: java.sql.Connection,
        plan: LakeViewPlan,
        view: LakeViewPlan.View,
        table: LakeRegisteredTable,
        adapter: DialectAdapter,
    ) {
        plan.prelude.forEach { statement -> connection.createStatement().use { it.execute(statement) } }
        connection.createStatement().use { it.execute(requireNotNull(view.sql)) }
        val qualified = (table.namespace + table.name).joinToString(".") { adapter.quoteIdentifier(it) }
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM $qualified LIMIT 1").use { }
        }
    }
}

/**
 * The deepest non-blank message in the cause chain — the probe's rule (the outermost frame is
 * usually a wrapper that says nothing), shared by the pre-flight and the pool's view applier.
 */
internal fun deepestThrowableMessage(e: Throwable): String =
    generateSequence(e) { it.cause }
        .toList()
        .lastOrNull { !it.message.isNullOrBlank() }
        ?.message
        ?: e.javaClass.simpleName
