package co.datapipelines.web.schedules

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.scheduler.Occurrence
import co.datapipelines.scheduler.RunDetail
import co.datapipelines.scheduler.Schedule
import co.datapipelines.scheduler.ScheduleErrorCodes
import co.datapipelines.scheduler.ScheduleException
import co.datapipelines.scheduler.ScheduleRequest
import co.datapipelines.scheduler.ScheduleRun
import co.datapipelines.scheduler.ScheduleService
import co.datapipelines.scheduler.TrailEvent
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.PagedData
import co.datapipelines.web.api.Pagination
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.config.WebHeaders
import co.datapipelines.web.pipelines.IfMatchHeader
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * **The schedules REST surface** (rest-api.md §20; scheduler design revision §6). Thin: every
 * route asks [ScheduleService] — the one scheduler type a transport may name
 * (`ArchitectureGuardTest`, B5) — and declares its permission (auth.md §7.6; the `schedule.*`
 * rows, R8, L2). Every read and write acts in the caller's ACTIVE workspace only (R9).
 *
 * Session-only in slice 1 (record §4): keys v2 confines every key kind away from `/api/v1`
 * framework routes, and the application credential is slice 5's.
 *
 * Audit: every mutation writes one `schedule.*` audit row (enums.md §15) — a schedule fires under
 * the system identity, so who created, changed, unblocked or ran it must be answerable afterwards.
 */
