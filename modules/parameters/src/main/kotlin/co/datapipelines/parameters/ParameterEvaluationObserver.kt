package co.datapipelines.parameters

import co.datapipelines.pipeline.TemplateRef
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * The observed evaluation's attachment point (the parameter-set workspace spec §4.3, R1) — the ONE optional
 * argument of [ParameterEvaluator.evaluate]. The Parameter Sets page's stream passes a streaming observer;
 * every ordinary caller (the REST evaluate, the dashboard runtime, the MCP tool) passes nothing, gets [NONE],
 * and the engine then builds no event, wraps no task and hands the pool no callback (an architecture guard
 * pins that the ordinary call sites never name this type).
 *
 * [on] is called synchronously from the evaluating coroutine and, for [ParameterEvaluationEvent.ParameterRunning],
 * from the selector's worker thread — an implementation is thread-safe and quick. The engine guarantees the
 * order a subscriber relies on: [ParameterEvaluationEvent.Started] first, [ParameterEvaluationEvent.Ended]
 * exactly once and last (nothing is delivered after it, whatever a worker thread does later). An observer
 * that throws is detached at that event — the evaluation is the recorded act, never the stream's casualty.
 */
fun interface ParameterEvaluationObserver {
    fun on(event: ParameterEvaluationEvent)

    companion object {
        /** No observer — what every ordinary caller gets. */
        val NONE: ParameterEvaluationObserver = ParameterEvaluationObserver { }
    }
}

/**
 * What one evaluation reports while it runs (spec §4.2's event table, decision D1 of #375: no id — the stream
 * that subscribed stamps `evaluation_id`, `parameter_set_id` and `version` from its own request). Identity and
 * progress only: parameter names, datasource names, template pins, catalogued codes, origins, reset marks and
 * row counts — never a selection, a value, an option, SQL, a bind or a driver message.
 */
sealed interface ParameterEvaluationEvent {
    /** Always first: the set's parameters in declaration order, and when the evaluate's deadline falls. */
    data class Started(
        val parameters: List<String>,
        val deadlineAt: Instant,
    ) : ParameterEvaluationEvent

    /** [parameter]'s coroutine awaits its parents ([waitingOn], distinct, as declared) — a root parameter never waits. */
    data class ParameterWaiting(
        val parameter: String,
        val waitingOn: List<String>,
    ) : ParameterEvaluationEvent

    /** A template-backed [parameter]'s statement holds a `SelectorPool` running slot. */
    data class ParameterAdmitted(
        val parameter: String,
    ) : ParameterEvaluationEvent

    /** Render + run began on the selector's worker thread for [parameter]: the pin [template] against [datasource]. */
    data class ParameterRunning(
        val parameter: String,
        val datasource: String,
        val template: TemplateRef,
    ) : ParameterEvaluationEvent

    /**
     * [parameter] resolved with no error: its value's [origin] (the §5.3 wire word), whether the client's value was
     * [reset], and — when a query produced options — their count in [rows].
     */
    data class ParameterResolved(
        val parameter: String,
        val origin: String,
        val reset: Boolean,
        val rows: Int?,
    ) : ParameterEvaluationEvent

    /**
     * [parameter] carries an error: the first error's catalogued [code], and a [detail] derived from the code's own
     * `details.reason` (≤ 200 characters) — never the error's message, which may quote a driver.
     */
    data class ParameterFailed(
        val parameter: String,
        val code: String,
        val detail: String?,
    ) : ParameterEvaluationEvent

    /**
     * Always last, exactly once, written under `NonCancellable` (decision D2): the [outcome], the whole-request
     * [code] when the evaluation refused (`TIMEOUT`, or `FAILED` with a catalogued code), and the [response] when it
     * `COMPLETED`.
     */
    data class Ended(
        val outcome: EvaluationOutcome,
        val code: String?,
        val response: EvaluateResponse?,
    ) : ParameterEvaluationEvent
}

/**
 * How one evaluation ended (#375 D2; S3's history `status` carries the same words beside `RUNNING` and `INCOMPLETE`).
 * `ABORTED` is the caller's cancellation — for the observed evaluation, the disconnect grace (the owner's §11.7
 * ruling) — told from our own deadline by scope liveness, never by exception type.
 */
enum class EvaluationOutcome {
    COMPLETED,
    TIMEOUT,
    FAILED,
    ABORTED,
}

/**
 * The engine's side of one observed evaluation: it delivers events to the observer in the order the contract
 * promises. Delivery is serialised (the evaluating coroutine and a selector worker may report at once), nothing
 * is delivered after [ParameterEvaluationEvent.Ended], and an observer that throws is detached at that event with
 * one warning — its failure never becomes the evaluation's.
 *
 * Built only for a real observer: an ordinary evaluate holds `null` and constructs no event at all.
 */
internal class ObservedEvaluation(
    private val observer: ParameterEvaluationObserver,
) {
    private var closed = false

    @Synchronized
    fun emit(event: ParameterEvaluationEvent) {
        if (closed) return
        if (event is ParameterEvaluationEvent.Ended) closed = true
        try {
            observer.on(event)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            // The defined boundary: the stream is a side channel of the evaluation, never its master.
            closed = true
            log.warn("event=parameter.evaluation_observer_failed while={} error={}", event.javaClass.simpleName, e.javaClass.simpleName)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ObservedEvaluation::class.java)
    }
}
