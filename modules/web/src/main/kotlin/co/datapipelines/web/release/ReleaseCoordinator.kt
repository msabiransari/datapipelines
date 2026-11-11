package co.datapipelines.web.release

import co.datapipelines.application.dependencies.ReleasePlanningWalk
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.release.BlockerReason
import co.datapipelines.pipeline.release.ClosureNode
import co.datapipelines.pipeline.release.LegacyReleaseAlias
import co.datapipelines.pipeline.release.PipelineDependencySource
import co.datapipelines.pipeline.release.PipelineReleaseCoordination
import co.datapipelines.pipeline.release.PipelineReleaseRequest
import co.datapipelines.pipeline.release.PreparedRelease
import co.datapipelines.pipeline.release.PrerequisiteCheck
import co.datapipelines.pipeline.release.ReleaseAuditRow
import co.datapipelines.pipeline.release.ReleaseAuditSink
import co.datapipelines.pipeline.release.ReleaseBlockers
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseClosure
import co.datapipelines.pipeline.release.ReleaseContext
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseFlip
import co.datapipelines.pipeline.release.ReleaseMutationGuard
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleaseParticipant
import co.datapipelines.pipeline.release.ReleasePlanRequest
import co.datapipelines.pipeline.release.ReleaseRefusals
import co.datapipelines.pipeline.release.ReleasedDependency
import co.datapipelines.pipeline.release.ReleasedVersion
import co.datapipelines.pipeline.release.ReleasedVersionObserver
import co.datapipelines.pipeline.release.TreeHash
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.pipelines.LifecycleVerbs
import co.datapipelines.web.templates.TemplateReleaseService
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionOperations
import java.util.UUID

/**
 * The release coordinator for a pipeline root (release-dependencies record §4.1; brief A.4): every pipeline release —
 * the REST route's and the dialog's — runs here, in ONE metadata transaction with the workspace writer guard acquired
 * first, its flips dependency-first and root last, and one strict audit row per actual flip written in that same
 * transaction ([ReleaseAuditSink]). A failure anywhere before commit rolls back every flip, pointer and audit row.
 *
 * Two request shapes (record §6):
 *
 * - **Compatibility** — no plan field. Only the root flips, plus, under the legacy alias `release_pinned_templates`, the
 *   root's DIRECT draft template pins ([LegacyReleaseAlias]); the native order is kept (validation and checks first,
 *   then the closure under the guard), so a direct-template fixture answers exactly what #142 answered, with the code
 *   renamed by §14. Each template flips at the hash the closure read under the guard, never a fresh pickup.
 * - **Plan** — `tree_hash` and optionally `include_dependencies` / `check_overrides`. The shape is validated, the digest
 *   is compared BEFORE any validation work, the selection must cover every reachable DRAFT, the caller must hold each
 *   flipped family's release permission, every node is prepared outside (its own checks, its own override reason), and
 *   inside the transaction the closure is re-resolved and compared again — any drift is `plan_conflict`.
 *
 * The arming rule (brief resolution 1): a bare alias is the compatibility path while [legacyAliasAdmitted]; the alias
 * with an explicit selection is `plan_invalid`; any plan without `tree_hash` is `plan_required`.
 */
