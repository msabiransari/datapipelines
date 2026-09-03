package co.datapipelines.application

import co.datapipelines.executor.IdempotencyKeys
import co.datapipelines.executor.IdempotencyOutcome
import co.datapipelines.executor.IdempotencyStore
import co.datapipelines.pipeline.ParameterBinder
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

/**
 * Everything the launcher needs to decide how one execution starts — the surface has already
 * authenticated the caller and found the pipeline row.
 *
 * [parametersJson] is the request's parameter object as the client sent it: it is what the
 * idempotency request hash is computed over, so it must be the wire text, not a re-serialization
 * of [parameters].
 */
data class ExecutionLaunch(
    val pipelineId: UUID,
    val pipelineVersion: Int,
    val pipeline: Pipeline,
    val userId: UUID,
    val parameters: Map<String, JsonNode>,
    val parametersJson: String,
    val idempotencyKey: String?,
)

/** What the launcher decided a surface should do (dag-executor.md §11.2). */
sealed interface LaunchDecision {
    /**
     * Run it. [executionId] is the id the idempotency reservation already claimed — pass it
     * to `ExecuteRequest.executionId` so the store maps directly onto the real execution with
     * no alias layer. Null when the caller sent no `Idempotency-Key`.
     */
    data class Start(
        val executionId: UUID?,
    ) : LaunchDecision

    /**
     * Do NOT run it: this key already ran, and [executionId] is the original. REST attaches to
     * that execution's event log; MCP reports the original execution's stored outcome.
     */
    data class Attach(
        val executionId: UUID,
    ) : LaunchDecision
}

/** The two idempotency counters a surface may want; supplying none is legal (see [NONE]). */
interface IdempotencyMetrics {
    /** A request served from a stored reservation instead of executing. */
    fun reservationHit()

    /** An `idempotency.key_reused_for_different_request` rejection. */
    fun reservationConflict()

    companion object {
        /** No instrumentation — the module-slice wiring and every test that does not assert counters. */
        val NONE: IdempotencyMetrics =
            object : IdempotencyMetrics {
                override fun reservationHit() = Unit

                override fun reservationConflict() = Unit
            }
    }
}

/**
 * The **cross-aggregate** half of "execute a pipeline" (ARCH-AUDIT-2026-08 D6, ruling R6): bind the
 * parameters and settle the idempotency reservation — before any surface opens a stream, builds an
 * executor or writes a row.
 *
 * ## Why it lives in `modules/application`
 *
 * It needs `pipeline-contract` (the declared parameters and their binder) AND `dag` (the
 * reservation store), so it is not a single-aggregate use case and does not belong with either. Before 056 it
 * lived in `modules/web` — not because that was its home, but because nothing else could hold it,
 * which is exactly the constraint S4 recorded and this module removes. `web` and `mcp-server` both
 * depend on this module; nothing here may depend on either (`ArchitectureGuardTest`).
 *
 * ## What this closes
 *
 * `PipelineExecuteTool` used to resolve the version, deserialize the body and run — with **no
 * idempotency support at all** (S2's D6: "a behavioural divergence... a bug wearing a duplication
 * costume"). It goes through this class now, so an MCP execute carrying an `Idempotency-Key` gets
 * the same reservation an equivalent REST call gets: a repeated key returns the original
 * execution instead of running a second one. The MCP **tool schema is unchanged** — the key rides
 * the same `Idempotency-Key` HTTP header REST uses, on the same `POST /mcp` request, so the wire
 * surface `McpToolSurfaceSpecDriftTest` guards is byte-identical.
 *
 * ## Order, and why it is this order
 *
 * 1. **Resolve** — `PipelineService.findExecutable`, the aggregate's own read; a version with no
 *    stored body is the surface's 404, before anything here is claimed.
 * 2. **Bind** ([decide]) — `ParameterBinder` runs BEFORE the reservation, so a rejected parameter
 *    is a plain refusal with no execution, no reservation and no stream. Reversing these two
 *    would burn an idempotency key on a request that never ran.
 * 3. **Reserve** — `SET NX`, claimed *before* executing, which is the whole point of the claim.
 *
 * Everything after the decision — the SSE emitter, the per-run executor, the blocking wait — is
 * surface-shaped and stays with the surface.
 */
class ExecutionLauncher(
    private val idempotencyStore: IdempotencyStore,
    private val idempotencyTtlSeconds: Long,
    private val metrics: IdempotencyMetrics = IdempotencyMetrics.NONE,
) {
    /**
     * D6 steps 2–3 — binds the parameters, then settles the reservation.
     *
     * @throws DatapipelinesException a §12 parameter refusal from [ParameterBinder], or
     *   `idempotency.key_reused_for_different_request` when the key is held by a different
     *   request (counted, then rethrown untouched).
     */
    fun decide(launch: ExecutionLaunch): LaunchDecision {
        // pipeline-contract §7.1: deterministic, and a rejected parameter costs nothing —
        // no execution, no reservation, no stream.
        ParameterBinder(launch.pipeline.parameters).bindOrThrow(launch.parameters)

        val key = launch.idempotencyKey ?: return LaunchDecision.Start(null)
        return when (val outcome = reserve(key, launch)) {
            is IdempotencyOutcome.Reserved -> {
                LaunchDecision.Start(outcome.executionId)
            }

            is IdempotencyOutcome.Existing -> {
                metrics.reservationHit()
                LaunchDecision.Attach(outcome.executionId)
            }
        }
    }

    private fun reserve(
        key: String,
        launch: ExecutionLaunch,
    ): IdempotencyOutcome =
        try {
            idempotencyStore.reserve(
                launch.userId,
                key,
                IdempotencyKeys.requestHash(launch.pipelineId, launch.pipelineVersion, launch.parametersJson),
                UUID.randomUUID(),
                idempotencyTtlSeconds,
            )
        } catch (e: DatapipelinesException) {
            if (e.code == PipelineErrorCodes.Limits.IDEMPOTENCY_KEY_REUSED) metrics.reservationConflict()
            throw e
        }
}
