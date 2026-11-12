package co.datapipelines.pipeline

import co.datapipelines.pipeline.release.ReleasedDependency
import co.datapipelines.typesystem.DatapipelinesException
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * #328 (D3) — what a release did about its caller node's result columns: the one word the
 * release response (`caller_output`, rest-api §5.10) and the `pipeline.version.released` audit
 * row carry. A release is NEVER refused for a missing record — `not_observed` is today's
 * skip-the-check behaviour, recorded honestly.
 */
enum class ReleaseCallerOutput(
    val wire: String,
) {
    /** The flip copied the version's latest qualifying execution's schema (D1 hit). */
    RECORDED("recorded"),

    /** The caller node is a SCRIPT: the pinned contract declares the columns and outranks any copied record (D4). */
    DECLARED("declared"),

    /** The released body has no caller node — it returns no rows at all. */
    NONE("none"),

    /** A SQL caller node with no qualifying execution: released with a NULL record. */
    NOT_OBSERVED("not_observed"),
    ;

    companion object {
        /** Strict read of the wire word — an unknown value is corruption, not a default. */
        fun fromWire(raw: String): ReleaseCallerOutput =
            entries.firstOrNull { it.wire == raw } ?: error("Unknown caller output word '$raw'")
    }
}

/**
 * Pipeline release and discard (versioning §5.3/§5.4) — the human half of the lifecycle.
 *
 * Release is an explicit, UI-driven action (D4: agents never release; the REST endpoint
 * exists for the editor, and an agent holding a raw MUTATE-scoped key calling it directly
 * is acceptable — "not in the agent" means no first-class tool surface). Its three
 * preconditions, evaluated server-side in order before the one-statement flip:
 *
 * 1. A DRAFT exists (`pipeline.version.not_draft` otherwise — there is nothing to release).
 * 2. Full pipeline-contract §12 validation re-runs on the draft body. Release is the final
 *    save-time gate; nothing is released the validator would refuse.
 * 3. **Every template version pinned by the draft body is RELEASED** (versioning §6 —
 *    templates lock first). A pin on a DRAFT template version fails with
 *    `pipeline.release.dependency_not_released` naming the template and version; pinning a
 *    draft template from a draft pipeline is legal while iterating and only becomes an
 *    error here. The caller may consent to releasing the draft dependencies with the pipeline
 *    (#517's plan, or 142's `release_pinned_templates` alias).
 *
 * Since #877 (§E.3) this service owns no release path of its own: every release runs through the
 * `web` release coordinator (`PipelineService` requires one), which walks the dependency closure,
 * refuses on the guard and flips every node in ONE transaction. This class keeps the pipeline
 * participant's seams the coordinator calls — [prepare], [revalidateStrict], [flip], [finish] and
 * [warnings] — and the draft [purge].
 *
 * Releasing is never refused for the semantic link (transform-nodes design §8.2, 7e): a pin
 * that reads `needs_review` — it cites a retired learned fact — is released as asked and
 * reported in [Released.warnings] as `pipeline.release.template_needs_review`, read through
 * the [TemplateReviewMarks] port AFTER the flip. A fact edit never blocks a release on its own.
 *
 * The hash precondition (§4.2) rides the flip statement itself: `you release what you
 * tested`. The draft verb is PURGE (§5.4, 101): the row is hard-deleted together with its
 * executions — the FK never forces a tombstone — and the sole-draft case takes the entity
 * row with it.
 */
