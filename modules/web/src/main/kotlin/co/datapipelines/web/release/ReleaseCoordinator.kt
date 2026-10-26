package co.datapipelines.web.release

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.release.ClosureNode
import co.datapipelines.pipeline.release.DependencySource
import co.datapipelines.pipeline.release.LegacyReleaseAlias
import co.datapipelines.pipeline.release.PipelineDependencySource
import co.datapipelines.pipeline.release.PipelineReleaseCoordination
import co.datapipelines.pipeline.release.PipelineReleaseRequest
import co.datapipelines.pipeline.release.PreparedRelease
import co.datapipelines.pipeline.release.ReleaseAuditRow
import co.datapipelines.pipeline.release.ReleaseAuditSink
import co.datapipelines.pipeline.release.ReleaseBlockers
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseClosure
import co.datapipelines.pipeline.release.ReleaseContext
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseFlip
import co.datapipelines.pipeline.release.ReleaseGraph
import co.datapipelines.pipeline.release.ReleaseMutationGuard
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleaseParticipant
import co.datapipelines.pipeline.release.ReleasePlanRequest
import co.datapipelines.pipeline.release.ReleaseRefusals
import co.datapipelines.pipeline.release.ReleasedDependency
import co.datapipelines.pipeline.release.TreeHash
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
    sources: Map<ReleaseFamily, DependencySource>,
    participants: List<ReleaseParticipant>,
    private val authorization: ReleaseAuthorization,
    private val guard: ReleaseMutationGuard,
    private val audit: ReleaseAuditSink,
    private val transactions: TransactionOperations,
    private val bounds: ReleaseBounds,
    private val releaseIds: () -> UUID = UUID::randomUUID,
    /** [LegacyReleaseAlias.LEGACY_ALIAS_WITHOUT_PLAN_ADMITTED]; a parameter so both arms are testable (#522 flips it). */
    private val legacyAliasAdmitted: Boolean = LegacyReleaseAlias.LEGACY_ALIAS_WITHOUT_PLAN_ADMITTED,
) : PipelineReleaseCoordination {
    private val participants = participants.associateBy { it.family }
    private val graph = ReleaseGraph(sources, bounds)

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
                val closure = walk(rootIdentity)
                val included = if (request.releasePinnedTemplates) LegacyReleaseAlias.selection(closure) else emptySet()
                refuseBlockers(closure, included)
                val order = LegacyReleaseAlias.order(closure, included).map(closure::node)
                order.map { it.identity.family }.distinct().forEach(authorization::requireRelease)
                val context = ReleaseContext(request.actor, closure, included + rootIdentity, "", null)
                val dependencies =
                    order.map { node ->
                        val dependency = participant(node).prepare(node, context.copy(expectedHash = requireNotNull(node.bodyHash)))
                        dependency to participant(node).commitPrepared(dependency, status)
                    }
                val flipped = releases.flip(prepared, request.expectedHash, request.actor)
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
        val closure = walk(rootIdentity)
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
                if (TreeHash.of(walk(rootIdentity), bounds.maxWireBytes) != treeHash) throw ReleaseRefusals.planConflict(ROOT)
                families.forEach(authorization::requireRelease)
                // Step 6: dependencies first, root last; each CAS at its consented hash.
                val dependencies = prepared.map { it to participant(it.node).commitPrepared(it, status) }
                val flipped = rootParticipant().commitPrepared(root, status).result as PipelineRepository.Released
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

    private fun walk(root: ReleaseNodeIdentity): ReleaseClosure = graph.walk(root) ?: throw ReleaseRefusals.planConflict(ROOT)

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
        val ids = plan.includeDependencies ?: return emptySet()
        if (ids.size > bounds.maxVersions) throw ReleaseRefusals.planInvalid(ROOT, "include_dependencies", "too many entries")
        val seen = HashSet<String>()
        return ids
            .mapIndexed { index, id ->
                val field = "include_dependencies[$index]"
                if (!seen.add(id)) throw ReleaseRefusals.planInvalid(ROOT, field, "a node is included twice")
                val node = closure.nodeById(id) ?: throw ReleaseRefusals.planInvalid(ROOT, field, "not a node of the plan")
                if (node.identity == closure.root) throw ReleaseRefusals.planInvalid(ROOT, field, "the root is released by the action")
                if (node.status != ReleaseNodeStatus.DRAFT) throw ReleaseRefusals.planInvalid(ROOT, field, "only a DRAFT can be included")
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
        if (entries.size > bounds.maxVersions) throw ReleaseRefusals.planInvalid(ROOT, "check_overrides", "too many entries")
        val reasons = HashMap<ReleaseNodeIdentity, String>()
        if (rootReason != null) reasons[closure.root] = rootReason
        entries.forEachIndexed { index, entry ->
            val field = "check_overrides[$index]"
            val node = closure.nodeById(entry.nodeId) ?: throw ReleaseRefusals.planInvalid(ROOT, field, "not a node of the plan")
            if (node.identity != closure.root && node.identity !in selection) {
                throw ReleaseRefusals.planInvalid(ROOT, field, "the node is not released by this plan")
            }
            if (reasons.putIfAbsent(node.identity, entry.reason) != null) {
                throw ReleaseRefusals.planInvalid(ROOT, field, "a node's override is given twice")
            }
        }
        return reasons
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
        val workspaceId = closure.root.workspaceId
        val rootVersion = closure.root.version
        val common = linkedMapOf<String, Any?>("scope" to "version", "release_id" to releaseId.toString())
        val cascadeRoot = linkedMapOf("family" to ROOT.wire, "id" to pipelineId.toString(), "version" to rootVersion)
        val source = LifecycleVerbs.CascadeSource(pipelineId, rootVersion)
        dependencies.forEach { (prepared, flip) ->
            val (event, details) =
                when (val result = flip.result) {
                    is TemplateReleaseService.Released -> {
                        LifecycleVerbs.TEMPLATE_AUDIT_VERSION_RELEASED to
                            LifecycleVerbs.templateReleaseDetails(principal, result.detail.templateId, result.detail.version, source)
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
                        error("no audit row shape for a ${prepared.node.identity.family} flip")
                    }
                }
            record(principal, workspaceId, event, details + common + ("cascade_root" to cascadeRoot))
        }
        val rootDetails =
            LifecycleVerbs.releaseDetails(principal, pipelineId, root) + common +
                ("dependencies_released" to root.dependenciesReleased.map { it.toWire() })
        record(principal, workspaceId, LifecycleVerbs.AUDIT_VERSION_RELEASED, rootDetails)
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
