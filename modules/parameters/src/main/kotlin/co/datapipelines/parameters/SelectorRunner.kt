package co.datapipelines.parameters

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceErrorCodes
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DatasourceUnreachableException
import co.datapipelines.datasources.LeasedStatement
import co.datapipelines.datasources.ReadOnlyStatementLease
import co.datapipelines.datasources.ResultRowReader
import co.datapipelines.datasources.SqlExecutionException
import co.datapipelines.datasources.SqlProbeExecutionException
import co.datapipelines.datasources.SqlProbeRefusalException
import co.datapipelines.datasources.SqlProbeTimeoutException
import co.datapipelines.datasources.isPermissionDenied
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.templates.TemplateRenderException
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import java.math.BigDecimal
import java.math.BigInteger
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * What one selector run produced (record §6.3) — the runner's answer to the evaluator, and (through
 * [SelectorRunner.probe]) to the save-time validator. Never an exception for an author's or a
 * datasource's problem: every failure is data, carrying the code the failing subsystem owns.
 */
sealed interface SelectorRun {
    /**
     * The statement ran: the result set's [columns] from METADATA (`ResultRowReader.schemaOf` —
     * record §6.4 decides from them, never from rows, P11) and the rows read — each a list of
     * canonical values in column order (the typesystem's parameter forms: `Int`, `BigInteger`,
     * `BigDecimal`, `String`, `Boolean`, `ByteArray`, `LocalDate`, `LocalTime`, `Instant`).
     */
    data class Rows(
        val columns: List<ColumnSchema>,
        val rows: List<List<Any?>>,
    ) : SelectorRun

    /**
     * The run failed before or at the statement: [code] is the owning subsystem's catalogued code —
     * the template engine's render code, `datasource.not_found`, `pipeline.node.sql_parameter_missing`,
     * `parameter.evaluate.too_many_binds`, `pipeline.node.query_execution_failed` (a refused read-only
     * gate included, `details.reason = read_only_gate`), `pipeline.node.query_timeout`,
     * `datasource.table_forbidden`. [detail] is bounded driver or engine text.
     */
    data class Failed(
        val code: String,
        val detail: String,
        val details: Map<String, Any?> = emptyMap(),
    ) : SelectorRun

    /** The datasource could not be reached (`pipeline.execution.datasource_unreachable`); [detail] is bounded driver text. */
    data class Unreachable(
        val detail: String,
    ) : SelectorRun
}

/** One selector statement — the unit of work the evaluator hands to the [SelectorPool] bulkhead. */
interface SelectorTask {
    /** Render, bind, gate, lease, run, read — blocking, on the caller's thread. Never throws for an author's or a datasource's problem. */
    fun run(): SelectorRun

    /**
     * Stop it from any thread (the evaluate's deadline): a statement already running is cancelled and
     * its connection discarded ([LeasedStatement.abandon]); one not yet leased never leases. Idempotent;
     * a no-op once [run] has returned.
     */
    fun abandon()
}

/** One template-backed source to run: the pin, the datasource, the render context and the binds, and the row budget. */
data class SelectorRequest(
    val workspaceId: UUID,
    val template: TemplateRef,
    val datasource: String,
    /** The render context: the parents' values (a `MULTI` as its count only, P29), the org and platform tiers. */
    val context: Map<String, Any?>,
    /** The binds: the parents' values (a `MULTI` as its list), the counts, the tiers. */
    val binds: Map<String, Any?>,
    /** `max-options-per-selector + 1` for a `SELECT` (the overflow probe row), 2 for an `INPUT` (§6.2a). */
    val maxRows: Int,
)

/** The name → datasource read one evaluate shares across its selectors (P31: workspace-visible, live). */
fun interface DatasourceResolver {
    fun resolve(name: String): Datasource?
}

/** The runtime the evaluator runs selectors through — [SelectorRunner] in production, a scripted double in the unit suites. */
interface SelectorTasks {
    /** A resolver for one evaluate in [workspaceId]: each datasource name read once, live, visibility-checked. */
    fun resolver(workspaceId: UUID): DatasourceResolver

