package co.datapipelines.parameters

import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.typesystem.ColumnSchema
import java.util.UUID

/**
 * The save-time validator's view of the selector runtime — record §4 steps 5 and 6: dry-render
 * each template-backed source, then run the rendered statement with `maxRows = 2` to prove its
 * columns, its `value` type and (for an `INPUT`) its row count, before anything is saved.
 *
 * A PORT, declared by lane B and implemented by lane C: [SelectorRunner] renders through
 * `templates`' engine and runs through `datasources`' pools. The validator calls it exactly where
 * §4 says and REQUIRES it (lane C retired the `selector_probe_unavailable` stand-in): a set whose
 * sources cannot be proven is not saved (the owner's refuse-at-the-entry-point principle). A
 * `constants`-only set never calls it.
 *
 * ## The contract an implementation keeps
 *
 * - **Never throws for an author's problem.** A render failure, an unreachable datasource and a
 *   failing statement are outcomes the validator reports with their own codes; an exception would
 *   abort the exhaustive collection and surface as a 500.
 * - **Read-only, bounded.** [probe] runs one statement through the same read-only gate the
 *   `sql_probe` tool runs (`SqlStatementClassifier`: a single `SELECT`/`WITH`), with the
 *   datasource's timeout clamped to `selector-query-timeout-seconds`, reading at most [maxRows]
 *   rows. Nothing is ever executed from an author's text except through this port, and the SQL is
 *   the RENDERED template with its `:name` binds sent as binds — never a value spliced into text.
 * - **Types from metadata.** [SelectorProbeOutcome.Probed.columns] is the result set's schema read
 *   through `ResultRowReader.schemaOf` — record §6.4 decides type compatibility from it, never from
 *   the rows (P11).
 * - **Outside the metadata transaction.** A probe opens a customer-datasource connection;
 *   `ConnectionLease` refuses one while a metadata transaction is open on the thread
 *   (`datasource.lease_in_transaction`), so callers probe BEFORE they open theirs (release does).
 */
interface SelectorProbe {
    /**
     * Renders [template] (resolved in [workspaceId]) against [context] — the parents' values, one
     * `<name>_count` per `MULTI` parent, the org tier and the platform tier (record §4 step 5).
     */
    fun render(
        workspaceId: UUID,
        template: TemplateRef,
        context: Map<String, Any?>,
    ): SelectorRender

    /**
     * Runs [sql] (a rendered selector) on the datasource named [datasource] as seen from
     * [workspaceId], binding [binds] by name, reading at most [maxRows] rows (record §4 step 6).
     */
    fun probe(
        workspaceId: UUID,
        datasource: String,
        sql: String,
        binds: Map<String, Any?>,
        maxRows: Int = SAVE_TIME_MAX_ROWS,
    ): SelectorProbeOutcome

    companion object {
        /** Record §4 step 6: two rows prove a column shape and an `INPUT`'s at-most-one-row rule. */
        const val SAVE_TIME_MAX_ROWS: Int = 2
    }
}

/** What a dry render produced (record §4 step 5). */
sealed interface SelectorRender {
    /** The rendered statement, `:name` binds intact. */
    data class Rendered(
        val sql: String,
    ) : SelectorRender

    /** The render failed — `parameter.validation.template_render_failed`; [detail] is safe to echo. */
    data class Failed(
        val detail: String,
    ) : SelectorRender
}

/** What a metadata execution produced (record §4 step 6). */
sealed interface SelectorProbeOutcome {
    /**
     * The statement ran: its result-set [columns] (canonical, from metadata) and up to `maxRows`
     * [rows], each a list of canonical values in column order.
     */
    data class Probed(
        val columns: List<ColumnSchema>,
        val rows: List<List<Any?>>,
    ) : SelectorProbeOutcome

    /** The datasource could not be reached — `parameter.validation.datasource_unreachable`. */
    data class Unreachable(
        val detail: String,
    ) : SelectorProbeOutcome

    /**
     * The datasource answered and the statement failed (a column, a permission, the read-only gate)
     * — `parameter.validation.selector_query_failed` with [datasourceCode] (owner ruling 2026-09-26).
     */
    data class StatementFailed(
        val datasourceCode: String,
        val detail: String,
    ) : SelectorProbeOutcome
}
