package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.util.UUID

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
 *    `pipeline.release.template_not_released` naming the template and version; pinning a
 *    draft template from a draft pipeline is legal while iterating and only becomes an
 *    error here. Since 142 the caller may CONSENT to the cascade instead
 *    (`releasePinnedTemplates = true`): every DRAFT pin is released through the
 *    [TemplateReleaser] port in the SAME transaction as the flip, templates first — one
 *    consent, one transaction, and the result names what was released so the surfaces can
 *    audit each template as if released by hand. A DISCARDED or MISSING pin is never
 *    releasable and refuses exactly as before, flag or no flag.
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
    private val templates: TemplateVersionStatuses,
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
     * The 142 release cascade's write, as the port this module declares — `web` wires it over
     * the template's own `TemplateReleaseService`. [TemplateReleaser.NONE] fails loudly if a
     * cascade is ever asked of a construction that did not wire it; with the flag false (the
     * default) the port is never touched, so pre-142 constructions behave as before.
     */
    private val templateReleaser: TemplateReleaser = TemplateReleaser.NONE,
    /**
     * The transaction the template-pin guard and the one-statement flip run in (see
     * [release]). A `TransactionTemplate` over `metadataTransactionManager` in production;
     * the default executes the action directly, which is what a directly-constructed test
     * always did. Programmatic rather than annotated because the check gate MUST run outside
     * the metadata transaction: its probes open customer-datasource connections, and
     * `ConnectionLease` refuses exactly that with `datasource.lease_in_transaction` while a
     * metadata transaction is open on the thread (056 §E.2).
     */
    private val transactions: TransactionOperations = DIRECT_TRANSACTIONS,
    /**
     * 7e — which pinned template versions cite a retired fact (the `needs_review` read), as the
     * port this module declares; `web` wires it over the templates module's citation read.
     * [TemplateReviewMarks.NONE] keeps pre-7e constructions releasing with no warnings.
     */
    private val reviewMarks: TemplateReviewMarks = TemplateReviewMarks.NONE,
) {
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
    )

    /**
     * Releases the pipeline's DRAFT at [expectedHash].
     *
     * Order of operations, and why the boundary sits where it does:
     *
     * 1. Authoring guard, draft read, body re-validation — plain reads.
     * 2. **The release-check gate (140):** when the body carries `checks[]`, they run NOW,
     *    fresh, `via = release`, OUTSIDE any metadata transaction (the probes open
     *    customer-datasource connections; see [transactions]). Any `fail` or `error` verdict
     *    refuses the release with `pipeline.check.failed` — unless [overrideChecksReason]
     *    is a non-blank string of at least [OVERRIDE_REASON_MIN_CHARS] characters, in which
     *    case the release proceeds and the result carries the overridden ids for the audit
     *    event. A version with no checks skips the gate entirely: checks are opt-in.
     * 3. The template-pin guard, the cascade and the flip, inside [transactions]: the guard's
     *    reads are what the flip depends on, and re-running them in the flip's transaction
     *    keeps the pre-140 guarantee that a pin cannot be released out from under the check.
     *    With [releasePinnedTemplates] (142) every DRAFT pin is released through the
     *    [TemplateReleaser] port FIRST, then the pipeline flips; any throw anywhere — a
     *    template's own refusal, a stale hash on the flip, a concurrent write — unwinds the
     *    transaction, so no template is left released with the pipeline still a draft.
     *    Without the flag a DRAFT pin refuses as it always has; the refusal's details name the
     *    first offending pin (unchanged) and, since 142, list every pin that is not RELEASED
     *    under `pins_not_released`, so a client can tell whether consenting would help.
     *
     * @throws DatapipelinesException / [PipelineValidationException]:
     *   `pipeline.version.not_draft`, §12 validation codes re-run on the draft body,
     *   `pipeline.release.template_not_released`, `pipeline.check.failed`,
     *   `pipeline.version.conflict` (stale hash), and — cascading — the template's own
     *   refusals (`template.version.not_draft`, `template.version.conflict`, its validation).
     */
    @Suppress("ThrowsCount") // a boundary maps each distinct failure to its own catalogued code
    open fun release(
        workspaceId: UUID,
        pipelineId: UUID,
        expectedHash: String,
        actor: UUID,
        overrideChecksReason: String? = null,
        releasePinnedTemplates: Boolean = false,
    ): Released {
        // §5.5: release is an authoring action — a promotion receiver refuses it.
        authoring.requirePipelineAuthoring()

        val draft =
            pipelines.findDraftDetail(workspaceId, pipelineId)
                ?: throw notDraft(pipelineId)

        // The body that will become immutable — re-validate it exactly as a save would
        // (§5.3 precondition 3), so release can never launder an invalid draft.
        val bodyJson =
            pipelines.findVersionBody(workspaceId, pipelineId, draft.version)
                ?: throw notDraft(pipelineId)
        val pipeline = deserializer.readOrThrow(bodyJson)
        validator.validateOrThrow(pipeline, workspaceId)

        val overridden = runCheckGate(workspaceId, pipelineId, draft.version, pipeline, actor, overrideChecksReason)

        val released =
            transactions.execute {
                // §6: templates lock first — a DRAFT template pin blocks the pipeline's
                // release unless the caller consented to the cascade (142). Checked — and,
                // consenting, released — in the flip's transaction (see the KDoc above).
                val cascaded =
                    draftPinsToRelease(workspaceId, pipeline, releasePinnedTemplates)
                        .map { ref -> templateReleaser.release(workspaceId, ref.id, ref.version, actor) }

                val flipped =
                    pipelines.releaseDraft(
                        workspaceId = workspaceId,
                        pipelineId = pipelineId,
                        name = pipeline.name,
                        displayName = pipeline.displayName,
                        description = pipeline.description,
                        expectedHash = expectedHash,
                        actor = actor,
                    ) ?: throw conflictAfterGuardFailure(workspaceId, pipelineId)
                flipped to cascaded
            }!!
        val (flipped, cascaded) = released
        // 7e: read AFTER the flip and outside its transaction — a warning describes what was
        // released and can never unwind it.
        val warnings = reviewWarnings(workspaceId, pipeline)
        return Released(flipped.record, flipped.version, bodyJson, overridden.first, overridden.second, cascaded, warnings)
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
     * PIPELINE node pins a child pipeline and a CALCULATOR node a catalog function (see
     * [draftPinsToRelease] for the defect the filter closed).
     */
    private fun templatePins(pipeline: Pipeline): List<TemplateRef> =
        pipeline.nodes
            .filter { it.type != NodeType.PIPELINE && it.type != NodeType.CALCULATOR }
            .map { it.template }
            .distinct()

    /**
     * The §6 pin guard, and the cascade's worklist (142): walks every template pin of the
     * body in node order and answers the DRAFT pins the caller consented to release,
     * deduplicated (two nodes pinning one version release it once). A pin that is not
     * RELEASED and not a consented DRAFT refuses with `pipeline.release.template_not_released`
     * — the first such pin in node order named at the top level exactly as before 142, every
     * non-RELEASED pin listed under `pins_not_released`. The scan completes BEFORE any
     * template is released, so a MISSING pin behind a DRAFT one never costs a rollback.
     *
     * Only the nodes that HAVE a template pin. A PIPELINE node pins a child pipeline and a
     * CALCULATOR node evaluates a catalog function; neither declares a template, so
     * `node.template` is the empty default there and asking the registry about `@0` answers
     * MISSING — which refused the release of every pipeline containing one, naming
     * `template_id: ""`. Latent since composition shipped and unmissable since D55, because
     * now EVERY pipeline needs a release. Found by `PromotionTwoDeploymentE2eTest`, whose
     * parent pipeline has a PIPELINE node.
     */
    private fun draftPinsToRelease(
        workspaceId: UUID,
        pipeline: Pipeline,
        releasePinnedTemplates: Boolean,
    ): List<TemplateRef> {
        val pins = templatePins(pipeline).map { ref -> ref to templates.statusOf(workspaceId, ref.id, ref.version) }
        val notReleased = pins.filter { (_, status) -> status != PipelineVersionStatus.RELEASED }
        val blocking =
            notReleased.firstOrNull { (_, status) -> !(releasePinnedTemplates && status == PipelineVersionStatus.DRAFT) }
        if (blocking != null) {
            val (ref, status) = blocking
            throw DatapipelinesException(
                code = PipelineErrorCodes.Versioning.RELEASE_TEMPLATE_NOT_RELEASED,
                message = "Template '${ref.id}' version ${ref.version} is not released; release the template first.",
                details =
                    mapOf(
                        "template_id" to ref.id,
                        "template_version" to ref.version,
                        "template_status" to (status?.name ?: "MISSING"),
                        "pins_not_released" to
                            notReleased.map { (pin, pinStatus) ->
                                mapOf(
                                    "template_id" to pin.id,
                                    "template_version" to pin.version,
                                    "template_status" to (pinStatus?.name ?: "MISSING"),
                                )
                            },
                    ),
            )
        }
        return notReleased.map { (ref, _) -> ref }
    }

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
     *   `pipeline.version.conflict` (stale hash / the draft vanished).
     */
    @Transactional("metadataTransactionManager")
    open fun purge(
        workspaceId: UUID,
        pipelineId: UUID,
        expectedHash: String,
    ): Purged {
        // §5.5: purging authored content is authoring — a receiver's sole writer is promotion.
        authoring.requirePipelineAuthoring()

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

        /**
         * The default [TransactionOperations] for directly-constructed instances (tests):
         * run the action straight through, with no transaction — exactly what an un-proxied
         * service always did. Production wiring passes a `TransactionTemplate` over
         * `metadataTransactionManager`.
         */
        private val DIRECT_TRANSACTIONS =
            object : TransactionOperations {
                override fun <T : Any?> execute(action: TransactionCallback<T>): T? = action.doInTransaction(SimpleTransactionStatus())
            }
    }
}
