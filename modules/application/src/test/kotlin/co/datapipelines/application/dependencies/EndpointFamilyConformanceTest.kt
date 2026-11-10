package co.datapipelines.application.dependencies

import co.datapipelines.application.endpoints.EndpointVersionBody
import co.datapipelines.application.endpoints.PublishedEndpointVersion
import co.datapipelines.application.endpoints.PublishedEndpointVersionRepository
import co.datapipelines.dependencies.Authority
import co.datapipelines.dependencies.Deadline
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.MetadataSnapshot
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.Purpose
import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.dependencies.Relationship
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.dependencies.SelectorKind
import co.datapipelines.dependencies.conformance.ProviderConformance
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ResolvedNode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * #786 C.1 — the API family through G1's registry (record §6, §7.1, §11.1): an exact-version family keyed by the API's
 * immutable path, with the artifact capability pair, whose one reference-bearing field is the exact pipeline pin at
 * `pipeline_version` (relationship `PIPELINE_PIN`). G1's [ProviderConformance] harness passes over a fixture with a
 * present DRAFT, a present RELEASED, a DISCARDED version (no outgoing pin: a discarded body pins nothing the planner
 * follows) and an absent version.
 */
class EndpointFamilyConformanceTest {
    private val workspaceId = UUID.randomUUID()
    private val endpointId = UUID.randomUUID()
    private val path = "/finance/v1/revenue"
    private val versions = mockk<PublishedEndpointVersionRepository>()
    private val descriptor = ReleaseDependencyFamilies.descriptor(ReleaseFamily.ENDPOINT, EndpointDependencySource(versions))
    private val context =
        ResolveContext(workspaceId, MetadataSnapshot.READ_COMMITTED, Purpose.RELEASE_PLANNING, Authority.System, Deadline.none(), ReadIsolation.NONE)

    private fun identity(version: Int) = ReleaseNodeIdentity(workspaceId, ReleaseFamily.ENDPOINT, path, version)

    private fun stored(
        version: Int,
        status: PipelineVersionStatus,
        pinned: Int,
    ) {
        every { versions.findByPath(workspaceId, path, version) } returns
            (
                PublishedEndpointVersionRepository.EntityPointer(endpointId, currentVersion = 2) to
                    PublishedEndpointVersion(
                        endpointId,
                        version,
                        status,
                        EndpointVersionBody("finance/revenue", pinned, 30, ""),
                        "h$version",
                        Instant.EPOCH,
                        UUID.randomUUID(),
                        null,
                        null,
                    )
            )
    }

    init {
        stored(3, PipelineVersionStatus.DRAFT, pinned = 4)
        stored(2, PipelineVersionStatus.RELEASED, pinned = 2)
        stored(1, PipelineVersionStatus.DISCARDED, pinned = 1)
        every { versions.findByPath(workspaceId, path, 9) } returns null
    }

    @Test
    fun `the API family is an exact-version artifact family whose one pin is PIPELINE_PIN at pipeline_version`() {
        descriptor.key shouldBe FamilyKey.API_ENDPOINT
        descriptor.rootSelectors shouldBe setOf(SelectorKind.EXACT_VERSION)
        descriptor.capabilities shouldBe FamilyKey.PRODUCTION.getValue(FamilyKey.API_ENDPOINT)
        val resolved =
            descriptor.provider
                .resolveMany(listOf(ReleaseDependencyFamilies.request(identity(3))), context)
                .values
                .single()
                .shouldBeInstanceOf<Outcome.Resolved<ResolvedNode>>()
        resolved.identity.stableEntityId shouldBe endpointId.toString()
        resolved.body.currentVersion shouldBe 2
        descriptor.provider.outgoing(resolved).map { Triple(it.relationship, it.sourcePath, it.request.selector) } shouldContainExactly
            listOf(Triple(Relationship.PIPELINE_PIN, "pipeline_version", co.datapipelines.dependencies.Selector.ExactVersion(4)))
    }

    @Test
    fun `the API adapter passes the provider conformance harness`() {
        val fixture =
            ProviderConformance.Fixture(
                descriptor = descriptor,
                context = context,
                present = listOf(3, 2, 1).map { ReleaseDependencyFamilies.request(identity(it)) },
                absent = listOf(ReleaseDependencyFamilies.request(identity(9))),
                references =
                    ProviderConformance.References.Fields(
                        mapOf(
                            ReleaseDependencyFamilies.request(identity(3)) to setOf("pipeline_version"),
                            ReleaseDependencyFamilies.request(identity(2)) to setOf("pipeline_version"),
                        ),
                    ),
            )
        ProviderConformance.check(fixture).shouldBeEmpty()
    }
}