@Suppress("LongParameterList") // one collaborator per step of the record's algorithm
class ReleaseCoordinator(
    private val releases: PipelineReleaseService,
    private val pipelines: PipelineRepository,
    /**
     * The closure reads (#868 G1): the plan's first walk in its own read-only REPEATABLE READ transaction, the guarded
     * re-walk and the compatibility walk inside the release transaction — both through the neutral walk and the release
     * adapter, byte-identical to the former `ReleaseGraph`.
     */
    private val planning: ReleasePlanningWalk,
    participants: List<ReleaseParticipant>,
    private val authorization: ReleaseAuthorization,
    private val guard: ReleaseMutationGuard,
    private val audit: ReleaseAuditSink,
    private val transactions: TransactionOperations,
    private val bounds: ReleaseBounds,
    /**
     * #778 — told of every PIPELINE version this transaction makes RELEASED (dependencies and root, both paths), inside
     * it, after the flips and before the audit: the lake reference scanner indexes each, and a scanner that throws rolls
     * the release back (the owner's 2026-11-02 seam ruling). [ReleasedVersionObserver.NONE] in a directly built test.
     */
    private val observer: ReleasedVersionObserver = ReleasedVersionObserver.NONE,
    /**
     * #873 §F.3 c — the datasource prerequisites of the guarded re-walk, resolved by NAME in the caller's workspace inside
     * the release transaction on every entry path, after the closure checks and before any permission check or flip: an
     * unavailable one refuses `<root family>.release.dependency_not_released` with nothing flipped.
     * [PrerequisiteCheck.NONE] in a directly built test.
     */
    private val prerequisites: PrerequisiteCheck = PrerequisiteCheck.NONE,
    private val releaseIds: () -> UUID = UUID::randomUUID,
    /** [LegacyReleaseAlias.LEGACY_ALIAS_WITHOUT_PLAN_ADMITTED]; a parameter so both arms are testable (#522 flips it). */
    private val legacyAliasAdmitted: Boolean = LegacyReleaseAlias.LEGACY_ALIAS_WITHOUT_PLAN_ADMITTED,
) : PipelineReleaseCoordination {
    private val participants = participants.associateBy { it.family }

    override fun release(request: PipelineReleaseRequest): PipelineReleaseService.Released {
        val plan = request.plan?.takeIf { it.present }
        if (plan == null) {
            if (request.releasePinnedTemplates && !legacyAliasAdmitted) throw ReleaseRefusals.planRequired(ROOT)
            return compatible(request)
        }
        if (request.releasePinnedTemplates && plan.includeDependencies != null) {
            throw ReleaseRefusals.planInvalid(
                ROOT,
                "include_dependencies",
                "the release_pinned_templates alias and a selection both consent",
            )
        }
        val treeHash = plan.treeHash ?: throw ReleaseRefusals.planRequired(ROOT)
        if (!TreeHash.SHAPE.matches(treeHash)) throw ReleaseRefusals.planInvalid(ROOT, "tree_hash", "not a tree hash")
        return planned(request, plan, treeHash)
    }

    /**
     * A release whose root is NOT a pipeline (#786 C.2: the API is the second root) — the same algorithm through the
     * family's [ReleaseRoot]: the plan's snapshot, the digest before any work, the selection covering every reachable
     * DRAFT, each flipped family's permission, every node prepared outside, and inside ONE transaction the guard, the
     * re-walk compared again, dependencies first and the root last, then the strict audit rows. Without a plan only the
     * root flips: a DRAFT anywhere below it is `dependency_not_released`, read again under the guard.
     *
     * An [ArtifactReleaseRoot] (#873: the template, parameter-set, visualization and dashboard roots) keeps its surfaces'
     * legacy consent: [releasePinned] is the family's `release_pinned_*` flag, scoped by [LegacyReleaseAlias.selection] to
     * exactly the direct drafts that flag released before, and a request without a plan takes [compatibleRoot] — today's
     * order and today's refusal keys. The alias with a selection is `plan_invalid`, as for a pipeline.
     */
    @Suppress("CyclomaticComplexMethod", "ThrowsCount", "LongMethod") // record §7.2's release steps in order, one refusal per step
    fun releaseRoot(
        request: RootReleaseRequest,
        releasePinned: Boolean = false,
    ): RootReleased {
        val rootFamily = request.family
        val root = rootOf(rootFamily)
        val plan = request.plan?.takeIf { it.present }
        if (plan == null && root is ArtifactReleaseRoot) {
            if (releasePinned && !legacyAliasAdmitted) throw ReleaseRefusals.planRequired(rootFamily)
            return compatibleRoot(request, root, releasePinned)
        }
        if (releasePinned && plan?.includeDependencies != null) {
            throw ReleaseRefusals.planInvalid(rootFamily, "include_dependencies", "the release_pinned alias and a selection both consent")
        }
        val treeHash =
            plan?.let {
                val hash = it.treeHash ?: throw ReleaseRefusals.planRequired(rootFamily)
                if (!TreeHash.SHAPE.matches(hash)) throw ReleaseRefusals.planInvalid(rootFamily, "tree_hash", "not a tree hash")
                hash
            }
        val principal = request.principal ?: authorization.principal()
        val rootIdentity = root.rootIdentity(request.workspaceId, request.entityId)
        val closure = plannedWalk(rootIdentity, request.actor)
        if (treeHash != null && TreeHash.of(closure, bounds.maxWireBytes) != treeHash) throw ReleaseRefusals.planConflict(rootFamily)
        val selection = if (plan == null) emptySet() else selection(closure, plan, alias = releasePinned)
        val overrides = if (plan == null) emptyMap() else overrides(closure, plan, null, selection)
        refuseBlockers(closure, selection)
        val order = closure.dependencyFirst.filter { it.identity in selection }
        val families = (order.map { it.identity.family } + rootFamily).distinct()
        families.forEach { authorization.requireRelease(principal, it) }

        val flipping = selection + rootIdentity

        fun context(
            node: ClosureNode,
            expectedHash: String,
        ) = ReleaseContext(request.actor, closure, flipping, expectedHash, overrides[node.identity])
        val prepared = order.map { node -> participant(node).prepare(node, context(node, requireNotNull(node.bodyHash))) }
        val preparedRoot = root.prepare(closure.rootNode, context(closure.rootNode, request.expectedHash))

        val releaseId = releaseIds()
        return inTransaction { status ->
            guard.acquire(request.workspaceId, rootFamily)
            val guarded = guardedWalk(rootIdentity, request.actor)
            if (treeHash != null && TreeHash.of(guarded, bounds.maxWireBytes) != treeHash) throw ReleaseRefusals.planConflict(rootFamily)
            if (treeHash == null) refuseBlockers(guarded, emptySet())
            refusePrerequisites(guarded, request.actor)
            families.forEach { authorization.requireRelease(principal, it) }
            val dependencies = prepared.map { it to participant(it.node).commitPrepared(it, status) }
            val flipped = root.commitPrepared(preparedRoot, status)
            observeDependencyFlips(request.workspaceId, dependencies)
            val released = dependencies.map { (it, _) -> released(it.node) }
            val common = writeDependencyAudit(principal, closure, releaseId, request.entityId, dependencies)
            val (event, details) = rootAuditRow(root, principal, request.entityId, flipped, released)
            record(principal, request.workspaceId, event, details + common + ("dependencies_released" to released.map { it.toWire() }))
            RootReleased(flipped, released)
        }
    }

    /**
     * The compatibility path of an [ArtifactReleaseRoot] (#873 §C.3, §C.8 — the [compatible] mold): no plan field, so
     * only the root flips plus, under the alias, the direct drafts its flag named. Outside the transaction: the root's
     * native preparation and the snapshot's blockers, in the family's own order. Inside: the guard, the closure walked
     * again, the alias selection re-read from it and its blockers refused in the family's native keys, each flipped
     * family's permission, every alias dependency prepared and flipped (today's in-transaction cascade), the root last,
     * then the strict audit rows.
     */
    private fun compatibleRoot(
        request: RootReleaseRequest,
        root: ArtifactReleaseRoot,
        alias: Boolean,
    ): RootReleased {
        val rootFamily = request.family
        val principal = request.principal ?: authorization.principal()
        val rootIdentity = root.rootIdentity(request.workspaceId, request.entityId)
        val snapshot = plannedWalk(rootIdentity, request.actor)
        val consented = if (alias) LegacyReleaseAlias.selection(snapshot) else emptySet()
        if (root.dependenciesBeforePrepare) refuseAlias(root, snapshot, consented)
        val context = ReleaseContext(request.actor, snapshot, consented + rootIdentity, request.expectedHash, null)
        val preparedRoot = root.prepare(snapshot.rootNode, context)
        if (!root.dependenciesBeforePrepare) refuseAlias(root, snapshot, consented)

        val releaseId = releaseIds()
        return inTransaction { status ->
            guard.acquire(request.workspaceId, rootFamily)
            val closure = guardedWalk(rootIdentity, request.actor)
            val included = if (alias) LegacyReleaseAlias.selection(closure) else emptySet()
            refuseAlias(root, closure, included)
            refusePrerequisites(closure, request.actor)
            val order = LegacyReleaseAlias.order(closure, included).map(closure::node)
            (order.map { it.identity.family } + rootFamily).distinct().forEach { authorization.requireRelease(principal, it) }
            val inner = ReleaseContext(request.actor, closure, included + rootIdentity, "", null)
            val dependencies =
                order.map { node ->
                    val dependency = participant(node).prepare(node, inner.copy(expectedHash = requireNotNull(node.bodyHash)))
                    dependency to participant(node).commitPrepared(dependency, status)
                }
            val flipped = root.commitPrepared(preparedRoot, status)
            observeDependencyFlips(request.workspaceId, dependencies)
            val released = dependencies.map { (it, _) -> released(it.node) }
            val common = writeDependencyAudit(principal, closure, releaseId, request.entityId, dependencies)
            val (event, details) = root.auditRow(principal, request.entityId, flipped, released)
            record(principal, request.workspaceId, event, details + common + ("dependencies_released" to released.map { it.toWire() }))
            RootReleased(flipped, released)
        }
    }

    /**
     * #873 §F.3 c: the guarded closure's datasource prerequisites, by name in the caller's workspace; any unavailable one
     * (absent or not granted — one outcome) refuses before any permission check and any flip.
     */
    private fun refusePrerequisites(
        closure: ReleaseClosure,
        actor: UUID,
    ) {
        val unavailable = prerequisites.check(closure, actor).filterNot { it.available }
        if (unavailable.isNotEmpty()) throw PrerequisiteCheck.refusal(closure, unavailable, bounds.maxBlockers)
    }

    /** The compatibility path's refusal: [closure]'s blockers beyond [included], in [root]'s native keys. */
    private fun refuseAlias(
        root: ArtifactReleaseRoot,
        closure: ReleaseClosure,
        included: Set<ReleaseNodeIdentity>,
    ) {
        val blockers = ReleaseBlockers.blockers(closure, included)
        if (blockers.isNotEmpty()) throw root.aliasRefusal(closure, blockers, bounds.maxBlockers)
    }

    private fun rootAuditRow(
        root: ReleaseRoot,
        principal: AuthenticatedPrincipal,
        entityId: UUID,
        flipped: ReleaseFlip,
        released: List<ReleasedDependency>,
    ): Pair<String, Map<String, Any?>> =
        if (root is ArtifactReleaseRoot) {
            root.auditRow(
                principal,
                entityId,
                flipped,
                released,
            )
        } else {
            root.auditRow(principal, entityId, flipped)
        }

    /**
     * The plan a release dialog shows for a non-pipeline root (#786 C.3): the closure read in its own planning snapshot and
     * its tree hash, which the dialog posts back as `tree_hash` so a closure changed after the dialog opened is
     * `plan_conflict` rather than released silently. Reads only; the POST re-runs every check.
     */
    fun previewRoot(
        family: ReleaseFamily,
        workspaceId: UUID,
        entityId: UUID,
        actor: UUID,
    ): RootPreview {
        val closure = plannedWalk(rootOf(family).rootIdentity(workspaceId, entityId), actor)
        return RootPreview(closure, TreeHash.of(closure, bounds.maxWireBytes))
    }

    private fun rootOf(family: ReleaseFamily): ReleaseRoot =
        participants[family] as? ReleaseRoot ?: throw IllegalArgumentException("$family is not a registered release root")

    /** The compatibility path (record §6): native preparation, then the legacy closure check and flips under the guard. */
    private fun compatible(request: PipelineReleaseRequest): PipelineReleaseService.Released {
        val prepared = releases.prepare(request.workspaceId, request.pipelineId, request.actor, request.overrideChecksReason)
        val principal = authorization.principal()
        val releaseId = releaseIds()
        val released =
            inTransaction { status ->
                guard.acquire(request.workspaceId, ROOT)
                // A draft may propose a rename; only the flip changes the registry's lookup name.
                val rootRecord =
                    pipelines.findById(request.workspaceId, request.pipelineId) ?: throw ReleaseRefusals.planConflict(ROOT)
                val rootIdentity = PipelineDependencySource.identity(request.workspaceId, rootRecord.name, prepared.draft.version)
                val closure = guardedWalk(rootIdentity, request.actor)
                val included = if (request.releasePinnedTemplates) LegacyReleaseAlias.selection(closure) else emptySet()
                refuseBlockers(closure, included)
                refusePrerequisites(closure, request.actor)
                val order = LegacyReleaseAlias.order(closure, included).map(closure::node)
                order.map { it.identity.family }.distinct().forEach(authorization::requireRelease)
                val context = ReleaseContext(request.actor, closure, included + rootIdentity, "", null)
                val dependencies =
                    order.map { node ->
                        val dependency = participant(node).prepare(node, context.copy(expectedHash = requireNotNull(node.bodyHash)))
                        dependency to participant(node).commitPrepared(dependency, status)
                    }
                val flipped = releases.flip(prepared, request.expectedHash, request.actor)
                observeFlips(request, flipped, dependencies)
                val result = complete(prepared, flipped, dependencies)
                writeAudit(principal, closure, releaseId, request.pipelineId, dependencies, result)
                result
            }
        return released.copy(warnings = releases.warnings(prepared))
    }

    /** The plan path (record §4.1 steps 2–7). */
    private fun planned(
        request: PipelineReleaseRequest,
        plan: ReleasePlanRequest,
        treeHash: String,
    ): PipelineReleaseService.Released {
        val rootIdentity = rootIdentity(request)
        // Step 2: one read-only REPEATABLE READ snapshot, committed before anything is built from it (record §7.2, §7.3).
        val closure = plannedWalk(rootIdentity, request.actor)
        // Step 3: the digest first, before any validation or check work — a stale preview costs nothing.
        if (TreeHash.of(closure, bounds.maxWireBytes) != treeHash) throw ReleaseRefusals.planConflict(ROOT)
        val selection = selection(closure, plan, request.releasePinnedTemplates)
        val overrides = overrides(closure, plan, request.overrideChecksReason, selection)
        refuseBlockers(closure, selection)
        val order = closure.dependencyFirst.filter { it.identity in selection }
        val families = (order.map { it.identity.family } + ROOT).distinct()
        families.forEach(authorization::requireRelease)

        // Step 4: every node prepared outside the metadata transaction, each with its OWN override reason.
        val flipping = selection + rootIdentity

        fun context(
            node: ClosureNode,
            expectedHash: String,
        ) = ReleaseContext(request.actor, closure, flipping, expectedHash, overrides[node.identity])
        val prepared = order.map { node -> participant(node).prepare(node, context(node, requireNotNull(node.bodyHash))) }
        val root = rootParticipant().prepare(closure.rootNode, context(closure.rootNode, request.expectedHash))

        val principal = authorization.principal()
        val releaseId = releaseIds()
        val released =
            inTransaction { status ->
                // Step 5: the guard, then the closure re-resolved and compared under it.
                guard.acquire(request.workspaceId, ROOT)
                val guarded = guardedWalk(rootIdentity, request.actor)
                if (TreeHash.of(guarded, bounds.maxWireBytes) != treeHash) throw ReleaseRefusals.planConflict(ROOT)
                refusePrerequisites(guarded, request.actor)
                families.forEach(authorization::requireRelease)
                // Step 6: dependencies first, root last; each CAS at its consented hash.
                val dependencies = prepared.map { it to participant(it.node).commitPrepared(it, status) }
                val flipped = rootParticipant().commitPrepared(root, status).result as PipelineRepository.Released
                observeFlips(request, flipped, dependencies)
                val result = complete(root.native, flipped, dependencies)
                // Step 7: one strict row per flip, in release order, in this transaction.
                writeAudit(principal, closure, releaseId, request.pipelineId, dependencies, result)
                result
            }
        return released.copy(warnings = releases.warnings(root.native))
    }

    /**
     * The root's identity for a plan, read without running anything: a pipeline with no draft (or none at all) is the
     * native refusal, raised by the native preparation itself so its code and order are unchanged.
     */
    private fun rootIdentity(request: PipelineReleaseRequest): ReleaseNodeIdentity {
        val record = pipelines.findById(request.workspaceId, request.pipelineId)
        val draft = pipelines.findDraftDetail(request.workspaceId, request.pipelineId)
        if (record == null || draft == null) {
            releases.prepare(request.workspaceId, request.pipelineId, request.actor, null)
            error("pipeline ${request.pipelineId} has no draft, yet its preparation did not refuse")
        }
        return PipelineDependencySource.identity(request.workspaceId, record.name, draft.version)
    }

    /** The plan's first closure read, in its own read-only REPEATABLE READ planning transaction. */
    private fun plannedWalk(
        root: ReleaseNodeIdentity,
        actor: UUID,
    ): ReleaseClosure = planning.plan(root, actor) ?: throw ReleaseRefusals.planConflict(root.family)

    /** The closure read inside the release transaction, under the guard. */
    private fun guardedWalk(
        root: ReleaseNodeIdentity,
        actor: UUID,
    ): ReleaseClosure = planning.walkInCurrentTransaction(root, actor) ?: throw ReleaseRefusals.planConflict(root.family)

    private fun refuseBlockers(
        closure: ReleaseClosure,
        included: Set<ReleaseNodeIdentity>,
    ) {
        val blockers = ReleaseBlockers.blockers(closure, included)
        if (blockers.isNotEmpty()) throw ReleaseRefusals.dependencyNotReleased(closure, blockers, bounds.maxBlockers)
    }

    /** Step 2: the selected node ids, each a DRAFT dependency of THIS snapshot, none twice, the root never. */
    @Suppress("ThrowsCount") // a boundary maps each distinct malformation to its own field locator
    private fun selection(
        closure: ReleaseClosure,
        plan: ReleasePlanRequest,
        alias: Boolean,
    ): Set<ReleaseNodeIdentity> {
        if (alias) return LegacyReleaseAlias.selection(closure)
        val root = closure.root.family
        val ids = plan.includeDependencies ?: return emptySet()
        if (ids.size > bounds.maxVersions) throw ReleaseRefusals.planInvalid(root, "include_dependencies", "too many entries")
        val seen = HashSet<String>()
        return ids
            .mapIndexed { index, id ->
                val field = "include_dependencies[$index]"
                if (!seen.add(id)) throw ReleaseRefusals.planInvalid(root, field, "a node is included twice")
                val node = closure.nodeById(id) ?: throw ReleaseRefusals.planInvalid(root, field, "not a node of the plan")
                if (node.identity == closure.root) throw ReleaseRefusals.planInvalid(root, field, "the root is released by the action")
                if (node.status != ReleaseNodeStatus.DRAFT) throw ReleaseRefusals.planInvalid(root, field, "only a DRAFT can be included")
                node.identity
            }.toSet()
    }

    /** Step 4's per-node override reasons: the root's from the query or its plan entry (never both), a dependency's own. */
    @Suppress("ThrowsCount") // a boundary maps each distinct malformation to its own field locator
    private fun overrides(
        closure: ReleaseClosure,
        plan: ReleasePlanRequest,
        rootReason: String?,
        selection: Set<ReleaseNodeIdentity>,
    ): Map<ReleaseNodeIdentity, String> {
        val entries = plan.checkOverrides.orEmpty()
        val root = closure.root.family
        if (entries.size > bounds.maxVersions) throw ReleaseRefusals.planInvalid(root, "check_overrides", "too many entries")
        val reasons = HashMap<ReleaseNodeIdentity, String>()
        if (rootReason != null) reasons[closure.root] = rootReason
        entries.forEachIndexed { index, entry ->
            val field = "check_overrides[$index]"
            val node = closure.nodeById(entry.nodeId) ?: throw ReleaseRefusals.planInvalid(root, field, "not a node of the plan")
            if (node.identity != closure.root && node.identity !in selection) {
                throw ReleaseRefusals.planInvalid(root, field, "the node is not released by this plan")
            }
            if (reasons.putIfAbsent(node.identity, entry.reason) != null) {
                throw ReleaseRefusals.planInvalid(root, field, "a node's override is given twice")
            }
        }
        return reasons
    }

    /** #778 — the PIPELINE versions this transaction flipped (a template has no datasource, slice D-D), root last. */
    private fun observeFlips(
        request: PipelineReleaseRequest,
        root: PipelineRepository.Released,
        dependencies: List<Pair<PreparedRelease, ReleaseFlip>>,
    ) {
        observeDependencyFlips(request.workspaceId, dependencies)
        observer.released(ReleasedVersion(request.workspaceId, ReleaseFamily.PIPELINE, request.pipelineId, root.version.version))
    }

    /** #778 — every PIPELINE dependency a release flipped, whatever its root's family. */
    private fun observeDependencyFlips(
        workspaceId: UUID,
        dependencies: List<Pair<PreparedRelease, ReleaseFlip>>,
    ) {
        dependencies
            .map { (prepared, _) -> prepared.node }
            .filter { it.identity.family == ReleaseFamily.PIPELINE }
            .forEach { node ->
                val entityId = checkNotNull(node.entityUuid) { "a flipped pipeline ${node.name} has no id" }
                observer.released(ReleasedVersion(workspaceId, ReleaseFamily.PIPELINE, entityId, node.identity.version))
            }
    }

    private fun participant(node: ClosureNode): ReleaseParticipant =
        checkNotNull(participants[node.identity.family]) { "no release participant for ${node.identity.family}" }

    private fun rootParticipant(): PipelineReleaseParticipant = participant(ROOT) as PipelineReleaseParticipant

    private fun participant(family: ReleaseFamily): ReleaseParticipant =
        checkNotNull(participants[family]) { "no release participant for $family" }

    /** The root's family result, built inside the transaction for its audit row; warnings are read after commit. */
    private fun complete(
        root: PipelineReleaseService.Prepared,
        flipped: PipelineRepository.Released,
        dependencies: List<Pair<PreparedRelease, ReleaseFlip>>,
    ): PipelineReleaseService.Released =
        releases.finish(
            root,
            flipped,
            dependencies.mapNotNull { (_, flip) -> (flip.result as? TemplateReleaseService.Released)?.let(::templateRef) },
            dependencies.map { (prepared, _) -> released(prepared.node) },
            auditedInTransaction = true,
            readWarnings = false,
        )

    private fun templateRef(released: TemplateReleaseService.Released) = TemplateRef(released.detail.templateId, released.detail.version)

    private fun released(node: ClosureNode) = ReleasedDependency(node.identity.family, node.name, node.identity.version)

    /**
     * Step 7 (record §11): one `*.version.released` row per flip, dependencies first in release order, root last — the
     * existing details of each family (the #142 keys included) plus `scope:version`, the transaction's `release_id`,
     * `cascade_root` on every cascaded row and `dependencies_released` on the root's.
     */
    private fun writeAudit(
        principal: AuthenticatedPrincipal,
        closure: ReleaseClosure,
        releaseId: UUID,
        pipelineId: UUID,
        dependencies: List<Pair<PreparedRelease, ReleaseFlip>>,
        root: PipelineReleaseService.Released,
    ) {
        val common = writeDependencyAudit(principal, closure, releaseId, pipelineId, dependencies)
        val rootDetails =
            LifecycleVerbs.releaseDetails(principal, pipelineId, root) + common +
                ("dependencies_released" to root.dependenciesReleased.map { it.toWire() })
        record(principal, closure.root.workspaceId, LifecycleVerbs.AUDIT_VERSION_RELEASED, rootDetails)
    }

    /**
     * The dependency rows of step 7, whatever the root's family: one row per dependency flip in release order, each with
     * `cascade_root` naming the root's family, id and version. Answers the details every row of this release shares.
     *
     * Each row keeps the details its family's cascade wrote before the planner (#873 §C.7): a template under a pipeline
     * (or API) root `cascade_from_pipeline_id` (#142); under any other root the root family's provenance
     * (`cascade_from_parameter_set_id`, `cascade_from_visualization_id`, `cascade_from_dashboard_id`,
     * `cascade_from_template_id` — #332's [LifecycleVerbs.FamilyCascade]); a dashboard-cascaded visualization its `name`
     * and no id, as #332 wrote it; a parameter set its id and name.
     */
    private fun writeDependencyAudit(
        principal: AuthenticatedPrincipal,
        closure: ReleaseClosure,
        releaseId: UUID,
        rootEntityId: UUID,
        dependencies: List<Pair<PreparedRelease, ReleaseFlip>>,
    ): Map<String, Any?> {
        val workspaceId = closure.root.workspaceId
        val rootVersion = closure.root.version
        val common = linkedMapOf<String, Any?>("scope" to "version", "release_id" to releaseId.toString())
        val cascadeRoot = linkedMapOf("family" to closure.root.family.wire, "id" to rootEntityId.toString(), "version" to rootVersion)
        val source = LifecycleVerbs.CascadeSource(rootEntityId, rootVersion)
        val pipelineRooted = closure.root.family == ReleaseFamily.PIPELINE || closure.root.family == ReleaseFamily.ENDPOINT
        val cascade = LifecycleVerbs.FamilyCascade("${closure.root.family.wire}_id", rootEntityId, rootVersion)

        fun provenance(details: Map<String, Any?>): Map<String, Any?> =
            if (closure.root.family != ReleaseFamily.TEMPLATE) {
                details
            } else {
                // A template is named by its authored id, never its surrogate (the template family's own keys).
                details + (cascade.provenance.first to closure.rootNode.name)
            }
        dependencies.forEach { (prepared, flip) ->
            val node = prepared.node
            val (event, details) =
                when (val result = flip.result) {
                    is TemplateReleaseService.Released -> {
                        LifecycleVerbs.TEMPLATE_AUDIT_VERSION_RELEASED to
                            if (pipelineRooted) {
                                LifecycleVerbs.templateReleaseDetails(principal, result.detail.templateId, result.detail.version, source)
                            } else {
                                provenance(
                                    LifecycleVerbs.cascadedReleaseDetails(
                                        principal,
                                        "template_id",
                                        result.detail.templateId,
                                        result.detail.version,
                                        cascade,
                                    ),
                                )
                            }
                    }

                    is PipelineRepository.Released -> {
                        val child = (prepared as PipelineReleaseParticipant.Prepared).native
                        LifecycleVerbs.AUDIT_VERSION_RELEASED to
                            LifecycleVerbs.releaseDetails(
                                principal,
                                child.pipelineId,
                                releases.finish(child, result, emptyList(), readWarnings = false),
                            )
                    }

                    else -> {
                        artifactAuditRow(principal, node, cascade).let { (event, details) -> event to provenance(details) }
                    }
                }
            record(principal, workspaceId, event, details + common + ("cascade_root" to cascadeRoot))
        }
        return common
    }

    /** The audit event and details of a flipped visualization or parameter set under [cascade], before provenance. */
    private fun artifactAuditRow(
        principal: AuthenticatedPrincipal,
        node: ClosureNode,
        cascade: LifecycleVerbs.FamilyCascade,
    ): Pair<String, Map<String, Any?>> =
        when (node.identity.family) {
            ReleaseFamily.VISUALIZATION -> {
                LifecycleVerbs.VISUALIZATION_EVENTS.versionReleased to
                    LifecycleVerbs.cascadedReleaseDetails(principal, null, null, node.identity.version, cascade) + ("name" to node.name)
            }

            ReleaseFamily.PARAMETER_SET -> {
                LifecycleVerbs.PARAMETER_SET_EVENTS.versionReleased to
                    LifecycleVerbs.cascadedReleaseDetails(
                        principal,
                        "parameter_set_id",
                        checkNotNull(node.entityUuid) { "a flipped parameter set ${node.name} has no id" }.toString(),
                        node.identity.version,
                        cascade,
                    ) + ("name" to node.name)
            }

            else -> {
                error("no audit row shape for a ${node.identity.family} flip")
            }
        }

    private fun record(
        principal: AuthenticatedPrincipal,
        workspaceId: UUID,
        event: String,
        details: Map<String, Any?>,
    ) = audit.record(ReleaseAuditRow(event, principal.userId, principal.keyId, details + ("workspace_id" to workspaceId.toString())))

    /** [block] in the one metadata transaction; a holder because `execute` answers a nullable. */
    private fun <T> inTransaction(block: (TransactionStatus) -> T): T {
        var result: Result<T>? = null
        transactions.executeWithoutResult { status -> result = Result.success(block(status)) }
        return requireNotNull(result) { "the release transaction did not run its callback" }.getOrThrow()
    }

    private companion object {
        val ROOT = ReleaseFamily.PIPELINE
    }
}

