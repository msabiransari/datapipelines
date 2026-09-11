package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.JsonEncoder
import org.slf4j.LoggerFactory
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.jdbc.core.SqlTypeValue
import org.springframework.jdbc.core.StatementCreatorUtils
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterUtils
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.UUID
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
     * The tempdb SYNTAX-AND-NAMES check (2026-09-11): the statement runs against a FRESH, EMPTY
     * in-memory H2 opened in the staging engine's own `MODE` and lower-folding — the same URL
     * shape `StagingFactory` builds, minus the execution. Staging tables do not exist here, so
     * the outcomes are:
     *
     *  - **H2 reports a missing table** (`42102`, the first `FROM` it could not resolve) → the
     *    statement PARSED and every name it defines itself resolved; only the staged input is
     *    absent. That is the pass signal — [ScratchProbeOutcome.Parsed] names the table.
     *  - **The statement ran** (a self-contained `VALUES` spine, a constant expression) → rows,
     *    exactly as a real probe returns them ([ScratchProbeOutcome.Rows]).
     *  - **Anything else** (a syntax error, a `VALUES` column named `column1` that H2 calls
     *    `C1`, a bad cast) → [SqlProbeExecutionException] with H2's message — the error that
     *    used to cost a full DAG run to see (the `pipeline-3` audit: five source nodes green,
     *    the caller node dead on `Column "column1" not found`).
     *
     * No registry, no lease, no visibility gate: nothing here can read data. The database is
     * discarded when the connection closes (no `DB_CLOSE_DELAY`, the staging rule).
     */
    fun probeScratch(
        sql: String,
        parameters: Map<String, SqlProbeParameter> = emptyMap(),
        mode: String = DEFAULT_SCRATCH_MODE,
        limit: Int = DEFAULT_LIMIT,
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    ): ScratchProbeOutcome {
        val statement = SqlStatementClassifier.classify(sql, Dialect.H2)
        val rowCap = limit.coerceIn(1, MAX_LIMIT)
        val timeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        val values = parameters.mapValues { (name, parameter) -> parameter.toJdbcValue(name) }
        val (positionalSql, bindValues) = translateBinds(statement, values)
        val scratch =
            Datasource(
                name = SCRATCH_NAME,
                displayName = "tempdb (empty scratch)",
                dialect = Dialect.H2,
                jdbcUrl = "jdbc:h2:mem:probe_${UUID.randomUUID()};MODE=$mode;DATABASE_TO_LOWER=TRUE",
            )
        val startedAt = System.nanoTime()
        DriverManager.getConnection(scratch.jdbcUrl).use { connection ->
            try {
                connection.prepareStatement(positionalSql).use { prepared ->
                    prepared.queryTimeout = timeout
                    prepared.maxRows = rowCap + 1
                    bind(prepared, bindValues)
                    return ScratchProbeOutcome.Rows(SqlProbeResult(readRows(prepared, scratch, rowCap), wallMs(startedAt), null))
                }
            } catch (e: SQLException) {
                if (e.errorCode in H2_TABLE_NOT_FOUND_CODES) {
                    return ScratchProbeOutcome.Parsed(missingTable = missingTableOf(e), wallMs = wallMs(startedAt))
                }
                throw SqlProbeExecutionException(scratch.name, e)
            }
        }
    }

    /** H2's `Table "STG_X" not found` — the quoted name, lower-folded like the staging tables are. */
    private fun missingTableOf(e: SQLException): String? =
        MISSING_TABLE
            .find(e.message ?: "")
            ?.groupValues
            ?.get(1)
            ?.lowercase()

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
        /** The staging engine's default `MODE` (`H2StagingProperties.mode`); callers pass the configured one. */
        const val DEFAULT_SCRATCH_MODE = "PostgreSQL"
        const val SCRATCH_NAME = "tempdb"

        /**
         * H2's table-not-found family: `TABLE_OR_VIEW_NOT_FOUND_1` (42102), `…_WITH_CANDIDATES_2`
         * (42103) and `…_DATABASE_EMPTY_1` (42104) — the last is what an EMPTY scratch reports.
         */
        private val H2_TABLE_NOT_FOUND_CODES = setOf(42102, 42103, 42104)
        private val MISSING_TABLE = Regex("Table \"([^\"]+)\" not found")
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

/** What [SqlProbe.probeScratch] found — the statement parsed and resolved (a staged input is absent), or it ran. */
sealed interface ScratchProbeOutcome {
    /** Parsed; every self-defined name resolved; [missingTable] is the staged table H2 could not find. */
    data class Parsed(
        val missingTable: String?,
        val wallMs: Long,
    ) : ScratchProbeOutcome

    /** The statement was self-contained and produced rows. */
    data class Rows(
        val result: SqlProbeResult,
    ) : ScratchProbeOutcome
}
