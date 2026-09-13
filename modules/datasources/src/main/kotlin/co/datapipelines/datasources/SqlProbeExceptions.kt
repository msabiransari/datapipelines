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

/**
 * The permission-denied family of a table-addressed read failure (123 §A): the table WAS in
 * the namespace's listing (resolution said present) and the engine then refused the read on
 * privilege grounds — surfaced as the catalogued `datasource.table_forbidden` (403) at the
 * statement-executing boundaries (preview rows, `sql_probe`). The walk mirrors
 * [isStatementTimeout]'s cause/nextException traversal with the same bound.
 *
 * The shapes, per dialect:
 *
 * - **Postgres** — SQLState `42501` `insufficient_privilege`.
 * - **MySQL** — vendor codes `1142`/`1143` (ER_TABLEACCESS_DENIED_ERROR /
 *   ER_COLUMNACCESS_DENIED_ERROR), arriving under SQLState `42000` — the state alone means
 *   "syntax error or access rule violation", so the VENDOR CODE discriminates.
 * - **SQL Server** — vendor codes `229`/`230` (permission denied on the object / on a
 *   column), also under `42000` — same discrimination.
 * - **Oracle** — `ORA-01031` `insufficient privileges`: vendor code `1031` under ojdbc's
 *   runtime-error state `72000`. `ORA-00942` (`942`, under `42000`) is deliberately NOT
 *   here — "table or view does not exist" is ambiguous between absent and invisible, so it
 *   stays on the not-found path.
 *
 * Public (unlike [isStatementTimeout]) because the classification lives in this module while
 * two of the boundaries that apply it — the `sql_probe` and preview-rows error mappings —
 * live in `mcp-server`.
 */
fun SQLException.isPermissionDenied(): Boolean {
    val seen = java.util.IdentityHashMap<Throwable, Boolean>()
    val queue = ArrayDeque<Throwable>()
    queue.add(this)
    while (queue.isNotEmpty() && seen.size < ConnectionLease.CHAIN_WALK_LIMIT) {
        val current = queue.removeFirst()
        if (seen.put(current, true) != null) continue
        if (current is SQLException && current.isPermissionDeniedShape()) return true
        (current as? SQLException)?.nextException?.let { queue.add(it) }
        current.cause?.let { queue.add(it) }
    }
    return false
}

private fun SQLException.isPermissionDeniedShape(): Boolean =
    when {
        sqlState == INSUFFICIENT_PRIVILEGE_STATE -> true
        sqlState == SYNTAX_OR_ACCESS_STATE && errorCode in PERMISSION_VENDOR_CODES_UNDER_42000 -> true
        sqlState == ORACLE_RUNTIME_STATE && errorCode == ORACLE_INSUFFICIENT_PRIVILEGES -> true
        else -> false
    }

/** SQL-standard `insufficient_privilege` — Postgres's 42501 (and any other engine honouring the standard state). */
private const val INSUFFICIENT_PRIVILEGE_STATE = "42501"

/** "Syntax error or access rule violation" — meaningful only WITH the vendor code (MySQL 1142/1143, MSSQL 229/230). */
private const val SYNTAX_OR_ACCESS_STATE = "42000"

/** MySQL's table/column access-denied codes and SQL Server's object/column permission-denied codes, both under 42000. */
private val PERMISSION_VENDOR_CODES_UNDER_42000 = setOf(1142, 1143, 229, 230)

/** ojdbc's runtime-error SQLState — the state ORA-01031 arrives under. */
private const val ORACLE_RUNTIME_STATE = "72000"

/** ORA-01031 `insufficient privileges`. ORA-00942 (942) is deliberately NOT classified — see the KDoc above. */
private const val ORACLE_INSUFFICIENT_PRIVILEGES = 1031
