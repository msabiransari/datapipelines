package co.datapipelines.pipeline

import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The pipeline aggregate's **use-case service** (ARCH-AUDIT-2026-08 S5, ruling R6) — the one
 * entry point every surface uses for a pipeline, and the exemplar slices B and C copy for
 * `TemplateService`, `DatasourceService` and `ExecutionService`.
 *
 * It lives in `pipeline-contract` because that is the module that owns the aggregate. A
 * cross-aggregate use case (one that needs `dag`, or `templates` AND `datasources`) lives in
 * `modules/application` instead; a single-aggregate one lives with its aggregate. That is the
 * placement rule module-structure.md §5.10 states, and this class is its first instance.
 *
 * ## What it absorbed (S2's drift list)
 *
 * - **D1 — save validation.** [validate] is the deserialize → §12 validate → canonical triple
 *   `PipelinesController` and the MCP `PipelineSaveSupport` each implemented. There is one
 *   copy now, and [create]/[update] are the write paths built on it.
 * - **D2 — list filtering.** [list] is the owner / datasource / `q` filter, implemented once.
 *   `PipelinesController.list` and `pipelines_list` differed only in how they spelled the same
 *   three rules; they now differ only in pagination, which is genuinely per-surface.
 * - **D6 — execute.** [findExecutable] is the aggregate's half of the execute path (resolve the
 *   version, read its body, deserialize it). The launch itself — parameter binding, the
 *   idempotency reservation — is cross-aggregate and lives in
 *   `co.datapipelines.application.ExecutionLauncher`.
 * - The **release / draft / discard** lifecycle, through the two collaborators that already
 *   owned those rules ([PipelineDraftService], [PipelineReleaseService]). They are composed,
 *   not inlined: they are the aggregate's own lifecycle rules with their own tests, and
 *   dissolving them into this class would have been churn with no reader benefit. What
 *   changed is that callers no longer reach past this service to them.
 *
 * ## What it deliberately does NOT do
 *
 * No `HttpStatus`, no `ResponseEntity`, no `ApiResponse`, no MCP wire type — a service that
 * imports a web type is the layering violation the module graph exists to prevent, and
 * `ArchitectureGuardTest` fails the build on one. Two consequences a reader should expect:
 *
 * - **Reads return null, they do not throw.** `pipeline.execution.not_found` is spelled
 *   `ApiErrors.pipelineNotFound` on REST and `McpNotFound.pipeline` on MCP — same catalogued
 *   code, different carrier — and translating an absence into a surface's error object is the
 *   surface's job. Writes DO throw [co.datapipelines.typesystem.DatapipelinesException] with a
 *   §13 code, because a refused write is a domain outcome, not a missing row.
 * - **Every operation takes `workspaceId` explicitly.** `TemplateRepository`'s KDoc rule — "no
 *   default anywhere: a missed caller is a compile error" — applies to services too.
 *
 * ## Transactions (S3)
 *
 * Multi-statement metadata writes carry `@Transactional("metadataTransactionManager")`. The
 * manager is **always named**: there is one Spring transaction manager (the metadata
 * database) and N Hikari pools for customer databases that are not Spring transaction
 * resources and must never become one, so a bare annotation is a trap that works by accident
 * today (`ArchitectureGuardTest` fails the build on a bare one). Single-statement writes keep
 * their data-modifying CTEs and gain nothing from a transaction.
 *
 * Two multi-statement writes are deliberately NOT transactional, and both are findings rather
 * than preferences — [update] (its 409 recovery reads AFTER catching a constraint violation,
 * which a transaction turns into `25P02`) and [discard] (its two statements are alternatives
 * selected by a foreign-key violation, not a composition). Each carries the reasoning on its
 * own KDoc; the round's handback lists them.
 *
 * No method here leases a customer datasource connection, and none may: `ConnectionLease`
 * refuses to lease while a metadata transaction is active on the thread (§E.2).
 *
 * ### Why this class and EVERY public method on it are explicitly `open`
 *
 * Two distinct CGLIB traps, both met in practice while building this class. They fail in
 * OPPOSITE ways, which is the part worth remembering:
 *
 * 1. **A `final` CLASS fails LOUDLY.** `kotlin("plugin.spring")` opens a class ANNOTATED with
 *    `@Transactional`; it does **not** open a class whose METHODS carry it, which is the shape
 *    used here (only some methods are transactional — see [update] and [discard] for why
 *    annotating the class instead would be wrong). Without `open` this compiled to
 *    `public final class PipelineService`, and a `@Transactional` bean Spring cannot subclass
 *    refuses to start the context at all: `AopConfigException: Cannot subclass final class`.
 *    Verified by falsification, not assumed — and it is good news, because a loud failure is the
 *    one you cannot ship.
 * 2. **A `final` METHOD on an opened class fails SILENTLY, at runtime.** Spring instantiates the
 *    CGLIB proxy with Objenesis, bypassing the constructor, so the PROXY's own fields are all
 *    null; correctness depends on every call being intercepted and delegated to the real target.
 *    A `final` method cannot be intercepted, so it executes ON THE PROXY and dies with
 *    `NullPointerException: ... "this.pipelines" is null`. The context starts, the build is green,
 *    and every read on this service throws the first time a request reaches it. That one shipped
 *    past compile, lint and every module test, and was caught only by sixteen failing E2E tests.
 *    It is why EVERY public method here is `open`, not only the transactional two.
 *
 * Private members stay final deliberately: they are reached from inside an already-delegated
 * `open` method, so they run on the target and never through the proxy.
 *
 * `TransactionRollbackIntegrationTest` guards both — it asserts every bean with a
 * `@Transactional` method is a proxy AND that no such target declares a `final` public method.
 */
