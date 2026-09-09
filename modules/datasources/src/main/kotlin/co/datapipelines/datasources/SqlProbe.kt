package co.datapipelines.datasources

import co.datapipelines.typesystem.JsonEncoder
import org.slf4j.LoggerFactory
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.jdbc.core.SqlTypeValue
import org.springframework.jdbc.core.StatementCreatorUtils
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterUtils
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.concurrent.TimeUnit

/**
 * The bounded, read-only free-SQL probe (§7D) behind the `sql_probe` tool: ONE classified
 * SELECT against a datasource, row-capped and timeboxed, answering rows + canonical schema + a
 * summarized EXPLAIN plan.
 *
 * A [SqlRunner] sibling with the same discipline — one leased connection per call
 * ([ConnectionLease]), `queryTimeout` + `fetchSize` + `maxRows = limit + 1` on the statement,
 * Spring [StatementCreatorUtils] binds, and [ResultRowReader]/[JsonEncoder] decoding — plus the
 * three things a probe needs beyond a preview:
 *
 *  - **the gate**: [SqlStatementClassifier] runs before anything is leased; only a single
 *    `SELECT`/`WITH` reaches a connection.
 *  - **the plan first**: the dialect's [DialectAdapter.explainSelectSql] runs on the SAME lease
 *    before the query, so a timed-out probe still answers with the plan that explains it. Any
 *    EXPLAIN failure (unsupported shape, driver refusal, its own timeout) degrades to a null
 *    plan — the plan is best-effort and never fails the probe.
 *  - **the timebox**: [timeoutSeconds] is the statement timeout. A driver timeout arrives as
 *    [SQLTimeoutException] OR as a server-side cancel (pgjdbc delivers its queryTimeout as
 *    SQLState 57014 `query_canceled`, a plain `PSQLException`) — [isStatementTimeout] covers
 *    both. The catch lives INSIDE the lease block: [ConnectionLease] classifies
 *    `SQLTimeoutException` as a connection failure ([ConnectionLease.isConnectionFailure]'s
 *    dead-network reasoning), and the typed [SqlProbeTimeoutException] is a RuntimeException, so
 *    it crosses the lease boundary untranslated — exactly the mechanism that keeps "the
 *    statement was slow" distinct from "the database is gone".
 *
 * [limit] is CLAMPED to [MAX_LIMIT] rather than refused — a probe asking for more rows than the
 * cap is a sizing error, not a defect, and the truncated flag already says the rest exists;
 * [timeoutSeconds] clamps likewise to [MAX_TIMEOUT_SECONDS].
 */
