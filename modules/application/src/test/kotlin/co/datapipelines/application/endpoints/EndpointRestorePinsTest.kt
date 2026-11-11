package co.datapipelines.application.endpoints

import co.datapipelines.application.dependencies.GoldenGraph
import co.datapipelines.application.dependencies.ReleaseDependencyFamilies
import co.datapipelines.application.dependencies.ReleasePlanningDeadline
import co.datapipelines.application.dependencies.ReleasePlanningWalk
import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleaseRefusal
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionOperations
import java.time.Instant
import java.util.UUID

/**
 * #786 C.4 — an API restore's recheck of its outbound pin (record §24, §25.4 "For API restore"). A restore lands the
 * version RELEASED without a release's consent, so the pinned pipeline version must still be live and released, and so
 * must everything it pins:
 *
 * - the direct pin missing or DISCARDED → the native 400 `endpoint.pipeline_version.not_found` naming `pipeline_version`;
 * - the direct pin DRAFT → 409 `endpoint.release.dependency_not_released`, one blocker with `reason: not_released`;
 * - below the pin, through G1's REAL planner walk over an in-memory graph: a MISSING/DISCARDED node → the same 400 at
 *   its prefixed field path, a DRAFT node → the same 409 at its prefixed path, a cycle → the API family's code.
 *
 * Metadata reads only: the repository is a strict mock and every case ends by confirming the recheck called nothing on
 * it beyond the two reads — a repin, a release or any other write would be an unanswered call and go red.
 */
class EndpointRestorePinsTest {
    private val graph = GoldenGraph()
    private val workspaceId = graph.workspaceId
    private val pipelines = mockk<PipelineRepository>()
    private val restored =
        PublishedEndpointVersion(
            ENDPOINT_ID,
            3,
            PipelineVersionStatus.DISCARDED,
            EndpointVersionBody(PIPELINE, 2, 30, ""),
            "h3",
            Instant.EPOCH,
            ACTOR,
            null,
            null,
        )
    private val pinned = ReleaseNodeIdentity(workspaceId, ReleaseFamily.PIPELINE, PIPELINE, 2)

    @Test
    fun `the pinned pipeline gone is the native 400 at pipeline_version`() {
        every { pipelines.findByNameAnyStatus(workspaceId, PIPELINE) } returns null
        dangling(withWalk = true) shouldBe "pipeline_version"
        verify { pipelines.findByNameAnyStatus(workspaceId, PIPELINE) }
        confirmVerified(pipelines)
        withClue("no walk when the direct pin already refuses") { graph.reads.shouldBeEmpty() }
    }

    @Test
    fun `the pinned version purged or DISCARDED is the native 400 at pipeline_version`() {
        stubPipeline(status = null)
        dangling(withWalk = true) shouldBe "pipeline_version"
        stubPipeline(PipelineVersionStatus.DISCARDED)
        dangling(withWalk = true) shouldBe "pipeline_version"
        readsOnly()
        graph.reads.shouldBeEmpty()
    }

    @Test
    fun `the pinned version present but DRAFT is 409 dependency_not_released with reason not_released`() {
        stubPipeline(PipelineVersionStatus.DRAFT)
        val refused = shouldThrow<DatapipelinesException> { restorePins(withWalk = true).recheck(workspaceId, PATH, restored, ACTOR) }
        refused.code shouldBe "endpoint.release.dependency_not_released"
        val root = refused.details["root"] as Map<*, *>
        root["family"] shouldBe "api_endpoint"
        root["name"] shouldBe PATH
        root["version"] shouldBe 3
        val blocker = (refused.details["blockers"] as List<*>).single() as Map<*, *>
        blocker["family"] shouldBe "pipeline"
        blocker["version"] shouldBe 2
        blocker["status"] shouldBe "DRAFT"
        blocker["reason"] shouldBe "not_released"
        blocker["paths"] shouldBe listOf("pipeline_version")
        readsOnly()
        withClue("no walk when the direct pin already refuses") { graph.reads.shouldBeEmpty() }
    }

    @Test
    fun `a RELEASED pin passes - with no walk wired, and through the walk when everything below is released`() {
        stubPipeline(PipelineVersionStatus.RELEASED)
        restorePins(withWalk = false).recheck(workspaceId, PATH, restored, ACTOR)
        val template = graph.node(graph.id("common", 4), ReleaseNodeStatus.RELEASED)
        graph.node(pinned, ReleaseNodeStatus.RELEASED, template to "nodes[0].template")
        restorePins(withWalk = true).recheck(workspaceId, PATH, restored, ACTOR)
        withClue("non-vacuity: the walk really read the pinned version and the node below it") {
            graph.reads.keys shouldBe setOf(pinned, template)
        }
        readsOnly(times = 2)
    }

