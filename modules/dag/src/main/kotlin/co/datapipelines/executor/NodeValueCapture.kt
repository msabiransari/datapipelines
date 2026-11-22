package co.datapipelines.executor

import co.datapipelines.scripting.CanonicalJson
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * #1043 (published-mutation-apis §6, §7 "Runtime capture") — the opt-in recording of one execution's declared value-mode
 * transformer outputs into private, administrator-only diagnostic storage.
 *
 * Present on an [ExecuteRequest] ONLY when a workspace administrator's capture session admitted the execution; absent
 * (null) on every other run, so a run without capture performs no per-node lookup and no write. A value-mode SCRIPT node
 * calls [record] once, after its Context write — i.e. only for a node whose output passed the gate, its invariants and
 * strict. Nothing else reaches it: no input envelope, no table output, no staging row, no rejection text, no SQL.
 *
 * The contract every implementation keeps: [record] returns at once — it never suspends on, waits for or throws from
 * diagnostic storage, so a slow, blocked or failing diagnostic store can neither take part in, fail, delay past a
 * deadline nor otherwise change the business run (its result, its timing against the node and execution deadlines, or
 * its idempotency decision).
 */
interface NodeValueCapture {
    /** Hands over [value], the gated output [nodeId] wrote under its declared [outputKey]. Never blocks, never throws. */
    fun record(
        nodeId: String,
        outputKey: String,
        value: Any?,
    )
}

/** One captured (or omitted) declared output — what [CaptureValueStore.write] persists. */
data class CapturedValue(
    val executionId: UUID,
    val workspaceId: UUID,
    val nodeId: String,
    val outputKey: String,
    /** The JSON text of the value; null when [omittedReason] is set (an omitted value keeps no preview). */
    val valueJson: String?,
    /** The serialized UTF-8 size; null when the value could not be serialized at all. */
    val valueBytes: Long?,
    val omittedReason: OmittedReason?,
    val recordedAt: Instant,
    val expiresAt: Instant,
) {
    init {
        require((valueJson == null) == (omittedReason != null)) { "a value is either captured or omitted with a reason" }
    }

    /** Why a declared output was not kept. The wire spelling is [wire]. */
    enum class OmittedReason(
        val wire: String,
    ) {
        /** The execution's byte budget (`datapipelines.script.max-value-bytes`) could not hold this value. */
        BYTE_BUDGET_EXHAUSTED("byte_budget_exhausted"),

        /** The value has no JSON form. */
        UNSERIALIZABLE("unserializable"),
    }
}

/** The run's closing summary — written once when the run ends, so the diagnostic view can say whether it is complete. */
data class CaptureSeal(
    val executionId: UUID,
    val workspaceId: UUID,
    val sessionId: UUID,
    val valuesCaptured: Int,
    val valuesOmitted: Int,
    val writeFailures: Int,
    val bytesUsed: Long,
    val sealedAt: Instant,
    val expiresAt: Instant,
)

/**
 * The private diagnostic store (metadata-db §4.63). Implemented over the metadata database by `application`; every
 * statement is its own autocommit statement — never inside a business transaction. A write at or after
 * [CapturedValue.expiresAt] is refused by the store itself (returns false).
 */
interface CaptureValueStore {
    /** Persists [value]; false when the store refused it (already recorded, or expired). Throws on a store failure. */
    fun write(value: CapturedValue): Boolean

    /** Persists the run's [seal]; false when refused (expired or already sealed). Throws on a store failure. */
    fun seal(seal: CaptureSeal): Boolean
}