open class PipelineService(
    private val pipelines: PipelineRepository,
    private val validator: PipelineValidator,
    private val drafts: PipelineDraftService,
    private val releases: PipelineReleaseService,
    private val authoring: AuthoringGuard,
    private val deserializer: PipelineDeserializer = PipelineDeserializer(),
    private val serializer: PipelineSerializer = PipelineSerializer(),
) {
    /** A body that passed §12 validation, paired with the canonical JSON that gets stored. */
    data class ValidatedPipeline(
        val pipeline: Pipeline,
        val canonicalJson: String,
    )

    /**
     * What a create or update produced: the index row, the body as stored, the version row it
     * landed on, and the draft pointer — null when there is no draft (a create, or a no-op
     * update whose body already equalled the released one, versioning §5.1).
     */
    data class SavedPipeline(
        val record: PipelineRecord,
        val bodyJson: String,
        val version: PipelineVersionDetail?,
        val draft: PipelineVersionDetail? = null,
    )

    /** A pipeline read at one version: the index row, that version's body and its detail. */
    data class LoadedPipeline(
        val record: PipelineRecord,
        val bodyJson: String,
        val version: PipelineVersionDetail,
        val draft: PipelineVersionDetail? = null,
    )

    /** One screen of the listing: the rows, the truthful total, whether more exist, and the draft badges. */
    data class PipelinePage(
        val items: List<PipelineRecord>,
        val total: Int,
        val hasMore: Boolean,
        val drafts: Map<UUID, PipelineVersionDetail>,
    )

    /** The execute path's resolved input (D6): which version, its body, and the parsed pipeline. */
    data class ExecutablePipeline(
        val record: PipelineRecord,
        val version: Int,
        val bodyJson: String,
        val pipeline: Pipeline,
    )

    // -------------------------------------------------------------------------------------
    // D1 — save validation, once
    // -------------------------------------------------------------------------------------

    /**
     * Deserialize → §12 validate → canonical JSON: the universal save-time validation
     * (pipeline-contract §2.8) both write surfaces run. Nothing invalid reaches the database.
     *
     * @throws co.datapipelines.typesystem.DatapipelinesException the deserializer's wire
     *   refusals and [PipelineValidationException] carrying the full §12 failure list.
     */
    open fun validate(
        bodyJson: String,
        workspaceId: UUID,
    ): ValidatedPipeline {
        val pipeline = validator.validateOrThrow(deserializer.readOrThrow(bodyJson), workspaceId)
        return ValidatedPipeline(pipeline, serializer.write(pipeline))
    }

    /**
     * §5.1 — create. Version 1 lands RELEASED and immediately executable (§3.2: creation is
     * not modification).
     *
     * Transactional because it is two statements: the insert, then the read-back of the row
     * the database actually stored (its server-generated hash and timestamps). Without the
     * transaction a concurrent draft-write between them could hand the caller a version detail
     * that never described the body it is being returned with.
     */
    @Transactional("metadataTransactionManager")
    open fun create(
        workspaceId: UUID,
        bodyJson: String,
        actor: UUID,
    ): SavedPipeline {
        // versioning §5.5: creation is authoring — a promotion receiver refuses it, before
        // anything is parsed or written.
        authoring.requirePipelineAuthoring()
        val validated = validate(bodyJson, workspaceId)
        val record =
            pipelines.create(
                workspaceId,
                NewPipeline.from(validated.pipeline, ownerId = actor),
                validated.canonicalJson,
                actor,
            )
        // Read back the row the database stored (its hash included) — a hand-built detail is
        // how a default or CHECK becomes invisible (metadata-db §6.1).
        return SavedPipeline(record, validated.canonicalJson, pipelines.findCurrentVersionDetail(workspaceId, record.id))
    }

    /**
     * §5.2 — update, writing the DRAFT branch: copy-on-write on the first write after a
     * release, in-place overwrite after ([PipelineDraftService] owns that rule).
     *
     * A no-op write (the body already equals the released one, versioning §5.1) reports the
     * current RELEASED state and carries NO draft pointer: nothing was opened.
     *
     * **Deliberately NOT transactional**, and this one is a finding rather than a preference.
     * The draft write path recovers from a lost race by CATCHING the unique-index violation
     * and then READING the winner's hash back to put in the 409's `details`
     * (`PipelineRepository.mappingDraftRace`). On PostgreSQL an error aborts the whole
     * transaction, so inside one that read fails with `25P02 current transaction is aborted`
     * and the caller gets a raw data-access fault instead of the catalogued
     * `pipeline.version.conflict` — the transaction would break the recovery it was supposed
     * to protect. The write does not need one anyway: every precondition already rides its own
     * statement's `WHERE` clause (versioning §4.2) and the partial unique index settles the
     * race. Rewriting that recovery is a repository change, which this slice does not make.
     */
    open fun update(
        workspaceId: UUID,
        pipelineId: UUID,
        bodyJson: String,
        expectedHash: String,
        actor: UUID,
    ): SavedPipeline {
        val validated = validate(bodyJson, workspaceId)
        val written =
            drafts.write(
                workspaceId = workspaceId,
                pipelineId = pipelineId,
                pipeline = validated.pipeline,
                canonical = validated.canonicalJson,
                expectedHash = expectedHash,
                actor = actor,
            )
        // A no-op reports the current RELEASED state and must NOT carry a draft pointer: nothing
        // was opened. Otherwise the pointer is the row just written — `PipelineDraftService`
        // returns the draft it created or overwrote, so there is nothing to re-read.
        val draft = written.version.takeIf { it.status != PipelineVersionStatus.RELEASED }
        return SavedPipeline(written.record, written.bodyJson, written.version, draft)
    }

    // -------------------------------------------------------------------------------------
    // D2 — list filtering, once
    // -------------------------------------------------------------------------------------

    /**
     * §5.7 — the workspace's pipelines under the `owner` / `datasource` / `q` filters, newest
     * first as the repository orders them. Pagination is the caller's: REST pages by
     * offset/limit, MCP truncates to `limit`, and those are genuinely different contracts.
     *
     * Datasource filtering is pushed down to SQL ([PipelineRepository.findAllByDatasource]);
     * `q` stays in memory because it matches across three columns.
     */
    open fun list(
        workspaceId: UUID,
        ownerId: UUID? = null,
        datasourceName: String? = null,
        query: String? = null,
    ): List<PipelineRecord> {
        val records =
            if (datasourceName != null) {
                pipelines.findAllByDatasource(workspaceId, datasourceName, ownerId)
            } else {
                pipelines.findAll(workspaceId, ownerId)
            }
        val needle = query?.lowercase() ?: return records
        return records.filter { it.matches(needle) }
    }

    /**
     * One screen of the pipelines listing, with its truthful total and the pending-release
     * badges — what a list SCREEN needs, as opposed to [list]'s "give me the rows".
     *
     * The two paths are deliberately different and both are preserved from the UI controllers
     * this replaced: with no `q`, the page is taken in SQL (`LIMIT size+1 OFFSET offset`) and
     * the total is a `COUNT(*)`, because an estimate rendered "Showing 25 of 26" on a 100-row
     * workspace once (034 E3); with a `q`, the rows are filtered in memory (the search spans
     * three columns) and the total is the filtered size.
     *
     * This existed as FOUR copies before 056 — the REST list, the MCP list, the UI list screen
     * and its HTMX partial — which is S2's D2 in its most literal form.
     */
    open fun page(
        workspaceId: UUID,
        query: String?,
        offset: Int,
        size: Int,
    ): PipelinePage {
        val needle = query?.trim()?.takeIf { it.isNotEmpty() }
        val page =
            if (needle == null) {
                val rows = pipelines.findAll(workspaceId, null, size + 1, offset)
                PipelinePage(rows.take(size), pipelines.countAll(workspaceId), rows.size > size, emptyMap())
            } else {
                val all = list(workspaceId, query = needle)
                PipelinePage(all.drop(offset).take(size), all.size, all.size > offset + size, emptyMap())
            }
        // versioning §7: the "unreleased edits exist" badge, for the rows actually shown.
        return page.copy(drafts = pipelines.findDrafts(workspaceId, page.items.map { it.id }))
    }

    /** The DRAFT detail of each of [pipelineIds] that has one — the list screens' badge (§7). */
    open fun findDrafts(
        workspaceId: UUID,
        pipelineIds: Collection<UUID>,
    ): Map<UUID, PipelineVersionDetail> = pipelines.findDrafts(workspaceId, pipelineIds)

    /** The `q` rule, in one place: a case-insensitive substring of name, display name or description. */
    private fun PipelineRecord.matches(lowercaseQuery: String): Boolean =
        name.lowercase().contains(lowercaseQuery) ||
            displayName.lowercase().contains(lowercaseQuery) ||
            description.lowercase().contains(lowercaseQuery)

    // -------------------------------------------------------------------------------------
    // Reads — null on absence; the surface owns the 404
    // -------------------------------------------------------------------------------------

    /** The index row, or null when it is unknown, soft-deleted, or in another workspace. */
    open fun findRecord(
        workspaceId: UUID,
        pipelineId: UUID,
    ): PipelineRecord? = pipelines.findById(workspaceId, pipelineId)

    /**
     * The **working version** (versioning §7): the DRAFT when one exists, else the current
     * RELEASED version — what an authoring read must show, so an editor never rebases on
     * released content and quietly discards a draft.
     */
    open fun findWorking(
        workspaceId: UUID,
        pipelineId: UUID,
    ): LoadedPipeline? {
        val record = pipelines.findById(workspaceId, pipelineId) ?: return null
        val draft = pipelines.findDraftDetail(workspaceId, record.id)
        val version = draft ?: pipelines.findCurrentVersionDetail(workspaceId, record.id) ?: return null
        val body = pipelines.findVersionBody(workspaceId, record.id, version.version) ?: return null
        return LoadedPipeline(record, body, version, draft)
    }

    /** One specific version of a known pipeline, body and detail together. */
    open fun findVersion(
        workspaceId: UUID,
        record: PipelineRecord,
        version: Int,
    ): LoadedPipeline? {
        val body = pipelines.findVersionBody(workspaceId, record.id, version) ?: return null
        val detail = pipelines.findVersionDetail(workspaceId, record.id, version) ?: return null
        return LoadedPipeline(record, body, detail)
    }

    /** The draft pointer, or null when the pipeline has no unreleased edits. */
    open fun findDraft(
        workspaceId: UUID,
        pipelineId: UUID,
    ): PipelineVersionDetail? = pipelines.findDraftDetail(workspaceId, pipelineId)

    /**
     * One version's stored body, without its detail row.
     *
     * [findVersion] is the composite read and is what a surface should reach for; this is the
     * narrow one the editor needs, because the editor tolerates a version whose detail row is
     * absent (it renders the body and simply shows no lifecycle badge) where an API read would
     * call that a 404. Keeping both is what let the editor's behaviour stay identical across 056.
     */
    open fun findVersionBody(
        workspaceId: UUID,
        pipelineId: UUID,
        version: Int,
    ): String? = pipelines.findVersionBody(workspaceId, pipelineId, version)

    /** The current RELEASED version's detail, or null — the execute-default pointer's row. */
    open fun findCurrentVersion(
        workspaceId: UUID,
        pipelineId: UUID,
    ): PipelineVersionDetail? = pipelines.findCurrentVersionDetail(workspaceId, pipelineId)

    /** §5.4 — version metadata, newest first; no bodies. */
    open fun listVersions(
        workspaceId: UUID,
        pipelineId: UUID,
    ): List<PipelineVersionRecord> = pipelines.listVersions(workspaceId, pipelineId)

    // -------------------------------------------------------------------------------------
    // D6 — the aggregate's half of execute
    // -------------------------------------------------------------------------------------

    /**
     * The execute path's resolution (D6): the body of [version] and the [Pipeline] parsed from
     * it. Null when that version has no stored body — the surface reports it as its own
     * version-not-found.
     *
     * The version is never clamped: a caller asking for a version that does not exist is
     * refused, not silently run at `current_version` (the REST and MCP surfaces each validate
     * the requested number before calling, and both default to [PipelineRecord.currentVersion]
     * when none was given).
     */
    open fun findExecutable(
        workspaceId: UUID,
        record: PipelineRecord,
        version: Int,
    ): ExecutablePipeline? {
        val body = pipelines.findVersionBody(workspaceId, record.id, version) ?: return null
        return ExecutablePipeline(record, version, body, deserializer.readOrThrow(body))
    }

    // -------------------------------------------------------------------------------------
    // Lifecycle writes
    // -------------------------------------------------------------------------------------

    /**
     * §5.10 — release the draft. Transactional: the three preconditions (a draft exists, it
     * re-validates, every template version it pins is RELEASED) are reads that the flip then
     * depends on, and a release that locked a body whose template pin was released out from
     * under it between the check and the flip would be exactly the corruption the check
     * exists to prevent.
     */
    @Transactional("metadataTransactionManager")
    open fun release(
        workspaceId: UUID,
        pipelineId: UUID,
        expectedHash: String,
        actor: UUID,
    ): PipelineReleaseService.Released = releases.release(workspaceId, pipelineId, expectedHash, actor)

    /**
     * §5.11 — discard the draft: hard-delete when never executed, DISCARDED-flip when the
     * `pipeline_executions` FK blocks the delete.
     *
     * **Deliberately NOT transactional**, and this is the case the round's "where any of them
     * is more than one statement" question actually turns on. It IS two statements — but they
     * are ALTERNATIVES, not a composition: the DELETE's foreign-key violation is the control
     * flow that selects the flip ([PipelineRepository.discardDraft] documents the swallow).
     * Wrapping them would be actively wrong on PostgreSQL, where an error aborts the whole
     * transaction and the following UPDATE fails with `current transaction is aborted` — the
     * discard would need a SAVEPOINT to survive the atomicity it does not need. Each statement
     * is atomic on its own and a failed DELETE leaves nothing behind, so there is no partial
     * state for a transaction to protect.
     */
    open fun discard(
        workspaceId: UUID,
        pipelineId: UUID,
        expectedHash: String,
    ): PipelineReleaseService.Discarded = releases.discard(workspaceId, pipelineId, expectedHash)

    /**
     * §5.6 — soft delete. The row stays, so the name stays taken (execution history references
     * it). Returns false when nothing was live to delete; the surface maps that to its 404.
     *
     * One statement, so no transaction — the guard is the authoring capability, checked first.
     */
    open fun delete(
        workspaceId: UUID,
        pipelineId: UUID,
    ): Boolean {
        // versioning §5.5: deleting authored content is authoring — a receiver's sole writer
        // is promotion.
        authoring.requirePipelineAuthoring()
        return pipelines.softDelete(workspaceId, pipelineId)
    }
}
