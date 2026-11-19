package co.datapipelines.executor

import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeSource
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineNodeRef
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.typesystem.Dialect
import java.time.Instant
import java.util.UUID

/**
 * The preflight write-set planner (pipeline-write-transactions §3.1-3.2): before any business
 * SQL, walk the actual pinned graph and its children, collect every declared durable
 * destination, and answer whether this run enrolls one local write transaction.
 *
 * ## What counts as a durable destination
 *
 *  - a `DML` node whose `source` names a registered datasource — the declared writer;
 *  - a `DQL` node whose `output.target` is `datasource` — external row write (write-back);
 *  - a `PIPELINE` node whose `output.target` is `datasource` — a child's write-back;
 *  - a `DDL` node whose `source` names a registered datasource — recorded SEPARATELY: DDL
 *    never enrolls, but an external DDL node in a plan that has a writer refuses the run.
 *
 * `tempdb` is never a durable target (execution-private staging), so a `DML`/`DDL` node on
 * `tempdb` does not count. Names are counted as registrations the way the design states:
 * unique by NAME — two names for the same server are two registrations.
 *
 * ## The verdict
 *
 *  - [LocalWriteVerdict.Ordinary] — no ELIGIBLE writer anywhere: pure reads, staging,
 *    excluded-only writers (H2/SQLite/DuckDB/LAKE) and DDL-only plans keep today's execution
 *    path, with no lease.
 *  - [LocalWriteVerdict.Enroll] — exactly one registration holds every eligible writer (and
 *    every other destination); the run leases one transaction on it (§3.3).
 *  - [LocalWriteVerdict.Refuse] — an eligible writer participates AND the plan has a second
 *    durable destination (eligible, excluded or external DDL), an external DDL node, or a
 *    child whose body could not be read. Refusal happens before any business write (§4 row
 *    1); a run already accepted ends FAILED at run admission.
 *
 * An unresolvable WRITER name beside an eligible one is a second durable destination whose
 * eligibility is unknown — refused. Alone, it stays [Ordinary][LocalWriteVerdict.Ordinary]:
 * the node fails at execution with today's `pipeline.node.datasource_not_found`, exactly as
 * without this feature.
 *
 * ## The walk
 *
 * Iterative with an explicit stack, a depth bound and a per-reference `expandedAt` map,
 * copying [co.datapipelines.application.endpoints.ReadOnlyPipelineRule]'s shape (which itself
 * copies `CompositionRules`): a deep reference tree must not exhaust the JVM stack, a shared
 * child is re-walked only when a strictly longer path reaches it, and a child that cannot be
 * expanded is recorded — never skipped — because "could not read it" must never be reported
 * as "it writes nowhere". That is fail-closed for a plan that already holds an eligible
 * writer; a plan with none stays ordinary and lets the run fail where it fails today.
 */