/**
 * A root whose surfaces post the legacy consent and read today's refusal keys (#873 §C.3, §C.5): the template,
 * parameter-set, visualization and dashboard families. On the coordinator's compatibility path — no plan field — the
 * root's native preparation and its dependency refusal run in the family's OWN order ([dependenciesBeforePrepare]) and
 * the refusal keeps the family's keys ([aliasRefusal]); the root's audit row names the flipped dependencies its #332 row
 * named ([auditRow] with the release's dependencies).
 */
interface ArtifactReleaseRoot : ReleaseRoot {
    /** True when the family's native release refused its dependencies BEFORE its own validation ran. */
    val dependenciesBeforePrepare: Boolean

    /** The compatibility path's `dependency_not_released`, in the family's native `details` keys. */
    fun aliasRefusal(
        closure: ReleaseClosure,
        blockers: List<Pair<ClosureNode, BlockerReason>>,
        maxBlockers: Int,
    ): DatapipelinesException

    /** The root's strict audit row naming [dependencies] — this release's flipped dependencies, in release order. */
    fun auditRow(
        principal: AuthenticatedPrincipal,
        entityId: UUID,
        flip: ReleaseFlip,
        dependencies: List<ReleasedDependency>,
    ): Pair<String, Map<String, Any?>>

    override fun auditRow(
        principal: AuthenticatedPrincipal,
        entityId: UUID,
        flip: ReleaseFlip,
    ): Pair<String, Map<String, Any?>> = auditRow(principal, entityId, flip, emptyList())