    fun task(
        request: SelectorRequest,
        resolver: DatasourceResolver,
    ): SelectorTask
}

/**
 * The selector runtime (record §6.3, lane C): renders a pinned template through the workspace's
 * [co.datapipelines.templates.TemplateEngine] and runs it through the datasource's pool with the
 * `SqlRunner` discipline, reading rows through [ResultRowReader] and the schema through
 * `schemaOf`. It is also the save-time [SelectorProbe] (record §4 steps 5–6): the same render, the
 * same binds, the same gate, `maxRows = 2`.
 *
 * ## One statement, in order
 *
 * 1. **Render** the pin against the context — the engine's own guards (render timeout, output cap,
 *    bounded render pool) apply; its `TemplateRenderException` becomes data with its own code.
 * 2. **Resolve** the datasource by its workspace-VISIBLE live read on every evaluate (P31): a grant
 *    revoked since release is `datasource.not_found` on the parameter, and nothing about the
 *    datasource's existence elsewhere is said.
 * 3. **Bind** ([SelectorBinds]): `:name` → `?` with list expansion, the empty list as `IN (NULL)`,
 *    P29's slices; every expanded placeholder counted against `max-binds-per-statement`.
 * 4. **Gate, lease, prepare** ([ReadOnlyStatementLease]): datasources.md §7D's read-only gate on
 *    the text BEFORE any lease (record §14 item 2: the classifier is the gate; the datasource's
 *    `readonly` flag says nothing about reads and is not consulted), the statement timeout = the
 *    datasource's clamped to `selector-query-timeout-seconds`, `maxRows` / `fetchSize` = the budget.
 * 5. **Read** at most `maxRows` rows, canonical — `BIGINTEGER` as `BigInteger`, a `TIMESTAMP` as its
 *    `Instant` — so a row's value compares with a submitted one by the §7.3 equality.
 *
 * Every SQL that reaches a customer database is the RENDERED pinned template with its values sent
 * as binds; nothing here splices a value into text.
 */
