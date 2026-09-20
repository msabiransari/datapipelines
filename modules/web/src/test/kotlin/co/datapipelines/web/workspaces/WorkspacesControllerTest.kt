package co.datapipelines.web.workspaces

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceDuplicateNameException
import co.datapipelines.auth.WorkspaceInUseException
import co.datapipelines.auth.WorkspaceInvitation
import co.datapipelines.auth.WorkspaceMemberRow
import co.datapipelines.auth.WorkspaceMembershipRequiredException
import co.datapipelines.auth.WorkspaceNameInvalidException
import co.datapipelines.auth.WorkspaceNotFoundException
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.api.ApiException
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
            isDeleted = false,
            createdBy = null,
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
    fun `create returns 201's payload, now carrying the workspace's active state (D-R10)`() {
        authenticate()
        every { service.create(any(), "acme", "Acme") } returns ws

        val data = controller.create(mapper.readTree("""{"name":"acme","display_name":"Acme"}""")).data

        data.keys shouldBe setOf("name", "display_name", "is_personal", "created_at", "active", "deactivated_at")
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
    fun `addMember answers 200 with the member row when the user exists`() {
        authenticate()
        every { service.addMember(any(), "acme", "alice@company.com", any()) } returns
            WorkspaceService.AddMemberOutcome.Added(
                WorkspaceMemberRow(userId, "alice@company.com", "Alice", WorkspaceRole.WORKSPACE_ADMIN, Instant.EPOCH),
            )

        val response = controller.addMember("acme", mapper.readTree("""{"email":"alice@company.com","role":"workspace_admin"}"""))

        response.statusCode.value() shouldBe 200
        response.body!!.data["user_id"] shouldBe userId.toString()
        response.body!!.data["role"] shouldBe "workspace_admin"
        // A membership, never a ghost: the invited flag exists only on the 202 branch.
        response.body!!.data.keys shouldBe setOf("user_id", "email", "display_name", "role", "joined_at")
        verify { service.addMember(any(), "acme", "alice@company.com", WorkspaceRole.WORKSPACE_ADMIN) }
    }

    /** D1: the body's `role` is one of four tokens; anything else is the surface's bad-parameter 400, before the service. */
    @Test
    fun `addMember with an unknown role is a 400 and never reaches the service`() {
        authenticate()

        val refusal =
            shouldThrow<ApiException> {
                controller.addMember("acme", mapper.readTree("""{"email":"alice@company.com","role":"owner"}"""))
            }

        refusal.details["field"] shouldBe "role"
        verify(exactly = 0) { service.addMember(any(), any(), any(), any()) }
    }

    /** An absent `role` is the least a membership can be — a viewer — the only safe reading of silence. */
    @Test
    fun `addMember without a role asks for a viewer`() {
        authenticate()
        every { service.addMember(any(), "acme", "alice@company.com", WorkspaceRole.VIEWER) } returns
            WorkspaceService.AddMemberOutcome.Added(
                WorkspaceMemberRow(userId, "alice@company.com", "Alice", WorkspaceRole.VIEWER, Instant.EPOCH),
            )

        controller.addMember("acme", mapper.readTree("""{"email":"alice@company.com"}""")).statusCode.value() shouldBe 200

        verify { service.addMember(any(), "acme", "alice@company.com", WorkspaceRole.VIEWER) }
    }

    @Test
    fun `addMember answers 202 with the invitation echo when the email has no user row`() {
        authenticate()
        every { service.addMember(any(), "acme", "ghost@company.com", any()) } returns
            WorkspaceService.AddMemberOutcome.Invited("ghost@company.com", WorkspaceRole.AUTHOR)

        val response = controller.addMember("acme", mapper.readTree("""{"email":"ghost@company.com","role":"author"}"""))

        response.statusCode.value() shouldBe 202
        response.body!!.data shouldBe
            mapOf(
                "invited" to true,
                "email" to "ghost@company.com",
                "role" to "author",
            )
    }

    @Test
    fun `the member listing returns members and invitations as SEPARATE arrays - a ghost is not a member`() {
        authenticate()
        val memberId = UUID.randomUUID()
        every { service.membersWithInvitations(any(), "acme") } returns
            WorkspaceService.MemberListing(
                members =
                    listOf(
                        WorkspaceMemberRow(
                            memberId,
                            "alice@company.com",
                            "Alice",
                            WorkspaceRole.WORKSPACE_ADMIN,
                            Instant.EPOCH,
                        ),
                    ),
                invitations =
                    listOf(
                        WorkspaceInvitation(
                            ws.id,
                            "bob@company.com",
                            WorkspaceRole.VIEWER,
                            invitedBy = memberId,
                            invitedAt = Instant.EPOCH,
                        ),
                    ),
            )

        val data = controller.members("acme").data

        data.keys shouldBe setOf("members", "invitations")
        data["members"]!!.single()["user_id"] shouldBe memberId.toString()
        // The invitation is in ITS array, with the email as identity and no user_id.
        val invitation = data["invitations"]!!.single()
        invitation.keys shouldBe setOf("email", "role", "invited_by", "invited_at")
        invitation["email"] shouldBe "bob@company.com"
    }

    @Test
    fun `list-own rows carry the caller's ONE role (D1, D21)`() {
        authenticate()
        every { service.listOwn(any()) } returns
            listOf(co.datapipelines.auth.WorkspaceMembership(ws.id, "acme", WorkspaceRole.WORKSPACE_ADMIN, Instant.EPOCH))

        val row = controller.list().data.single()

        // `role` is BACK on the wire (2026-09-20): one of four tokens, replacing the three
        // booleans V23's additive flags had put there.
        row["role"] shouldBe "workspace_admin"
        row.keys shouldBe setOf("name", "role", "active", "joined_at")
        row["active"] shouldBe true
    }
}
