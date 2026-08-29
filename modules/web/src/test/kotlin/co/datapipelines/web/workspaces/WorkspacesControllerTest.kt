package co.datapipelines.web.workspaces

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceDuplicateNameException
import co.datapipelines.auth.WorkspaceInUseException
import co.datapipelines.auth.WorkspaceMemberRow
import co.datapipelines.auth.WorkspaceMembershipRequiredException
import co.datapipelines.auth.WorkspaceNameInvalidException
import co.datapipelines.auth.WorkspaceNotFoundException
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.api.ApiException
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * §17's HTTP surface over a mocked [WorkspaceService]: the §8 codes surface unchanged
 * (AuthException → its own status through ApiExceptionHandler), the unknown-member-email
 * mapping is the §16.3 stand-in, and the payload shapes are the documented fields only.
 */
class WorkspacesControllerTest {
    private val service = mockk<WorkspaceService>(relaxed = true)
    private val controller = WorkspacesController(service)
    private val mapper = JsonMapper.builder().build()
    private val userId = UUID.randomUUID()

    private val ws =
        co.datapipelines.auth.Workspace(
            UUID.randomUUID(),
            "acme",
            "Acme",
            isPersonal = false,
            createdBy = null,
            isDeleted = false,
            createdAt = Instant.EPOCH,
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    userId,
                    "alice@company.com",
                    "Alice",
                    Scope.AUTHOR.expand(),
                    AuthMethod.OIDC,
                    workspace = WorkspaceContext(ws.id, "acme"),
                ),
                null,
                emptyList(),
            )
    }

    @Test
    fun `create returns 201's payload with name, display_name, is_personal, created_at`() {
        authenticate()
        every { service.create(any(), "acme", "Acme") } returns ws

        val data = controller.create(mapper.readTree("""{"name":"acme","display_name":"Acme"}""")).data

        data.keys shouldBe setOf("name", "display_name", "is_personal", "created_at")
    }

    @Test
    fun `create without a name is the name_invalid 400`() {
        authenticate()
        val thrown = shouldThrow<ApiException> { controller.create(mapper.readTree("""{"display_name":"Acme"}""")) }
        thrown.code shouldBe PipelineErrorCodes.Workspace.NAME_INVALID
    }

    @Test
    fun `the §8 catalogued service refusals surface unchanged`() {
        authenticate()
        every { service.create(any(), "ACME!", any()) } throws WorkspaceNameInvalidException("ACME!")
        every { service.create(any(), "acme", any()) } throws WorkspaceDuplicateNameException("acme")
        every { service.read(any(), "ghost") } throws WorkspaceMembershipRequiredException()
        every { service.read(any(), "gone") } throws WorkspaceNotFoundException("gone")
        every { service.delete(any(), "acme") } throws WorkspaceInUseException("acme", mapOf("pipelines" to 2))

        shouldThrow<WorkspaceNameInvalidException> { controller.create(mapper.readTree("""{"name":"ACME!"}""")) }
        shouldThrow<WorkspaceDuplicateNameException> { controller.create(mapper.readTree("""{"name":"acme"}""")) }
        shouldThrow<WorkspaceMembershipRequiredException> { controller.get("ghost") }
        shouldThrow<WorkspaceNotFoundException> { controller.get("gone") }.status shouldBe 404
        shouldThrow<WorkspaceInUseException> { controller.delete("acme") }
    }

    @Test
    fun `update with an absent display_name keeps the current one`() {
        authenticate()
        every { service.read(any(), "acme") } returns ws
        every { service.updateDisplayName(any(), "acme", "Acme") } returns ws

        controller.update("acme", mapper.readTree("""{}""")).data["display_name"] shouldBe "Acme"
    }

    @Test
    fun `addMember maps the unknown email to the §16-3 stand-in`() {
        authenticate()
        every { service.addMember(any(), "acme", "ghost@company.com") } throws
            WorkspaceService.UnknownMemberEmailException("ghost@company.com")

        val thrown = shouldThrow<ApiException> { controller.addMember("acme", mapper.readTree("""{"email":"ghost@company.com"}""")) }
        thrown.code shouldBe PipelineErrorCodes.Execution.NOT_FOUND
        thrown.details["reason"] shouldBe "user_not_found"
    }

    @Test
    fun `the member listing projects identity, role and join date`() {
        authenticate()
        every { service.members(any(), "acme") } returns
            listOf(WorkspaceMemberRow(userId, "alice@company.com", "Alice", WorkspaceRole.OWNER, Instant.EPOCH))

        val row = controller.members("acme").data.single()

        row.keys shouldBe setOf("user_id", "email", "display_name", "role", "joined_at")
        row["role"] shouldBe "owner"
    }

    @Test
    fun `list-own rows carry the caller's role`() {
        authenticate()
        every { service.listOwn(any()) } returns
            listOf(co.datapipelines.auth.WorkspaceMembership(ws.id, "acme", WorkspaceRole.OWNER, Instant.EPOCH))

        controller.list().data.single()["role"] shouldBe "owner"
    }
}
