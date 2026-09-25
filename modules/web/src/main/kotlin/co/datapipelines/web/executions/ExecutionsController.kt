package co.datapipelines.web.executions

import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.PagedData
import co.datapipelines.web.api.Pagination
import co.datapipelines.web.api.cancellableBy
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.api.visibleTo
import co.datapipelines.web.sse.SseLogStreamer
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.time.Instant
import java.util.UUID

/**
 * Execution history and result delivery (rest-api.md §10, §7).
 *
 * ## Ownership (carry-forward #2)
 * `dag`'s cancellation service does no owner check, so this controller does: every
 * execution-scoped handler resolves the record and applies [visibleTo] **before** acting, and a
 * non-owner — including for cancel — gets `404 result.execution_not_found`, never a 403. An
 * `admin` sees and may cancel any execution; the admin listing is served by
 * [ExecutionRepository.findAll], a normal user's by `findByUser` (carry-forward #7).
 */
@RestController
@RequestMapping("/api/v1/executions")
class ExecutionsController(
    private val executions: ExecutionRepository,
    private val cancellation: ExecutionCancellationService,
    private val cursor: ResultCursor,
    private val resultStore: ResultStore,
    private val resultUrls: ResultUrlFactory,
    private val streamer: SseLogStreamer,
    private val pipelines: co.datapipelines.pipeline.PipelineRepository,
    /** §7.2/§7.7 — the one visibility rule, shared with the result cursor. */
    private val visibility: ExecutionVisibility = ExecutionVisibility(),
    /** §10.2 — the ONE metadata projection, shared with the business-path paging read (A16). */
    private val metadata: ExecutionMetadataProjection =
        ExecutionMetadataProjection(pipelines, resultStore, resultUrls),
) {
    /**
     * §10.1 — the listing. Filters are evaluated **in SQL** by the repository (gate C, B4): the
     * page is cut after filtering, so `has_more` and page fullness are honest. `total` remains the
     * proven lower bound — the repository offers no count. Ownership (D11): a workspace admin
     * reads `findAll` (optionally pipeline-narrowed); everyone else reads `findByUser`, which is
     * scoped by `executed_by` in SQL and excludes endpoint-key runs — no other user's execution
     * can reach the page. The promoter never reaches this handler (`READ_EXECUTIONS`).
     */
    @GetMapping
    @RequiredScope(Permission.EXECUTION_READ)
    fun list(
        @RequestParam(name = "pipeline_id", required = false) pipelineId: UUID?,
        @RequestParam(required = false) status: String?,
        @RequestParam(name = "started_after", required = false) startedAfter: Instant?,
        @RequestParam(name = "started_before", required = false) startedBefore: Instant?,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<PagedData<Map<String, Any?>>> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val page = Pagination.clampOffset(offset)
        val size = Pagination.clampLimit(limit)
        val wanted = status?.let { parseStatus(it) }
        val raw =
            if (principal.holds(Permission.EXECUTION_READ_ALL)) {
                executions.findAll(workspaceId, pipelineId, wanted, startedAfter, startedBefore, limit = size + 1, offset = page)
            } else {
                executions.findByUser(
                    workspaceId,
                    principal.userId,
                    pipelineId,
                    wanted,
                    startedAfter,
                    startedBefore,
                    limit = size + 1,
                    offset = page,
                )
            }
        val pageItems = raw.take(size)
        // §8's derivation, read in bulk for the page: a draft run is `started_at < released_at`
        // or no `released_at` at all (still DRAFT, or DISCARDED). Informational only — never
        // execution behaviour, never promotion eligibility.
        val releasedAt = pipelines.releasedAtFor(workspaceId, pageItems.map { it.pipelineId to it.pipelineVersion })
        val items = pageItems.map { metadata.project(it, metadata.draftRun(it, releasedAt), includeResult = false) }
        return ApiResponse.of(PagedData(items, Pagination.unknownTotal(page, size, items.size, raw.size > size)))
    }

    /** §10.2 — one execution's metadata; `result_url` only while the result is unexpired. */
    @GetMapping("/{id}")
    @RequiredScope(Permission.EXECUTION_READ)
    fun get(
        @PathVariable id: UUID,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        // Keys v2 A16/B3: these framework reads are SESSIONS-ONLY — an `api` key pages its
        // runs under the business path it is bound to. The interceptor already refuses every
        // key kind before any handler; this is the second line, so a handler reached some
        // other way still refuses.
        requireSession(principal)
        val workspaceId = principal.requireWorkspace().id
        // §7.7 — the SHARED rule, not `visibleTo` alone: an endpoint key that could fetch a
        // result but not see that its execution exists would be a contradiction a client trips
        // over immediately.
        val record =
            executions.findById(workspaceId, id)?.takeIf { visibility.visible(it, principal, id) }
                ?: throw ApiErrors.executionNotFound(id.toString())
        return ApiResponse.of(metadata.project(record, workspaceId, includeResult = true))
    }

    /**
     * §10.4 — cancel a RUNNING execution. The `204` acknowledges the *request*; the
     * `execution_aborted` event marks its completion. Ownership was checked before we get here.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(Permission.EXECUTION_CANCEL)
    fun cancel(
        @PathVariable id: UUID,
    ) {
        val workspaceId = currentPrincipal().requireWorkspace().id
        val record =
            executions.findById(workspaceId, id)?.takeIf { it.cancellableBy(currentPrincipal()) }
                ?: throw ApiErrors.executionNotFound(id.toString())
        if (record.status != ExecutionStatus.RUNNING) {
            throw ApiErrors.executionNotRunning(id.toString(), record.status.name)
        }
        cancellation.cancel(id, AbortReason.CANCELLED)
    }

    /**
     * §10.3 — replays the Redis event log. The log lives one hour past completion (fixed, not
     * configurable); afterwards this is `410`, answered with the catalogued `result.expired` — the
     * spec names the status but no code, and the two §13.10 `410` codes are the only candidates.
     * Reported to the orchestrator as a catalog gap.
     */
    @GetMapping(
        "/{id}/events",
        // B6: a pre-stream error (404/410) must render the JSON envelope even when the client
        // only accepts text/event-stream — see PipelineExecuteController.
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE],
    )
    @RequiredScope(Permission.EXECUTION_READ)
    fun events(
        @PathVariable id: UUID,
    ): SseEmitter {
        val workspaceId = currentPrincipal().requireWorkspace().id
        val record =
            executions.findById(workspaceId, id)?.takeIf { it.visibleTo(currentPrincipal()) }
                ?: throw ApiErrors.executionNotFound(id.toString())
        if (!streamer.hasLog(record.executionId)) {
            throw ApiErrors.resultExpired(id.toString())
        }
        return streamer.replay(record.executionId)
    }

    /** §7.2 — the result cursor. `format=json` pages through the envelope; `csv` streams. */
    @GetMapping("/{id}/result")
    @RequiredScope(Permission.EXECUTION_RESULT_READ)
    fun result(
        @PathVariable id: UUID,
        @RequestParam(required = false) offset: Long?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) format: String?,
    ): ResponseEntity<Any> {
        val principal = currentPrincipal()
        // Keys v2 A16/B3 — the session-only second line, as on `get`.
        requireSession(principal)
        val chosen = cursor.formatOf(format)
        val record = cursor.readable(id, principal)
        if (chosen == ResultCursor.FORMAT_CSV) {
            val body = StreamingResponseBody { out -> cursor.writeCsv(record, out) }
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv")).body(body)
        }
        val page = cursor.jsonPage(record, offset ?: 0L, limit)
        return ResponseEntity.ok(ApiResponse.of(page))
    }

    /** Keys v2 A16 — the framework's execution reads are session-only; the serve path's answer for a key. */
    private fun requireSession(principal: co.datapipelines.auth.AuthenticatedPrincipal) {
        if (principal.authMethod == co.datapipelines.auth.AuthMethod.API_KEY) {
            throw co.datapipelines.web.api.ApiException(
                PipelineErrorCodes.Auth.SESSION_REQUIRED,
                "Execution reads under /api/v1 are session-only. An API key pages the results of the runs it " +
                    "started under the business path it is bound to (keys v2 A16).",
                mapOf("reason" to "key_off_surface"),
            )
        }
    }

    private fun parseStatus(raw: String): ExecutionStatus =
        runCatching { ExecutionStatus.valueOf(raw.trim().uppercase()) }.getOrNull()
            ?: throw co.datapipelines.web.api.ApiException(
                co.datapipelines.pipeline.PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                "Unknown execution status '$raw'.",
                mapOf("status" to raw.take(MAX_ECHOED_VALUE_CHARS), "supported" to ExecutionStatus.entries.map { it.name }),
            )

    private companion object {
        /** Reflected client input is bounded before it reaches an error message. */
        const val MAX_ECHOED_VALUE_CHARS = 32
    }
}
