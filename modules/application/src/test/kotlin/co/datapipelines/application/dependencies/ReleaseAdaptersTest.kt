package co.datapipelines.application.dependencies

import co.datapipelines.dependencies.Authority
import co.datapipelines.dependencies.Deadline
import co.datapipelines.dependencies.DeadlineExceeded
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.GraphLimits
import co.datapipelines.dependencies.LookupScope
import co.datapipelines.dependencies.MetadataSnapshot
import co.datapipelines.dependencies.ObjectAddress
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.Purpose
import co.datapipelines.dependencies.Relationship
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.dependencies.Selector
import co.datapipelines.dependencies.SelectorKind
import co.datapipelines.dependencies.conformance.ProviderConformance
import co.datapipelines.pipeline.release.DependencySource
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleasePin
import co.datapipelines.pipeline.release.ResolvedNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The release adapters' own rules (#868 D.1–D.3): the provider, the limits mapping and the planning walk's transaction. */
class ReleaseAdaptersTest {
    private val g = GoldenGraph()
    private val template = g.node(g.id("query", 3), ReleaseNodeStatus.RELEASED)
    private val child = g.node(g.id("child", 2, ReleaseFamily.PIPELINE), ReleaseNodeStatus.DRAFT)
    private val root =
        g.node(
            g.id("orders", 1, ReleaseFamily.PIPELINE),
            ReleaseNodeStatus.DRAFT,
            template to "nodes[0].template",
            child to "nodes[1].pipeline",
        )
    private val context =
        ResolveContext(g.workspaceId, MetadataSnapshot.READ_COMMITTED, Purpose.RELEASE_PLANNING, Authority.System, Deadline.none())

    @Test
    fun `a release family is an exact-version family with the artifact capability pair and section 6 pins`() {
        val descriptor = ReleaseDependencyFamilies.descriptor(ReleaseFamily.PIPELINE, g.source())
        descriptor.key shouldBe FamilyKey.PIPELINE
        descriptor.rootSelectors shouldBe setOf(SelectorKind.EXACT_VERSION)
        descriptor.capabilities shouldBe FamilyKey.PRODUCTION.getValue(FamilyKey.PIPELINE)
        val outcome =
            descriptor.provider
                .resolveMany(listOf(ReleaseDependencyFamilies.request(root)), context)
                .values
                .single()
        val resolved = outcome.shouldBeInstanceOf<Outcome.Resolved<ResolvedNode>>()
        resolved.identity.stableEntityId shouldBe resolved.body.entityUuid.toString()
        resolved.identity.artifactVersion shouldBe 1
        descriptor.provider.outgoing(resolved).map { it.relationship to it.sourcePath } shouldContainExactly
            listOf(Relationship.TEMPLATE to "nodes[0].template", Relationship.CHILD_PIPELINE to "nodes[1].pipeline")
        ReleaseDependencyFamilies.registry(ReleaseFamily.entries.associateWith { g.source() }).keys.size shouldBe ReleaseFamily.entries.size
    }

    @Test
    fun `the pipeline adapter passes the provider conformance harness`() {
        val descriptor = ReleaseDependencyFamilies.descriptor(ReleaseFamily.PIPELINE, g.source())
        val absent = ReleaseDependencyFamilies.request(g.id("absent", 1, ReleaseFamily.PIPELINE))
        val fixture =
            ProviderConformance.Fixture(
                descriptor = descriptor,
                context = context,
                present = listOf(ReleaseDependencyFamilies.request(root), ReleaseDependencyFamilies.request(child)),
                absent = listOf(absent),
                references =
                    ProviderConformance.References.Fields(
                        mapOf(ReleaseDependencyFamilies.request(root) to setOf("nodes[0].template", "nodes[1].pipeline")),
                    ),
            )
        ProviderConformance.check(fixture).shouldBeEmpty()
    }

    @Test
    fun `a request the source cannot answer is unsupported, and a source that lies or pins outside section 6 is a defect`() {
        val provider = ReleaseDependencyFamilies.descriptor(ReleaseFamily.PIPELINE, g.source()).provider
        val current = ObjectRequest(ObjectAddress(FamilyKey.PIPELINE, LookupScope(g.workspaceId), "orders"), Selector.Current)
        val other = ReleaseDependencyFamilies.request(template)
        val unknown = ObjectRequest(ObjectAddress(FamilyKey.SCHEDULE, LookupScope(g.workspaceId), "x"), Selector.Entity)
        provider.resolveMany(listOf(current, other, unknown), context).values.toSet() shouldBe setOf(Outcome.Unsupported)

        val liar = DependencySource { g.source().resolve(child) }
        shouldThrow<IllegalStateException> {
            ReleaseDependencyFamilies
                .descriptor(
                    ReleaseFamily.PIPELINE,
                    liar,
                ).provider
                .resolveMany(listOf(ReleaseDependencyFamilies.request(root)), context)
        }.message shouldContain "answered"

        val templatePinsPipeline =
            DependencySource { identity ->
                ResolvedNode(
                    identity,
                    "acme/t",
                    ReleaseNodeStatus.DRAFT,
                    "h",
                    null,
                    listOf(ReleasePin(root, "acme/orders", "x")),
                    UUID.randomUUID(),
                )
            }
        val descriptor = ReleaseDependencyFamilies.descriptor(ReleaseFamily.TEMPLATE, templatePinsPipeline)
        val resolved =
            descriptor.provider
                .resolveMany(listOf(ReleaseDependencyFamilies.request(template)), context)
                .values
                .single()
        shouldThrow<IllegalStateException> {
            descriptor.provider.outgoing(resolved as Outcome.Resolved<ResolvedNode>)
        }.message shouldContain "not a §6 row"
    }

