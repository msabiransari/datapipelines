package co.datapipelines.executor

import java.time.Instant
import java.util.UUID

/**
 * What a node is doing overall (149; rest-api §6.4.9 `node_progress.operation`).
 *
 * One operation per node attempt. The kind says which measured boundaries exist for it — a
 * `CTAS` has no fetch/write alternation to observe (the engine does both inside one statement),
 * a `STATEMENT` has no rows to fetch, a `CHILD` is a whole child execution followed by the
 * parent's own output write. CALCULATOR nodes have no operation: nothing crosses a wire.
 */
enum class OperationKind(
    val wire: String,
) {
    /** A source cursor drained into a tempdb table. */
    STAGE("stage"),

    /** tempdb `CREATE TABLE … AS` — query and materialisation are one indivisible statement. */
    CTAS("ctas"),

    /** The caller result drained into the result store, or `direct` to an invoking parent. */
    MATERIALIZE("materialize"),

    /** A cursor written into an external datasource table in one transaction. */
    WRITEBACK("writeback"),

    /** A DML/DDL statement executed against tempdb or a datasource. */
    STATEMENT("statement"),

    /** A PIPELINE node: the child execution, then the parent's own output write. */
    CHILD("child"),
}

/**
 * The measured phases of an operation (§3 of the 149 addendum). Each is an interval at the
 * executor's own boundary — wall time, never engine CPU.
 */
enum class OperationPhase(
    val wire: String,
) {
    /** A SOURCE connection is being obtained (pool checkout, or a tempdb read lease). */
    CONNECTING("connecting"),

    /** The statement is submitted and has not returned. A returned cursor is not a fetched row. */
    EXECUTING("executing"),

    /** Advancing the source cursor / decoding rows for the next batch. */
    FETCHING("fetching"),

    /** An OUTPUT connection was requested and is not yet held. */
    WAITING_OUTPUT("waiting_output"),

    /** Inside a destination write, holding its connection. */
    WRITING("writing"),

    /** Commit, row count, budget check, result meta write. */
    FINALIZING("finalizing"),
}

/** How an operation ended. Exactly one per started operation. */
enum class OperationOutcome(
    val wire: String,
) {
    COMPLETED("completed"),
    FAILED("failed"),
    ABORTED("aborted"),
}

/**
 * The single lifecycle `state` field on the wire: every [OperationPhase] while running, then
 * one [OperationOutcome]. One field, so a client reduces one thing.
 */
enum class OperationState(
    val wire: String,
) {
    CONNECTING("connecting"),
    EXECUTING("executing"),
    FETCHING("fetching"),
    WAITING_OUTPUT("waiting_output"),
    WRITING("writing"),
    FINALIZING("finalizing"),
    COMPLETED("completed"),
    FAILED("failed"),
    ABORTED("aborted"),
    ;

    companion object {
        fun of(phase: OperationPhase): OperationState = entries.first { it.wire == phase.wire }

        fun of(outcome: OperationOutcome): OperationState = entries.first { it.wire == outcome.wire }
    }
}

/**
 * The REAL output destination of an operation — identifiers only, never a connection string
 * (observability §9.3). `table` is absent on a DML/DDL statement: the executor does no SQL
 * lineage inference.
 */
data class OperationDestination(
    val kind: Kind,
    val datasource: String? = null,
    val table: String? = null,
) {
    enum class Kind(
        val wire: String,
    ) {
        TEMPDB("tempdb"),
        DATASOURCE("datasource"),
        CALLER("caller"),

        /** A child's caller rows streamed to the invoking PIPELINE node. */
        PARENT("parent"),

        /** DDL, or a node that writes nothing. */
        NONE("none"),
    }

    companion object {
        val NONE = OperationDestination(Kind.NONE)
        val CALLER = OperationDestination(Kind.CALLER)
        val PARENT = OperationDestination(Kind.PARENT)

        fun tempdb(table: String? = null) = OperationDestination(Kind.TEMPDB, table = table)

        fun datasource(
            name: String,
            table: String? = null,
        ) = OperationDestination(Kind.DATASOURCE, datasource = name, table = table)
    }
}

/**
 * One observation of an operation — what a `node_progress` event carries (rest-api §6.4.9).
 *
 * Counts are nullable and mean "not observed" when null: a CTAS knows no fetched rows, DDL
 * writes none. [committed] is non-null ONLY on a terminal sample; there is no "committed"
 * before the commit. [timingsMs] holds the phases actually observed, the open one counted up
 * to [observedAt].
 */
data class OperationSnapshot(
    val nodeId: String,
    val attempt: Int,
    val sequence: Int,
    val kind: OperationKind,
    val destination: OperationDestination,
    val state: OperationState,
    val startedAt: Instant,
    val observedAt: Instant,
    val elapsedMs: Long,
    val timingsMs: Map<OperationPhase, Long>,
    val rowsFetched: Long?,
    val rowsWritten: Long?,
    val batchesWritten: Long,
    val committed: Boolean?,
    val rolledBack: Boolean?,
    val childExecutionId: UUID?,
) {
    val isTerminal: Boolean
        get() = state == OperationState.COMPLETED || state == OperationState.FAILED || state == OperationState.ABORTED
}

/**
 * What the writers (staging, write-back, result store, the composition sink) report to the
 * tracker — plain, non-blocking calls that record a boundary and return. Never invoked under a
 * pool metadata lock; never suspends; never throws.
 */
interface OperationObserver {
    /** The operation entered [phase] now. Re-entering the current phase is a no-op. */
    fun phase(phase: OperationPhase)

    /** [rows] more rows were read off the source (or received from the child). */
    fun fetched(rows: Long)

    /** [rows] more rows were ACCEPTED by the destination in one batch. */
    fun written(rows: Long)

    /** The destination transaction committed. */
    fun committed()

    /** The destination write was rolled back (or the partial table dropped). */
    fun rolledBack()

    companion object {
        /** Records nothing — every fixture and every caller that has no tracker. */
        val NONE: OperationObserver =
            object : OperationObserver {
                override fun phase(phase: OperationPhase) = Unit

                override fun fetched(rows: Long) = Unit

                override fun written(rows: Long) = Unit

                override fun committed() = Unit

                override fun rolledBack() = Unit
            }
    }
}
