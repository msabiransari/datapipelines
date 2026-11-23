package co.datapipelines.application.endpoints

import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * #1111 — [EndpointKeyService.setNamespaceWrites]: the refusals of an explicit `allow: true` run BEFORE any write, and
 * the withdrawals (`allow: false`) are never refused by the posture or the tree check, so an operator can always retire
 * an opt-in — on a receiver, and after the namespace's last route has gone.
 */
class EndpointKeyNamespaceWritesServiceTest {
    private val bindings = mockk<EndpointKeyBindingRepository>()
    private val audit = mockk<AuditEventSink>(relaxed = true)
    private val published = mockk<PublishedEndpointRepository>()

    private fun service(
        authoring: Boolean = true,
        managed: ManagedEndpointKeys = ManagedEndpointKeys.NONE,
    ) = EndpointKeyService(mockk<ApiKeyService>(), bindings, audit, published, AuthoringGuard(authoring), managed)

    init {
        every { published.findByWorkspace(WORKSPACE) } returns listOf(publishedAt("/school/v1/seed"))
        every { bindings.setNamespaceWrites(any(), any(), any(), any(), true) } returns
            EndpointKeyBindingRepository.NamespaceWritesState(allow = true, bound = true, created = true)
        every { bindings.setNamespaceWrites(any(), any(), any(), any(), false) } returns
            EndpointKeyBindingRepository.NamespaceWritesState(allow = false, bound = false, created = false)
    }

    @Test
    fun `allow true on an authoring deployment writes once, at the normalised node, and audits the resulting state`() {
        val state = service().setNamespaceWrites(principal(), KEY, "school/v1/", allow = true)
        state.allow shouldBe true
        verify(exactly = 1) { bindings.setNamespaceWrites("/school/v1", KEY, WORKSPACE, ACTOR, true) }
        verify(exactly = 1) {
            audit.log(
                "endpoint.key_namespace_writes",
                ACTOR,
                KEY,
                any(),
                any(),
                match { it["allow"] == true && it["requested"] == true && it["path_prefix"] == "/school/v1" },
            )
        }
    }

    @Test
    fun `allow true is refused on a receiver before anything is written`() {
        shouldThrow<DatapipelinesException> {
            service(authoring = false).setNamespaceWrites(principal(), KEY, "/school/v1", allow = true)
        }.code shouldBe PipelineErrorCodes.Endpoint.AUTHORING_DISABLED
        verify(exactly = 0) { bindings.setNamespaceWrites(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `allow true is refused for a key the deployment manages, before anything is written`() {
        val managed = ManagedEndpointKeys { keyId, workspace -> keyId == KEY && workspace == WORKSPACE }
        val refused =
            shouldThrow<DatapipelinesException> { service(managed = managed).setNamespaceWrites(principal(), KEY, "/school/v1", allow = true) }
        refused.code shouldBe PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED
        refused.details["reason"] shouldBe "managed_key"
        verify(exactly = 0) { bindings.setNamespaceWrites(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `allow true outside the caller's published tree is refused, and the root is legal`() {
        shouldThrow<DatapipelinesException> {
            service().setNamespaceWrites(principal(), KEY, "/elsewhere/v1", allow = true)
        }.code shouldBe PipelineErrorCodes.Endpoint.PATH_INVALID
        verify(exactly = 0) { bindings.setNamespaceWrites(any(), any(), any(), any(), any()) }
        service().setNamespaceWrites(principal(), KEY, "/", allow = true).allow shouldBe true
    }

    @Test
    fun `allow false is permitted with authoring off, for a managed key and at a node with no route left`() {
        every { published.findByWorkspace(WORKSPACE) } returns emptyList()
        val state =
            service(authoring = false, managed = { _, _ -> true }).setNamespaceWrites(principal(), KEY, "/school/v1", allow = false)
        state.bound shouldBe false
        verify(exactly = 1) { bindings.setNamespaceWrites("/school/v1", KEY, WORKSPACE, ACTOR, false) }
    }

    @Test
    fun `a malformed node is refused for both values before anything is written`() {
        listOf(true, false).forEach { allow ->
            shouldThrow<DatapipelinesException> {
                service().setNamespaceWrites(principal(), KEY, "/Bad Path", allow)
            }.code shouldBe PipelineErrorCodes.Endpoint.PATH_INVALID
        }
        verify(exactly = 0) { bindings.setNamespaceWrites(any(), any(), any(), any(), any()) }
    }

    private fun publishedAt(pattern: String) =
        PublishedEndpoint.of(
            id = UUID.randomUUID(),
            workspaceId = WORKSPACE,
            pathPattern = pattern,
            isEnabled = true,
            createdBy = ACTOR,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            current = CurrentVersion(1, PipelineVersionStatus.RELEASED, EndpointVersionBody("test/pipeline", 1, 60, "")),
        )

    private fun principal() =
        AuthenticatedPrincipal(
            userId = ACTOR,
            email = "a@b.c",
            displayName = "A",
            authMethod = AuthMethod.OIDC,
            workspaceName = "default",
            workspace = WorkspaceContext(WORKSPACE, "default"),
        )

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        const val KEY = "dpk_DEVKEYAAAAAA"
    }
}
