package co.datapipelines.pipeline.release

import org.springframework.transaction.TransactionStatus
import java.util.UUID

/**
 * One family's release of one exact version inside a cascade (record §4.2), split at the transaction boundary:
 *
 * - [prepare] runs OUTSIDE any metadata transaction: the family's native structural/source validation and its gates
 *   (a pipeline's fresh release checks, which open customer connections) against the exact snapshot node. It writes no
 *   lifecycle state; diagnostic records (check runs) may persist on refusal.
 * - [commitPrepared] runs INSIDE the coordinator's one transaction, after the workspace guard: the compare-and-set flip of
 *   exactly that version at exactly the consented body hash, plus any gate the record keeps in-transaction.
 *
 * The coordinator calls each once per node (a diamond's shared node is prepared and committed once), dependencies before
 * dependants. S1 ships the pipeline and a minimal template participant; S2–S5 the rest (handback: predeclared names).
 */
interface ReleaseParticipant {
    val family: ReleaseFamily

    fun prepare(
        node: ClosureNode,
        context: ReleaseContext,
    ): PreparedRelease

    fun commitPrepared(
        prepared: PreparedRelease,
        transaction: TransactionStatus,
    ): ReleaseFlip
}

/** What [ReleaseParticipant.prepare] knows about the release it serves. */
data class ReleaseContext(
    val actor: UUID,
    val closure: ReleaseClosure,
    /** The versions this release flips: the selected dependencies and the root. */
    val flipping: Set<ReleaseNodeIdentity>,
    /** The hash the node flips at: the root's `If-Match`, a dependency's consented (snapshot) body hash. */
    val expectedHash: String,
    /** The node's own check-override reason — a root reason never waives a dependency's check (record §4.1 step 4). */
    val checkOverrideReason: String?,
)

/** A prepared node; families extend it with what their commit needs. */
open class PreparedRelease(
    val node: ClosureNode,
    val expectedHash: String,
)

/** One flip that happened: the node, and the family's own result for the response (the root's) and the audit row. */
data class ReleaseFlip(
    val node: ClosureNode,
    val result: Any?,
)
