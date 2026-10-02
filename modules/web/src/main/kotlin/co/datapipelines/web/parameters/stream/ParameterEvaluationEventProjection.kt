package co.datapipelines.web.parameters.stream

import co.datapipelines.parameters.EvaluateResponseJson
import co.datapipelines.parameters.EvaluationOutcome
import co.datapipelines.parameters.ParameterEvaluationEvent
import co.datapipelines.pipeline.PipelineErrorCodes
import java.util.UUID

/** One stream frame: the `event:` name and its `data:` object. */
internal data class EvaluationFrame(
    val name: String,
    val data: Map<String, Any?>,
)

/**
 * The engine's events as the observed-evaluation stream's frames — the parameter-set workspace spec §4.2's event table,
 * FROZEN. The engine's events carry no id (#375 D1): every frame is stamped here with the request's `evaluation_id`,
 * and `evaluation_started` with the `parameter_set_id` and `version` the caller named.
 *
 * What a frame may carry is the table's keys and nothing else (a test pins the exact key set per event): parameter
 * names, datasource names, template pins, catalogued codes, origins, reset marks, row counts and — on
 * `evaluation_completed` only — the §5.3 response, verbatim from its one writer. `evaluation_failed` carries the code
 * alone: the optional `message` is never sent, so no exception text can reach a frame.
 *
 * `ABORTED` projects to NO frame. It happens when the client is gone past the disconnect grace (the owner's §11.7 ruling)
 * or the process is stopping; the table has no aborted frame and the catalogue no aborted code (an abort is a history
 * outcome and a log event). A client still reading at shutdown sees its stream end without a terminal frame — the
 * dashboards §6.6 transport-failure path, which is the truth.
 */
internal object ParameterEvaluationEventProjection {
    /**
     * A 500-class failure has no catalogued code of its own: it travels as the 500 backstop's stand-in, exactly as the
     * ordinary evaluate's envelope would carry it (`ApiExceptionHandler.INTERNAL_STAND_IN_CODE`; a test pins parity).
     */
    val INTERNAL_STAND_IN_CODE: String = PipelineErrorCodes.Execution.ABORTED

    fun frame(
        event: ParameterEvaluationEvent,
        evaluationId: UUID,
        parameterSetId: UUID,
        version: Int,
    ): EvaluationFrame? {
        val id = evaluationId.toString()
        return when (event) {
            is ParameterEvaluationEvent.Started -> {
                EvaluationFrame(
                    "evaluation_started",
                    linkedMapOf(
                        "evaluation_id" to id,
                        "parameter_set_id" to parameterSetId.toString(),
                        "version" to version,
                        "deadline_at" to event.deadlineAt.toString(),
                    ),
                )
            }

            is ParameterEvaluationEvent.ParameterWaiting -> {
                EvaluationFrame(
                    "parameter_waiting",
                    linkedMapOf("evaluation_id" to id, "parameter" to event.parameter, "waiting_on" to event.waitingOn),
                )
            }

            is ParameterEvaluationEvent.ParameterAdmitted -> {
                EvaluationFrame("parameter_admitted", linkedMapOf("evaluation_id" to id, "parameter" to event.parameter))
            }

            is ParameterEvaluationEvent.ParameterRunning -> {
                EvaluationFrame(
                    "parameter_running",
                    linkedMapOf(
                        "evaluation_id" to id,
                        "parameter" to event.parameter,
                        "datasource" to event.datasource,
                        "template" to linkedMapOf("id" to event.template.id, "version" to event.template.version),
                    ),
                )
            }

            is ParameterEvaluationEvent.ParameterResolved -> {
                EvaluationFrame(
                    "parameter_resolved",
                    linkedMapOf<String, Any?>(
                        "evaluation_id" to id,
                        "parameter" to event.parameter,
                        "origin" to event.origin,
                        "reset" to event.reset,
                    ).apply { event.rows?.let { put("rows", it) } },
                )
            }

            is ParameterEvaluationEvent.ParameterFailed -> {
                EvaluationFrame(
                    "parameter_failed",
                    linkedMapOf<String, Any?>("evaluation_id" to id, "parameter" to event.parameter, "code" to event.code)
                        .apply { event.detail?.let { put("detail", it) } },
                )
            }

            is ParameterEvaluationEvent.Ended -> {
                ended(event, id)
            }
        }
    }

    private fun ended(
        event: ParameterEvaluationEvent.Ended,
        id: String,
    ): EvaluationFrame? =
        when (event.outcome) {
            EvaluationOutcome.COMPLETED -> {
                val response = checkNotNull(event.response) { "a COMPLETED evaluation always carries its response" }
                EvaluationFrame(
                    "evaluation_completed",
                    linkedMapOf(
                        "evaluation_id" to id,
                        "response" to EvaluateResponseJson.write(response),
                    ),
                )
            }

            EvaluationOutcome.TIMEOUT, EvaluationOutcome.FAILED -> {
                EvaluationFrame("evaluation_failed", linkedMapOf("evaluation_id" to id, "code" to (event.code ?: INTERNAL_STAND_IN_CODE)))
            }

            EvaluationOutcome.ABORTED -> {
                null
            }
        }
}