/**
 * The bounded recorder of ONE captured execution: serializes each declared output, charges it against the execution's
 * total byte budget across every node, and hands it to the execution's own diagnostic write queue — or, once the budget
 * cannot hold it, an OMITTED marker with its reason and no preview.
 *
 * **Off the business path.** [record] only serializes and enqueues (a bounded channel of [QUEUE_CAPACITY]); the store
 * is called by one drain coroutine per captured execution, launched on [writes] — the diagnostic scope, never the run's
 * own (so neither a business deadline nor a business cancellation reaches a diagnostic write, and no diagnostic wait
 * reaches a node). The writes of one execution are applied in order, the seal last.
 *
 * **Bounded lifecycle.** Memory: the queue holds at most [QUEUE_CAPACITY] items whose kept values together fit the
 * budget. Time: each store statement carries the store's own statement bound; the drain ends at the detail expiry
 * ([expiresAt]) at the latest, and the store refuses any write that reaches it at or after that instant. [seal] closes
 * the queue: the drain writes the remaining values, then the closing summary, then ends.
 *
 * **Honest status, value-free telemetry.** A write the expiry overtook is not attempted. A store failure or a full or
 * closed queue is counted and logged value-free (`event=execution.capture.write_failed`, the metric
 * `datapipelines.executions.capture.values{outcome=failed}`) and swallowed; the summary then carries `write_failures`,
 * or no summary is written at all, and the diagnostic view reports the run as incomplete.
 */