    @Test
    fun `a DRAFT below the pin is 409 at its prefixed path`() {
        stubPipeline(PipelineVersionStatus.RELEASED)
        val template = graph.node(graph.id("common", 4), ReleaseNodeStatus.DRAFT)
        graph.node(pinned, ReleaseNodeStatus.RELEASED, template to "nodes[0].template")
        val refused = shouldThrow<DatapipelinesException> { restorePins(withWalk = true).recheck(workspaceId, PATH, restored, ACTOR) }
        refused.code shouldBe "endpoint.release.dependency_not_released"
        val blocker = (refused.details["blockers"] as List<*>).single() as Map<*, *>
        blocker["family"] shouldBe "template"
        blocker["status"] shouldBe "DRAFT"
        blocker["reason"] shouldBe "not_released"
        blocker["paths"] shouldBe listOf("pipeline_version.nodes[0].template")
        readsOnly()
    }

    @Test
    fun `a MISSING or DISCARDED node below the pin is the native 400 at its prefixed path`() {
        stubPipeline(PipelineVersionStatus.RELEASED)
        val gone = graph.id("purged", 1)
        val discarded = graph.node(graph.id("retired", 2), ReleaseNodeStatus.DISCARDED)
        graph.node(pinned, ReleaseNodeStatus.RELEASED, gone to "nodes[0].template")
        dangling(withWalk = true) shouldBe "pipeline_version.nodes[0].template"
        graph.node(pinned, ReleaseNodeStatus.RELEASED, discarded to "nodes[1].template")
        dangling(withWalk = true) shouldBe "pipeline_version.nodes[1].template"
        readsOnly(times = 2)
    }

    @Test
    fun `a cycle below the pin answers in the API family's code`() {
        stubPipeline(PipelineVersionStatus.RELEASED)
        val child = graph.id("child", 1, ReleaseFamily.PIPELINE)
        graph.node(child, ReleaseNodeStatus.RELEASED, pinned to "nodes[0].pipeline")
        graph.node(pinned, ReleaseNodeStatus.RELEASED, child to "nodes[0].pipeline")
        val refused = shouldThrow<DatapipelinesException> { restorePins(withWalk = true).recheck(workspaceId, PATH, restored, ACTOR) }
        refused.code shouldBe ReleaseFamily.ENDPOINT.code(ReleaseRefusal.DEPENDENCY_CYCLE)
        readsOnly()
    }

    // ------------------------------------------------------------------------- helpers

    private fun restorePins(withWalk: Boolean) =
        EndpointRestorePins(
            pipelines,
            if (withWalk) {
                ReleasePlanningWalk(
                    ReleaseDependencyFamilies.registry(graph.allFamilies()),
                    ReleaseBounds(),
                    TransactionOperations.withoutTransaction(),
                    ReadIsolation.NONE,
                    ReleasePlanningDeadline.NONE,
                )
            } else {
                null
            },
            maxBlockers = 20,
        )

    /** The recheck refuses with the native 400; returns the field path it names. */
    private fun dangling(withWalk: Boolean): Any? {
        val refused = shouldThrow<DatapipelinesException> { restorePins(withWalk).recheck(workspaceId, PATH, restored, ACTOR) }
        refused.code shouldBe PipelineErrorCodes.Endpoint.PIPELINE_VERSION_NOT_FOUND
        refused.details["pipeline"] shouldBe PIPELINE
        refused.details["pipeline_version"] shouldBe 2
        return refused.details["path"]
    }

    /** [status] null = the version row does not exist (purged). */
    private fun stubPipeline(status: PipelineVersionStatus?) {
        every { pipelines.findByNameAnyStatus(workspaceId, PIPELINE) } returns
            PipelineRecord(PIPELINE_ID, PIPELINE, "Revenue", "", ACTOR, 1, Instant.EPOCH, Instant.EPOCH)
        every { pipelines.findVersionDetail(workspaceId, PIPELINE_ID, 2) } returns
            status?.let {
                PipelineVersionDetail(
                    pipelineId = PIPELINE_ID,
                    version = 2,
                    status = it,
                    bodyHash = "hash",
                    createdAt = Instant.EPOCH,
                    createdBy = ACTOR,
                    releasedAt = null,
                    releasedBy = null,
                    updatedBy = null,
                    updatedAt = Instant.EPOCH,
                )
            }
    }

    /** The recheck read the pin (name, then version) and touched nothing else on the repository. */
    private fun readsOnly(times: Int = 1) {
        verify(atLeast = times) {
            pipelines.findByNameAnyStatus(workspaceId, PIPELINE)
            pipelines.findVersionDetail(workspaceId, PIPELINE_ID, 2)
        }
        confirmVerified(pipelines)
    }

    private companion object {
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-00000000a786")
        val ENDPOINT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000e786")
        val PIPELINE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000b786")
        const val PIPELINE = "finance/revenue"
        const val PATH = "/finance/v1/revenue"
    }
}
