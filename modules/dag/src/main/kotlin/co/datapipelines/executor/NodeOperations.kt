package co.datapipelines.executor

import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeSource
import co.datapipelines.pipeline.NodeType
import co.datapipelines.staging.StageObserver
import kotlinx.coroutines.sync.Mutex
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap

/**
 * One execution's operation trackers (149), keyed by node id — the registry the runner writes
 * into and the executor's pump and terminal flush read from.
 *
 * Per execution, not per runner: one [NodeRunner] serves every execution on the instance, and
 * two nodes writing at once are two trackers that never touch one another (§2 of the addendum:
 * no global phase field). Defaulted on [NodeExecutionContext] so every construction site and
 * fixture is unchanged; an executor that never starts the pump simply never emits.
 *
 * @param sampleIntervalMs the periodic-sample cadence — the executor passes
 *   `progress-sample-interval-seconds` (configuration §3.2).
 */
class NodeOperations(
    private val sampleIntervalMs: Long = DEFAULT_SAMPLE_INTERVAL_MS,
) {
    private val trackers = ConcurrentHashMap<String, Tracked>()

    /** A tracker plus the lock that serialises the pump against the terminal flush (§4). */
    class Tracked(
        val tracker: NodeOperationTracker,
    ) {
        val publishLock = Mutex()

        @Volatile
        var terminalPublished = false
    }

    /** Starts [nodeId]'s operation. A second begin for the same node keeps the first tracker. */
    fun begin(
        nodeId: String,
        kind: OperationKind,
        destination: OperationDestination,
    ): NodeOperationTracker =
        trackers
            .computeIfAbsent(nodeId) {
                Tracked(
                    NodeOperationTracker(
                        nodeId = nodeId,
                        attempt = FIRST_ATTEMPT,
                        kind = kind,
                        destination = destination,
                        sampleIntervalMs = sampleIntervalMs,
                    ),
                )
            }.tracker

    fun tracked(nodeId: String): Tracked? = trackers[nodeId]

    fun tracker(nodeId: String): NodeOperationTracker? = trackers[nodeId]?.tracker

    /** The observer the writers report to — [OperationObserver.NONE] for a node with no operation. */
    fun observerFor(nodeId: String): OperationObserver = tracker(nodeId) ?: OperationObserver.NONE

    fun all(): Collection<Tracked> = trackers.values

    companion object {
        const val DEFAULT_SAMPLE_INTERVAL_MS = 1_000L

        /** v1 has no per-node retries (dag-executor §11.1): the attempt `node_started` carries. */
        const val FIRST_ATTEMPT = 1

        /**
         * Which operation a SQL node runs and where its output goes (addendum §1) — decided from
         * the node's shape alone, before any connection is leased.
         */
        fun operationFor(node: ExecutableNode): Pair<OperationKind, OperationDestination> =
            when (node.type) {
                NodeType.DQL -> dqlOperation(node)
                NodeType.DML -> OperationKind.STATEMENT to statementDestination(node)
                NodeType.DDL -> OperationKind.STATEMENT to OperationDestination.NONE
                NodeType.PIPELINE -> OperationKind.CHILD to destinationOf(node.output)
                NodeType.CALCULATOR -> error("a CALCULATOR node has no operation")
                NodeType.TRANSFORM -> transformOperation(node)
            }

        /**
         * A TRANSFORM node's operation follows its output (§4.12): a tempdb write is a STAGE,
         * the caller result is a MATERIALIZE. A value-mode node writes only a Context key — no
         * operation, exactly like a CALCULATOR — and a datasource output is unreachable
         * (§12.13 refuses it at save).
         */
        private fun transformOperation(node: ExecutableNode): Pair<OperationKind, OperationDestination> =
            when (val output = node.output) {
                is NodeOutput.Tempdb -> OperationKind.STAGE to destinationOf(output)
                NodeOutput.Caller -> OperationKind.MATERIALIZE to OperationDestination.CALLER
                is NodeOutput.Datasource, null -> error("a value-mode TRANSFORM node has no operation")
            }

        private fun dqlOperation(node: ExecutableNode): Pair<OperationKind, OperationDestination> =
            when (val output = node.output) {
                is NodeOutput.Tempdb -> {
                    val kind = if (node.source is NodeSource.Tempdb) OperationKind.CTAS else OperationKind.STAGE
                    kind to destinationOf(output)
                }

                is NodeOutput.Datasource -> {
                    OperationKind.WRITEBACK to destinationOf(output)
                }

                NodeOutput.Caller, null -> {
                    OperationKind.MATERIALIZE to OperationDestination.CALLER
                }
            }

        /** A DML statement writes INTO its source; no table is inferred from the SQL. */
        private fun statementDestination(node: ExecutableNode): OperationDestination =
            when (val source = node.source) {
                is NodeSource.Tempdb -> OperationDestination.tempdb()
                is NodeSource.Datasource -> OperationDestination.datasource(source.name)
            }

        fun destinationOf(output: NodeOutput?): OperationDestination =
            when (output) {
                is NodeOutput.Tempdb -> OperationDestination.tempdb(output.table)
                is NodeOutput.Datasource -> OperationDestination.datasource(output.datasource, output.table)
                NodeOutput.Caller -> OperationDestination.CALLER
                null -> OperationDestination.NONE
            }
    }
}

/**
 * Bridges staging's own observer type (staging cannot depend on `dag`) onto the tracker, and
 * keeps the 108 §D rows-so-far sink fed from the same batch boundary it always was. Public
 * because the composition runner in `web` stages a child's rows through the same bridge.
 */
class StagingObserverBridge(
    private val nodeId: String,
    private val observer: OperationObserver,
    private val rowsSoFar: NodeProgressSink,
) : StageObserver {
    override fun fetchStarted() = observer.phase(OperationPhase.FETCHING)

    override fun fetchFinished(rows: Int) = observer.fetched(rows.toLong())

    override fun connectionRequested() = observer.phase(OperationPhase.WAITING_OUTPUT)

    override fun connectionAcquired() = observer.phase(OperationPhase.WRITING)

    override fun batchWritten(
        rows: Int,
        rowsSoFar: Long,
    ) {
        observer.written(rows.toLong())
        this.rowsSoFar.staged(nodeId, rowsSoFar)
    }

    override fun partialTableDropped() = observer.rolledBack()
}

/**
 * A cursor whose advancement is measured (addendum §3): every `next()` is a FETCHING boundary,
 * and rows are counted as fetched in bounded reports — the caller-materialisation path, where
 * the store consumes the cursor and only the executor can see it advance.
 */
internal class FetchObservingResultSet(
    private val delegate: ResultSet,
    private val observer: OperationObserver,
    private val reportEvery: Int = REPORT_EVERY,
) : ResultSet by delegate {
    private var unreported = 0

    override fun next(): Boolean {
        observer.phase(OperationPhase.FETCHING)
        val more = delegate.next()
        if (more) {
            unreported++
            if (unreported >= reportEvery) flush()
        } else {
            flush()
        }
        return more
    }

    override fun close() {
        flush()
        delegate.close()
    }

    private fun flush() {
        if (unreported > 0) {
            observer.fetched(unreported.toLong())
            unreported = 0
        }
    }

    private companion object {
        const val REPORT_EVERY = 1_000
    }
}
