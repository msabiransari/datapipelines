package co.datapipelines.web.release

import co.datapipelines.application.dependencies.ReleaseDependencyFamilies
import co.datapipelines.application.dependencies.ReleasePlanningDeadline
import co.datapipelines.application.dependencies.ReleasePlanningWalk
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.parameters.ParameterSetRecord
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.ParameterSetVersion
import co.datapipelines.parameters.ParameterSetVersionDetail
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.release.DependencySource
import co.datapipelines.pipeline.release.PipelineReleaseRequest
import co.datapipelines.pipeline.release.PrerequisiteCheck
import co.datapipelines.pipeline.release.ReleaseAuditRow
import co.datapipelines.pipeline.release.ReleaseAuditSink
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseGraph
import co.datapipelines.pipeline.release.ReleaseMutationGuard
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleasePin
import co.datapipelines.pipeline.release.ReleasePlanRequest
import co.datapipelines.pipeline.release.ReleasedDependency
import co.datapipelines.pipeline.release.ResolvedNode
import co.datapipelines.pipeline.release.TreeHash
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactRecord
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.ArtifactVersionDetail
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.ReleaseCandidate
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.templates.TemplateReleaseService
import io.mockk.every
import io.mockk.mockk
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * A [ReleaseCoordinator] over the REAL pipeline and template participants, with the native services they call mocked at
 * their seams and every side effect the record orders written to one [log]: `prepare:<name>` (outside), `begin`,
 * `guard:<family>`, `auth:<family>`, `flip:<name>@<hash>`, `audit:<event>:<name>`, then `commit` or `rollback`.
 *
 * The dependency sources read an in-memory graph the test edits between preview and submit — or, through [onPrepare],
 * DURING preparation, which is the only window the in-transaction re-resolution exists for.
 *
 * #873 §B/§C: the template, parameter-set, visualization and dashboard ROOTS run through their REAL participants too,
 * over their services mocked at the four release seams (`releaseTarget`, `prepareRelease`, `commitRelease`,
 * `staleRelease`); each native prepare and commit also records whether a transaction was active ([phases]) — the
 * fixture's transaction manager marks one active between `begin` and `commit`/`rollback`, as the real manager does.
 */
