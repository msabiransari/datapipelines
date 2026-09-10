package co.datapipelines.datasources.pooling

import co.datapipelines.datasources.LakeViewOutcomeRecorder
import co.datapipelines.datasources.LakeViewPlan
import co.datapipelines.datasources.deepestThrowableMessage
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * What one pool build needs to apply a LAKE datasource's per-table views with per-table
 * isolation (109 §A): the [plan] [co.datapipelines.datasources.LakeViewStatements.planForTables]
 * generated from the registry rows, the datasource name (the recorder's key prefix), and the
 * [recorder] outcomes are written through. See [LakeViewApplyingDataSource] for the mechanics.
 */
class LakeViewInit(
    val datasourceName: String,
    val plan: LakeViewPlan,
    val recorder: LakeViewOutcomeRecorder,
)

/**
 * 109 §A — the per-table-isolation seam for a LAKE datasource's connect-time view creation.
 *
 * HikariCP's `connectionInitSql` is ONE string per physical connection and SQL has no
 * try/catch, so a view that fails inside it fails the whole connection — and, through
 * `initializationFailTimeout`, the pool build: one bad registered table (a bad prefix, a file
 * that is not Parquet) made EVERY table on the datasource unreachable. This wrapper is the
 * alternative seam: it wraps the driver's own [DataSource], and after each new physical
 * connection opens it applies the [LakeViewPlan] — the adapter's init statements and the plan's
 * prelude STRICTLY (a failure there is a datasource fault and fails the connect exactly as the
 * joined `connectionInitSql` did), then each table's view INDEPENDENTLY: a failure is recorded
 * on the table's registry row through [recorder] and skipped, and the connection serves the
 * surviving views.
 *
 * ## Transition-only recording
 *
 * Physical connections are created over the pool's whole life, so a write per connection would
 * be the hot path. Instead the wrapper seeds its last-known outcomes from the registry rows the
 * pool was built with ([co.datapipelines.datasources.LakeRegisteredTable.lastError]) and calls
 * [recorder] only when a table's outcome CHANGES: a new or changed error text, or a success
 * after an error (which clears the row). An unchanged outcome writes nothing.
 *
 * [datasourceName] is carried only because the recorder's key is the registry's
 * (datasource, namespace, table) triple and the plan's rows do not repeat it.
 */
class LakeViewApplyingDataSource(
    private val delegate: DataSource,
    /** The adapter's own `connectionInit` statements — strict, before everything of the plan's. */
    private val adapterInit: List<String>,
    private val plan: LakeViewPlan,
    private val datasourceName: String,
    private val recorder: LakeViewOutcomeRecorder,
) : DataSource by delegate {
    private val log = org.slf4j.LoggerFactory.getLogger(LakeViewApplyingDataSource::class.java)

    /**
     * Qualified name → last outcome the pool has APPLIED (ABSENCE = healthy), seeded from the
     * rows this pool was built with (`table.lastError`). The value is never null on purpose —
     * [ConcurrentHashMap] rejects null values, and "healthy" is better spelled by the key's
     * absence than by a sentinel string someone would eventually print. An emission refusal
     * ([LakeViewPlan.View.emissionError]) is deliberately NOT a seed: it is this connection's
     * fresh outcome, and recording it when the row still says healthy is exactly the transition
     * the recorder exists for.
     */
    private val knownOutcomes = ConcurrentHashMap<String, String>()

    init {
        plan.views.forEach { view -> view.table.lastError?.let { knownOutcomes[view.qualifiedName] = it } }
    }

    override fun getConnection(): Connection = initialized(delegate.connection)

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = initialized(delegate.getConnection(username, password))

    private fun initialized(connection: Connection): Connection {
        // Strict half: the adapter init (extensions, secret, limits) and the plan's prelude
        // (ATTACHes, schemas) fail the connect exactly as the joined connectionInitSql did —
        // they are shared infrastructure, and a failure there is never one table's fault.
        (adapterInit + plan.prelude).forEach { statement -> connection.createStatement().use { it.execute(statement) } }
        plan.views.forEach { view -> applyView(connection, view) }
        plan.postlude.forEach { statement -> connection.createStatement().use { it.execute(statement) } }
        return connection
    }

    /**
     * One table's view, isolated: an emission refusal is recorded without touching the engine;
     * an engine failure is caught, recorded and skipped — the connection keeps the views that
     * succeeded. Both exception families are caught on purpose (the probe's DS-SEC-6 rule): a
     * driver reports query failure as [SQLException], but DuckDB also surfaces some internal
     * faults as RuntimeExceptions, and neither may escape into connection creation.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun applyView(
        connection: Connection,
        view: LakeViewPlan.View,
    ) {
        val outcome: String? =
            when (val sql = view.sql) {
                null -> {
                    view.emissionError
                }

                else -> {
                    try {
                        connection.createStatement().use { it.execute(sql) }
                        null
                    } catch (e: SQLException) {
                        deepestThrowableMessage(e)
                    } catch (e: RuntimeException) {
                        deepestThrowableMessage(e)
                    }
                }
            }
        if (outcome != null) {
            log.warn(
                "event=lake.view_failed datasource={} table={} error=\"{}\" " +
                    "message=\"lake table view skipped at connect; recorded on its registry row\"",
                datasourceName,
                view.qualifiedName,
                outcome.take(MAX_ERROR_CHARS),
            )
        }
        recordTransition(view, outcome)
    }

    /**
     * Records [outcome] through [recorder] only when it differs from the last known one (see the
     * class KDoc): a healthy outcome REMOVES the key, an error writes it, and the map's own
     * atomic remove/put return values decide the transition — two connections created at once
     * cannot double-write one (the second call sees the first's value and matches it). The
     * bounded text is what both compare and store.
     */
    private fun recordTransition(
        view: LakeViewPlan.View,
        outcome: String?,
    ) {
        val bounded = outcome?.take(MAX_ERROR_CHARS)
        val transition =
            if (bounded == null) {
                knownOutcomes.remove(view.qualifiedName) != null
            } else {
                knownOutcomes.put(view.qualifiedName, bounded) != bounded
            }
        if (transition) {
            recorder.record(datasourceName, view.table.namespace, view.table.name, bounded)
        }
    }

    private companion object {
        /** Upper bound on recorded engine text (the ErrorCodeMapper.MAX_MESSAGE_CHARS value). */
        const val MAX_ERROR_CHARS = 2000
    }
}