@Suppress("LongParameterList") // one recorder per captured execution; every value is its identity or bound
class BoundedValueCapture(
    private val executionId: UUID,
    private val workspaceId: UUID,
    private val sessionId: UUID,
    private val expiresAt: Instant,
    private val budgetBytes: Long,
    private val store: CaptureValueStore,
    writes: CoroutineScope,
    private val meters: MeterRegistry? = null,
    private val clock: Clock = Clock.systemUTC(),
) : NodeValueCapture {
    private val used = AtomicLong(0)
    private val captured = AtomicInteger(0)
    private val omitted = AtomicInteger(0)
    private val failures = AtomicInteger(0)
    private val queue = Channel<CapturedValue>(QUEUE_CAPACITY)
    private val drain: Job

    init {
        require(budgetBytes > 0) { "the capture byte budget must be positive, was $budgetBytes" }
        drain = writes.launch { drainUntilExpiry() }
    }

    override fun record(
        nodeId: String,
        outputKey: String,
        value: Any?,
    ) {
        val now = clock.instant()
        // A run that outlives its detail expiry writes nothing more (the store refuses it too).
        if (!now.isBefore(expiresAt)) return
        val captureOf = admit(nodeId, outputKey, serialize(value), now)
        if (queue.trySend(captureOf).isFailure) failed(nodeId, REASON_QUEUE_UNAVAILABLE)
    }

    /**
     * Ends recording: the queue is closed, and the drain writes what is still queued, then the closing summary. Returns
     * at once — the business path never waits for a diagnostic write. Called once, when the run has ended however it
     * ended; a later [record] is counted as a failure.
     */
    fun seal() {
        queue.close()
    }

    /**
     * Suspends until the drain has ended (every queued write and the summary attempted, or the expiry reached). Never
     * called on the business path — for an orderly test or shutdown only.
     */
    suspend fun join() = drain.join()

    private suspend fun drainUntilExpiry() {
        val remaining = Duration.between(clock.instant(), expiresAt)
        if (remaining.isNegative || remaining.isZero) return
        withTimeoutOrNull(remaining.toMillis()) {
            for (value in queue) {
                ensureActive() // a buffered value is received without suspending: the expiry is checked here, per write
                write(value)
            }
            ensureActive()
            writeSeal()
        }
    }

    /** One diagnostic write; a refusal is silent (the store decided), a failure is counted value-free. */
    private fun write(value: CapturedValue) {
        try {
            val written = store.write(value) // blocking, on the diagnostic scope's IO dispatcher, bounded by the store
            if (written) {
                if (value.omittedReason == null) count(OUTCOME_CAPTURED, captured) else count(OUTCOME_OMITTED, omitted)
            }
        } catch (
            // Whatever the store threw, the business run is unaffected: the failure is counted and logged without a value.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            failed(value.nodeId, e.javaClass.simpleName)
        }
    }

    private fun failed(
        nodeId: String,
        reason: String,
    ) {
        count(OUTCOME_FAILED, failures)
        LOG.warn("event=execution.capture.write_failed execution_id={} node_id={} error={}", executionId, nodeId, reason)
    }

    /** Charges [json] against the budget: the value itself when it fits, an omitted marker otherwise. */
    private fun admit(
        nodeId: String,
        outputKey: String,
        json: String?,
        now: Instant,
    ): CapturedValue {
        if (json == null) return marker(nodeId, outputKey, null, CapturedValue.OmittedReason.UNSERIALIZABLE, now)
        val bytes = json.toByteArray(Charsets.UTF_8).size.toLong()
        val fits =
            used.getAndUpdate { current -> if (current + bytes <= budgetBytes) current + bytes else current }.let {
                it + bytes <= budgetBytes
            }
        return if (fits) {
            CapturedValue(executionId, workspaceId, nodeId, outputKey, json, bytes, null, now, expiresAt)
        } else {
            marker(nodeId, outputKey, bytes, CapturedValue.OmittedReason.BYTE_BUDGET_EXHAUSTED, now)
        }
    }

    private fun marker(
        nodeId: String,
        outputKey: String,
        bytes: Long?,
        reason: CapturedValue.OmittedReason,
        now: Instant,
    ) = CapturedValue(executionId, workspaceId, nodeId, outputKey, null, bytes, reason, now, expiresAt)

    /**
     * Writes the closing summary — after the last queued value. A store failure here leaves the run unsealed, which the
     * diagnostic view reports as incomplete; it is counted and logged value-free, never thrown.
     */
    private fun writeSeal() {
        val now = clock.instant()
        if (!now.isBefore(expiresAt)) return
        val summary =
            CaptureSeal(executionId, workspaceId, sessionId, captured.get(), omitted.get(), failures.get(), used.get(), now, expiresAt)
        try {
            store.seal(summary)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            meters?.counter(METER, OUTCOME_TAG, OUTCOME_SEAL_FAILED)?.increment()
            LOG.warn("event=execution.capture.seal_failed execution_id={} error={}", executionId, e.javaClass.simpleName)
        }
    }

    private fun count(
        outcome: String,
        counter: AtomicInteger,
    ) {
        counter.incrementAndGet()
        meters?.counter(METER, OUTCOME_TAG, outcome)?.increment()
    }

    companion object {
        /** `datapipelines.executions.capture.values` — one count per declared output, tagged by a closed `outcome`. */
        const val METER = "datapipelines.executions.capture.values"
        const val OUTCOME_TAG = "outcome"
        const val OUTCOME_CAPTURED = "captured"
        const val OUTCOME_OMITTED = "omitted"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_SEAL_FAILED = "seal_failed"

        /**
         * The most diagnostic writes one execution may have queued: one per declared value-mode output, so far above
         * any pipeline's node count; every kept value in it is charged against the byte budget, so the queue's memory
         * is bounded by the budget. Past it a value is counted as failed, never waited for.
         */
        const val QUEUE_CAPACITY = 256

        /** The value-free `error` of a value the queue could not take (full, or already sealed). */
        const val REASON_QUEUE_UNAVAILABLE = "QueueUnavailable"

        private val LOG = LoggerFactory.getLogger(BoundedValueCapture::class.java)

        /**
         * The JSON text of a gated Context value: an object or array through the canonical writer (the same one the
         * node stats render it with), a string as a JSON string, a number or boolean as itself, any other scalar
         * (temporal values) as its ISO text. Null for a value with no JSON form.
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException") // an unserializable value is OMITTED, not an error
        fun serialize(value: Any?): String? =
            try {
                when (value) {
                    null -> "null"
                    is Map<*, *>, is List<*> -> CanonicalJson.write(value)
                    is BigDecimal -> value.toPlainString()
                    is Double -> value.takeIf { it.isFinite() }?.toString()
                    is Float -> value.takeIf { it.isFinite() }?.toString()
                    is Number, is Boolean -> value.toString()
                    else -> ExecutorJson.mapper.writeValueAsString(value.toString())
                }
            } catch (e: RuntimeException) {
                null
            }
    }
}