@RestController
@RequestMapping("/api/v1/schedules")
@Suppress("TooManyFunctions") // one handler per route of rest-api §20
class SchedulesController(
    private val schedules: ScheduleService,
    private val audit: AuditEventSink,
    private val mapper: ObjectMapper,
) {
    // ------------------------------------------------------------------------------ reads

    /** §20.1 — the workspace's schedules, by name; `prefix` narrows to a folder. */
    @GetMapping
    @RequiredScope(Permission.SCHEDULE_READ)
    fun list(
        @RequestParam(required = false) prefix: String?,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<PagedData<Map<String, Any?>>> {
        val principal = currentPrincipal()
        val page = Pagination.clampOffset(offset)
        val size = Pagination.clampLimit(limit)
        val found = schedules.list(workspaceOf(principal), prefix?.trim(), size + 1, page, PrincipalTargetViewer(principal))
        val items = found.take(size).map(::scheduleJson)
        return ApiResponse.of(PagedData(items, Pagination.unknownTotal(page, size, items.size, found.size > size)))
    }

    /** §20.3 — the next occurrences of a pattern, before any save (the form's preview). */
    @GetMapping("/preview")
    @RequiredScope(Permission.SCHEDULE_READ)
    fun preview(
        @RequestParam cron: String,
        @RequestParam timezone: String,
        @RequestParam(required = false) count: Int?,
    ): ApiResponse<Map<String, Any?>> {
        val pattern = cron.trim()
        val zone = timezone.trim()
        val occurrences = schedules.preview(pattern, zone, count ?: DEFAULT_PREVIEW)
        return ApiResponse.of(mapOf("cron" to pattern, "timezone" to zone, "occurrences" to occurrences.map(::occurrenceJson)))
    }

    /** §20.4 — one schedule; its revision is the `ETag`. */
    @GetMapping("/{id}")
    @RequiredScope(Permission.SCHEDULE_READ)
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<ApiResponse<Map<String, Any?>>> {
        val principal = currentPrincipal()
        return withEtag(schedules.get(workspaceOf(principal), id, PrincipalTargetViewer(principal)), HttpStatus.OK)
    }

    /** §20.9 — the saved schedule's next occurrences (the ONE function, record §3.2). */
    @GetMapping("/{id}/upcoming")
    @RequiredScope(Permission.SCHEDULE_READ)
    fun upcoming(
        @PathVariable id: UUID,
        @RequestParam(required = false) count: Int?,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        val occurrences = schedules.upcoming(workspaceOf(principal), id, count ?: DEFAULT_PREVIEW, PrincipalTargetViewer(principal))
        return ApiResponse.of(mapOf("schedule_id" to id.toString(), "occurrences" to occurrences.map(::occurrenceJson)))
    }

    /** §20.10 — a schedule's runs, newest first; each carries its `execution_id` (the Messages join). */
    @GetMapping("/{id}/runs")
    @RequiredScope(Permission.SCHEDULE_READ)
    fun runs(
        @PathVariable id: UUID,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<PagedData<Map<String, Any?>>> {
        val principal = currentPrincipal()
        val page = Pagination.clampOffset(offset)
        val size = Pagination.clampLimit(limit)
        val found = schedules.runs(workspaceOf(principal), id, size + 1, page, PrincipalTargetViewer(principal))
        val items = found.take(size).map(::runJson)
        return ApiResponse.of(PagedData(items, Pagination.unknownTotal(page, size, items.size, found.size > size)))
    }

    /** §20.11 — one run and its append-only trail (R10). */
    @GetMapping("/{id}/runs/{runId}")
    @RequiredScope(Permission.SCHEDULE_READ)
    fun run(
        @PathVariable id: UUID,
        @PathVariable runId: UUID,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        return ApiResponse.of(runDetailJson(schedules.run(workspaceOf(principal), id, runId, PrincipalTargetViewer(principal))))
    }

    // ------------------------------------------------------------------------------ writes

    /** §20.2 — create; optional durable `Idempotency-Key` (L1): a replay answers 200 with the original. */
    @PostMapping
    @RequiredScope(Permission.SCHEDULE_CREATE)
    fun create(
        @RequestBody body: String,
        @RequestHeader(value = WebHeaders.IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
    ): ResponseEntity<ApiResponse<Map<String, Any?>>> {
        val principal = currentPrincipal()
        val written = schedules.create(workspaceOf(principal), principal.userId, requestOf(body), idempotencyKey)
        if (!written.replayed) auditOf(principal, ScheduleAuditEvents.CREATED, written.value)
        return withEtag(written.value, if (written.replayed) HttpStatus.OK else HttpStatus.CREATED)
    }

    /** §20.5 — edit (rename/move included); `If-Match` carries the revision you read. */
    @PutMapping("/{id}")
    @RequiredScope(Permission.SCHEDULE_UPDATE)
    fun update(
        @PathVariable id: UUID,
        @RequestBody body: String,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
    ): ResponseEntity<ApiResponse<Map<String, Any?>>> {
        val principal = currentPrincipal()
        val updated = schedules.update(workspaceOf(principal), id, principal.userId, revisionOf(ifMatch), requestOf(body))
        auditOf(principal, ScheduleAuditEvents.UPDATED, updated)
        return withEtag(updated, HttpStatus.OK)
    }

    /** §20.6 — soft delete; its runs, their trail and any running execution stay. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(Permission.SCHEDULE_DELETE)
    fun delete(
        @PathVariable id: UUID,
        @RequestHeader(value = IfMatchHeader.NAME, required = false) ifMatch: String?,
    ) {
        val principal = currentPrincipal()
        schedules.delete(workspaceOf(principal), id, principal.userId, revisionOf(ifMatch))
        audit.log(event = ScheduleAuditEvents.DELETED, userId = principal.userId, details = mapOf("schedule_id" to id.toString()))
    }

    /** §20.7 — pause (idempotent). */
    @PostMapping("/{id}/pause")
    @RequiredScope(Permission.SCHEDULE_PAUSE)
    fun pause(
        @PathVariable id: UUID,
    ): ResponseEntity<ApiResponse<Map<String, Any?>>> {
        val principal = currentPrincipal()
        val paused = schedules.pause(workspaceOf(principal), id, principal.userId)
        auditOf(principal, ScheduleAuditEvents.PAUSED, paused)
        return withEtag(paused, HttpStatus.OK)
    }

    /** §20.7 — resume (idempotent); never clears a block. */
    @PostMapping("/{id}/resume")
    @RequiredScope(Permission.SCHEDULE_PAUSE)
    fun resume(
        @PathVariable id: UUID,
    ): ResponseEntity<ApiResponse<Map<String, Any?>>> {
        val principal = currentPrincipal()
        val resumed = schedules.resume(workspaceOf(principal), id, principal.userId)
        auditOf(principal, ScheduleAuditEvents.RESUMED, resumed)
        return withEtag(resumed, HttpStatus.OK)
    }

    /** §20.8 — unblock after the executor revalidates the payload (record §1.1). */
    @PostMapping("/{id}/unblock")
    @RequiredScope(Permission.SCHEDULE_PAUSE)
    fun unblock(
        @PathVariable id: UUID,
    ): ResponseEntity<ApiResponse<Map<String, Any?>>> {
        val principal = currentPrincipal()
        val unblocked = schedules.unblock(workspaceOf(principal), id, principal.userId)
        auditOf(principal, ScheduleAuditEvents.UNBLOCKED, unblocked)
        return withEtag(unblocked, HttpStatus.OK)
    }

    /** §20.12 — Run now: 202 with the queued run; a replayed `Idempotency-Key` answers 200 with the original (L1, L2). */
    @PostMapping("/{id}/run")
    @RequiredScope(Permission.SCHEDULE_RUN)
    fun runNow(
        @PathVariable id: UUID,
        @RequestHeader(value = WebHeaders.IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
    ): ResponseEntity<ApiResponse<Map<String, Any?>>> {
        val principal = currentPrincipal()
        val written = schedules.runNow(workspaceOf(principal), id, principal.userId, idempotencyKey)
        if (!written.replayed) {
            audit.log(
                event = ScheduleAuditEvents.RUN_REQUESTED,
                userId = principal.userId,
                details = mapOf("schedule_id" to id.toString(), "run_id" to written.value.id.toString()),
            )
        }
        return ResponseEntity
            .status(
                if (written.replayed) HttpStatus.OK else HttpStatus.ACCEPTED,
            ).body(ApiResponse.of(runJson(written.value)))
    }

    // ------------------------------------------------------------------------------ mapping

    private fun workspaceOf(principal: AuthenticatedPrincipal): UUID = principal.requireWorkspace().id

    private fun auditOf(
        principal: AuthenticatedPrincipal,
        event: String,
        schedule: Schedule,
    ) {
        audit.log(
            event = event,
            userId = principal.userId,
            details = mapOf("schedule_id" to schedule.id.toString(), "name" to schedule.name, "revision" to schedule.revision),
        )
    }

    /** A create/edit body (rest-api §20.2): `executor` defaults to `pipeline`, `parameters` to `{}`, the policy to `skip`. */
    private fun requestOf(body: String): ScheduleRequest {
        val node =
            runCatching { mapper.readTree(body) }.getOrNull()?.takeIf { it.isObject }
                ?: throw requestInvalid("body", "a JSON object")
        return ScheduleRequest(
            name = text(node, "name"),
            executor = node.path("executor").takeIf { it.isTextual }?.asText() ?: PipelineJobExecutor.EXECUTOR_ID,
            payload = node.get("payload") ?: throw requestInvalid("payload", "a JSON object"),
            parameters = node.get("parameters")?.takeUnless { it.isNull } ?: JsonNodeFactory.instance.objectNode(),
            cron = text(node, "cron"),
            timezone = text(node, "timezone"),
            missedRunPolicy = node.path("missed_run_policy").takeIf { it.isTextual }?.asText() ?: "skip",
        )
    }

    private fun text(
        node: JsonNode,
        field: String,
    ): String = node.path(field).takeIf { it.isTextual }?.asText() ?: throw requestInvalid(field, "a string")

    /** `If-Match` carries the revision, bare or quoted (`3`, `"3"`, `W/"3"`). */
    private fun revisionOf(ifMatch: String?): Int {
        val raw = IfMatchHeader.required(ifMatch).removePrefix("W/").trim('"')
        return raw.toIntOrNull()?.takeIf { it >= 1 } ?: throw requestInvalid(IfMatchHeader.NAME, "the schedule's revision (its ETag)")
    }

    private fun withEtag(
        schedule: Schedule,
        status: HttpStatus,
    ): ResponseEntity<ApiResponse<Map<String, Any?>>> =
        ResponseEntity
            .status(status)
            .header(HttpHeaders.ETAG, "\"${schedule.revision}\"")
            .body(ApiResponse.of(scheduleJson(schedule)))

    private fun scheduleJson(schedule: Schedule): Map<String, Any?> =
        linkedMapOf(
            "id" to schedule.id.toString(),
            "name" to schedule.name,
            "revision" to schedule.revision,
            "executor" to schedule.executorId,
            "payload_schema_version" to schedule.payloadSchemaVersion,
            "payload" to schedule.payload,
            "parameters" to schedule.parameters,
            "target_ref" to schedule.targetRef,
            "cron" to schedule.cron,
            "timezone" to schedule.timezone,
            "missed_run_policy" to schedule.missedRunPolicy.wire,
            "enabled" to schedule.enabled,
            "condition" to schedule.condition,
            "blocked" to
                schedule.blockedAt?.let {
                    mapOf("reason" to schedule.blockedReason, "at" to it.toString(), "run_id" to schedule.blockedRunId?.toString())
                },
            "next_due_at" to schedule.nextDueAt?.toString(),
            "created_by" to schedule.createdBy.toString(),
            "updated_by" to schedule.updatedBy.toString(),
            "created_at" to schedule.createdAt.toString(),
            "updated_at" to schedule.updatedAt.toString(),
        )

    private fun runJson(run: ScheduleRun): Map<String, Any?> =
        linkedMapOf(
            "id" to run.id.toString(),
            "schedule_id" to run.scheduleId?.toString(),
            "origin" to run.origin.wire,
            "scheduled_at" to run.scheduledAt?.toString(),
            "reference_at" to run.referenceAt.toString(),
            "reference_timezone" to run.referenceTimezone,
            "admit_by" to run.admitBy.toString(),
            "schedule_revision" to run.scheduleRevision,
            "state" to run.state.wire,
            "reason" to run.reason,
            "execution_id" to run.executionId?.toString(),
            "prepared" to run.prepared,
            "requested_by" to run.requestedBy?.toString(),
            "attempts" to run.attempts,
            "created_at" to run.createdAt.toString(),
            "claimed_at" to run.claimedAt?.toString(),
            "started_at" to run.startedAt?.toString(),
            "finished_at" to run.finishedAt?.toString(),
        )

    private fun runDetailJson(detail: RunDetail): Map<String, Any?> =
        runJson(detail.run) +
            mapOf("payload" to detail.run.payload, "parameters" to detail.run.parameters, "trail" to detail.trail.map(::trailJson))

    private fun trailJson(event: TrailEvent): Map<String, Any?> =
        linkedMapOf(
            "seq" to event.seq,
            "kind" to event.kind.wire,
            "reason" to event.reason,
            "at" to event.at.toString(),
            "worker" to event.worker,
            "details" to event.details,
        )

    private fun occurrenceJson(occurrence: Occurrence): Map<String, Any?> =
        linkedMapOf(
            "at" to occurrence.at.toString(),
            "local" to occurrence.local.toString(),
            "offset" to occurrence.offset.id,
        )

    private companion object {
        const val DEFAULT_PREVIEW = 5

        fun requestInvalid(
            field: String,
            expected: String,
        ) = ScheduleException(
            ScheduleErrorCodes.REQUEST_INVALID,
            "The schedule request's '$field' must be $expected.",
            mapOf("field" to field, "expected" to expected),
        )
    }
}
