package co.datapipelines.application.dependencies.restore

import co.datapipelines.application.dependencies.ReleaseDependencyFamilies
import co.datapipelines.application.dependencies.ReleasePlanningDeadline
import co.datapipelines.application.dependencies.prerequisites.DatasourceEdges
import co.datapipelines.dependencies.Deadline
import co.datapipelines.dependencies.DependencyProvider
import co.datapipelines.dependencies.FamilyDescriptor
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.dependencies.Reference
import co.datapipelines.dependencies.Relationship
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.dependencies.SelectorKind
import co.datapipelines.pipeline.release.DependencySource
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleasePin
import co.datapipelines.pipeline.release.ResolvedNode
import co.datapipelines.pipeline.release.RestoreReach
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * #873 §D.0 — the restore recheck port over the family registry: the DISCARDED root's STORED body is the walk's seed (its
 * own provider reads it; the walk itself never expands a DISCARDED node), its references are the roots of one walk, and
 * every not-RELEASED node below is one [RestoreReach] with its full path and the nodes it is reached via. G1's rules hold:
 * a DISCARDED or MISSING descendant is terminal; a cycle and an expired deadline are the ROOT family's refusals.
 */
class WalkingRestoreDependencyGuardTest {
    private val workspace = UUID.randomUUID()
    private val stored = HashMap<ReleaseNodeIdentity, ResolvedNode>()
    private val reads = mutableListOf<ReleaseNodeIdentity>()
    private val source =
        DependencySource { identity ->
            reads += identity
            stored[identity]
        }

    private fun id(
        family: ReleaseFamily,
        key: String,
    ) = ReleaseNodeIdentity(workspace, family, key, 1)

    /** Stores [identity] at [status] pinning [pins] (target to field path), in pin order. */
    private fun node(
        identity: ReleaseNodeIdentity,
        status: ReleaseNodeStatus,
        vararg pins: Pair<ReleaseNodeIdentity, String>,
    ): ReleaseNodeIdentity {
        stored[identity] =
            ResolvedNode(
                identity,
                identity.entityId,
                status,
                "hash-${identity.entityId}",
                1.takeIf { status == ReleaseNodeStatus.RELEASED },
                pins.map { (target, path) -> ReleasePin(target, target.entityId, path) },
                UUID.nameUUIDFromBytes(identity.entityId.toByteArray()),
            )
        return identity
    }

    private fun guard(deadline: ReleasePlanningDeadline = ReleasePlanningDeadline.NONE) =
        WalkingRestoreDependencyGuard(
            registry = ReleaseDependencyFamilies.registry(ReleaseFamily.entries.associateWith { source }),
            bounds = ReleaseBounds(),
            speculativeReads = ReadIsolation.NONE,
            deadline = deadline,
        )

    private fun summary(reaches: List<RestoreReach>) =
        reaches.map { "${it.identity.entityId}:${it.status}:${it.path}:${it.via.map(ReleaseNodeIdentity::entityId)}" }

    /** A DISCARDED dashboard over a RELEASED visualization whose pinned template imports [leafStatus] `lib`. */
    private fun board(leafStatus: ReleaseNodeStatus?): ReleaseNodeIdentity {
        val lib = id(ReleaseFamily.TEMPLATE, "test/lib.sql")
        if (leafStatus != null) node(lib, leafStatus)
        val shape = node(id(ReleaseFamily.TEMPLATE, "test/shape.sql"), ReleaseNodeStatus.RELEASED, lib to "imports[0]")
        val chart = node(id(ReleaseFamily.VISUALIZATION, "test/chart"), ReleaseNodeStatus.RELEASED, shape to "transform.template")
        return node(id(ReleaseFamily.DASHBOARD, "test/board"), ReleaseNodeStatus.DISCARDED, chart to "visualizations[0].visualization")
    }

    @Test
    fun `a fully released closure below the restored body reaches nothing - and every level of it was read`() {
        val root = board(ReleaseNodeStatus.RELEASED)

        guard().recheck(root) shouldBe emptyList()
        reads.map { it.entityId } shouldContainExactly listOf("test/board", "test/chart", "test/shape.sql", "test/lib.sql")
    }

