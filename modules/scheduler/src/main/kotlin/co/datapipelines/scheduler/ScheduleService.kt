package co.datapipelines.scheduler

import co.datapipelines.pipeline.PipelineNameGrammar
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.dao.DuplicateKeyException
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

/**
 * **The schedule management use cases** (scheduler design revision §6) — the ONE scheduler type a
 * transport may name (`ArchitectureGuardTest`: the dispatcher, worker and reconciler are jobs, B5).
 * Every method takes the caller's workspace and acts only inside it (R9). The REST layer has
 * already judged the caller's permission (`@RequiredScope`); this service judges everything else.
 *
 * Run-inserting paths ([runNow]) lock the schedule row — the same lock the dispatcher's `SKIP
 * LOCKED` scan respects — so the overlap guard (record §3) is decided under one lock, and the
 * partial unique index on `schedule_runs` is the database's second line.
 *
 * Suppressions: TooManyFunctions — one method per REST route of record §6; LongParameterList — the
 * service's collaborators, each named above; ThrowsCount — every refusal is a DIFFERENT catalogued
 * code (§13.19) a caller must be able to tell apart, and folding them would lose that.
 */
@Suppress("TooManyFunctions", "LongParameterList", "ThrowsCount")
class ScheduleService(
    private val schedules: ScheduleRepository,
    private val runs: ScheduleRunRepository,
    private val executors: JobExecutors,
    private val ledger: RunLedger,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val properties: SchedulerProperties,
    private val queue: RunQueue,
    private val mapper: ObjectMapper,
    /** The system identity's user id (R2) — every run's `actor_user_id`. */
    private val systemActor: () -> UUID,
) {
    // ------------------------------------------------------------------------------ writes

    /**
     * Creates a schedule (record §6). With [idempotencyKey], a replay of the same request answers the
     * original schedule and a different request under the key is refused (L1, durable in Postgres).
     */
    fun create(
        workspaceId: UUID,
        actor: UUID,
        request: ScheduleRequest,
        idempotencyKey: String?,
    ): Written<Schedule> {
        val key = idempotencyKey?.let(::checkedKey)
        val hash = key?.let { hashOf(request) }
        key?.let { replayCreate(workspaceId, actor, it, hash!!) }?.let { return it }
        val validated = validate(workspaceId, request)
        return try {
            transactions.execute {
                if (schedules.countLive(workspaceId) >= properties.maxSchedulesPerWorkspace) {
                    throw ScheduleException(
                        ScheduleErrorCodes.LIMIT_PER_WORKSPACE,
                        "This workspace already holds ${properties.maxSchedulesPerWorkspace} schedules — the most it may.",
                        mapOf("limit" to properties.maxSchedulesPerWorkspace),
                    )
                }
                Written(
                    schedules.insert(
                        NewSchedule(
                            id = UUID.randomUUID(),
                            workspaceId = workspaceId,
                            name = validated.name,
                            executorId = validated.executor.id,
                            payloadSchemaVersion = validated.executor.payloadSchemaVersion,
                            payload = request.payload,
                            parameters = validated.parameters,
                            targetRef = validated.targetRef,
                            cron = validated.pattern.pattern,
                            timezone = validated.zone.id,
                            missedRunPolicy = validated.policy,
                            nextDueAt = OccurrenceFunction.next(validated.pattern, validated.zone, clock.instant()),
                            createdBy = actor,
                            idempotencyKey = key,
                            idempotencyHash = hash,
                        ),
                    ),
                    replayed = false,
                )
            }!!
        } catch (e: DuplicateKeyException) {
            // Two uniquenesses can refuse the insert: the create key (a concurrent replay) and the
            // live name. Re-read the key first — it is the one whose answer is not an error.
            key?.let { replayCreate(workspaceId, actor, it, hash!!) } ?: throw nameTaken(request.name, e)
        }
    }

    /** Edits a schedule, guarded by [expectedRevision] (`If-Match`, record §6). Timing changes recompute from now (D-9.6). */
    fun update(
        workspaceId: UUID,
        id: UUID,
        actor: UUID,
        expectedRevision: Int,
        request: ScheduleRequest,
    ): Schedule {
        val current = schedules.findLive(workspaceId, id) ?: throw notFound(id)
        if (current.revision != expectedRevision) throw revisionConflict(current)
        val validated = validate(workspaceId, request)
        val timingChanged = validated.pattern.pattern != current.cron || validated.zone.id != current.timezone
        val edit =
            ScheduleEdit(
                name = validated.name,
                executorId = validated.executor.id,
                payloadSchemaVersion = validated.executor.payloadSchemaVersion,
                payload = request.payload,
                parameters = validated.parameters,
                targetRef = validated.targetRef,
                cron = validated.pattern.pattern,
                timezone = validated.zone.id,
                missedRunPolicy = validated.policy,
                // An edit of the cron or timezone recomputes from now: old-pattern occurrences are not missed.
                nextDueAt = if (timingChanged) OccurrenceFunction.next(validated.pattern, validated.zone, clock.instant()) else null,
            )
        return try {
            schedules.update(workspaceId, id, expectedRevision, edit, actor, clock.instant())
                ?: throw schedules.findLive(workspaceId, id)?.let(::revisionConflict) ?: notFound(id)
        } catch (e: DuplicateKeyException) {
            throw nameTaken(request.name, e)
        }
    }

    /** Soft-deletes a schedule (D-9.6/B17); its runs and their trail stay, and a running execution runs on. */
    fun delete(
        workspaceId: UUID,
        id: UUID,
        actor: UUID,
        expectedRevision: Int,
    ) {
        val current = schedules.findLive(workspaceId, id) ?: throw notFound(id)
        if (current.revision != expectedRevision) throw revisionConflict(current)
        if (!schedules.softDelete(workspaceId, id, expectedRevision, actor, clock.instant())) {
            throw schedules.findLive(workspaceId, id)?.let(::revisionConflict) ?: notFound(id)
        }
    }

    /** Pauses a schedule; idempotent (record §1.1). */
    fun pause(
        workspaceId: UUID,
        id: UUID,
        actor: UUID,
    ): Schedule {
        val current = schedules.findLive(workspaceId, id) ?: throw notFound(id)
        if (!current.enabled) return current
        return schedules.setEnabled(workspaceId, id, enabled = false, nextDueAt = null, by = actor, now = clock.instant())
            ?: throw notFound(id)
    }

    /**
     * Resumes a schedule; idempotent. `next_due_at` is recomputed from now — occurrences due while
     * paused are not missed (D-9.6). A block is NOT cleared: that is [unblock] (record §1.1).
     */
    fun resume(
        workspaceId: UUID,
        id: UUID,
        actor: UUID,
    ): Schedule {
        val current = schedules.findLive(workspaceId, id) ?: throw notFound(id)
        if (current.enabled) return current
        val next =
            OccurrenceFunction.next(
                OccurrenceFunction.parse(current.cron),
                OccurrenceFunction.zone(current.timezone),
                clock.instant(),
            )
        return schedules.setEnabled(workspaceId, id, enabled = true, nextDueAt = next, by = actor, now = clock.instant())
            ?: throw notFound(id)
    }

    /**
     * Clears a block after the executor revalidates the payload (record §1.1): a payload that still
     * does not validate keeps the schedule blocked, answered with the executor's refusal. The person
     * is named on the trail of the run that caused the block.
     */
    fun unblock(
        workspaceId: UUID,
        id: UUID,
        actor: UUID,
    ): Schedule {
        val current = schedules.findLive(workspaceId, id) ?: throw notFound(id)
        if (!current.blocked) {
            throw ScheduleException(
                ScheduleErrorCodes.NOT_BLOCKED,
                "Schedule '${current.name}' is not blocked.",
                mapOf("schedule_id" to id.toString()),
            )
        }
        executors.require(current.executorId).validate(workspaceId, current.payload, current.parameters)
        val next =
            OccurrenceFunction.next(
                OccurrenceFunction.parse(current.cron),
                OccurrenceFunction.zone(current.timezone),
                clock.instant(),
            )
        return transactions.execute {
            val unblocked = schedules.unblock(workspaceId, id, next, actor, clock.instant()) ?: throw notFound(id)
            current.blockedRunId?.let { runId ->
                ledger.note(
                    runId,
                    TrailKind.UNBLOCKED,
                    current.blockedReason,
                    RunLedger.details("unblocked_by" to actor, "schedule_id" to id),
                )
            }
            unblocked
        }!!
    }

    /**
     * **Run now** (record §3): an explicit manual run, fired by the system identity (R2) with the
     * person recorded as `requested_by`. Refused while the schedule is blocked or has an active run
     * (409, D-9.6); allowed on a paused schedule. [idempotencyKey] is durable (L1).
     */
    fun runNow(
        workspaceId: UUID,
        id: UUID,
        actor: UUID,
        idempotencyKey: String?,
    ): Written<ScheduleRun> {
        val key = idempotencyKey?.let(::checkedKey)
        val hash = key?.let { sha256("run-now:$id") }
        return try {
            transactions.execute { runNowLocked(workspaceId, id, actor, key, hash) }!!
        } catch (e: DuplicateKeyException) {
            // A concurrent replay under the same key won the insert: answer it (L1).
            key?.let { replayRun(id, actor, it, hash!!) } ?: throw e
        }
    }

    private fun runNowLocked(
        workspaceId: UUID,
        id: UUID,
        actor: UUID,
        key: String?,
        hash: String?,
    ): Written<ScheduleRun> {
        val schedule = schedules.lockLive(workspaceId, id) ?: throw notFound(id)
        key?.let { replayRun(id, actor, it, hash!!) }?.let { return it }
        if (schedule.blocked) {
            throw ScheduleException(
                ScheduleErrorCodes.BLOCKED,
                "Schedule '${schedule.name}' is blocked (${schedule.blockedReason}); unblock it before running it.",
                mapOf("schedule_id" to id.toString(), "blocked_reason" to schedule.blockedReason),
            )
        }
        if (runs.hasActive(id)) {
            throw ScheduleException(
                ScheduleErrorCodes.RUN_OVERLAP,
                "Schedule '${schedule.name}' already has a run in progress; a schedule runs one at a time.",
                mapOf("schedule_id" to id.toString()),
            )
        }
        val now = clock.instant()
        val run =
            NewRun(
                id = UUID.randomUUID(),
                scheduleId = id,
                workspaceId = workspaceId,
                origin = RunOrigin.MANUAL,
                scheduledAt = null,
                referenceAt = now,
                referenceTimezone = schedule.timezone,
                admitBy = now.plus(properties.lateness),
                scheduleRevision = schedule.revision,
                executorId = schedule.executorId,
                payloadSchemaVersion = schedule.payloadSchemaVersion,
                payload = schedule.payload,
                parameters = schedule.parameters,
                actorUserId = systemActor(),
                requestedBy = actor,
                state = RunState.QUEUED,
                reason = null,
                createdAt = now,
                idempotencyKey = key,
                idempotencyHash = hash,
            )
        runs.insertManual(run)
        ledger.note(run.id, TrailKind.RECORDED, null, RunLedger.details("origin" to RunOrigin.MANUAL.wire, "requested_by" to actor))
        queue.enqueue(run.id, now)
        return Written(runs.find(run.id)!!, replayed = false)
    }

    // ------------------------------------------------------------------------------ reads

    /** One live schedule the [viewer] may see, or `schedule.not_found` (a hidden one answers as absent). */
    fun get(
        workspaceId: UUID,
        id: UUID,
        viewer: TargetViewer,
    ): Schedule = visible(workspaceId, id, viewer)

    /** The workspace's live schedules the [viewer] may see, under a folder [prefix]. */
    fun list(
        workspaceId: UUID,
        prefix: String?,
        limit: Int,
        offset: Int,
        viewer: TargetViewer,
    ): List<Schedule> {
        prefix?.takeIf { it.isNotEmpty() }?.let {
            if (!PipelineNameGrammar.matchesPrefix(it)) {
                throw requestInvalid("prefix", "a folder path: 1 to 9 lower-case segments separated by `/`")
            }
        }
        val page = schedules.listLive(workspaceId, prefix, limit, offset)
        if (!viewer.narrowed) return page
        val admitted =
            page.groupBy { it.executorId }.flatMap { (executorId, group) ->
                val executor = executors.find(executorId) ?: return@flatMap emptyList()
                executor.visibleTargets(viewer, workspaceId, group.map { it.targetRef }).let { refs ->
                    group.filter { it.targetRef in refs }
                }
            }
        return page.filter { it in admitted }
    }

    /** The saved schedule's next [count] occurrences — the ONE function (record §3.2). */
    fun upcoming(
        workspaceId: UUID,
        id: UUID,
        count: Int,
        viewer: TargetViewer,
    ): List<Occurrence> {
        val schedule = visible(workspaceId, id, viewer)
        return preview(schedule.cron, schedule.timezone, count)
    }

    /** A form's preview before any save (record §6). */
    fun preview(
        cron: String,
        timezone: String,
        count: Int,
    ): List<Occurrence> {
        if (count !in 1..MAX_PREVIEW) throw requestInvalid("count", "1 to $MAX_PREVIEW")
        return OccurrenceFunction.upcoming(OccurrenceFunction.parse(cron), OccurrenceFunction.zone(timezone), clock.instant(), count)
    }

    /** A schedule's runs, newest first. */
    fun runs(
        workspaceId: UUID,
        scheduleId: UUID,
        limit: Int,
        offset: Int,
        viewer: TargetViewer,
    ): List<ScheduleRun> {
        visible(workspaceId, scheduleId, viewer)
        return runs.listForSchedule(workspaceId, scheduleId, limit, offset)
    }

    /** One run and its trail (R10). */
    fun run(
        workspaceId: UUID,
        scheduleId: UUID,
        runId: UUID,
        viewer: TargetViewer,
    ): RunDetail {
        visible(workspaceId, scheduleId, viewer)
        val run =
            runs.findInSchedule(workspaceId, scheduleId, runId)
                ?: throw ScheduleException(
                    ScheduleErrorCodes.RUN_NOT_FOUND,
                    "No run '$runId' under schedule '$scheduleId'.",
                    mapOf("schedule_id" to scheduleId.toString(), "run_id" to runId.toString()),
                )
        return RunDetail(run, runs.trail(runId))
    }

    /** Live schedules in [workspaceId] — the workspace delete's content count. */
    fun countLive(workspaceId: UUID): Int = schedules.countLive(workspaceId)

    // ------------------------------------------------------------------------------ helpers

    private fun visible(
        workspaceId: UUID,
        id: UUID,
        viewer: TargetViewer,
    ): Schedule {
        val schedule = schedules.findLive(workspaceId, id) ?: throw notFound(id)
        if (viewer.narrowed) {
            val executor = executors.find(schedule.executorId) ?: throw notFound(id)
            // A schedule outside the lens answers exactly as an absent one (auth.md §11A.1).
            if (schedule.targetRef !in executor.visibleTargets(viewer, workspaceId, listOf(schedule.targetRef))) throw notFound(id)
        }
        return schedule
    }

    /** Everything a create or an edit must satisfy before a row is written (record §3, §5, §6). */
    private fun validate(
        workspaceId: UUID,
        request: ScheduleRequest,
    ): Validated {
        val name = request.name.trim()
        if (!PipelineNameGrammar.matches(name)) {
            throw ScheduleException(
                ScheduleErrorCodes.NAME_INVALID,
                "'${name.take(MAX_ECHO)}' is not a schedule name. ${PipelineNameGrammar.DESCRIPTION}",
                mapOf("name" to name.take(MAX_ECHO), "reason" to PipelineNameGrammar.refusalReason(name)),
            )
        }
        val executor = executors.require(request.executor)
        checkShape("payload", request.payload)
        checkShape("parameters", request.parameters)
        val pattern = OccurrenceFunction.parse(request.cron)
        val zone = OccurrenceFunction.zone(request.timezone)
        val spacing = OccurrenceFunction.minSpacing(pattern, zone, clock.instant(), properties.minInterval)
        if (spacing != null && spacing < properties.minInterval) {
            throw ScheduleException(
                ScheduleErrorCodes.INTERVAL_TOO_SHORT,
                "'${pattern.pattern}' fires ${spacing.seconds} s apart; this deployment's floor is ${properties.minIntervalSeconds} s.",
                mapOf(
                    "cron" to pattern.pattern,
                    "spacing_seconds" to spacing.seconds,
                    "min_interval_seconds" to properties.minIntervalSeconds,
                ),
            )
        }
        val policy =
            MissedRunPolicy.fromWire(request.missedRunPolicy)
                ?: throw requestInvalid("missed_run_policy", "one of ${MissedRunPolicy.entries.map { it.wire }}")
        val targetRef = executor.validate(workspaceId, request.payload, request.parameters)
        return Validated(name, executor, request.parameters, targetRef, pattern, zone, policy)
    }

    /** The generic limits a payload honours whatever its executor (record §5): an object, 16 KiB, depth 8. */
    private fun checkShape(
        field: String,
        node: JsonNode,
    ) {
        val refusal =
            when {
                !node.isObject -> "must be a JSON object"
                mapper.writeValueAsBytes(node).size > MAX_PAYLOAD_BYTES -> "must be at most ${MAX_PAYLOAD_BYTES / KIB} KiB"
                depthOf(node) > MAX_PAYLOAD_DEPTH -> "must nest at most $MAX_PAYLOAD_DEPTH levels"
                else -> null
            } ?: return
        throw ScheduleException(
            ScheduleErrorCodes.PAYLOAD_INVALID,
            "The schedule's $field $refusal.",
            mapOf("field" to field, "reason" to refusal),
        )
    }

    private fun replayCreate(
        workspaceId: UUID,
        actor: UUID,
        key: String,
        hash: String,
    ): Written<Schedule>? {
        val (existing, storedHash) = schedules.findByIdempotencyKey(workspaceId, actor, key) ?: return null
        if (storedHash != hash) throw keyReused(key)
        return Written(existing, replayed = true)
    }

    private fun replayRun(
        scheduleId: UUID,
        actor: UUID,
        key: String,
        hash: String,
    ): Written<ScheduleRun>? {
        val (existing, storedHash) = runs.findByIdempotencyKey(scheduleId, actor, key) ?: return null
        if (storedHash != hash) throw keyReused(key)
        return Written(existing, replayed = true)
    }

    /** The request's canonical hash (sorted keys), so a replay compares meaning, not whitespace. */
    private fun hashOf(request: ScheduleRequest): String {
        val canonical = mapper.createObjectNode()
        canonical.put("name", request.name.trim())
        canonical.put("executor", request.executor)
        canonical.set<JsonNode>("payload", sorted(request.payload))
        canonical.set<JsonNode>("parameters", sorted(request.parameters))
        canonical.put("cron", request.cron.trim())
        canonical.put("timezone", request.timezone.trim())
        canonical.put("missed_run_policy", request.missedRunPolicy)
        return sha256(mapper.writeValueAsString(canonical))
    }

    private fun sorted(node: JsonNode): JsonNode =
        when {
            node.isObject -> {
                val out = mapper.createObjectNode()
                node
                    .fieldNames()
                    .asSequence()
                    .sorted()
                    .forEach { out.set<JsonNode>(it, sorted(node.get(it))) }
                out
            }

            node.isArray -> {
                mapper.createArrayNode().also { array -> node.forEach { array.add(sorted(it)) } }
            }

            else -> {
                node
            }
        }

    private fun checkedKey(key: String): String {
        val trimmed = key.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_KEY_CHARS) {
            throw requestInvalid("Idempotency-Key", "1 to $MAX_KEY_CHARS characters")
        }
        return trimmed
    }

    private fun notFound(id: UUID) =
        ScheduleException(ScheduleErrorCodes.NOT_FOUND, "No schedule '$id' in this workspace.", mapOf("schedule_id" to id.toString()))

    private fun revisionConflict(current: Schedule) =
        ScheduleException(
            ScheduleErrorCodes.REVISION_CONFLICT,
            "Schedule '${current.name}' changed since you read it (now revision ${current.revision}); re-read it and retry.",
            mapOf("schedule_id" to current.id.toString(), "current_revision" to current.revision),
        )

    private fun nameTaken(
        name: String,
        cause: DuplicateKeyException,
    ) = ScheduleException(
        ScheduleErrorCodes.NAME_TAKEN,
        "A schedule named '${name.trim().take(MAX_ECHO)}' already exists in this workspace.",
        mapOf("name" to name.trim().take(MAX_ECHO)),
        cause,
    )

    private fun keyReused(key: String) =
        ScheduleException(
            ScheduleErrorCodes.IDEMPOTENCY_KEY_REUSED,
            "The Idempotency-Key '${key.take(MAX_ECHO)}' was already used for a different request.",
            mapOf("idempotency_key" to key.take(MAX_ECHO)),
        )

    companion object {
        /** The preview's and upcoming's most occurrences (record §6). */
        const val MAX_PREVIEW = 20

        /** The generic payload size cap (record §5). */
        const val MAX_PAYLOAD_BYTES = 16 * 1024

        /** The generic payload depth cap (record §5). */
        const val MAX_PAYLOAD_DEPTH = 8

        private const val KIB = 1024
        private const val MAX_KEY_CHARS = 255
        private const val MAX_ECHO = 64

        private fun requestInvalid(
            field: String,
            expected: String,
        ) = ScheduleException(
            ScheduleErrorCodes.REQUEST_INVALID,
            "The schedule request's '$field' must be $expected.",
            mapOf("field" to field, "expected" to expected),
        )

        private fun depthOf(node: JsonNode): Int =
            if (node.isContainerNode) 1 + (node.elements().asSequence().maxOfOrNull(::depthOf) ?: 0) else 0

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** A create or an edit, as the REST body carries it (record §6). */
data class ScheduleRequest(
    val name: String,
    val executor: String,
    val payload: JsonNode,
    val parameters: JsonNode,
    val cron: String,
    val timezone: String,
    val missedRunPolicy: String,
)

/** A write's result; [replayed] when an idempotent replay answered an earlier write (L1). */
data class Written<T>(
    val value: T,
    val replayed: Boolean,
)

/** One run with its trail. */
data class RunDetail(
    val run: ScheduleRun,
    val trail: List<TrailEvent>,
)

private data class Validated(
    val name: String,
    val executor: JobExecutor,
    val parameters: JsonNode,
    val targetRef: String,
    val pattern: CronPattern,
    val zone: java.time.ZoneId,
    val policy: MissedRunPolicy,
)