    @Test
    fun `a source failure propagates through the planning walk, never as a missing node`() {
        val failing = DependencySource { error("metadata store unavailable") }
        val walk = walk(mapOf(ReleaseFamily.PIPELINE to failing))
        shouldThrow<IllegalStateException> { walk.plan(root, ACTOR) }.message shouldBe "metadata store unavailable"
    }

    @Test
    fun `the release bounds become the walk's limits - versions are nodes, blockers are diagnostics`() {
        val limits =
            ReleaseGraphLimits.of(
                ReleaseBounds(maxDepth = 3, maxVersions = 4, maxEdges = 5, maxBlockers = 6, maxWireBytes = 7, lockWaitSeconds = 8),
            )
        listOf(limits.depth, limits.nodes, limits.edges, limits.diagnostics, limits.wireBytes, limits.lockWaitSeconds) shouldContainExactly
            listOf(3, 4, 5, 6, 7, 8)
        limits.resolveBatchSize shouldBe GraphLimits.DEFAULT_RESOLVE_BATCH_SIZE
        limits.metadataTimeout shouldBe GraphLimits.DEFAULT_METADATA_TIMEOUT
        limits.statementTimeout shouldBe GraphLimits.DEFAULT_STATEMENT_TIMEOUT
    }

    @Test
    fun `the planning transaction is read-only REPEATABLE READ REQUIRES_NEW and the plan walk runs inside it`() {
        val template = ReleasePlanningWalk.planningTransaction(RecordingManager())
        template.isolationLevel shouldBe TransactionDefinition.ISOLATION_REPEATABLE_READ
        template.isReadOnly shouldBe true
        template.propagationBehavior shouldBe TransactionDefinition.PROPAGATION_REQUIRES_NEW
        template.name shouldBe ReleasePlanningWalk.PLANNING_TRANSACTION_NAME

        val log = ArrayList<String>()
        val recording =
            object : TransactionOperations {
                override fun <T> execute(action: TransactionCallback<T>): T? {
                    log += "begin"
                    return action.doInTransaction(SimpleTransactionStatus()).also { log += "end" }
                }
            }
        val source = DependencySource { identity -> g.source().resolve(identity).also { log += "read:${identity.entityId}" } }
        val walk =
            ReleasePlanningWalk(
                ReleaseDependencyFamilies.registry(
                    mapOf(
                        ReleaseFamily.PIPELINE to source,
                        ReleaseFamily.TEMPLATE to source,
                    ),
                ),
                ReleaseBounds(),
                recording,
                ReleasePlanningDeadline.NONE,
            )
        walk.plan(root, ACTOR)?.nodes?.size shouldBe 3
        log.first() shouldBe "begin"
        log.last() shouldBe "end"
        log.clear()
        walk.walkInCurrentTransaction(root, ACTOR)?.nodes?.size shouldBe 3
        log.none { it == "begin" } shouldBe true
    }

    @Test
    fun `the planning deadline is read for every walk, and an expired one refuses before the first read`() {
        val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
        var opened = 0
        val expired = ReleasePlanningDeadline { Deadline.after(Duration.ZERO, clock).also { opened++ } }
        val reads = ArrayList<String>()
        val source = DependencySource { identity -> g.source().resolve(identity).also { reads += identity.entityId } }
        val walk =
            ReleasePlanningWalk(
                ReleaseDependencyFamilies.registry(mapOf(ReleaseFamily.PIPELINE to source)),
                ReleaseBounds(),
                TransactionOperations.withoutTransaction(),
                expired,
                clock,
            )
        shouldThrow<DeadlineExceeded> { walk.plan(root, ACTOR) }.family shouldBe FamilyKey.PIPELINE
        shouldThrow<DeadlineExceeded> { walk.walkInCurrentTransaction(root, ACTOR) }
        opened shouldBe 2
        reads.shouldBeEmpty()
    }

    private fun walk(sources: Map<ReleaseFamily, DependencySource>) =
        ReleasePlanningWalk(
            ReleaseDependencyFamilies.registry(sources),
            ReleaseBounds(),
            TransactionOperations.withoutTransaction(),
            ReleasePlanningDeadline.NONE,
        )

    private class RecordingManager : org.springframework.transaction.PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()

        override fun commit(status: TransactionStatus) = Unit

        override fun rollback(status: TransactionStatus) = Unit
    }

    private companion object {
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-00000000a868")
    }
}