open class PipelineReleaseService(
    private val pipelines: PipelineRepository,
    private val validator: PipelineValidator,
    private val authoring: AuthoringGuard,
    private val deserializer: PipelineDeserializer = PipelineDeserializer(),
    /**
     * The 140 release-check gate, as the port this module declares — `web` wires the
     * implementation riding `modules/application`'s check runner. [ReleaseCheckGate.NONE]
     * keeps pre-140 constructions (unit tests of the lifecycle alone) releasing as before.
     */
    private val checkGate: ReleaseCheckGate = ReleaseCheckGate.NONE,
    /**
     * 7e — which pinned template versions cite a retired fact (the `needs_review` read), as the
     * port this module declares; `web` wires it over the templates module's citation read.
     * [TemplateReviewMarks.NONE] keeps pre-7e constructions releasing with no warnings.
     */
    private val reviewMarks: TemplateReviewMarks = TemplateReviewMarks.NONE,
    /** Required dashboard pin evidence for purge; production supplies the workspace-scoped consumer. */
    private val dashboards: PipelineVersionConsumers,
    /** #786 B.4 — the live API versions pinning a draft: a purge refuses them like a dashboard (record §14, A39). */
    private val endpointVersions: EndpointVersionConsumers,
    /** #786 B.4 — the live schedules running a draft (development posture's CURRENT draft) refuse its purge too. */
    private val schedules: ScheduleConsumers,
) {
    private val pinGuard = PipelinePurgePinGuard(pipelines, dashboards, endpointVersions, schedules)

    /** What a release produced: the bumped record, the released version, the released body. */
    data class Released(
        val record: PipelineRecord,
        val version: PipelineVersionDetail,
        val bodyJson: String,
        /**
         * The ids of the checks that did NOT pass and were overridden to release anyway
         * (140) — empty on a clean release. The surfaces add it to the
         * `pipeline.version.released` audit event as `checks_overridden`.
         */
        val checksOverridden: List<String> = emptyList(),
        /** The override reason the request carried, when [checksOverridden] is non-empty. */
        val checksOverrideReason: String? = null,
        /**
         * The template versions the release CASCADED to (142) — each a DRAFT pin the caller
         * consented to release in the flip's transaction, in pin order, deduplicated; empty
         * on a release that cascaded nothing. The surfaces emit one `template.version.released`
         * audit event per entry and list them on the pipeline's own event.
         */
        val templatesReleased: List<TemplateRef> = emptyList(),
        /**
         * 7e (transform-nodes design §8.2) — what the releaser should know about what was
         * released, never a refusal: one `pipeline.release.template_needs_review` per pin that
         * cites a retired learned fact, in pin order; empty on a clean release. The REST body
         * carries it as `warnings`; the dialog renders the same facts on its pin rows.
         */
        val warnings: List<ReleaseWarning> = emptyList(),
        /**
         * #328 (D3) — what the release did about its caller node's result columns, as the one
         * word the response, the audit row and every doc share: `recorded` (the D1 read found
         * the version's latest run and copied its schema), `declared` (a SCRIPT caller — the
         * pinned contract is the answer; a copied record never overrides it), `none` (no caller node), or
         * `not_observed` (a SQL caller node with no qualifying execution — the version released
         * anyway, recording NULL; today's validator behaviour continues for that release).
         * Never the column list: ids, names, versions and this word are all an audit row says
         * (enums.md §15). The owner may later rule that `not_observed` refuses; that is a
         * one-line change behind this field.
         */
        val callerOutput: ReleaseCallerOutput = ReleaseCallerOutput.NONE,
        /**
         * #517 (record §6) — every dependency the release flipped, in release order, whatever its family; the REST
         * body appends it as `dependencies_released` only to a request that carried a plan, so a legacy request's
         * bytes are unchanged.
         */
        val dependenciesReleased: List<ReleasedDependency> = emptyList(),
        /**
         * #517 (record §11) — true when the release coordinator already wrote every `*.version.released` row in the
         * release's own transaction; the surfaces' after-commit audit then emits nothing for it.
         */
        val auditedInTransaction: Boolean = false,
    )

    /**
     * A release prepared OUTSIDE any metadata transaction (#517, record §4.1 step 4): the draft it found, the body
     * that will become immutable, validated, and the release-check gate's verdict.
     */
    data class Prepared(
        val workspaceId: UUID,
        val pipelineId: UUID,
        val draft: PipelineVersionDetail,
        val bodyJson: String,
        val pipeline: Pipeline,
        val checksOverridden: List<String>,
        val checksOverrideReason: String?,
    )

    /**
     * A release's first steps, OUTSIDE any metadata transaction (#517, record §4.1 step 4): the authoring guard, the
     * draft read, the body re-validated exactly as a save would (§5.3 precondition 3, so release can never launder an
     * invalid draft) and the release-check gate (140). The gate runs the body's `checks[]` fresh, `via = release`, outside
     * the transaction because its probes open customer-datasource connections, which `ConnectionLease` refuses with
     * `datasource.lease_in_transaction` while a metadata transaction is open (056 §E.2). Any `fail` or `error` verdict
     * refuses with `pipeline.check.failed` unless [overrideChecksReason] carries at least [OVERRIDE_REASON_MIN_CHARS]
     * characters; a body with no checks skips the gate. With
     * [allowDraftChildren] a DRAFT child pipeline pin passes validation — the coordinator flips that child first and
     * re-validates strictly in its transaction ([revalidateStrict]).
     */
    open fun prepare(
        workspaceId: UUID,
        pipelineId: UUID,
        actor: UUID,
        overrideChecksReason: String?,
        allowDraftChildren: Boolean = false,
    ): Prepared {
        // §5.5: release is an authoring action — a promotion receiver refuses it.
        authoring.requirePipelineAuthoring()

        val draft =
            pipelines.findDraftDetail(workspaceId, pipelineId)
                ?: throw notDraft(pipelineId)

        val snapshot = pipelines.findDraftBodySnapshot(workspaceId, pipelineId, draft.version) ?: throw notDraft(pipelineId)
        val bodyJson = snapshot.bodyJson
        val pipeline = deserializer.readOrThrow(bodyJson)
        validator.validateOrThrow(pipeline, workspaceId, allowDraftPipelineReferences = allowDraftChildren)

        val overridden = runCheckGate(workspaceId, pipelineId, draft.version, pipeline, actor, overrideChecksReason)
        return Prepared(
            workspaceId,
            pipelineId,
            draft.copy(bodyHash = snapshot.bodyHash),
            bodyJson,
            pipeline,
            overridden.first,
            overridden.second,
        )
    }

    /** Strict §12 validation of a prepared body, in the caller's transaction: children flipped in it now read RELEASED. */
    open fun revalidateStrict(prepared: Prepared) {
        validator.validateOrThrow(prepared.pipeline, prepared.workspaceId)
    }

    /**
     * The one-statement flip of a prepared draft at exactly [expectedHash], in the release coordinator's transaction
     * (#517). A stale hash or a vanished draft refuses with `pipeline.version.conflict` / `pipeline.version.not_draft`.
     */
    open fun flip(
        prepared: Prepared,
        expectedHash: String,
        actor: UUID,
    ): PipelineRepository.Released {
        if (prepared.draft.bodyHash != expectedHash) throw conflictAfterGuardFailure(prepared.workspaceId, prepared.pipelineId)
        return pipelines.releaseDraft(
            workspaceId = prepared.workspaceId,
            pipelineId = prepared.pipelineId,
            name = prepared.pipeline.name,
            displayName = prepared.pipeline.displayName,
            description = prepared.pipeline.description,
            expectedHash = prepared.draft.bodyHash,
            actor = actor,
            expectedVersion = prepared.draft.version,
        ) ?: throw conflictAfterGuardFailure(prepared.workspaceId, prepared.pipelineId)
    }

    /**
     * The release's result after its transaction committed: 7e's warnings are read AFTER the flip and outside its
     * transaction — a warning describes what was released and can never unwind it. The coordinator (#517) builds the
     * result inside its transaction for the strict audit row with [readWarnings] false, and adds [warnings] after commit.
     */
    open fun finish(
        prepared: Prepared,
        flipped: PipelineRepository.Released,
        cascaded: List<TemplateRef>,
        dependenciesReleased: List<ReleasedDependency> = emptyList(),
        auditedInTransaction: Boolean = false,
        readWarnings: Boolean = true,
    ): Released =
        Released(
            flipped.record,
            flipped.version,
            prepared.bodyJson,
            prepared.checksOverridden,
            prepared.checksOverrideReason,
            cascaded,
            if (readWarnings) warnings(prepared) else emptyList(),
            callerOutputOf(prepared.pipeline, flipped.version),
            dependenciesReleased,
            auditedInTransaction,
        )

    /** 7e's review warnings for a prepared body — read after the release committed, never inside it. */
    open fun warnings(prepared: Prepared): List<ReleaseWarning> = reviewWarnings(prepared.workspaceId, prepared.pipeline)

    /**
     * #328 (D3) — the release's one word about its caller output, decided from the body the
     * flip just locked and the row the flip just wrote: a caller node that is a SCRIPT
     * answers `declared` (its pinned contract names the columns; the record, if one exists,
     * never overrides it — D4), no caller node answers `none`, and a SQL caller node answers
     * `recorded` when the flip copied a record and `not_observed` when it found nothing.
     *
     * Resolution is `singleOrNull`, not [CallerNodeResolver.resolve]: the resolver's
     * multiple-caller throw is the EXECUTOR's defensive answer to an unvalidated pipeline,
     * while this body has already passed §12 (which rejects >1 caller before the flip) —
     * a second resolution path that can throw mid-release would only re-litigate what the
     * precondition settled.
     */
    private fun callerOutputOf(
        pipeline: Pipeline,
        flipped: PipelineVersionDetail,
    ): ReleaseCallerOutput {
        val caller = pipeline.nodes.singleOrNull { it.isCallerNode } ?: return ReleaseCallerOutput.NONE
        return when {
            caller.type == NodeType.SCRIPT -> ReleaseCallerOutput.DECLARED
            flipped.callerOutputJson != null -> ReleaseCallerOutput.RECORDED
            else -> ReleaseCallerOutput.NOT_OBSERVED
        }
    }

    /**
     * The §8.2 warnings: every distinct pin of the released body whose version cites a retired
     * learned fact, in pin order, one warning each naming every retired fact and its successor.
     */
    private fun reviewWarnings(
        workspaceId: UUID,
        pipeline: Pipeline,
    ): List<ReleaseWarning> {
        val pins = templatePins(pipeline)
        if (pins.isEmpty()) return emptyList()
        val marks = reviewMarks.retiredCitations(workspaceId, pins)
        return pins.mapNotNull { pin ->
            marks[pin]?.takeIf { it.isNotEmpty() }?.let { ReleaseWarning.templateNeedsReview(pin, it) }
        }
    }

    /**
     * The body's template pins, distinct, in node order — only the nodes that HAVE one: a
     * PIPELINE node pins a child pipeline and a CALCULATOR node a catalog function; asking about
     * their empty `@0` template would answer nothing true (`PromotionTwoDeploymentE2eTest` found
     * the defect when the release guard asked it).
     */
    private fun templatePins(pipeline: Pipeline): List<TemplateRef> =
        pipeline.nodes
            .filter { it.type != NodeType.PIPELINE && it.type != NodeType.CALCULATOR }
            .map { it.template }
            .distinct()

    /**
     * The 140 gate: run the body's checks fresh (`via = release`) and refuse on any verdict
     * short of [CheckRunVerdict.PASS], unless the override reason rides the request.
     *
     * Returns the overridden check ids and the trimmed reason for the audit event; both
     * empty on a clean pass or a check-less version. The run rows the gate's implementation
     * persists are what the refusal's `details.checks` and the UI's latest-run list both
     * read — one run, one truth.
     */
    private fun runCheckGate(
        workspaceId: UUID,
        pipelineId: UUID,
        version: Int,
        pipeline: Pipeline,
        actor: UUID,
        overrideChecksReason: String?,
    ): Pair<List<String>, String?> {
        if (pipeline.checks.isEmpty()) return emptyList<String>() to null
        val outcomes = checkGate.runForRelease(workspaceId, pipelineId, version, pipeline, actor)
        val failing = outcomes.filter { it.verdict != CheckRunVerdict.PASS }
        if (failing.isEmpty()) return emptyList<String>() to null
        val reason = overrideChecksReason?.trim()
        if (reason != null && reason.length >= OVERRIDE_REASON_MIN_CHARS) {
            return failing.map { it.checkId } to reason
        }
        throw DatapipelinesException(
            code = PipelineErrorCodes.Check.FAILED,
            message =
                "Release blocked: ${failing.size} of ${outcomes.size} release checks did not pass. " +
                    "Fix the pipeline or the data, or release with an override_checks_reason of at least " +
                    "$OVERRIDE_REASON_MIN_CHARS characters (it is audited).",
            details =
                mapOf(
                    "pipeline_id" to pipelineId.toString(),
                    "version" to version,
                    "checks" to failing.map { checkFailureDetails(it) },
                ),
        )
    }

    /** One failing check's entry in the `pipeline.check.failed` refusal's `details.checks`. */
    private fun checkFailureDetails(outcome: CheckRunOutcome): Map<String, Any?> =
        mapOf(
            "check_id" to outcome.checkId,
            "name" to outcome.name,
            "expected" to expectedDetails(outcome.expected),
            "observed" to outcome.observed,
            "verdict" to outcome.verdict.wire,
            "message" to outcome.message,
        )

    private fun expectedDetails(expected: CheckExpectation): Map<String, Any?> =
        buildMap {
            put("kind", expected.kind)
            expected.value?.let { put("value", it) }
            expected.min?.let { put("min", it) }
            expected.max?.let { put("max", it) }
            expected.rows?.let { put("rows", it) }
            expected.tolerance?.let { put("tolerance", it) }
        }

    /** What a draft purge did (§5.4, 101) — the row (and its executions) are gone either way. */
    sealed interface Purged {
        /** How many execution rows went with the draft (§5.4) — the audit detail. */
        val executionsDeleted: Int

        /** The draft went; other versions remain, so the entity stays. */
        data class Version(
            override val executionsDeleted: Int,
            val record: PipelineRecord,
        ) : Purged

        /** The draft was the ONLY version: the entity row went with it (§3.2). */
        data class Entity(
            override val executionsDeleted: Int,
        ) : Purged
    }

    /**
     * Purges the pipeline's DRAFT at [expectedHash] (versioning §5.4, 101): the row is
     * hard-deleted **together with its executions** — the pre-101 flip-to-DISCARDED branch
     * is withdrawn; development runs of a thing that never shipped are not history. When
     * the draft was the sole version the entity row goes too, and when the draft had
     * become `current_version` (the development fallback) the pointer recomputes.
     *
     * @throws DatapipelinesException `pipeline.authoring.disabled` (§5.5) or
     *   `pipeline.version.conflict` (stale hash / the draft vanished), or
     *   `pipeline.version.pinned` (named parent/dashboard blockers, before deletion).
     */
    @Transactional("metadataTransactionManager")
    open fun purge(
        workspaceId: UUID,
        pipelineId: UUID,
        expectedHash: String,
    ): Purged {
        // §5.5: purging authored content is authoring — a receiver's sole writer is promotion.
        authoring.requirePipelineAuthoring()

        val draft = requirePurgeDraft(workspaceId, pipelineId, expectedHash)
        val record = pipelines.findByIdAnyStatus(workspaceId, pipelineId) ?: throw notDraft(pipelineId)
        pinGuard.refuseIfDraftPinned(workspaceId, record, draft.version)

        return when (
            val outcome =
                pipelines.purgeDraft(
                    workspaceId,
                    pipelineId,
                    expectedHash,
                    draftEligible = authoring.developmentPosture,
                )
        ) {
            is PurgeOutcome.VersionPurged -> Purged.Version(outcome.executionsDeleted, outcome.record)
            is PurgeOutcome.EntityPurged -> Purged.Entity(outcome.executionsDeleted)
            null -> throw conflictAfterGuardFailure(workspaceId, pipelineId)
        }
    }

    private fun requirePurgeDraft(
        workspaceId: UUID,
        pipelineId: UUID,
        expectedHash: String,
    ): PipelineVersionDetail {
        // Status and hash refusals precede the pin preflight.
        val draft = pipelines.findDraftDetail(workspaceId, pipelineId) ?: throw notDraft(pipelineId)
        if (draft.bodyHash != expectedHash) throw conflictAfterGuardFailure(workspaceId, pipelineId)
        return draft
    }

    /**
     * The guard failed AFTER we saw a draft — either the hash went stale under us (409
     * conflict with the current state) or the draft vanished (discard raced us: not_draft).
     */
    private fun conflictAfterGuardFailure(
        workspaceId: UUID,
        pipelineId: UUID,
    ): DatapipelinesException {
        val current = pipelines.findDraftDetail(workspaceId, pipelineId) ?: throw notDraft(pipelineId)
        return DatapipelinesException(
            code = PipelineErrorCodes.Versioning.VERSION_CONFLICT,
            message = "Pipeline was modified by someone else after you loaded it.",
            details =
                mapOf(
                    "current_body_hash" to current.bodyHash,
                    "current_status" to current.status.name,
                    "updated_by" to (current.updatedBy?.toString() ?: ""),
                    "updated_at" to (current.updatedAt?.toString() ?: ""),
                ),
        )
    }

    private fun notDraft(pipelineId: UUID): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Versioning.NOT_DRAFT,
            message = "Pipeline '$pipelineId' has no draft to release or discard.",
            details = mapOf("pipeline_id" to pipelineId.toString()),
        )

    companion object {
        /**
         * §13.17 (140) — the minimum length of the `override_checks_reason` a release past
         * failing checks must carry. Bounded below so "ok" cannot stand as a reason: the
         * override is audited with the release, and a reason shorter than a sentence is not
         * one.
         */
        const val OVERRIDE_REASON_MIN_CHARS = 10
    }
}