    /** Helpers the four artifact roots share. */
    companion object {
        /** A participant's changed snapshot: the ROOT family's `plan_conflict`, naming the visible node. */
        fun conflict(
            rootFamily: ReleaseFamily,
            node: ClosureNode,
        ): DatapipelinesException = ReleaseRefusals.planConflict(rootFamily, node.nodeId)

        /** The root's direct pins, distinct, in the root's pin order — the order every native refusal listed them in. */
        fun directPins(closure: ReleaseClosure): List<ClosureNode> =
            closure.edges
                .filter { it.from == closure.root }
                .map { it.to }
                .distinct()
                .map(closure::node)

        /** [blockers] with the root's direct pins first (pin order), then the deeper ones (discovery order). */
        fun directFirst(
            closure: ReleaseClosure,
            blockers: List<Pair<ClosureNode, BlockerReason>>,
        ): List<Pair<ClosureNode, BlockerReason>> {
            val direct = directPins(closure).map { it.identity }
            val byIdentity = blockers.associateBy { it.first.identity }
            return direct.mapNotNull(byIdentity::get) + blockers.filter { it.first.identity !in direct }
        }

        /** The flipped templates of [dependencies] (a template's name is its id) — a set's and a visualization's cascade list. */
        fun templates(dependencies: List<ReleasedDependency>): List<ReleasedDependency> =
            dependencies.filter { it.family == ReleaseFamily.TEMPLATE }

        /** The flipped visualizations of [dependencies] — a dashboard's cascade list. */
        fun visualizations(dependencies: List<ReleasedDependency>): List<ReleasedDependency> =
            dependencies.filter { it.family == ReleaseFamily.VISUALIZATION }
    }
}