class SelectorRunner(
    private val templates: WorkspaceTemplateEngines,
    private val datasources: DatasourceRegistry,
    private val config: ParametersConfig = ParametersConfig(),
) : SelectorProbe,
    SelectorTasks {
    private val lease = ReadOnlyStatementLease(datasources)

    override fun render(
        workspaceId: UUID,
        template: TemplateRef,
        context: Map<String, Any?>,
    ): SelectorRender =
        when (val rendered = renderSql(workspaceId, template, context)) {
            is Rendering.Sql -> SelectorRender.Rendered(rendered.sql)
            is Rendering.Refused -> SelectorRender.Failed(rendered.failure.detail)
        }

    override fun probe(
        workspaceId: UUID,
        datasource: String,
        sql: String,
        binds: Map<String, Any?>,
        maxRows: Int,
    ): SelectorProbeOutcome =
        when (
            val run =
                Execution(
                    sql = sql,
                    datasourceName = datasource,
                    binds = binds,
                    maxRows = maxRows,
                    resolver = resolver(workspaceId),
                ).run()
        ) {
            is SelectorRun.Rows -> SelectorProbeOutcome.Probed(run.columns, run.rows)
            is SelectorRun.Unreachable -> SelectorProbeOutcome.Unreachable(run.detail)
            is SelectorRun.Failed -> SelectorProbeOutcome.StatementFailed(run.code, run.detail)
        }

    override fun resolver(workspaceId: UUID): DatasourceResolver {
        val resolved = ConcurrentHashMap<String, Resolution>()
        return DatasourceResolver { name ->
            resolved.computeIfAbsent(name) { Resolution(datasources.getVisibleLive(it, workspaceId)) }.datasource
        }
    }

    override fun task(
        request: SelectorRequest,
        resolver: DatasourceResolver,
    ): SelectorTask =
        Execution(
            sql = null,
            datasourceName = request.datasource,
            binds = request.binds,
            maxRows = request.maxRows,
            resolver = resolver,
            render = { renderSql(request.workspaceId, request.template, request.context) },
        )

    /** A resolved (or absent) datasource — a holder, because a concurrent map cannot store null. */
    private class Resolution(
        val datasource: Datasource?,
    )

    private sealed interface Rendering {
        data class Sql(
            val sql: String,
        ) : Rendering

        data class Refused(
            val failure: SelectorRun.Failed,
        ) : Rendering
    }

    private fun renderSql(
        workspaceId: UUID,
        template: TemplateRef,
        context: Map<String, Any?>,
    ): Rendering =
        try {
            Rendering.Sql(templates.engineFor(workspaceId).render(template, context))
        } catch (e: TemplateRenderException) {
            val detail = (e.details["detail"] as? String) ?: e.message.orEmpty()
            Rendering.Refused(SelectorRun.Failed(e.code, bounded(detail)))
        }

    /**
     * One run. [sql] is given (the save-time probe renders first, through [render]) or produced by
     * [render] on the running thread (an evaluate's statement renders inside its bulkhead slot, so
     * the evaluate's deadline covers the render too).
     */
    private inner class Execution(
        private val sql: String?,
        private val datasourceName: String,
        private val binds: Map<String, Any?>,
        private val maxRows: Int,
        private val resolver: DatasourceResolver,
        private val render: (() -> Rendering)? = null,
    ) : SelectorTask {
        private val abandoned = AtomicBoolean(false)
        private val leased = AtomicReference<LeasedStatement?>()

        override fun abandon() {
            abandoned.set(true)
            leased.get()?.abandon()
        }

        @Suppress("ReturnCount") // one early answer per stage that can refuse
        override fun run(): SelectorRun {
            val text =
                sql ?: when (val rendered = checkNotNull(render).invoke()) {
                    is Rendering.Sql -> rendered.sql
                    is Rendering.Refused -> return rendered.failure
                }
            val datasource =
                resolver.resolve(datasourceName)
                    ?: return SelectorRun.Failed(
                        DatasourceErrorCodes.NOT_FOUND,
                        "Datasource '${datasourceName.safeEcho()}' is not visible from this workspace.",
                        mapOf("datasource" to datasourceName.safeEcho()),
                    )
            val (positional, values) =
                when (val translated = SelectorBinds.translate(text, binds, config.maxBindsPerStatement)) {
                    is SelectorBinds.Translation.Translated -> translated.sql to translated.values
                    is SelectorBinds.Translation.Missing -> return missingBind(translated.name)
                    is SelectorBinds.Translation.TooManyBinds -> return tooManyBinds(translated)
                    is SelectorBinds.Translation.Refused -> return refusedBinds(translated.detail)
                }
            if (abandoned.get()) return ABANDONED
            return execute(datasource, positional, values)
        }

        private fun execute(
            datasource: Datasource,
            positional: String,
            values: List<Any?>,
        ): SelectorRun =
            try {
                val statement = lease.open(datasource, positional, values, maxRows, config.selectorQueryTimeoutSeconds.toInt())
                leased.set(statement)
                // abandon() may have run between open() returning and the set above: honour it now.
                if (abandoned.get()) statement.abandon()
                statement.use { it.query { rs -> read(rs, datasource) } }
            } catch (e: SqlProbeRefusalException) {
                SelectorRun.Failed(
                    PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED,
                    "The rendered selector is not a single read-only SELECT or WITH statement: ${e.message.orEmpty()}",
                    mapOf("reason" to READ_ONLY_GATE),
                )
            } catch (e: DatasourceUnreachableException) {
                SelectorRun.Unreachable(bounded(causeText(e.cause)))
            } catch (e: SqlProbeTimeoutException) {
                SelectorRun.Failed(
                    PipelineErrorCodes.Node.QUERY_TIMEOUT,
                    "The selector's statement exceeded its timeout on '${datasource.name.safeEcho()}' after ${e.wallMs} ms.",
                    mapOf("reason" to "timeout", "wall_ms" to e.wallMs),
                )
            } catch (e: SqlProbeExecutionException) {
                val denied = (e.cause as? SQLException)?.isPermissionDenied() == true
                SelectorRun.Failed(
                    if (denied) PipelineErrorCodes.Datasource.TABLE_FORBIDDEN else PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED,
                    bounded(e.driverMessage),
                )
            } catch (e: DatapipelinesException) {
                // datasource.lease_in_transaction — a caller probing inside a metadata transaction (a defect in the caller).
                SelectorRun.Failed(e.code, bounded(e.message.orEmpty()))
            }
    }

    /** The result set, canonical: metadata first (P11), then at most the statement's `maxRows` rows. */
    private fun read(
        rs: ResultSet,
        datasource: Datasource,
    ): SelectorRun.Rows {
        val columns = ResultRowReader.schemaOf(rs.metaData, datasource.dialect).columns
        val rows = ArrayList<List<Any?>>()
        while (rs.next()) {
            rows +=
                columns.mapIndexed { index, column -> SelectorValues.canonical(ResultRowReader.readValue(rs, index + 1, column), column) }
        }
        return SelectorRun.Rows(columns, rows)
    }

    private fun missingBind(name: String) =
        SelectorRun.Failed(
            PipelineErrorCodes.Node.SQL_PARAMETER_MISSING,
            "The rendered selector binds :${name.safeEcho()}, which is neither a parent in depends_on nor an org or platform key.",
            mapOf("bind" to name.safeEcho()),
        )

    private fun tooManyBinds(translated: SelectorBinds.Translation.TooManyBinds) =
        SelectorRun.Failed(
            ParameterErrorCodes.EVALUATE_TOO_MANY_BINDS,
            "The rendered selector needs ${translated.count} bind placeholders; at most ${translated.max} are allowed " +
                "(datapipelines.parameters.max-binds-per-statement) — use the in_list macro's chunks or a narrower parent.",
            mapOf("binds" to translated.count, "max" to translated.max),
        )

    private fun refusedBinds(detail: String) =
        SelectorRun.Failed(
            PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED,
            "The rendered selector's placeholders could not be bound: ${bounded(detail)}",
            mapOf("reason" to "placeholders"),
        )

    private companion object {
        /** `details.reason` of a statement the read-only gate refused (§7D) — nothing ran. */
        const val READ_ONLY_GATE = "read_only_gate"

        /** Driver and engine text reaching a refusal — bounded here; every consumer clips again for its echo. */
        const val MAX_DETAIL_CHARS = 500

        /** What a run that its caller abandoned before the lease answers — nobody reads it. */
        val ABANDONED = SelectorRun.Failed(PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED, "abandoned before the statement ran")

        fun bounded(text: String): String = text.safeEcho(MAX_DETAIL_CHARS)

        fun causeText(cause: Throwable?): String =
            when (cause) {
                null -> "unreachable"
                is SQLException -> SqlExecutionException.boundedMessage(cause)
                else -> cause.message ?: cause.javaClass.simpleName
            }
    }
}

/**
 * [ResultRowReader]'s values as the PARAMETER forms the typesystem's coercion produces — one
 * canonical value per logical type, so a selector's `value` and a submitted value compare by the
 * §7.3 equality and re-encode through `ParameterWireEncoder`: the reader hands a `BIGINTEGER` as a
 * `Long` and a `TIMESTAMP` as an `OffsetDateTime` (or a zoneless `LocalDateTime`, read as UTC, the
 * `JsonEncoder` rule); an approximate `DECIMAL` (a float column) is its `BigDecimal` text form.
 */
internal object SelectorValues {
    fun canonical(
        value: Any?,
        column: ColumnSchema,
    ): Any? =
        when {
            value == null -> null
            column.type == LogicalType.BIGINTEGER && value is Long -> BigInteger.valueOf(value)
            column.type == LogicalType.DECIMAL && value is Double -> BigDecimal(value.toString())
            value is OffsetDateTime -> value.toInstant()
            value is LocalDateTime -> value.toInstant(ZoneOffset.UTC)
            value is Instant -> value
            else -> value
        }
}
