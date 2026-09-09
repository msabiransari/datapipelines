package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException
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
@Suppress(
    "TooManyFunctions", // the aggregate's one-stop use-case surface (S5/R6); the 101 verbs grew it
    "ThrowsCount", // each refusal is a distinct catalogued code the caller distinguishes — the boundary's shape
)
open class PipelineService(
    private val pipelines: PipelineRepository,
    private val validator: PipelineValidator,
    private val drafts: PipelineDraftService,
    private val releases: PipelineReleaseService,
    private val authoring: AuthoringGuard,
    private val draftTemplates: ExclusiveDraftTemplates,
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
     * landed on, and the draft pointer — null when there is no draft, which since D55 means a
     * no-op update whose body already equalled the released one (versioning §5.1) and nothing
     * else: a CREATE now lands a draft and carries it in BOTH fields.
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
     * §5.1 — create. Version 1 lands **DRAFT** and `current_version` stays null (§3.2 as
     * ruled by D55): creation is authoring, and DRAFT → RELEASED is a human step with no
     * exception (D4). The pipeline is executable the moment it is created all the same —
     * drafts have been executable since 039 — so the "so an MCP-authored pipeline can run"
     * argument that used to land version 1 RELEASED buys nothing and cost a review.
     *
     * The RELEASED create still exists and is reached only by the paths that are not
     * authoring: promotion imports and the seeders that ride them
     * ([CreateLifecycle], [PipelineRepository.create]).
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
                CreateLifecycle.DRAFT,
            )
        // Read back the row the database stored (its hash included) — a hand-built detail is
        // how a default or CHECK becomes invisible (metadata-db §6.1). It is the DRAFT detail
        // now, and it is BOTH the landed version and the draft pointer: one row, two roles.
        val draft = pipelines.findDraftDetail(workspaceId, record.id)
        return SavedPipeline(record, validated.canonicalJson, draft, draft)
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

    /**
     * **One level** of the pipeline tree under [prefix] — the 067 browse presentation
     * (`pipelines_list {prefix}`, mirrored on REST): [prefix]'s direct sub-folders with their
     * subtree counts and its direct pipeline leaves, never a subtree
     * (template-hierarchy-design §9.2).
     *
     * A blank [prefix] is the ROOT — present-but-empty means "the tree's top level", a
     * different request from an absent prefix (the flat listing). A prefix that is not a legal
     * folder path answers an ordinary EMPTY level rather than an error — the rule
     * `pipelines_list` and the explorer partial already settled on; an illegal prefix cannot
     * name a real folder, so it never reaches the database. `owner`/`datasource`/`q` do not
     * apply here: browse and search are different presentations.
     */
    open fun browseLevel(
        workspaceId: UUID,
        prefix: String?,
        offset: Int = 0,
        limit: Int = PipelineFolderLevel.DEFAULT_PAGE_LIMIT,
    ): PipelineFolderLevel {
        val normalized = prefix?.takeIf { it.isNotBlank() }
        if (normalized != null && !PipelineNameGrammar.matchesPrefix(normalized)) {
            return PipelineFolderLevel(emptyList(), foldersTruncated = false, emptyList(), total = 0, hasMore = false)
        }
        return pipelines.listFolder(workspaceId, normalized, offset, limit)
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
     * **The working version's NUMBER** (versioning §7, D55): the DRAFT's when one exists, else
     * the current RELEASED version's. This is the execute default on every surface — REST
     * `POST /pipelines/{id}/execute`, `pipelines_execute`, the MCP body resource — so that
     * "run it" means "run what the pipeline currently IS", which on a development server may
     * well be a draft and on a hardened one is a release by construction (no draft can exist
     * where authoring is disabled).
     *
     * Null only for a pipeline that has NO version at all. Creation always writes version 1, so
     * the only way there is to discard the sole draft of a never-released pipeline: the discard
     * hard-deletes a never-executed draft row (§5.4) and leaves the index row behind with
     * nothing to run. The surfaces report that as their version-not-found refusal — the same
     * answer they already give for `{"version": 7}` on a pipeline that has six.
     *
     * The resolution lives HERE and nowhere else: it was three inline copies (the REST execute
     * controller, `PipelineExecuteTool`, `pipelines_get`) that could drift apart, which is the
     * D6 lesson applied to the default rather than to the lookup.
     */
    open fun workingVersion(
        workspaceId: UUID,
        record: PipelineRecord,
    ): Int? = pipelines.findDraftDetail(workspaceId, record.id)?.version ?: record.currentVersion

    /**
     * The execute path's resolution (D6): the body of [version] and the [Pipeline] parsed from
     * it. Null when that version has no stored body — the surface reports it as its own
     * version-not-found.
     *
     * The version is never clamped: a caller asking for a version that does not exist is
     * refused, not silently run at the working version (the REST and MCP surfaces each validate
     * the requested number before calling, and both default to [workingVersion] when none was
     * given).
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
     * §5.11 — purge the draft: the row and its executions are deleted (no tombstone since
     * 101), and the sole-draft case takes the entity row with it.
     *
     * Transactional because the repository purge is deliberately multi-statement
     * (executions → version → maybe entity → maybe pointer); without the transaction a
     * failure between them would leave a purged version's executions orphaned.
     */
    @Transactional("metadataTransactionManager")
    open fun purge(
        workspaceId: UUID,
        pipelineId: UUID,
        expectedHash: String,
    ): PipelineReleaseService.Purged = releases.purge(workspaceId, pipelineId, expectedHash)

    /**
     * §3.1 (101) — discard RELEASED version [version]: the row flips to DISCARDED, and the
     * pointer recomputes only when THIS version was the pointer (D60), falling back to the
     * highest eligible live version or NULL.
     *
     * Preconditions, evaluated in order: the entity exists (404); the target version exists
     * (404) and is RELEASED (`pipeline.version.not_released` otherwise — a draft is purged,
     * never discarded); no LIVE parent version exact-pins it (`pipeline.version.pinned`,
     * naming the pinners — the guard also rides the statement itself, so a pin that lands
     * between check and flip still refuses). An authoring write (§5.5).
     *
     * @throws co.datapipelines.typesystem.DatapipelinesException the codes above.
     */
    open fun discardVersion(
        workspaceId: UUID,
        pipelineId: UUID,
        version: Int,
        actor: UUID,
    ): DiscardResult {
        authoring.requirePipelineAuthoring()
        // AnyStatus: the entity may already be all-DISCARDED (every version discarded) — the
        // refusal is the TARGET's status (not_released), never a 404 that hides the version.
        val record = pipelines.findByIdAnyStatus(workspaceId, pipelineId) ?: throw pipelineNotFound(pipelineId)
        val detail =
            pipelines.findVersionDetail(workspaceId, pipelineId, version)
                ?: throw versionNotFound(pipelineId, version)
        if (detail.status == PipelineVersionStatus.DRAFT) throw notReleased(pipelineId, version, detail.status)
        if (detail.status == PipelineVersionStatus.DISCARDED) throw notReleased(pipelineId, version, detail.status)
        refuseIfPinned(workspaceId, record, version)

        val flipped =
            pipelines.discardVersion(
                workspaceId = workspaceId,
                pipelineId = pipelineId,
                pipelineName = record.name,
                version = version,
                actor = actor,
                draftEligible = authoring.developmentPosture,
            ) ?: throw pinnedOrConcurrent(workspaceId, record, version)
        return DiscardResult(recordBefore = record, recordAfter = flipped.first, version = flipped.second)
    }

    /**
     * §3.1 (101) — restore DISCARDED version [version] to RELEASED. Its original
     * `released_at`/`released_by` return untouched (§8's derivation depends on one release
     * stamp per version); the pointer moves only above-current-or-NULL (D60). No pin can
     * exist on a DISCARDED version (the `pinned` guard blocked its discard), so restore is
     * always safe. An authoring write (§5.5).
     */
    open fun restoreVersion(
        workspaceId: UUID,
        pipelineId: UUID,
        version: Int,
    ): PipelineRecord {
        authoring.requirePipelineAuthoring()
        // AnyStatus: restoring the first version of a DISCARDED entity is the one way back
        // (§3.5's `{X,X}` rows) — a live-only read would 404 the restore.
        pipelines.findByIdAnyStatus(workspaceId, pipelineId) ?: throw pipelineNotFound(pipelineId)
        val detail =
            pipelines.findVersionDetail(workspaceId, pipelineId, version)
                ?: throw versionNotFound(pipelineId, version)
        if (detail.status != PipelineVersionStatus.DISCARDED) throw notDiscarded(pipelineId, version, detail.status)

        return pipelines.restoreVersion(
            workspaceId,
            pipelineId,
            version,
            draftEligible = authoring.developmentPosture,
        ) ?: throw versionNotFound(pipelineId, version)
    }

    /**
     * §3.1 (101) — purge DRAFT version [version] (drafts only): the row and its executions
     * are deleted; the sole-draft case takes the entity with it. The hash-free admin verb —
     * it names an explicit version, so there is no two-writer protocol to honour.
     *
     * Preconditions: entity + target exist (404); target is DRAFT (a RELEASED target is
     * `pipeline.version.last_release` — a release is never purged; a DISCARDED target is
     * history, same code). An authoring write (§5.5).
     */
    @Transactional("metadataTransactionManager")
    open fun purgeVersion(
        workspaceId: UUID,
        pipelineId: UUID,
        version: Int,
    ): PipelineReleaseService.Purged {
        authoring.requirePipelineAuthoring()
        // AnyStatus: purging a DISCARDED entity's version is last_release (history is never
        // purged), not a 404 hiding it.
        pipelines.findByIdAnyStatus(workspaceId, pipelineId) ?: throw pipelineNotFound(pipelineId)
        val detail =
            pipelines.findVersionDetail(workspaceId, pipelineId, version)
                ?: throw versionNotFound(pipelineId, version)
        if (detail.status != PipelineVersionStatus.DRAFT) throw lastRelease(pipelineId, version, detail.status)
        // Graph rule 1: a draft PIPELINE version cannot be pinned by a saved parent (D58
        // refuses the pin at save), so there is no pin guard here — the invariant is
        // enforced upstream, and this comment is what makes that a decision rather than a gap.
        return when (
            val outcome =
                pipelines.purgeDraft(
                    workspaceId,
                    pipelineId,
                    expectedHash = null,
                    draftEligible = authoring.developmentPosture,
                )
        ) {
            is PurgeOutcome.VersionPurged -> PipelineReleaseService.Purged.Version(outcome.executionsDeleted, outcome.record)
            is PurgeOutcome.EntityPurged -> PipelineReleaseService.Purged.Entity(outcome.executionsDeleted)
            null -> throw versionNotFound(pipelineId, version)
        }
    }

    /** What a discard produced: the pointer before/after and the flipped version's detail. */
    data class DiscardResult(
        val recordBefore: PipelineRecord,
        val recordAfter: PipelineRecord,
        val version: PipelineVersionDetail,
    )

    /** What an entity purge produced (§3.2) — and the exclusive draft templates it offered. */
    data class EntityPurgeResult(
        val executionsDeleted: Int,
        /** Draft-only templates pinned by this draft body and by no OTHER live pipeline version. */
        val exclusiveDraftTemplates: List<String>,
        /** Whether [exclusiveDraftTemplates] were purged with the entity (the `include` flag). */
        val exclusiveTemplatesPurged: Boolean,
    )

    /**
     * §3.2 (101) — the entity purge: allowed only when the entity's ONLY version is a DRAFT
     * and nothing pins it. The entity row goes, with the draft and its executions. A
     * non-draft version present ⇒ `pipeline.version.last_release`; an inbound exact pin ⇒
     * `pipeline.version.pinned`.
     *
     * [includeExclusiveDraftTemplates] computes the set of DRAFT-ONLY templates the draft
     * body pins that no OTHER live pipeline version pins, and (when true) purges them with
     * the entity. Either way the response carries the set (§3.5) — the UI (102) shows the
     * offer even when the caller did not take it.
     *
     * Transactional: the guard reads, the template purge and the entity delete must not
     * strand half a cleanup.
     */
    @Transactional("metadataTransactionManager")
    open fun purgeEntity(
        workspaceId: UUID,
        pipelineId: UUID,
        includeExclusiveDraftTemplates: Boolean = false,
    ): EntityPurgeResult {
        authoring.requirePipelineAuthoring()
        // AnyStatus: a DISCARDED entity is addressable — its refusal is last_release (the
        // version shape), never a 404 that would hide the restore path (§3.5's `{X,X}` rows).
        val record = pipelines.findByIdAnyStatus(workspaceId, pipelineId) ?: throw pipelineNotFound(pipelineId)

        val versions = pipelines.listVersions(workspaceId, pipelineId)
        when {
            versions.size != 1 -> throw lastReleaseEntity(pipelineId, versions)
            versions[0].status != PipelineVersionStatus.DRAFT -> throw lastReleaseEntity(pipelineId, versions)
        }

        // Graph rule 3: no inbound edges of any kind. A never-released pipeline cannot be
        // published (the pointer is NULL) and cannot be pinned by a saved parent (D58), but
        // the check is stated, not assumed — a template-style pin edge would refuse here.
        refuseIfPinned(workspaceId, record, versions[0].version)

        val exclusive = exclusiveDraftTemplates(workspaceId, record)
        val purged =
            pipelines.purgeDraft(
                workspaceId,
                pipelineId,
                expectedHash = null,
                draftEligible = authoring.developmentPosture,
            ) ?: throw pipelineNotFound(pipelineId)
        if (purged !is PurgeOutcome.EntityPurged) {
            // Versions appeared between the guard read and the purge — the transaction's
            // own guard made the purge refuse everything but the single draft; re-read.
            throw lastReleaseEntity(pipelineId, pipelines.listVersions(workspaceId, pipelineId))
        }

        var purgedTemplates = false
        if (includeExclusiveDraftTemplates && exclusive.isNotEmpty()) {
            exclusive.forEach { templateId -> draftTemplates.purge(workspaceId, templateId) }
            purgedTemplates = true
        }
        return EntityPurgeResult(purged.executionsDeleted, exclusive, purgedTemplates)
    }

    /**
     * §3.4 (101) — the manual switch: `current = [version]`, which must be a LIVE,
     * posture-eligible version. NOT an authoring verb: this is the promotion receiver's
     * rollout/rollback lever, so no `requirePipelineAuthoring` here — D60 named the human
     * the mover, and the receiver's human is included.
     */
    open fun switchCurrent(
        workspaceId: UUID,
        pipelineId: UUID,
        version: Int,
    ): PipelineRecord {
        // AnyStatus: a DISCARDED entity's versions are addressable — their switch answer is
        // not_eligible (the status check below), not a 404.
        pipelines.findByIdAnyStatus(workspaceId, pipelineId) ?: throw pipelineNotFound(pipelineId)
        val detail =
            pipelines.findVersionDetail(workspaceId, pipelineId, version)
                ?: throw versionNotFound(pipelineId, version)
        if (!PipelineVersionStatus.eligibleForPointer(detail.status, authoring.developmentPosture)) {
            throw notEligible(pipelineId, version, detail.status)
        }
        return pipelines.switchCurrent(
            workspaceId,
            pipelineId,
            version,
            draftEligible = authoring.developmentPosture,
        ) ?: throw notEligible(pipelineId, version, detail.status)
    }

    /** The draft-only templates exclusively pinned by [record]'s draft body (§3.5, 101). */
    private fun exclusiveDraftTemplates(
        workspaceId: UUID,
        record: PipelineRecord,
    ): List<String> = draftTemplates.exclusiveIds(workspaceId, record.id)

    /** Graph rule 1's service-side arm: names the pinning entities in `details`. */
    private fun refuseIfPinned(
        workspaceId: UUID,
        record: PipelineRecord,
        version: Int,
    ) {
        val pinners = pipelines.findLiveParentsPinningVersion(workspaceId, record.name, version)
        if (pinners.isNotEmpty()) {
            throw pinned(workspaceId, record, version, pinners)
        }
    }

    @Suppress("UnusedParameter") // workspaceId rides for a future per-workspace pin report
    private fun pinned(
        workspaceId: UUID,
        record: PipelineRecord,
        version: Int,
        pinners: List<TemplatePin>,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Versioning.PINNED,
            message =
                "Version $version of '${record.name.truncateForError()}' is pinned by ${pinners.size} " +
                    "live pipeline version(s); discard or repoint them first.",
            details =
                mapOf(
                    "pipeline_id" to record.id.toString(),
                    "version" to version,
                    "pinned_by" to
                        pinners.map { mapOf("pipeline" to it.pipelineName, "version" to it.pipelineVersion, "node" to it.nodeId) },
                ),
        )

    /**
     * The discard statement returned zero rows after the service-side guard passed: a pin
     * landed in between, or a concurrent discard won.
     */
    private fun pinnedOrConcurrent(
        workspaceId: UUID,
        record: PipelineRecord,
        version: Int,
    ): DatapipelinesException {
        val pinners = pipelines.findLiveParentsPinningVersion(workspaceId, record.name, version)
        if (pinners.isNotEmpty()) return pinned(workspaceId, record, version, pinners)
        val current = pipelines.findVersionDetail(workspaceId, record.id, version) ?: throw versionNotFound(record.id, version)
        return when (current.status) {
            PipelineVersionStatus.RELEASED -> {
                DatapipelinesException(
                    code = PipelineErrorCodes.Versioning.VERSION_CONFLICT,
                    message = "Pipeline was modified by someone else after you loaded it.",
                    details = mapOf("current_status" to current.status.name),
                )
            }

            else -> {
                notReleased(record.id, version, current.status)
            }
        }
    }

    private fun lastRelease(
        pipelineId: UUID,
        version: Int,
        status: PipelineVersionStatus,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Versioning.LAST_RELEASE,
            message =
                "Version $version of pipeline '$pipelineId' is ${status.name} and is never purged — " +
                    "discard is per version, the entity stays; restore or release something first.",
            details = mapOf("pipeline_id" to pipelineId.toString(), "version" to version, "status" to status.name),
        )

    private fun lastReleaseEntity(
        pipelineId: UUID,
        versions: List<PipelineVersionRecord>,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Versioning.LAST_RELEASE,
            message =
                "Pipeline '$pipelineId' cannot be purged: an entity purge requires the only version " +
                    "to be a DRAFT (this one has ${versions.size} version(s)).",
            details =
                mapOf(
                    "pipeline_id" to pipelineId.toString(),
                    "versions" to versions.map { mapOf("version" to it.version, "status" to it.status.name) },
                ),
        )

    private fun notReleased(
        pipelineId: UUID,
        version: Int,
        status: PipelineVersionStatus,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Versioning.NOT_RELEASED,
            message =
                "Version $version of pipeline '$pipelineId' is ${status.name}; discard targets a RELEASED " +
                    "version — a draft is purged, not discarded.",
            details = mapOf("pipeline_id" to pipelineId.toString(), "version" to version, "status" to status.name),
        )

    private fun notDiscarded(
        pipelineId: UUID,
        version: Int,
        status: PipelineVersionStatus,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Versioning.NOT_DISCARDED,
            message = "Version $version of pipeline '$pipelineId' is ${status.name}; restore targets a DISCARDED version.",
            details = mapOf("pipeline_id" to pipelineId.toString(), "version" to version, "status" to status.name),
        )

    private fun notEligible(
        pipelineId: UUID,
        version: Int,
        status: PipelineVersionStatus,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Versioning.NOT_ELIGIBLE,
            message =
                "Version $version of pipeline '$pipelineId' is ${status.name} and cannot be switched to — " +
                    "the pointer names a live version eligible for this deployment's posture.",
            details = mapOf("pipeline_id" to pipelineId.toString(), "version" to version, "status" to status.name),
        )

    private fun pipelineNotFound(pipelineId: UUID): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Execution.NOT_FOUND,
            message = "Pipeline '$pipelineId' does not exist.",
            details = mapOf("pipeline_id" to pipelineId.toString()),
        )

    private fun versionNotFound(
        pipelineId: UUID,
        version: Int,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Execution.NOT_FOUND,
            message = "Pipeline '$pipelineId' has no version $version.",
            details =
                mapOf(
                    "pipeline_id" to pipelineId.toString(),
                    "pipeline_version" to version,
                ),
        )
}
