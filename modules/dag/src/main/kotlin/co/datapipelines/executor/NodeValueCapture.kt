package co.datapipelines.executor

import co.datapipelines.scripting.CanonicalJson
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Clock
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
 * The contract every implementation keeps: [record] never throws into the node except the caller's own cancellation —
 * a diagnostic write never takes part in, fails or changes the business run.
 */
interface NodeValueCapture {
    /** Records [value], the gated output [nodeId] wrote under its declared [outputKey]. Never throws (see the KDoc). */
    suspend fun record(
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
 * total byte budget across every node, and writes it — or, once the budget cannot hold it, writes an OMITTED marker
 * with its reason and no preview. A store failure is counted and logged value-free (`event=execution.capture.write_failed`,
 * the metric `datapipelines.executions.capture.values{outcome=failed}`) and swallowed: diagnostics never change the
 * business run, its result or its idempotency decision. [seal] writes the closing summary the diagnostic view reads.
 */
@Suppress("LongParameterList") // one recorder per captured execution; every value is its identity or bound
class BoundedValueCapture(
    private val executionId: UUID,
    private val workspaceId: UUID,
    private val sessionId: UUID,
    private val expiresAt: Instant,
    private val budgetBytes: Long,
    private val store: CaptureValueStore,
    private val meters: MeterRegistry? = null,
    private val clock: Clock = Clock.systemUTC(),
) : NodeValueCapture {
    private val used = AtomicLong(0)
    private val captured = AtomicInteger(0)
    private val omitted = AtomicInteger(0)
    private val failures = AtomicInteger(0)

    init {
        require(budgetBytes > 0) { "the capture byte budget must be positive, was $budgetBytes" }
    }

    override suspend fun record(
        nodeId: String,
        outputKey: String,
        value: Any?,
    ) {
        val now = clock.instant()
        // A run that outlives its detail expiry writes nothing more (the store refuses it too).
        if (!now.isBefore(expiresAt)) return
        val captureOf = admit(nodeId, outputKey, serialize(value), now)
        try {
            val written = withContext(Dispatchers.IO) { store.write(captureOf) }
            if (written) {
                if (captureOf.omittedReason == null) count(OUTCOME_CAPTURED, captured) else count(OUTCOME_OMITTED, omitted)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            // Whatever the store threw, the business run goes on: the failure is counted and logged without a value.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            count(OUTCOME_FAILED, failures)
            LOG.warn(
                "event=execution.capture.write_failed execution_id={} node_id={} error={}",
                executionId,
                nodeId,
                e.javaClass.simpleName,
            )
        }
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
     * Writes the closing summary — once, when the run has ended however it ended. A store failure here leaves the run
     * unsealed, which the diagnostic view reports as incomplete; it is counted and logged value-free, never thrown.
     */
    fun seal() {
        val now = clock.instant()
        if (!now.isBefore(expiresAt)) return
        try {
            store.seal(CaptureSeal(executionId, workspaceId, sessionId, captured.get(), omitted.get(), failures.get(), used.get(), now, expiresAt))
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