    @Test
    fun `a DRAFT beneath two RELEASED intermediates is one transitive reach with its full path and via`() {
        val reaches = guard().recheck(board(ReleaseNodeStatus.DRAFT))

        summary(reaches) shouldContainExactly
            listOf(
                "test/lib.sql:DRAFT:visualizations[0].visualization.transform.template.imports[0]:[test/chart, test/shape.sql]",
            )
        reaches.single().transitive shouldBe true
        reaches.single().directPath shouldBe "visualizations[0].visualization"
        reaches.single().viaDetails().first() shouldBe mapOf("family" to "visualization", "name" to "test/chart", "version" to 1)
    }

    @Test
    fun `a missing descendant is MISSING, a DISCARDED one terminal - its own pins are never walked`() {
        summary(guard().recheck(board(null))) shouldContainExactly
            listOf("test/lib.sql:MISSING:visualizations[0].visualization.transform.template.imports[0]:[test/chart, test/shape.sql]")

        stored.clear()
        reads.clear()
        val deeper = node(id(ReleaseFamily.TEMPLATE, "test/deeper.sql"), ReleaseNodeStatus.DRAFT)
        val lib = node(id(ReleaseFamily.TEMPLATE, "test/lib.sql"), ReleaseNodeStatus.DISCARDED, deeper to "imports[0]")
        val root = node(id(ReleaseFamily.TEMPLATE, "test/root.sql"), ReleaseNodeStatus.DISCARDED, lib to "imports[0]")

        summary(guard().recheck(root)) shouldContainExactly listOf("test/lib.sql:DISCARDED:imports[0]:[]")
        reads.map { it.entityId } shouldContainExactly listOf("test/root.sql", "test/lib.sql")
    }

    @Test
    fun `the restored body's own DRAFT pin is a direct reach, and every pin is walked in body order`() {
        val draft = node(id(ReleaseFamily.PIPELINE, "test/source"), ReleaseNodeStatus.DRAFT)
        val released = node(id(ReleaseFamily.VISUALIZATION, "test/ok"), ReleaseNodeStatus.RELEASED)
        val set = node(id(ReleaseFamily.PARAMETER_SET, "test/set"), ReleaseNodeStatus.DRAFT)
        val root =
            node(
                id(ReleaseFamily.DASHBOARD, "test/board"),
                ReleaseNodeStatus.DISCARDED,
                set to "parameter_set",
                draft to "sources[0].pipeline",
                released to "visualizations[0].visualization",
            )

        val reaches = guard().recheck(root)

        summary(reaches) shouldContainExactly listOf("test/set:DRAFT:parameter_set:[]", "test/source:DRAFT:sources[0].pipeline:[]")
        reaches.none { it.transitive } shouldBe true
    }

    @Test
    fun `the pins entry walks the caller's extracted pins - a family whose provider emits nothing for a DISCARDED version`() {
        val lib = node(id(ReleaseFamily.TEMPLATE, "test/lib.sql"), ReleaseNodeStatus.DRAFT)
        val template = node(id(ReleaseFamily.TEMPLATE, "test/query.sql"), ReleaseNodeStatus.RELEASED, lib to "imports[0]")
        // The stored pipeline answers no pins (PipelineDependencySource's DISCARDED rule): the seed is the caller's.
        val root = node(id(ReleaseFamily.PIPELINE, "test/pipeline"), ReleaseNodeStatus.DISCARDED)

        guard().recheck(root) shouldBe emptyList()
        summary(guard().recheck(root, listOf(ReleasePin(template, template.entityId, "nodes[0].template")))) shouldContainExactly
            listOf("test/lib.sql:DRAFT:nodes[0].template.imports[0]:[test/query.sql]")
    }