class SqlProbe(
    private val registry: DatasourceRegistry,
) {
    /**
     * Runs [sql] against [datasource] (already visibility-gated by the caller) with named
     * `:name` parameters, returning at most [limit] rows.
     *
     * @throws SqlProbeRefusalException the SQL is not a single read-only SELECT/WITH.
     * @throws SqlProbeParameterException a parameter is missing or fails its declared type.
     * @throws SqlProbeTimeoutException the statement exceeded the timebox (plan attached).
     * @throws SqlProbeExecutionException the database refused the statement.
     * @throws DatasourceUnreachableException the datasource could not be connected to.
     */
    fun probe(
        datasource: Datasource,
        sql: String,
        parameters: Map<String, SqlProbeParameter> = emptyMap(),
        limit: Int = DEFAULT_LIMIT,
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    ): SqlProbeResult {
        val statement = SqlStatementClassifier.classify(sql, datasource.dialect)
        val rowCap = limit.coerceIn(1, MAX_LIMIT)
        val timeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        val values = parameters.mapValues { (name, parameter) -> parameter.toJdbcValue(name) }
        val (positionalSql, bindValues) = translateBinds(statement, values)
        val explainSql = DialectAdapters.forDialect(datasource.dialect).explainSelectSql(positionalSql)
        return ConnectionLease.lease(registry, datasource) { connection ->
            val plan = explainSql?.let { explain(connection, it, bindValues, timeout, datasource) }
            query(connection, datasource, positionalSql, bindValues, rowCap, timeout, plan)
        }
    }

    /**
     * `:name` → positional `?` + ordered values through Spring's [NamedParameterUtils] — the
     * exact binder the executor's `SqlBindTranslator` and the templates module's
     * `NodeSqlResolver` ride (both out of reach under module-structure §5.4), so probe SQL
     * parses `:name` exactly the way pipeline SQL does. A reference with no supplied value is
     * refused, never bound as null silently.
     */
    private fun translateBinds(
        sql: String,
        values: Map<String, Any?>,
    ): Pair<String, List<Any?>> {
        val positional =
            try {
                NamedParameterUtils.substituteNamedParameters(sql, MapSqlParameterSource(values))
            } catch (e: InvalidDataAccessApiUsageException) {
                throw missingParameter(sql, values, e)
            }
        val bindValues =
            try {
                NamedParameterUtils.buildValueArray(sql, values).toList()
            } catch (e: InvalidDataAccessApiUsageException) {
                throw missingParameter(sql, values, e)
            }
        return positional to bindValues
    }

    /** The missing-name refusal; the parameter's name is the only detail worth carrying. */
    private fun missingParameter(
        sql: String,
        values: Map<String, Any?>,
        cause: InvalidDataAccessApiUsageException,
    ): SqlProbeParameterException {
        val referenced = PARAMETER_NAME.findAll(sql).map { it.groupValues[1] }.toSet()
        val missing = referenced.firstOrNull { it !in values.keys } ?: ""
        return SqlProbeParameterException.missing(missing, cause)
    }

    /**
     * The EXPLAIN read: same lease, same timeout, same binds (a plan can depend on the values).
     * Best-effort by contract — any failure is logged and degrades to a null plan rather than
     * failing the probe.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun explain(
        connection: Connection,
        explainSql: String,
        bindValues: List<Any?>,
        timeoutSeconds: Int,
        datasource: Datasource,
    ): ExplainPlanSummary? {
        try {
            connection.prepareStatement(explainSql).use { statement ->
                statement.queryTimeout = timeoutSeconds
                bind(statement, bindValues)
                return readPlan(statement, datasource)
            }
        } catch (e: SQLException) {
            return explainFailed(datasource, e)
        } catch (e: RuntimeException) {
            // A driver-side RuntimeException mid-EXPLAIN degrades exactly like a refusal —
            // the plan is best-effort and never fails the probe.
            return explainFailed(datasource, e)
        }
    }

    /** The EXPLAIN result rows, bounded at [EXPLAIN_MAX_ROWS], summarized by dialect. */
    private fun readPlan(
        statement: PreparedStatement,
        datasource: Datasource,
    ): ExplainPlanSummary =
        statement.executeQuery().use { rs ->
            val labels = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
            val rows = ArrayList<List<String?>>()
            while (rs.next() && rows.size < EXPLAIN_MAX_ROWS) {
                rows.add((1..labels.size).map { rs.getString(it) })
            }
            ExplainPlanParser.summarize(datasource.dialect, labels, rows)
        }

    /** The null-plan degradation — the EXPLAIN is best-effort and never fails the probe. */
    private fun explainFailed(
        datasource: Datasource,
        failure: Exception,
    ): ExplainPlanSummary? {
        LOG.debug(
            "EXPLAIN for a probe on '{}' failed ({}); the probe continues plan-less",
            datasource.name,
            failure.javaClass.simpleName,
        )
        return null
    }

    /** The query itself, capped and timeboxed, decoded exactly like [SqlRunner.select]. */
    private fun query(
        connection: Connection,
        datasource: Datasource,
        sql: String,
        bindValues: List<Any?>,
        limit: Int,
        timeoutSeconds: Int,
        plan: ExplainPlanSummary?,
    ): SqlProbeResult {
        val startedAt = System.nanoTime()
        try {
            connection.prepareStatement(sql).use { statement ->
                statement.queryTimeout = timeoutSeconds
                statement.fetchSize = limit
                statement.maxRows = limit + 1
                bind(statement, bindValues)
                return SqlProbeResult(readRows(statement, datasource, limit), wallMs(startedAt), plan)
            }
        } catch (e: SQLException) {
            if (e.isStatementTimeout()) throw SqlProbeTimeoutException(datasource.name, wallMs(startedAt), plan, e)
            throw SqlProbeExecutionException(datasource.name, e)
        }
    }

    /** The [SqlRunner.readRows] discipline: canonical schema, wire-encoded values, cap + 1 probe row. */
    private fun readRows(
        statement: PreparedStatement,
        datasource: Datasource,
        limit: Int,
    ): QueryRows =
        statement.executeQuery().use { rs ->
            val schema = ResultRowReader.schemaOf(rs.metaData, datasource.dialect)
            val rows = ArrayList<Map<String, Any?>>(limit.coerceAtMost(PREALLOCATED_CAPACITY))
            var truncated = false
            while (rs.next()) {
                if (rows.size == limit) {
                    truncated = true
                    break
                }
                val row = LinkedHashMap<String, Any?>(schema.columns.size * 2)
                schema.columns.forEachIndexed { index, column ->
                    row[column.name] = JsonEncoder.encode(ResultRowReader.readValue(rs, index + 1, column), column)
                }
                rows.add(row)
            }
            QueryRows(schema, rows, truncated)
        }

    private fun bind(
        statement: PreparedStatement,
        bindValues: List<Any?>,
    ) {
        bindValues.forEachIndexed { index, value ->
            StatementCreatorUtils.setParameterValue(statement, index + 1, SqlTypeValue.TYPE_UNKNOWN, value)
        }
    }

    private fun wallMs(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    companion object {
        const val DEFAULT_LIMIT = 50

        /** The hard row cap — a probe is a debug read, not an export. */
        const val MAX_LIMIT = 500

        const val DEFAULT_TIMEOUT_SECONDS = 10

        /** The hard timebox. */
        const val MAX_TIMEOUT_SECONDS = 30

        /** The [ExplainPlanSummary.raw] cap. */
        const val EXPLAIN_RAW_MAX_CHARS = 4096

        /** The EXPLAIN row budget — a pathological plan must not stream through the wire. */
        const val EXPLAIN_MAX_ROWS = 256

        private const val PREALLOCATED_CAPACITY = 64

        private val LOG = LoggerFactory.getLogger(SqlProbe::class.java)

        /** A `:name` reference — used only to NAME the missing parameter in the refusal. */
        private val PARAMETER_NAME = Regex(""":([A-Za-z_][A-Za-z0-9_]*)""")
    }
}
