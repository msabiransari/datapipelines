package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException
import java.util.UUID

/**
 * The one write rule (versioning §3.2): **when we write, we make sure it is not a released
 * version. If it is released, we first create a draft out of the released version, then
 * write. Otherwise we write.** There is no third branch — a PUT never appends a released
 * version and never touches a RELEASED or DISCARDED row.
 *
 * This is the service both write surfaces share — REST `PUT /pipelines/{id}` and the MCP
 * `pipelines_update` tool — which is the whole point: an agent iterating and an engineer
 * saving from the editor produce the SAME bounded artifact, one mutable draft row that
 * each keeps overwriting (versioning §7), instead of the version-per-save pile the old
 * semantics created.
 *
 * ## The three checks, in order
 *
 * 1. **§3.5's draft-write-time name check** — a rename to a taken name must fail NOW, not
 *    at release: uniqueness is checked AGAINST the index row without being enforced FROM
 *    it (`pipeline.validation.duplicate_name`, the existing code; the release-time
 *    constraint stays the authority and the backstop).
 * 2. **The branch** — draft exists ⇒ [PipelineRepository.writeDraft] (in-place); no draft
 *    ⇒ [PipelineRepository.createDraft] (copy-on-write). The branch decision is itself
 *    guarded: the partial unique index makes two simultaneous first-writers race-safe,
 *    the loser surfacing as `pipeline.version.conflict` pointing at the winner's hash.
 * 3. **The precondition on both branches** — [expectedHash] must equal the stored hash of
 *    the version the caller based its edit on: the DRAFT's for an in-place write, the
 *    current RELEASED row's for a first write (§4.2). Zero rows ⇒ stale base ⇒ 409 with
 *    the current state in `details`; the server never merges and never overwrites.
 *
 * Callers have ALREADY run §12 save-time validation and canonicalization — this service
 * takes the validated [Pipeline] and its canonical JSON, not the raw wire body.
 */
class PipelineDraftService(
    private val pipelines: PipelineRepository,
) {
    /** What a draft write produced: the unchanged record, the version row's state, the stored body. */
    data class DraftWrite(
        val record: PipelineRecord,
        val version: PipelineVersionDetail,
        val bodyJson: String,
    )

    /**
     * Writes [canonical] as the pipeline's draft — creating the draft first when the caller
     * is the first writer after a release (§5.1), overwriting it in place otherwise (§5.2).
     *
     * @throws DatapipelinesException `pipeline.execution.not_found` (unknown pipeline),
     *   `pipeline.validation.duplicate_name` (§3.5 rename check), or
     *   `pipeline.version.conflict` (stale [expectedHash], with the current hash/author in details).
     */
    @Suppress("ThrowsCount") // three throws, three catalogued refusals a caller must distinguish
    fun write(
        workspaceId: UUID,
        pipelineId: UUID,
        pipeline: Pipeline,
        canonical: String,
        expectedHash: String,
        actor: UUID,
    ): DraftWrite {
        val record =
            pipelines.findById(workspaceId, pipelineId)
                ?: throw notFound(pipelineId)

        // §3.5: the index row keeps the released values until lock, so a rename would only
        // collide at release — after the work is done. The early check costs nothing
        // structurally; the UNIQUE constraint remains the authority.
        if (pipelines.nameTakenByAnother(workspaceId, pipeline.name, pipelineId)) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Validation.DUPLICATE_NAME,
                message = "A pipeline named '${pipeline.name.truncateForError()}' already exists.",
                details = mapOf("name" to pipeline.name.truncateForError()),
            )
        }

        val existingDraft = pipelines.findDraftDetail(workspaceId, pipelineId)
        if (existingDraft != null) {
            pipelines.writeDraft(workspaceId, pipelineId, canonical, expectedHash, actor)?.let {
                return DraftWrite(record, it, canonical)
            }
            // No rows: either the hash was stale, or the draft was discarded mid-write and
            // this caller may become the new first writer — fall through to the create
            // branch, whose guard decides.
        }
        val created = pipelines.createDraft(workspaceId, pipelineId, canonical, expectedHash, actor)
        return if (created != null) {
            DraftWrite(record, created, canonical)
        } else {
            throw staleBase(workspaceId, pipelineId, record.currentVersion)
        }
    }

    /**
     * The 409's evidence: re-read what the pipeline's version state actually is NOW — the
     * draft if one exists (the likely winner of the race), else the current released row —
     * and report it in §4.2's shape so the client's recovery path (reload, diff, re-apply)
     * starts from the truth.
     */
    private fun staleBase(
        workspaceId: UUID,
        pipelineId: UUID,
        currentVersion: Int,
    ): DatapipelinesException {
        val current =
            pipelines.findDraftDetail(workspaceId, pipelineId)
                ?: pipelines.findVersionDetail(workspaceId, pipelineId, currentVersion)
        return conflict(current, "Pipeline was modified by someone else after you loaded it.")
    }

    private fun notFound(pipelineId: UUID): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Execution.NOT_FOUND,
            message = "Pipeline '$pipelineId' not found.",
            details = mapOf("pipeline_id" to pipelineId.toString()),
        )

    private fun conflict(
        current: PipelineVersionDetail?,
        message: String,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Versioning.VERSION_CONFLICT,
            message = message,
            details =
                mapOf(
                    "current_body_hash" to (current?.bodyHash ?: ""),
                    "current_status" to (current?.status?.name ?: "UNKNOWN"),
                    "updated_by" to (current?.updatedBy?.toString() ?: ""),
                    "updated_at" to (current?.updatedAt?.toString() ?: ""),
                ),
        )
}
