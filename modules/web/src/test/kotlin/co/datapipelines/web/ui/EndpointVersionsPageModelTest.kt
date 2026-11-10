package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.CurrentVersion
import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointPinValidator
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.EndpointVersionBody
import co.datapipelines.application.endpoints.EndpointVersionService
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedEndpointVersion
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.CurrentPipelineVersion
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionRecord
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.release.ClosureNode
import co.datapipelines.pipeline.release.ReleaseClosure
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.release.ReleaseCoordinator
import co.datapipelines.web.release.RootPreview
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * #786 B.3/C.3 — what the API versions page and its dialogs READ, over mocked services: the default selection is the
 * current version (viewing never moves it), the draft picker preserves a noncurrent pin and never offers a DISCARDED
 * pipeline version, the release dialog splits the planner's closure into consentable DRAFTs and blockers, and an API
 * the caller cannot see is not-found everywhere.
 */
class EndpointVersionsPageModelTest {
    private val workspaceId = UUID.randomUUID()
    private val id = UUID.randomUUID()
    private val pipelineId = UUID.randomUUID()
    private val principal =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "a@b.c",
            displayName = "A",
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext(workspaceId, "acme", WorkspaceRole.AUTHOR),
        )

    private val publishing = mockk<EndpointPublishService>()
    private val versions = mockk<EndpointVersionService>()
    private val bindings = mockk<EndpointKeyBindingRepository> { every { findByWorkspace(any()) } returns emptyList() }
    private val apiKeys = mockk<ApiKeyRepository> { every { findByWorkspaceAndKind(any(), any()) } returns emptyList() }
    private val pipelines = mockk<PipelineRepository>()
    private val pins =
        mockk<EndpointPinValidator> {
            every { eligible(any()) } answers
                { firstArg<PipelineVersionStatus>() == PipelineVersionStatus.RELEASED }
        }
    private val coordinator = mockk<ReleaseCoordinator>()
    private val model =
        EndpointVersionsPageModel(publishing, versions, bindings, apiKeys, pipelines, pins, AuthoringGuard(false), coordinator)

    private fun version(
        n: Int,
        status: PipelineVersionStatus,
        pinned: Int,
    ) = PublishedEndpointVersion(
        id,
        n,
        status,
        EndpointVersionBody("finance/revenue", pinned, 30, ""),
        "h$n",
        Instant.EPOCH,
        UUID.randomUUID(),
        null,
        null,
    )

    private fun visible(current: Int) {
        every { publishing.getById(principal, id) } returns
            PublishedEndpoint.of(
                id,
                workspaceId,
                "/finance/v1/revenue",
                true,
                UUID.randomUUID(),
                Instant.EPOCH,
                Instant.EPOCH,
                CurrentVersion(current, PipelineVersionStatus.RELEASED, EndpointVersionBody("finance/revenue", 2, 30, "")),
            )
    }

    private fun listed(vararg views: EndpointVersionService.VersionView) {
        every { versions.versions(principal, id) } returns views.toList()
    }

    @Test
    fun `the page selects the current version by default and the requested one when asked`() {
        visible(current = 2)
        listed(
            EndpointVersionService.VersionView(version(3, PipelineVersionStatus.DRAFT, 4), isCurrent = false, eligible = false),
            EndpointVersionService.VersionView(version(2, PipelineVersionStatus.RELEASED, 2), isCurrent = true, eligible = true),
        )
        model.page(principal, id, null)?.selected?.version shouldBe 2
        model.page(principal, id, 3)?.selected?.version shouldBe 3
        // An unknown requested version falls back to the current one; it never invents a row.
        model.page(principal, id, 9)?.selected?.version shouldBe 2
        val page = requireNotNull(model.page(principal, id, null))
        page.authoringEnabled shouldBe false
        page.canPublish shouldBe true
        page.hasDraft shouldBe true
    }

    @Test
    fun `the draft picker keeps the draft's noncurrent pin and never offers a DISCARDED pipeline version`() {
        visible(current = 2)
        listed(
            EndpointVersionService.VersionView(version(3, PipelineVersionStatus.DRAFT, 2), isCurrent = false, eligible = false),
            EndpointVersionService.VersionView(version(2, PipelineVersionStatus.RELEASED, 3), isCurrent = true, eligible = true),
        )
        every { pipelines.findCurrentVersions(workspaceId) } returns
            listOf(CurrentPipelineVersion(pipelineId, "finance/revenue", "Revenue", 4, "x", PipelineVersionStatus.RELEASED))
        val record =
            mockk<PipelineRecord> {
                every { id } returns pipelineId
                every { currentVersion } returns 4
            }
        every { pipelines.findByName(workspaceId, "finance/revenue") } returns record
        every { pipelines.listVersions(workspaceId, pipelineId) } returns
            listOf(
                PipelineVersionRecord(pipelineId, 5, PipelineVersionStatus.DRAFT, "", Instant.EPOCH, UUID.randomUUID()),
                PipelineVersionRecord(pipelineId, 4, PipelineVersionStatus.RELEASED, "", Instant.EPOCH, UUID.randomUUID()),
                PipelineVersionRecord(pipelineId, 3, PipelineVersionStatus.DISCARDED, "", Instant.EPOCH, UUID.randomUUID()),
                PipelineVersionRecord(pipelineId, 2, PipelineVersionStatus.RELEASED, "", Instant.EPOCH, UUID.randomUUID()),
            )
        val dialog = model.draft(principal, id, null)
        dialog.isNew shouldBe false
        dialog.ifMatch shouldBe "h3"
        dialog.selectedVersion shouldBe 2
        dialog.options.map { it.version to it.eligible } shouldContainExactly listOf(5 to false, 4 to true, 2 to true)
        dialog.options.single { it.isCurrent }.version shouldBe 4
    }

    @Test
    fun `the release dialog offers the closure's DRAFTs for consent and lists a MISSING pin as a blocker`() {
        visible(current = 2)
        listed(EndpointVersionService.VersionView(version(3, PipelineVersionStatus.DRAFT, 4), isCurrent = false, eligible = false))
        val root = ReleaseNodeIdentity(workspaceId, ReleaseFamily.ENDPOINT, id.toString(), 3)
        val draft = ReleaseNodeIdentity(workspaceId, ReleaseFamily.PIPELINE, "finance/revenue", 4)
        val missing = ReleaseNodeIdentity(workspaceId, ReleaseFamily.TEMPLATE, "finance/t.sql", 1)
        val nodes =
            listOf(
                ClosureNode("n0", root, "/finance/v1/revenue", ReleaseNodeStatus.DRAFT, "h3", 2, id),
                ClosureNode("n1", draft, "finance/revenue", ReleaseNodeStatus.DRAFT, "p4", 3, pipelineId),
                ClosureNode("n2", missing, "finance/t.sql", ReleaseNodeStatus.MISSING, null, null, null),
            )
        every { coordinator.previewRoot(ReleaseFamily.ENDPOINT, workspaceId, id, principal.userId) } returns
            RootPreview(ReleaseClosure(root, nodes, emptyList(), 2, nodes.reversed()), "t".repeat(64))
        val dialog = model.release(principal, id)
        dialog.treeHash shouldBe "t".repeat(64)
        dialog.bodyHash shouldBe "h3"
        dialog.drafts.map { it.nodeId to it.label } shouldContainExactly listOf("n1" to "finance/revenue@4")
        dialog.blockers.map { it.nodeId to it.status } shouldContainExactly listOf("n2" to "MISSING")
    }

    @Test
    fun `an API the caller cannot see is not-found on the page and in every dialog`() {
        every { publishing.getById(principal, id) } returns null
        model.page(principal, id, null) shouldBe null
        listOf({ model.draft(principal, id, null) }, { model.release(principal, id) }, { model.version(principal, id, 1) }).forEach {
            shouldThrow<DatapipelinesException> { it() }.code shouldBe "endpoint.not_found"
        }
    }
}
