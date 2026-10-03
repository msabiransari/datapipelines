package co.datapipelines.web.parameters

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.parameters.EvaluationAttempt
import co.datapipelines.parameters.EvaluationCaller
import co.datapipelines.parameters.ParameterEvaluationRepository
import co.datapipelines.parameters.ParameterEvaluator
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.SelectionKeys
import co.datapipelines.persistence.FailureShape
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.config.WebSurfaceConfiguration.ExecutionCoroutineScope
import co.datapipelines.web.parameters.stream.ParameterEvaluationRequests
import co.datapipelines.web.parameters.stream.ParameterEvaluationStreamAuthority
import co.datapipelines.web.parameters.stream.ParameterEvaluationStreamRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

/**
 * The OBSERVED evaluation (rest-api.md §21.5; the parameter-set workspace spec §4, R1) — the Parameter Sets page's one
 * stream: the same engine, the same bulkhead and the same caps as `POST /{id}/evaluate`, with the cascade's progress as
 * Server-Sent Events. A separate route by the owner's §11.4 ruling, so the ordinary evaluate stays byte-for-byte what it
 * was (`ParameterEvaluateGoldenTest`, and `ArchitectureGuardTest` pins that no ordinary call site names the observer).
 *
 * ## Refusals, in order — a refused observation opens nothing and starts nothing
 * The route's matrix row (`parameter_set.evaluate`, declared below) is judged by the scope interceptor before this
 * handler runs, as for every route. Then: the body's shape ([ParameterEvaluationRequests] — the 1 MiB pre-parse bound,
 * `version` REQUIRED, `selections` an object, both ids v4 UUIDs), the set AND the explicit version through the caller's
 * lens (a hidden set or a missing version is the ordinary evaluate's IDENTICAL `parameter.not_found` 404 — the same
 * lensed read, never clamped), every selections key against the set (`parameter.evaluate.unknown_parameter` — the
 * evaluator's own judge, #375 D4), a reused `evaluation_id` (`body_invalid`, `reason: reused`, D5 — open on this
 * instance, or already recorded in the caller's workspace history, #417), then the one per-user SSE cap
 * (`rate_limit.exceeded`, D7).
 *
 * ## The stream
 * `produces` lists `application/json` beside `text/event-stream` so a pre-stream refusal renders the §4.2 envelope for a
 * client that sent only `Accept: text/event-stream` (the dashboard runtime's B6). The evaluation runs on the
 * [ExecutionCoroutineScope], never the request thread; the stream is its observer, and the registry's disconnect grace
 * cancels it when nobody reads (the owner's §11.7 ruling).
 *
 * ## Roles (roles design §4.9)
 * viewer ✓ · author ✓ · promoter ✗ · workspace admin ✓ · super admin ✓ · `api_caller` ✗ · `promotion_receiver` ✗ —
 * the `parameter_set.evaluate` row, auth.md §7.6; session-only: no key kind reaches a REST route here, and the route has
 * no MCP placement (spec §7).
 */
@RestController
@RequestMapping("/api/v1/parameter-sets")
class ParameterEvaluationStreamController(
    private val sets: ParameterSetService,
    private val lens: PromoterLens,
    private val evaluations: ParameterEvaluationRepository,
    private val evaluator: ParameterEvaluator,
    private val streams: ParameterEvaluationStreamRegistry,
    private val authority: ParameterEvaluationStreamAuthority,
    private val scope: ExecutionCoroutineScope,
) {
    private val log = LoggerFactory.getLogger(ParameterEvaluationStreamController::class.java)

    /** §21.5 — one observed evaluation, streamed. Every refusal is decided BEFORE the stream opens. */
    @PostMapping(
        "/{id}/evaluations",
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE],
    )
    @RequiredScope(Permission.PARAMETER_SET_EVALUATE)
    @Suppress("ThrowsCount") // each throw is a distinct catalogued refusal, in the spec's order
    fun evaluations(
        @PathVariable id: UUID,
        @RequestBody body: String,
    ): SseEmitter {
        val request = ParameterEvaluationRequests.read(body)
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal).parameterSets
        // The ordinary evaluate's explicit-version read, exactly: a miss is the catalogued 404, never a clamp.
        val set =
            sets.findVersion(workspaceId, view, id, request.version) ?: throw ApiErrors.parameterNotFound(id.toString(), request.version)
        SelectionKeys.refuseUnknown(set.body, request.selections)
        // The id is also the history record's key (#376): an id open on this instance OR already recorded in THIS workspace
        // is reused. The read is the workspace's own — another workspace's id answers as unused (its insert conflict is the backstop).
        if (streams.isOpen(request.evaluationId) || evaluations.exists(workspaceId, request.evaluationId)) {
            throw ParameterEvaluationRequests.bad("evaluation_id", ParameterEvaluationRequests.REUSED)
        }
        if (streams.atStreamLimit(principal.userId)) throw ApiErrors.streamLimitExceeded(streams.maxStreamsPerUser)
        val stream = streams.open(request.evaluationId, id, request.version, principal, authority)
        // #376: the client-minted evaluation id is the history record's key; the page is the PAGE caller.
        val attempt = EvaluationAttempt.of(EvaluationCaller.PAGE, principal.userId, principal.keyId, evaluationId = request.evaluationId)
        // LAZY: the job is attached for the grace BEFORE it can run, so even an instant evaluation is abortable by it.
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    evaluator.evaluate(workspaceId, set, request.selections, attempt = attempt, observation = stream)
                } catch (_: CancellationException) {
                    // The grace or the shutdown: the evaluator already wrote Ended(ABORTED) under NonCancellable.
                } catch (e: DatapipelinesException) {
                    // A whole-request refusal (timeout, response_too_large): its evaluation_failed frame is already written.
                    log.info("event=parameter.evaluation_refused evaluation_id={} code={}", request.evaluationId, e.code)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    // A defect, never an author's problem: the frame carried the stand-in code only. The ERROR line names the
                    // cause by class and SQLState and attaches no throwable (its message may carry SQL or a value, §3.4M);
                    // the stack is the DEBUG line's.
                    log.error(
                        "event=parameter.evaluation_failed evaluation_id={} error={} sql_state={}",
                        request.evaluationId,
                        e.javaClass.name,
                        FailureShape.sqlState(e),
                    )
                    log.debug("event=parameter.evaluation_failed_cause evaluation_id={}", request.evaluationId, e)
                }
            }
        streams.attach(request.evaluationId, job)
        job.start()
        return stream.emitter
    }
}
