package co.datapipelines.lake.settle

import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.definitions.LakeDefinitionApplier
import co.datapipelines.persistence.FailureShape
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Duration
import java.util.UUID

/**
 * Bounded normal-mode recovery; durable revisions remain protected until an observation sees their supersession.
 *
 * Per-tick work bound (record §8b; lane 799b): one [SettleBudget] of [SWEEP_BUDGET] per tick, minted after the tick's
 * first mode read and consulted before the protection page, the table page, every table (before and after its mode
 * read), every delivery row and statement group, the projection repair, the applying verification and the semantics
 * repair. A tick visits a rotating page of at most [PAGE_SIZE] managed tables and, per table, one keyset page of at most
 * [rowPage] unsettled deliveries after that table's row cursor (D1), which advances past visited rows — live, busy and
 * failing ones included — and wraps when a page comes back short. Once the budget is spent no new step starts; what
 * still runs is the statement group under way, the release of a lease it took over or the deadline comparison of a
 * refused takeover, and the `last_sweep_at` heartbeat: with five-second statement timeouts, at most 20 seconds past
 * the budget (docs/lake.md §8). A verification or semantics repair that started inside the budget runs to its end.
 * [rowPage] and [budgets] are test seams; pass [budgets] by name.
 */
class LakeSettlementSweep(
    private val jdbc: NamedParameterJdbcTemplate,
    private val leases: LakeLeases,
    private val settler: DeliverySettler,
    private val applier: LakeDefinitionApplier,
    private val semantics: LakeSemanticsRepair,
    private val rowPage: Int = SETTLE_PAGE_SIZE,
    private val budgets: () -> SettleBudget = { SettleBudget.startingNow(leases, SWEEP_BUDGET) },
) {
    private var after: UUID? = null

    /** Last visited `lake_deliveries.id` per table; an absent entry starts that table's page at its first row. */
    private val rowCursors = HashMap<UUID, UUID>()

    /** Tables holding a row cursor after their visit in the current table pass; a finished pass drops every other cursor. */
    private val cursorsSeen = HashSet<UUID>()

    init {
        require(jdbc.jdbcTemplate.queryTimeout > 0) { "settlement sweep JDBC must be bounded" }
        require(rowPage > 0) { "settlement row page must be positive: $rowPage" }
    }

    /** One finite page per tick, rotating through managed tables without starving the shared scheduled thread. */
    fun runOnce() {
        if (!normal()) return
        val budget = budgets()
        if (!budget.expired()) finalizeProtection()
        // Null when the budget was spent before the table page: nothing was visited, so the pass neither wraps nor forgets.
        val tables = if (budget.expired()) null else tablePage()
        var visited = 0
        var rows = 0
        var changed = 0
        for (table in tables.orEmpty()) {
            // Checked again after the mode read: that statement may be the one that spent the budget.
            if (budget.expired() || !normal() || budget.expired()) break
            val progress = recover(table, budget)
            if (rowCursors.containsKey(table)) cursorsSeen += table
            rows += progress.visited
            changed += progress.changed
            after = table
            visited++
        }
        val exhausted = budget.expired()
        if (tables != null && visited == tables.size && tables.size < PAGE_SIZE) {
            after = null
            rowCursors.keys.retainAll(cursorsSeen)
            cursorsSeen.clear()
        }
        jdbc.update(
            "UPDATE lake_control SET last_sweep_at = clock_timestamp() WHERE id = 1 AND mode = :mode",
            mapOf("mode" to LakeProperties.MODE_NORMAL),
        )
        log.info(
            "event=lake.settle.sweep tables={} rows={} changed={} exhausted={} cause=none sql_state=none",
            visited,
            rows,
            changed,
            exhausted,
        )
    }

    private fun tablePage(): List<UUID> =
        jdbc.query(
            "SELECT id FROM lake_tables_managed WHERE (CAST(:after AS uuid) IS NULL OR id > CAST(:after AS uuid)) " +
                "ORDER BY id LIMIT :limit",
            mapOf("after" to after, "limit" to PAGE_SIZE),
        ) { rows, _ -> rows.getObject("id", UUID::class.java) }

    /**
     * Autocommit visibility, rather than a trigger timestamp, starts the full retention window. At most [PAGE_SIZE]
     * revisions per statement; an unobserved supersession stays protected (NULL) until a later tick observes it.
     */
    fun finalizeProtection(): Int =
        jdbc.update(
            "UPDATE lake_table_revisions r SET protected_until = now() + c.protection_interval FROM lake_control c " +
                "WHERE c.id = 1 AND c.mode = :mode AND r.superseded_at IS NOT NULL AND r.protected_until IS NULL " +
                "AND r.seq IN (SELECT seq FROM lake_table_revisions WHERE superseded_at IS NOT NULL AND protected_until IS NULL " +
                "ORDER BY seq LIMIT :limit)",
            mapOf("mode" to LakeProperties.MODE_NORMAL, "limit" to PAGE_SIZE),
        )

    @Suppress("TooGenericExceptionCaught") // One busy/broken table cannot prevent recovery of the next page.
    private fun recover(
        table: UUID,
        budget: SettleBudget,
    ): SettleProgress {
        var progress = SettleProgress(0, 0, rowCursors[table])
        try {
            progress = settler.settle(table, budget, rowCursors[table], rowPage)
            advance(table, progress)
            if (!budget.expired()) applier.verifyApplying(table, budget.deadline)
            if (!budget.expired()) semantics.repair(table, budget.deadline)
        } catch (deferred: SettlementDeferred) {
            progress = deferred.progress
            advance(table, progress)
            logDeferred(table, deferred.cause ?: deferred)
        } catch (failure: Exception) {
            logDeferred(table, failure)
        }
        return progress
    }

    private fun advance(
        table: UUID,
        progress: SettleProgress,
    ) {
        val next = progress.resumeAfter
        if (next == null) {
            rowCursors.remove(table)
        } else {
            rowCursors[table] = next
        }
    }

    private fun logDeferred(
        table: UUID,
        failure: Throwable,
    ) {
        log.warn(
            "event=lake.settle.deferred table_id={} cause={} sql_state={}",
            table,
            FailureShape.cause(failure),
            FailureShape.sqlState(failure),
        )
    }

    private fun normal(): Boolean =
        jdbc.queryForObject(
            "SELECT mode = :mode FROM lake_control WHERE id = 1",
            mapOf("mode" to LakeProperties.MODE_NORMAL),
            Boolean::class.java,
        ) == true

    companion object {
        /** Managed tables per tick. */
        const val PAGE_SIZE = 100

        /**
         * Unsettled deliveries per table per tick (D1). A row costs at most nine statements (a PUBLISHING takeover: the
         * observation, the acquire's two, the evidence read, the four-statement conclusion transaction with its strict
         * audit rows, and the release), so a page is at most 450: far inside [SWEEP_BUDGET] at healthy statement
         * latencies, and past it only on a stalled server, where the per-row and per-group budget checks stop the page.
         */
        const val SETTLE_PAGE_SIZE = 50

        /** The tick's work budget, minted from the database clock with its monotonic mirror (D2). */
        val SWEEP_BUDGET: Duration = Duration.ofSeconds(10)

        private val log = LoggerFactory.getLogger(LakeSettlementSweep::class.java)
    }
}
