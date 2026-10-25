package co.datapipelines.web.release

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.release.DependencySource
import co.datapipelines.pipeline.release.PipelineReleaseRequest
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
import co.datapipelines.web.templates.TemplateReleaseService
import io.mockk.every
import io.mockk.mockk
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
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
    private val source = DependencySource { identity -> graph[identity] }
    val sources: Map<ReleaseFamily, DependencySource> = mapOf(ReleaseFamily.PIPELINE to source, ReleaseFamily.TEMPLATE to source)

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
            log += "audit:${row.event}:${row.details["template_id"] ?: row.details["pipeline_name"]}"
        }

    private val transactions =
        object : PlatformTransactionManager {
            override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
                log += "begin"
                return SimpleTransactionStatus()
            }

            override fun commit(status: TransactionStatus) {
                log += "commit"
            }

            override fun rollback(status: TransactionStatus) {
                log += "rollback"
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
                PipelineRepository.Released(record, draft.copy(status = PipelineVersionStatus.RELEASED))
            }
        }
        return identity
    }

    /** A template version in the graph — a leaf in S1 (its imports are S2's arm). */
    fun template(
        id: String,
        status: ReleaseNodeStatus = ReleaseNodeStatus.DRAFT,
        version: Int = 1,
        hash: String = "hash-$id",
        current: Int? = if (status == ReleaseNodeStatus.RELEASED) version else null,
    ): ReleaseNodeIdentity {
        val identity = ReleaseNodeIdentity(workspaceId, ReleaseFamily.TEMPLATE, id, version)
        graph[identity] = ResolvedNode(identity, id, status, hash, current, emptyList(), UUID.nameUUIDFromBytes(id.toByteArray()))
        return identity
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
    ) = ReleaseCoordinator(
        releases = releases,
        pipelines = pipelines,
        sources = sources,
        participants = listOf(PipelineReleaseParticipant(releases, pipelines), TemplateReleaseParticipant(templateReleases)),
        authorization = authorization,
        guard = guard,
        audit = audit,
        transactions = TransactionTemplate(transactions),
        bounds = bounds,
        releaseIds = { releaseId },
        legacyAliasAdmitted = legacyAliasAdmitted,
    )

    fun principal() = AuthenticatedPrincipal(actor, "author@datapipelines.test", "Author", AuthMethod.OIDC)

    /** The log without the permission rechecks, for order assertions about the writes alone. */
    fun writes(): List<String> = log.filterNot { it.startsWith("auth:") }
}

/** An authorization double that logs every recheck and refuses the [refused] families with the matrix's code. */
internal class RecordingAuthorization(
    private val log: MutableList<String>,
    private val principal: AuthenticatedPrincipal,
    private val refused: Set<ReleaseFamily> = emptySet(),
) : ReleaseAuthorization {
    override fun principal(): AuthenticatedPrincipal = principal

    override fun requireRelease(family: ReleaseFamily) {
        log += "auth:${family.wire}"
        if (family in refused) throw DatapipelinesException(code = "auth.role_required", message = "refused ${family.wire}")
    }
}