    /**
     * #873 §F.3 f: the restore recheck walks as the SYSTEM authority, under which G3's datasource provider reads the
     * whole deployment — so the guard walks release references only. A RELEASED pipeline below the restored body that
     * names a datasource reaches nothing, and the registry's datasource provider is never called.
     */
    @Test
    fun `a datasource below the restored body is never resolved - the recheck walks release references only`() {
        val datasourceReads = mutableListOf<ObjectRequest>()
        val pipeline = node(id(ReleaseFamily.PIPELINE, "test/report"), ReleaseNodeStatus.RELEASED)
        val root = node(id(ReleaseFamily.DASHBOARD, "test/board"), ReleaseNodeStatus.DISCARDED, pipeline to "sources[0].pipeline")
        val release = ReleaseDependencyFamilies.registry(ReleaseFamily.entries.associateWith { source })
        val warehouse = DatasourceEdges.request(workspace, "warehouse")
        val naming =
            ReleaseFamily.entries.map { family ->
                val descriptor = checkNotNull(release[ReleaseDependencyFamilies.keyOf(family)])
                if (family == ReleaseFamily.PIPELINE) withDatasourceReference(descriptor, warehouse) else descriptor
            }
        val datasources =
            FamilyDescriptor(
                key = FamilyKey.DATASOURCE,
                rootSelectors = setOf(SelectorKind.ENTITY),
                capabilities = FamilyKey.PRODUCTION.getValue(FamilyKey.DATASOURCE),
                extractorSchemaVersion = 1,
                provider =
                    object : DependencyProvider<Unit> {
                        override fun resolveMany(
                            requests: List<ObjectRequest>,
                            context: ResolveContext,
                        ): Map<ObjectRequest, Outcome<Unit>> {
                            datasourceReads += requests
                            return requests.associateWith { Outcome.Missing }
                        }

                        override fun outgoing(resolved: Outcome.Resolved<Unit>): List<Reference> = emptyList()
                    },
                safeSummary = FamilyDescriptor.NO_SAFE_SUMMARY,
            )
        val guard =
            WalkingRestoreDependencyGuard(
                registry = FamilyRegistry(naming + datasources),
                bounds = ReleaseBounds(),
                speculativeReads = ReadIsolation.NONE,
                deadline = ReleasePlanningDeadline.NONE,
            )

        guard.recheck(root) shouldBe emptyList()
        datasourceReads.shouldBeEmpty()
        reads.map { it.entityId } shouldContainExactly listOf("test/board", "test/report")
    }

    @Suppress("UNCHECKED_CAST") // the adapted release descriptor's provider reads ResolvedNode bodies
    private fun withDatasourceReference(
        descriptor: FamilyDescriptor<*>,
        datasource: ObjectRequest,
    ): FamilyDescriptor<*> {
        val inner = descriptor.provider as DependencyProvider<Any>
        return FamilyDescriptor(
            key = descriptor.key,
            rootSelectors = descriptor.rootSelectors,
            capabilities = descriptor.capabilities,
            extractorSchemaVersion = descriptor.extractorSchemaVersion,
            provider =
                object : DependencyProvider<Any> {
                    override fun resolveMany(
                        requests: List<ObjectRequest>,
                        context: ResolveContext,
                    ) = inner.resolveMany(requests, context)

                    override fun outgoing(resolved: Outcome.Resolved<Any>): List<Reference> =
                        inner.outgoing(resolved) +
                            Reference(datasource, Relationship.DATASOURCE, "nodes[0].source", spelledName = "warehouse")
                },
            safeSummary = descriptor.safeSummary,
        )
    }

    @Test
    fun `an absent root reaches nothing`() {
        guard().recheck(id(ReleaseFamily.VISUALIZATION, "test/gone")) shouldBe emptyList()
    }

    @Test
    fun `a planted import cycle below the body is the ROOT family's dependency_cycle`() {
        val a = id(ReleaseFamily.TEMPLATE, "test/a.sql")
        val b = id(ReleaseFamily.TEMPLATE, "test/b.sql")
        node(a, ReleaseNodeStatus.RELEASED, b to "imports[0]")
        node(b, ReleaseNodeStatus.RELEASED, a to "imports[0]")
        val chart = node(id(ReleaseFamily.VISUALIZATION, "test/chart"), ReleaseNodeStatus.DISCARDED, a to "transform.template")

        shouldThrow<DatapipelinesException> { guard().recheck(chart) }.code shouldBe "visualization.release.dependency_cycle"
    }

    @Test
    fun `an expired deadline is the ROOT family's metadata_unavailable`() {
        val expired = ReleasePlanningDeadline { Deadline.after(Duration.ZERO, Clock.systemUTC()) }

        shouldThrow<DatapipelinesException> { guard(expired).recheck(board(ReleaseNodeStatus.DRAFT)) }.code shouldBe
            "dashboard.release.metadata_unavailable"
    }
}