class LocalWritePlanner(
    private val pipelines: PipelineResolver,
    private val datasources: DatasourceRegistry,
    private val maxCompositionDepth: Int,
) {
    /**
     * The plan for [pipeline] as the root of a run in [workspaceId] (§3.1-3.2). Eligibility
     * of each declared name is resolved against the LIVE registry once, here: this is run
     * admission (§4 row 1 — "recheck … datasource capabilities … at run admission"), not
     * save-time validation. A later repoint cannot change this verdict retroactively; enlist
     * snapshots what the registration points to THEN.
     *
     * `NestedBlockDepth` is suppressed for this function: the walk IS one iterative loop whose
     * depth is the graph's — stack + node + branch, `ReadOnlyPipelineRule`'s shape.
     */
    @Suppress("NestedBlockDepth")
    fun plan(
        pipeline: Pipeline,
        workspaceId: UUID,
    ): LocalWriteVerdict {
        val writers = mutableListOf<Destination>()
        val rowWrites = mutableListOf<Destination>()
        val externalDdl = mutableListOf<Destination>()
        val unreadableChildren = mutableListOf<String>()
        val expandedAt = HashMap<PipelineNodeRef, Int>()
        val stack = ArrayDeque<Walked>()
        stack.addLast(Walked(pipeline, depth = 1, trail = emptyList()))

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            for (node in current.pipeline.nodes) {
                when (node.type) {
                    NodeType.PIPELINE -> {
                        (node.output as? NodeOutput.Datasource)?.let {
                            rowWrites += Destination(it.datasource, node.id, current.trail)
                        }
                        expand(node, current, workspaceId, expandedAt, unreadableChildren)?.let(stack::addLast)
                    }

                    NodeType.DDL -> {
                        (node.resolvedSource as? NodeSource.Datasource)?.let {
                            externalDdl += Destination(it.name, node.id, current.trail)
                        }
                    }

                    NodeType.DML -> {
                        (node.resolvedSource as? NodeSource.Datasource)?.let {
                            writers += Destination(it.name, node.id, current.trail)
                        }
                    }

                    NodeType.DQL -> {
                        (node.output as? NodeOutput.Datasource)?.let {
                            rowWrites += Destination(it.datasource, node.id, current.trail)
                        }
                    }

                    else -> {
                        // CALCULATOR and SCRIPT touch no datasource (§4.10/§4.12) — nothing to collect.
                    }
                }
            }
        }
        val eligibility =
            (writers + rowWrites + externalDdl)
                .map { it.registration }
                .toSet()
                .associateWith { name -> eligibleIn(datasources.getVisible(name, workspaceId)) }
        return verdict(writers, rowWrites, externalDdl, unreadableChildren, eligibility)
    }

    /**
     * The verdict over the collected destinations and their resolved eligibility — pure, so
     * every rule below is unit-testable without a registry or a resolver.
     */
    @Suppress("ReturnCount") // a decision table reads top-to-bottom: one return per verdict row, no wrapping
    internal fun verdict(
        writers: List<Destination>,
        rowWrites: List<Destination>,
        externalDdl: List<Destination>,
        unreadableChildren: List<String>,
        eligibility: Map<String, Boolean>,
    ): LocalWriteVerdict {
        val everyName = (writers + rowWrites + externalDdl).map { it.registration }.toSet()
        // R1: eligibility is decided by ROW writers alone. External DDL never enrolls a
        // transaction (§3.2: "DDL never enrolls"), so a DDL-only plan — however eligible its
        // engine — must stay ordinary rather than trip the `external_ddl` refusal below. The DDL
        // refusal fires only when an eligible row writer is already participating.
        val hasEligibleRowWriter = (writers + rowWrites).map { it.registration }.toSet().any { eligibility[it] == true }
        if (!hasEligibleRowWriter) return LocalWriteVerdict.Ordinary

        // Every distinct name here is a counted registration (§3.1: "two names for the same
        // server are two registrations"), whatever its engine — an excluded or unresolvable
        // second destination refuses an eligible writer exactly like an eligible one.
        if (everyName.size > 1) {
            return LocalWriteVerdict.Refuse(
                reason = "second_durable_destination",
                details =
                    mapOf(
                        "registrations" to everyName.sorted(),
                        "writers" to writers.map { it.describe() },
                        "row_writes" to rowWrites.map { it.describe() },
                        "external_ddl" to externalDdl.map { it.describe() },
                    ),
            )
        }
        if (externalDdl.isNotEmpty()) {
            return LocalWriteVerdict.Refuse(
                reason = "external_ddl",
                details = mapOf("external_ddl" to externalDdl.map { it.describe() }),
            )
        }
        if (unreadableChildren.isNotEmpty()) {
            return LocalWriteVerdict.Refuse(
                reason = "unknown_child_writes",
                details = mapOf("children" to unreadableChildren),
            )
        }
        return LocalWriteVerdict.Enroll(everyName.single())
    }

    /**
     * The child frame a `PIPELINE` node adds to the walk, or null — already expanded at least
     * this deep, or not expandable at all, in which case [unreadable] carries the reason.
     */
    @Suppress("ReturnCount") // each "cannot expand" reason is its own record, and each names itself
    private fun expand(
        node: Node,
        current: Walked,
        workspaceId: UUID,
        expandedAt: MutableMap<PipelineNodeRef, Int>,
        unreadable: MutableList<String>,
    ): Walked? {
        val childDepth = current.depth + 1
        val ref = node.pipeline
        if (ref == null) {
            unreadable += "node '${node.id}' carries no pipeline reference"
            return null
        }
        if (childDepth > maxCompositionDepth) {
            unreadable += "node '${node.id}' is deeper than the composition limit of $maxCompositionDepth"
            return null
        }
        val child = pipelines.resolve(workspaceId, ref.name, ref.version)?.pipeline
        if (child == null) {
            unreadable += "node '${node.id}' pins '${ref.name}' v${ref.version}, which is not in the registry"
            return null
        }
        // A shared child is re-walked only when a strictly longer path reaches it, which with
        // the depth bound caps the whole walk at `references × maxDepth` resolutions.
        if ((expandedAt[ref] ?: 0) >= childDepth) return null
        expandedAt[ref] = childDepth
        return Walked(child, childDepth, current.trail + node.id)
    }

    /**
     * Opens the [Enroll][LocalWriteVerdict.Enroll] verdict's transaction (§3.3): the lease
     * bound to this root, this workspace and the planned registration. Born here — the
     * write-set authority is the transaction's factory — so the executor never has to hold
     * the registry just to construct one. [deadline] is the execution's wall-clock bound (R7):
     * the transaction refuses an acquisition that would begin or return past it.
     */
    fun open(
        verdict: LocalWriteVerdict.Enroll,
        executionId: UUID,
        workspaceId: UUID,
        metrics: ExecutorMetrics,
        dispatcher: ExecutorDispatcher,
        deadline: Instant? = null,
    ): LocalWriteTransaction =
        LocalWriteTransaction(
            rootExecutionId = executionId,
            workspaceId = workspaceId,
            registration = verdict.registration,
            registry = datasources,
            metrics = metrics,
            dispatcher = dispatcher,
            deadline = deadline,
        )

    private fun eligibleIn(datasource: co.datapipelines.datasources.Datasource?): Boolean =
        datasource != null && datasource.dialect in ELIGIBLE_DIALECTS

    /** One declared durable destination: a registration name, the node that declared it, and how to reach that node. */
    data class Destination(
        val registration: String,
        val nodeId: String,
        val trail: List<String>,
    ) {
        internal fun describe(): Map<String, Any?> =
            mapOf(
                "registration" to registration,
                "node_id" to nodeId,
                "via" to trail,
            )
    }

    private data class Walked(
        val pipeline: Pipeline,
        val depth: Int,
        val trail: List<String>,
    )

    companion object {
        /**
         * The engines the single-database guarantee is qualified for (§3.2: PostgreSQL, MySQL,
         * SQL Server, Oracle — completion requires all four per §5). H2, SQLite, DuckDB and
         * dp-lake are excluded from this feature and never enroll.
         */
        val ELIGIBLE_DIALECTS: Set<Dialect> =
            setOf(Dialect.POSTGRES, Dialect.MYSQL, Dialect.MSSQL, Dialect.ORACLE)
    }
}

/** What run admission does with the plan (§3.2). */
sealed interface LocalWriteVerdict {
    /** No eligible writer: today's execution path, zero pipeline-transaction leases (§9). */
    data object Ordinary : LocalWriteVerdict

    /** One registration holds every eligible write: enroll one local transaction on it (§3.3). */
    data class Enroll(
        val registration: String,
    ) : LocalWriteVerdict

    /**
     * An eligible writer participates, but the write set is not single-destination. The
     * executor refuses the run before any business write; [reason] is one of
     * `second_durable_destination` / `external_ddl` / `unknown_child_writes`.
     */
    data class Refuse(
        val reason: String,
        val details: Map<String, Any?>,
    ) : LocalWriteVerdict
}
