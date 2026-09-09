package co.datapipelines.datasources

import java.sql.SQLException
import java.sql.SQLTimeoutException

/**
 * The probe statement exceeded its timebox. Carries what the calling surface needs for the
 * error envelope: [wallMs] of query execution before the cancel, and the [plan] captured BEFORE
 * the query ran — the plan is what explains a timeout, so it is deliberately read first and
 * survives one.
 *
 * Module-local with a STATIC message (the [DatasourceUnreachableException] precedent): the
 * driver text stays in the cause, bounded by [SqlExecutionException.boundedMessage] at the
 * envelope, and never names a byte of the probe SQL.
 */
class SqlProbeTimeoutException(
    val datasourceName: String,
    val wallMs: Long,
    val plan: ExplainPlanSummary?,
    cause: SQLException,
) : RuntimeException("The probe statement exceeded its timeout against datasource '$datasourceName'.", cause)

/**
 * The probe statement ran and the database refused it (syntax, missing object, permission) —
 * the probe-path sibling of [SqlExecutionException], distinguished so a probe failure is never
 * confused with a node-run failure at the surface. The message is STATIC; [driverMessage] is
 * the same bounded driver text [SqlExecutionException] sanctions (which CAN echo the statement
 * on drivers like H2 — it lives one level down, never in this exception's own message).
 */
class SqlProbeExecutionException(
    val datasourceName: String,
    cause: SQLException,
) : RuntimeException("The probed statement failed against datasource '$datasourceName'.", cause) {
    /** The driver message, bounded at the B1 limit — may contain driver-quoted SQL fragments. */
    val driverMessage: String = SqlExecutionException.boundedMessage(cause)
}

/**
 * The statement-timeout family of a probe failure: the typed [SQLTimeoutException] (what most
 * drivers raise for `queryTimeout`) and SQLState `57014` `query_canceled` — the shape pgjdbc's
 * queryTimeout actually arrives as, since it cancels the statement SERVER-side and relays the
 * server's error. The walk mirrors [isConnectionFailure]'s cause/nextException traversal with
 * the same bound. A user pressing "cancel" produces 57014 too; on the probe path the probe's own
 * timebox is the only canceller, so the classification is unambiguous there.
 */
internal fun SQLException.isStatementTimeout(): Boolean {
    val seen = java.util.IdentityHashMap<Throwable, Boolean>()
    val queue = ArrayDeque<Throwable>()
    queue.add(this)
    while (queue.isNotEmpty() && seen.size < ConnectionLease.CHAIN_WALK_LIMIT) {
        val current = queue.removeFirst()
        if (seen.put(current, true) != null) continue
        if (current is SQLTimeoutException) return true
        if (current is SQLException && current.sqlState == QUERY_CANCELED_STATE) return true
        (current as? SQLException)?.nextException?.let { queue.add(it) }
        current.cause?.let { queue.add(it) }
    }
    return false
}

private const val QUERY_CANCELED_STATE = "57014"