internal class CoordinatorFixture(
    private val bounds: ReleaseBounds = ReleaseBounds(),
    private val realNative: Boolean = false,
) {
    val workspaceId: UUID = UUID.randomUUID()
    val actor: UUID = UUID.randomUUID()
    val releaseId: UUID = UUID.randomUUID()
    val log = mutableListOf<String>()
    val audited = mutableListOf<ReleaseAuditRow>()

    /** Runs when the named pipeline is prepared — the drift-during-preparation hook. */
    val onPrepare = mutableMapOf<String, () -> Unit>()

    /** Set to make the strict audit sink refuse, as unavailable audit storage does. */
    var auditFails = false

    private val graph = LinkedHashMap<ReleaseNodeIdentity, ResolvedNode>()
    private val records = HashMap<String, PipelineRecord>()

    /** Every dependency-source read, in order — kept apart from [log] so the write-order assertions stay unchanged. */
    val reads = mutableListOf<ReleaseNodeIdentity>()
    private val source = DependencySource { identity -> graph[identity].also { reads += identity } }
    val sources: Map<ReleaseFamily, DependencySource> = ReleaseFamily.entries.associateWith { source }

    /** `<step>:<name>:<transaction active>` for every native prepare and commit of the artifact families (§C.8). */
    val phases = mutableListOf<String>()
    val sets = mockk<ParameterSetService>()
    val visualizations = mockk<VisualizationService>()
    val dashboards = mockk<DashboardService>()

    val pipelines = mockk<PipelineRepository>()
    private val bodies = HashMap<String, String>()
    val releases =
        if (realNative) {
            io.mockk.spyk(
                PipelineReleaseService(
                    pipelines,
                    mockk(relaxed = true),
                    mockk<co.datapipelines.pipeline.PipelineValidator> {
                        every { validateOrThrow(any(), any(), any()) } answers { firstArg() }
                    },
                    co.datapipelines.pipeline.AuthoringGuard(true),
                    checkGate =
                        co.datapipelines.pipeline.ReleaseCheckGate { _, _, _, _, _ ->
                            listOf(
                                co.datapipelines.pipeline.CheckRunOutcome(
                                    "fail",
                                    "Fail",
                                    co.datapipelines.pipeline.CheckExpectation(kind = "value", value = 0.0),
                                    "1",
                                    co.datapipelines.pipeline.CheckRunVerdict.FAIL,
                                    null,
                                ),
                            )
                        },
                    dashboards = mockk(relaxed = true),
                    endpointVersions = co.datapipelines.web.NO_API_VERSION_PINS,
                    schedules = co.datapipelines.web.NO_SCHEDULE_PINS,
                ),
            )
        } else {
            mockk<PipelineReleaseService>()
        }
    val templateReleases = mockk<TemplateReleaseService>()

    val guard = ReleaseMutationGuard { _, family -> log += "guard:${family.wire}" }

    private val audit =
        ReleaseAuditSink { row ->
            check(!auditFails) { "audit storage unavailable" }
            audited += row
            log += "audit:${row.event}:${NAME_KEYS.firstNotNullOfOrNull { row.details[it] }}"
        }

    private val transactions =
        object : PlatformTransactionManager {
            override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
                log += "begin"
                TransactionSynchronizationManager.setActualTransactionActive(true)
                return SimpleTransactionStatus()
            }

            override fun commit(status: TransactionStatus) {
                log += "commit"
                TransactionSynchronizationManager.setActualTransactionActive(false)
            }

            override fun rollback(status: TransactionStatus) {
                log += "rollback"
                TransactionSynchronizationManager.setActualTransactionActive(false)
            }
        }

    init {
        if (!realNative) every { releases.revalidateStrict(any()) } returns Unit
        every { releases.warnings(any()) } returns emptyList()
        every { releases.finish(any(), any(), any(), any(), any(), any()) } answers {
            val flipped = secondArg<PipelineRepository.Released>()
            PipelineReleaseService.Released(
                record = flipped.record,
                version = flipped.version,
                bodyJson = firstArg<PipelineReleaseService.Prepared>().bodyJson,
                templatesReleased = thirdArg<List<TemplateRef>>(),
                dependenciesReleased = arg<List<ReleasedDependency>>(3),
                auditedInTransaction = arg<Boolean>(4),
            )
        }
        every { templateReleases.releaseExact(workspaceId, any(), any(), any(), actor, any()) } answers {
            val id = secondArg<String>()
            val version = thirdArg<Int>()
            val consented = arg<String>(3)
            val node = graph[ReleaseNodeIdentity(workspaceId, ReleaseFamily.TEMPLATE, id, version)]
            // The native compare-and-set: the draft at exactly the consented hash, or the caller's conflict.
            if (node?.status != ReleaseNodeStatus.DRAFT || node.bodyHash != consented) throw arg<() -> DatapipelinesException>(5)()
            log += "flip:$id@$consented"
            TemplateReleaseService.Released(
                TemplateVersionDetail(id, version, PipelineVersionStatus.RELEASED, consented, Instant.EPOCH, actor),
                mockk(),
            )
        }
    }

    /** A pipeline version in the graph, pinning [pins] in node order; its native seams answer for it. */
    fun pipeline(
        name: String,
        pins: List<ReleaseNodeIdentity> = emptyList(),
        status: ReleaseNodeStatus = ReleaseNodeStatus.DRAFT,
        version: Int = 1,
        hash: String = "hash-$name",
    ): ReleaseNodeIdentity {
        val identity = ReleaseNodeIdentity(workspaceId, ReleaseFamily.PIPELINE, name, version)
        val path = { index: Int, pin: ReleaseNodeIdentity ->
            if (pin.family == ReleaseFamily.PIPELINE) "nodes[$index].pipeline" else "nodes[$index].template"
        }
        val record = PipelineRecord(UUID.randomUUID(), name, name, "d", actor, null, Instant.EPOCH, Instant.EPOCH)
        graph[identity] =
            ResolvedNode(
                identity,
                name,
                status,
                hash,
                if (status == ReleaseNodeStatus.RELEASED) version else null,
                pins.mapIndexed { index, pin -> ReleasePin(pin, pin.entityId, path(index, pin)) },
                record.id,
            )
        records[name] = record
        val draft = PipelineVersionDetail(record.id, version, PipelineVersionStatus.DRAFT, hash, Instant.EPOCH, actor)
        every { pipelines.findByNameAnyStatus(workspaceId, name) } returns record
        every { pipelines.findById(workspaceId, record.id) } returns record
        every { pipelines.findDraftDetail(workspaceId, record.id) } returns draft.takeIf { status == ReleaseNodeStatus.DRAFT }
        val model = mockk<Pipeline> { every { this@mockk.name } returns name }
        bodies[name] =
            """{"schema_version":1,"name":"$name","display_name":"$name","description":"d","parameters":{},""" +
            """"settings":{"tempdb":{"engine":"H2"}},"nodes":[]}"""
        every { pipelines.findVersionBody(workspaceId, record.id, version) } answers {
            onPrepare[name]?.invoke()
            bodies.getValue(name)
        }
        every { pipelines.findDraftBodySnapshot(workspaceId, record.id, version) } answers {
            onPrepare[name]?.invoke()
            PipelineRepository.DraftBodySnapshot(bodies.getValue(name), graph.getValue(identity).bodyHash)
        }
        every { pipelines.releaseDraft(workspaceId, record.id, any(), any(), any(), any(), actor, version) } answers {
            val stored = graph.getValue(identity)
            if (stored.status != ReleaseNodeStatus.DRAFT || stored.bodyHash != arg<String>(5)) {
                null
            } else {
                graph[identity] = stored.copy(status = ReleaseNodeStatus.RELEASED, currentVersion = version)
                log += "flip:$name@${arg<String>(5)}"
                PipelineRepository.Released(record.copy(currentVersion = version), draft.copy(status = PipelineVersionStatus.RELEASED))
            }
        }
        if (!realNative) {
            every { releases.prepare(workspaceId, record.id, actor, any(), any()) } answers {
                log += "prepare:$name"
                onPrepare[name]?.invoke()
                PipelineReleaseService.Prepared(workspaceId, record.id, draft, "{}", model, emptyList(), arg(3))
            }
        }
        if (!realNative) {
            every { releases.flip(match { it.pipelineId == record.id }, any(), actor) } answers {
                log += "flip:$name@${secondArg<String>()}"
                // The flip is visible to what commits after it in the same transaction (a dashboard's R5 re-validation).
                graph[identity] = graph.getValue(identity).copy(status = ReleaseNodeStatus.RELEASED, currentVersion = version)
                PipelineRepository.Released(record, draft.copy(status = PipelineVersionStatus.RELEASED))
            }
        }
        return identity
    }

    /**
     * A template version in the graph, importing [imports] (#873: the import edge); as a ROOT its service seams answer for
     * it — the name by entity, the DRAFT, and the native exact-hash flip whose stale hash is `template.version.conflict`.
     */
    fun template(
        id: String,
        status: ReleaseNodeStatus = ReleaseNodeStatus.DRAFT,
        version: Int = 1,
        hash: String = "hash-$id",
        current: Int? = if (status == ReleaseNodeStatus.RELEASED) version else null,
        imports: List<ReleaseNodeIdentity> = emptyList(),
    ): ReleaseNodeIdentity {
        val identity = ReleaseNodeIdentity(workspaceId, ReleaseFamily.TEMPLATE, id, version)
        val entity = UUID.nameUUIDFromBytes(id.toByteArray())
        graph[identity] =
            ResolvedNode(
                identity,
                id,
                status,
                hash,
                current,
                imports.mapIndexed { index, pin -> ReleasePin(pin, pin.entityId, "imports[$index]") },
                entity,
            )
        every { templateReleases.releaseTargetName(workspaceId, entity) } returns id
        if (status == ReleaseNodeStatus.DRAFT) {
            every { templateReleases.releaseTarget(workspaceId, id) } answers {
                entity to
                    TemplateVersionDetail(id, version, PipelineVersionStatus.DRAFT, graph.getValue(identity).bodyHash, Instant.EPOCH, actor)
            }
            every { templateReleases.flip(workspaceId, id, any(), actor) } answers {
                val consented = thirdArg<String>()
                phases += "commit:$id:${TransactionSynchronizationManager.isActualTransactionActive()}"
                if (graph.getValue(identity).bodyHash != consented) {
                    throw DatapipelinesException(code = "template.version.conflict", message = "stale $id")
                }
                log += "flip:$id@$consented"
                TemplateReleaseService.Released(
                    TemplateVersionDetail(id, version, PipelineVersionStatus.RELEASED, consented, Instant.EPOCH, actor),
                    mockk(),
                )
            }
        }
        return identity
    }

    /**
     * A parameter set, a visualization or a dashboard version in the graph pinning [pins] (#873 §B): its native release
     * seams answer for it from the graph — `prepareRelease` the DRAFT at exactly the asked version and hash or null,
     * `commitRelease` the compare-and-set flip or null, `staleRelease` the family's native `version.conflict`.
     */
    fun artifact(
        family: ReleaseFamily,
        name: String,
        pins: List<ReleaseNodeIdentity> = emptyList(),
        status: ReleaseNodeStatus = ReleaseNodeStatus.DRAFT,
        version: Int = 1,
        hash: String = "hash-$name",
    ): ReleaseNodeIdentity {
        val identity = ReleaseNodeIdentity(workspaceId, family, name, version)
        val id = UUID.nameUUIDFromBytes("${family.wire}:$name".toByteArray())
        graph[identity] =
            ResolvedNode(
                identity,
                name,
                status,
                hash,
                if (status == ReleaseNodeStatus.RELEASED) version else null,
                pins.mapIndexed { index, pin -> ReleasePin(pin, pin.entityId, "pins[$index]") },
                id,
            )
        entities[identity] = id
        when (family) {
            ReleaseFamily.PARAMETER_SET -> stubSet(identity, id)
            ReleaseFamily.VISUALIZATION -> stubVisualization(identity, id)
            ReleaseFamily.DASHBOARD -> stubDashboard(identity, id)
            else -> error("$family is not an artifact family of this fixture")
        }
        return identity
    }

    private val entities = HashMap<ReleaseNodeIdentity, UUID>()

    /** The entity id a root request names. */
    fun entityOf(identity: ReleaseNodeIdentity): UUID = entities[identity] ?: requireNotNull(graph.getValue(identity).entityUuid)

    /** A root release request for any registered root family, at the node's stored hash unless [expectedHash] says otherwise. */
    fun rootRequest(
        root: ReleaseNodeIdentity,
        plan: ReleasePlanRequest? = null,
        expectedHash: String = graph.getValue(root).bodyHash,
    ) = RootReleaseRequest(root.family, workspaceId, entityOf(root), expectedHash, actor, plan)

    /** The compare-and-set every native flip is: DRAFT at [identity]'s version and exactly [hash]. */
    private fun draftAt(
        identity: ReleaseNodeIdentity,
        version: Int,
        hash: String,
    ): Boolean = graph.getValue(identity).let { it.status == ReleaseNodeStatus.DRAFT && it.bodyHash == hash && identity.version == version }

    private fun flipped(
        identity: ReleaseNodeIdentity,
        hash: String,
    ) {
        graph[identity] = graph.getValue(identity).copy(status = ReleaseNodeStatus.RELEASED, currentVersion = identity.version)
        log += "flip:${identity.entityId}@$hash"
    }

    private fun inTransaction(): Boolean = TransactionSynchronizationManager.isActualTransactionActive()

    private fun stubSet(
        identity: ReleaseNodeIdentity,
        id: UUID,
    ) {
        val name = identity.entityId
        val record = ParameterSetRecord(id, workspaceId, name, name, "d", null, Instant.EPOCH, Instant.EPOCH, actor)

        fun detail(status: PipelineVersionStatus) =
            ParameterSetVersionDetail(id, identity.version, status, graph.getValue(identity).bodyHash, Instant.EPOCH, actor)
        every { sets.releaseTarget(workspaceId, id) } answers { record to detail(PipelineVersionStatus.DRAFT) }
        every { sets.prepareRelease(workspaceId, id, any(), any()) } answers {
            log += "prepare:$name"
            phases += "prepare:$name:${inTransaction()}"
            onPrepare[name]?.invoke()
            if (draftAt(identity, thirdArg(), arg(3))) ParameterSetVersion(record, detail(PipelineVersionStatus.DRAFT), mockk()) else null
        }
        every { sets.commitRelease(workspaceId, id, any(), actor) } answers {
            phases += "commit:$name:${inTransaction()}"
            val hash = thirdArg<String>()
            if (!draftAt(identity, identity.version, hash)) {
                null
            } else {
                flipped(identity, hash)
                ParameterSetVersion(record.copy(currentVersion = identity.version), detail(PipelineVersionStatus.RELEASED), mockk())
            }
        }
        every { sets.staleRelease(workspaceId, id) } answers
            { DatapipelinesException(code = "parameter.version.conflict", message = "stale $name") }
    }

    private fun stubVisualization(
        identity: ReleaseNodeIdentity,
        id: UUID,
    ) {
        val name = identity.entityId
        val record = ArtifactRecord(id, workspaceId, name, name, "d", null, Instant.EPOCH, Instant.EPOCH, actor)

        fun detail(status: PipelineVersionStatus) =
            ArtifactVersionDetail(id, identity.version, status, graph.getValue(identity).bodyHash, Instant.EPOCH, actor)
        every { visualizations.releaseTarget(workspaceId, id) } answers { record to detail(PipelineVersionStatus.DRAFT) }
        every { visualizations.prepareRelease(workspaceId, id, any(), any()) } answers {
            log += "prepare:$name"
            phases += "prepare:$name:${inTransaction()}"
            onPrepare[name]?.invoke()
            if (draftAt(identity, thirdArg(), arg(3))) ReleaseCandidate(id, name, identity.version, arg(3), mockk()) else null
        }
        every { visualizations.commitRelease(workspaceId, match { it.visualizationId == id }, any(), actor) } answers {
            phases += "commit:$name:${inTransaction()}"
            val hash = thirdArg<String>()
            if (!draftAt(identity, identity.version, hash)) {
                null
            } else {
                flipped(identity, hash)
                ArtifactVersion(record.copy(currentVersion = identity.version), detail(PipelineVersionStatus.RELEASED), mockk())
            }
        }
        every { visualizations.staleRelease(workspaceId, id) } answers {
            DatapipelinesException(code = "visualization.version.conflict", message = "stale $name")
        }
    }

    private fun stubDashboard(
        identity: ReleaseNodeIdentity,
        id: UUID,
    ) {
        val name = identity.entityId
        val record = ArtifactRecord(id, workspaceId, name, name, "d", null, Instant.EPOCH, Instant.EPOCH, actor)

        fun detail(status: PipelineVersionStatus) =
            ArtifactVersionDetail(id, identity.version, status, graph.getValue(identity).bodyHash, Instant.EPOCH, actor)
        every { dashboards.releaseTarget(workspaceId, id) } answers { record to detail(PipelineVersionStatus.DRAFT) }
        every { dashboards.prepareRelease(workspaceId, id, any(), any()) } answers {
            log += "prepare:$name"
            phases += "prepare:$name:${inTransaction()}"
            onPrepare[name]?.invoke()
            if (draftAt(identity, thirdArg(), arg(3))) ArtifactVersion(record, detail(PipelineVersionStatus.DRAFT), mockk()) else null
        }
        every { dashboards.commitRelease(workspaceId, match { it.record.id == id }, any(), actor) } answers {
            phases += "commit:$name:${inTransaction()}"
            // R5: the strict re-validation after every selected dependency flipped — a source still not RELEASED refuses.
            val unreleased =
                graph.getValue(identity).pins.map { graph[it.target] }.firstOrNull {
                    it != null && it.identity.family == ReleaseFamily.PIPELINE && it.status != ReleaseNodeStatus.RELEASED
                }
            if (unreleased != null) {
                throw DatapipelinesException(code = "dashboard.validation.source_not_released", message = "source ${unreleased.name}")
            }
            val hash = thirdArg<String>()
            if (!draftAt(identity, identity.version, hash)) {
                null
            } else {
                flipped(identity, hash)
                ArtifactVersion(record.copy(currentVersion = identity.version), detail(PipelineVersionStatus.RELEASED), mockk())
            }
        }
        every { dashboards.staleRelease(workspaceId, id) } answers {
            DatapipelinesException(code = "dashboard.version.conflict", message = "stale $name")
        }
    }

    /** An identity nothing resolves — a MISSING pin. */
    fun absent(id: String): ReleaseNodeIdentity = ReleaseNodeIdentity(workspaceId, ReleaseFamily.TEMPLATE, id, 1)

    /** Changes a stored node's body hash in place: an edit WITHOUT a version change. */
    fun edit(
        identity: ReleaseNodeIdentity,
        hash: String,
    ) {
        graph[identity] = graph.getValue(identity).copy(bodyHash = hash)
    }

    /** Changes the actual body consumed by the REAL native preparation as well as its stored hash. */
    fun body(
        identity: ReleaseNodeIdentity,
        body: String,
        hash: String,
    ) {
        bodies[identity.entityId] = body
        edit(identity, hash)
    }

    /** Simulates a purge/recreation: same pin key and facts, fresh resolved identity. */
    fun replaceEntity(identity: ReleaseNodeIdentity) {
        graph[identity] = graph.getValue(identity).copy(entityUuid = UUID.randomUUID())
    }

    /** Moves a node's entity current pointer: a release of another version of it. */
    fun moveCurrent(
        identity: ReleaseNodeIdentity,
        current: Int,
    ) {
        graph[identity] = graph.getValue(identity).copy(currentVersion = current)
    }

    fun recordOf(name: String): PipelineRecord = records.getValue(name)

    /** The preview a client would hold: the session plan's tree hash and node id per identity. */
    fun preview(root: ReleaseNodeIdentity): Preview {
        val closure = requireNotNull(ReleaseGraph(sources, bounds).walk(root))
        return Preview(TreeHash.of(closure, bounds.maxWireBytes), closure.nodes.associate { it.identity to it.nodeId })
    }

    data class Preview(
        val treeHash: String,
        val nodeIds: Map<ReleaseNodeIdentity, String>,
    ) {
        fun ids(vararg identities: ReleaseNodeIdentity): List<String> = identities.map { nodeIds.getValue(it) }
    }

    fun request(
        root: ReleaseNodeIdentity,
        plan: ReleasePlanRequest? = null,
        alias: Boolean = false,
        rootReason: String? = null,
    ) = PipelineReleaseRequest(
        workspaceId = workspaceId,
        pipelineId = recordOf(root.entityId).id,
        expectedHash = graph.getValue(root).bodyHash,
        actor = actor,
        overrideChecksReason = rootReason,
        releasePinnedTemplates = alias,
        plan = plan,
    )

    fun coordinator(
        authorization: ReleaseAuthorization = RecordingAuthorization(log, principal()),
        legacyAliasAdmitted: Boolean = true,
        planningTransactions: TransactionOperations = TransactionOperations.withoutTransaction(),
        deadline: ReleasePlanningDeadline = ReleasePlanningDeadline.NONE,
        prerequisites: PrerequisiteCheck = PrerequisiteCheck.NONE,
    ) = ReleaseCoordinator(
        releases = releases,
        pipelines = pipelines,
        // By default the planning read has no transaction of its own, so the fixture's transaction log stays the release
        // transaction's alone; ReleasePlanningTransactionTest passes the production template and pins its order.
        planning =
            ReleasePlanningWalk(
                ReleaseDependencyFamilies.registry(sources),
                bounds,
                planningTransactions,
                ReadIsolation.NONE,
                deadline,
            ),
        participants =
            listOf(
                PipelineReleaseParticipant(releases, pipelines),
                TemplateReleaseParticipant(templateReleases),
                ParameterSetReleaseParticipant(sets),
                VisualizationReleaseParticipant(visualizations),
                DashboardReleaseParticipant(dashboards),
            ),
        authorization = authorization,
        guard = guard,
        audit = audit,
        transactions = TransactionTemplate(transactions),
        bounds = bounds,
        prerequisites = prerequisites,
        releaseIds = { releaseId },
        legacyAliasAdmitted = legacyAliasAdmitted,
    )

    fun principal() = AuthenticatedPrincipal(actor, "author@datapipelines.test", "Author", AuthMethod.OIDC)

    /** The log without the permission rechecks, for order assertions about the writes alone. */
    fun writes(): List<String> = log.filterNot { it.startsWith("auth:") }

    private companion object {
        /** The detail key naming each family's row in [log] (`audit:<event>:<name>`), first present wins. */
        val NAME_KEYS = listOf("template_id", "pipeline_name", "parameter_set_name", "visualization_name", "dashboard_name", "name")
    }
}

/** An authorization double that logs every recheck and refuses the [refused] families with the matrix's code. */
internal class RecordingAuthorization(
    private val log: MutableList<String>,
    private val principal: AuthenticatedPrincipal,
    private val refused: Set<ReleaseFamily> = emptySet(),
) : ReleaseAuthorization {
    override fun principal(): AuthenticatedPrincipal = principal

    override fun requireRelease(family: ReleaseFamily) = requireRelease(principal, family)

    /** #786 — the explicit-principal recheck (a boot-time root release); logged under the same name. */
    override fun requireRelease(
        principal: AuthenticatedPrincipal,
        family: ReleaseFamily,
    ) {
        log += "auth:${family.wire}"
        if (family in refused) throw DatapipelinesException(code = "auth.role_required", message = "refused ${family.wire}")
    }
}
