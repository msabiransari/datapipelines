package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Workspace resolution after RBAC round 1: the `DP-Workspace` switch, session fallback,
 * login stamping, and the **404 rule** (D-R5) that replaced every membership 403 on a path
 * where a caller supplied a name.
 *
 * The resolved [WorkspaceContext] now carries the caller's capability FLAGS, so these tests
 * assert what the request will be authorized with, not merely which workspace it landed in —
 * a context with the right id and the wrong flags is the failure this suite exists to catch.
 */
class WorkspaceServiceTest {
    private val repository = mockk<WorkspaceRepository>()
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val cache = AuthCache(AuthProperties())
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val lastUsed = mockk<LastUsedWorkspaceStore>(relaxed = true)
    private val demoSeeder = mockk<DemoWorkspaceSeeder>(relaxed = true)
    private val invitationRepository = mockk<WorkspaceInvitationRepository>(relaxed = true)

    private val userId = UUID.randomUUID()
    private val inviterId = UUID.randomUUID()
    private val wsA = workspace("alpha")
    private val wsB = workspace("beta")

    private fun service() =
        WorkspaceService(
            repository,
            userRepository,
            cache,
            lastUsed,
            auditLogger,
            invitationRepository,
            AuthProperties(),
            WorkspaceContentCheck.NONE,
            demoSeeder,
        )

    private fun workspace(
        name: String,
        deactivatedAt: Instant? = null,
    ): Workspace =
        Workspace(
            UUID.randomUUID(),
            name,
            name,
            isPersonal = false,
            createdBy = null,
            isDeleted = false,
            createdAt = Instant.now(),
            deactivatedAt = deactivatedAt,
        )

    private fun membership(
        ws: Workspace,
        flags: MembershipFlags = MembershipFlags(author = true, admin = true),
        active: Boolean = true,
    ): WorkspaceMembership = WorkspaceMembership(ws.id, ws.name, flags, Instant.now(), workspaceActive = active)

    private fun principal(
        superAdmin: Boolean = false,
        memberships: List<WorkspaceMembership> = emptyList(),
    ): AuthenticatedPrincipal {
        every { repository.membershipsOf(userId) } returns memberships
        return AuthenticatedPrincipal(
            userId = userId,
            email = "alice@company.com",
            displayName = "Alice",
            // D-R1: a session carries NO scopes. Anything this principal may do comes from the
            // membership the context resolves, which is the whole point of the round.
            scopes = emptySet(),
            authMethod = AuthMethod.OIDC,
            superAdmin = superAdmin,
        )
    }

    // ------------------------------------------------------------ resolveSwitch

    @Test
    fun `a switch to a member workspace resolves WITH that membership's flags`() {
        val flags = MembershipFlags(promoter = true)
        val principal = principal(memberships = listOf(membership(wsA, flags)))
        every { repository.findByName("alpha") } returns wsA

        service().resolveSwitch(principal, "alpha") shouldBe WorkspaceContext(wsA.id, "alpha", flags)
    }

    @Test
    fun `a switch naming a non-membership is NOT FOUND, not forbidden (D-R5)`() {
        val principal = principal(memberships = listOf(membership(wsA)))
        every { repository.findByName("beta") } returns wsB

        shouldThrow<WorkspaceNotFoundException> { service().resolveSwitch(principal, "beta") }
    }

    @Test
    fun `an unknown name and a non-membership are the SAME answer - nothing is probeable`() {
        val principal = principal(memberships = listOf(membership(wsA)))
        every { repository.findByName("ghost") } returns null

        val unknown = shouldThrow<WorkspaceNotFoundException> { service().resolveSwitch(principal, "ghost") }

        every { repository.findByName("beta") } returns wsB
        val foreign = shouldThrow<WorkspaceNotFoundException> { service().resolveSwitch(principal, "beta") }

        // Same code, same status. The details differ only by the name the caller supplied,
        // which they already knew — that is what makes the two indistinguishable.
        unknown.code shouldBe foreign.code
        unknown.status shouldBe foreign.status
    }

    @Test
    fun `a DEACTIVATED workspace is not selectable, and looks like it never existed (D-R10)`() {
        val dead = workspace("retired", deactivatedAt = Instant.now())
        val principal = principal(memberships = listOf(membership(dead)))
        every { repository.findByName("retired") } returns dead

        shouldThrow<WorkspaceNotFoundException> { service().resolveSwitch(principal, "retired") }
    }

    @Test
    fun `a super admin switches into a workspace they are not a member of, flagged implicit (D-R8)`() {
        val principal = principal(superAdmin = true, memberships = emptyList())
        every { repository.findByName("beta") } returns wsB

        val context = service().resolveSwitch(principal, "beta")

        context.id shouldBe wsB.id
        context.flags.superAdmin shouldBe true
        // The audit flag: this action is outside their own memberships.
        context.actingViaSuperAdmin shouldBe true
    }

    @Test
    fun `a super admin who IS a member is not flagged implicit - there is nothing unusual to record`() {
        val principal = principal(superAdmin = true, memberships = listOf(membership(wsB)))
        every { repository.findByName("beta") } returns wsB

        service().resolveSwitch(principal, "beta").actingViaSuperAdmin shouldBe false
    }

    @Test
    fun `not even a super admin selects a DEACTIVATED workspace - they reactivate it first`() {
        val dead = workspace("retired", deactivatedAt = Instant.now())
        val principal = principal(superAdmin = true, memberships = emptyList())
        every { repository.findByName("retired") } returns dead

        shouldThrow<WorkspaceNotFoundException> { service().resolveSwitch(principal, "retired") }
    }

    // ------------------------------------------------------------ resolveForSession

    @Test
    fun `the stamped claim resolves while the membership is live`() {
        val principal = principal(memberships = listOf(membership(wsA), membership(wsB)))
        every { repository.findByName("beta") } returns wsB

        service().resolveForSession(principal, "beta")?.id shouldBe wsB.id
    }

    @Test
    fun `a stamped claim whose membership is gone falls back to the first ACTIVE membership`() {
        val principal = principal(memberships = listOf(membership(wsA)))
        every { repository.findByName("revoked-ws") } returns null

        service().resolveForSession(principal, "revoked-ws")?.id shouldBe wsA.id
    }

    @Test
    fun `a workspace deactivated since login does not lock a user out of the ones they can reach`() {
        val dead = workspace("retired", deactivatedAt = Instant.now())
        val principal =
            principal(memberships = listOf(membership(dead, active = false), membership(wsA)))
        every { repository.findByName("retired") } returns dead

        service().resolveForSession(principal, "retired")?.id shouldBe wsA.id
    }

    @Test
    fun `zero memberships resolve to no workspace - every operation is then refused downstream`() {
        val principal = principal(memberships = emptyList())
        every { repository.findByName("alpha") } returns null

        service().resolveForSession(principal, "alpha").shouldBeNull()
    }

    @Test
    fun `a super admin with no membership at all lands in the first active workspace (D-R8)`() {
        // Without this, the one principal who can fix an empty deployment is the one principal
        // who cannot act in it.
        val principal = principal(superAdmin = true, memberships = emptyList())
        every { repository.findByName(any()) } returns null
        every { repository.findAllActive() } returns listOf(wsA, wsB)

        val context = service().resolveForSession(principal, null)

        context?.id shouldBe wsA.id
        context?.flags?.superAdmin shouldBe true
    }

    // ------------------------------------------------------------ workspaceForLogin

    @Test
    fun `login stamps the last-used workspace while it is still a membership`() {
        every { lastUsed.lastUsed(userId) } returns "beta"
        principal(memberships = listOf(membership(wsA), membership(wsB)))

        service().workspaceForLogin(user(), "alice@company.com")?.id shouldBe wsB.id
    }

    @Test
    fun `a stale last-used falls back to the first membership`() {
        every { lastUsed.lastUsed(userId) } returns "long-gone"
        principal(memberships = listOf(membership(wsA)))

        service().workspaceForLogin(user(), "alice@company.com")?.id shouldBe wsA.id
    }

    @Test
    fun `a first login with NO membership joins demo as a viewer (D-R11)`() {
        every { lastUsed.lastUsed(userId) } returns null
        principal(memberships = emptyList())
        val demo = WorkspaceContext(UUID.randomUUID(), "demo", MembershipFlags.VIEWER)
        every { demoSeeder.joinDemoIfUnaffiliated(userId) } returns demo

        service().workspaceForLogin(user(), "alice@company.com") shouldBe demo
    }

    @Test
    fun `no membership and no reachable demo stamps NOTHING - round 2 draws that page`() {
        every { lastUsed.lastUsed(userId) } returns null
        principal(memberships = emptyList())
        every { demoSeeder.joinDemoIfUnaffiliated(userId) } returns null

        service().workspaceForLogin(user(), "alice@company.com").shouldBeNull()
    }

    @Test
    fun `an existing member never reaches the demo join - a deliberate removal is not undone`() {
        every { lastUsed.lastUsed(userId) } returns null
        principal(memberships = listOf(membership(wsA)))

        service().workspaceForLogin(user(), "alice@company.com")?.id shouldBe wsA.id
        verify(exactly = 0) { demoSeeder.joinDemoIfUnaffiliated(any()) }
    }

    @Test
    fun `a pending invitation materialises at login and stamps the INVITED workspace, never demo (113)`() {
        val invitedFlags = MembershipFlags(author = true)
        every {
            invitationRepository.materialiseFor("alice@company.com", userId)
        } returns
            listOf(
                WorkspaceInvitationRepository.MaterialisedInvitation("alpha", invitedFlags, inviterId, Instant.EPOCH),
            )
        every { lastUsed.lastUsed(userId) } returns null
        // The materialise invalidated the cache, so the resolution below re-reads the
        // memberships — which now hold the invited workspace.
        principal(memberships = listOf(membership(wsA, flags = invitedFlags)))

        val stamped =
            service().workspaceForLogin(user(), "alice@company.com")
                ?: error("an invited user's first login must stamp the invited workspace")

        stamped.id shouldBe wsA.id
        stamped.flags shouldBe invitedFlags
        // The materialisation was audited with the inviter and the flags (auth.md §10), and
        // the demo join never fired: the invited workspace REPLACES the default (D-R11).
        verify(exactly = 0) { demoSeeder.joinDemoIfUnaffiliated(any()) }
    }

    @Test
    fun `the materialise audit names the workspace, the normalized email, the flags and the INVITER`() {
        every {
            invitationRepository.materialiseFor("alice@company.com", userId)
        } returns
            listOf(
                WorkspaceInvitationRepository.MaterialisedInvitation(
                    "alpha",
                    MembershipFlags(promoter = true),
                    inviterId,
                    Instant.EPOCH,
                ),
            )
        every { lastUsed.lastUsed(userId) } returns null
        principal(memberships = listOf(membership(wsA)))

        service().workspaceForLogin(user(), "  alice@company.com  ")

        verify {
            auditLogger.log(
                "workspace.invitation_materialised",
                userId,
                null,
                null,
                null,
                match {
                    it["workspace"] == "alpha" &&
                        it["email"] == "alice@company.com" &&
                        it["flags"] == listOf("promoter") &&
                        it["inviter"] == inviterId.toString()
                },
            )
        }
    }

    // ------------------------------------------------------------ create (D-R11)

    @Test
    fun `only a super admin creates a workspace (D-R11)`() {
        val principal = principal(memberships = listOf(membership(wsA)))

        val refusal = shouldThrow<RoleRequiredException> { service().create(principal, "acme", "Acme") }

        refusal.code shouldBe AuthErrorCodes.ROLE_REQUIRED
        refusal.details["required"] shouldBe Capability.SUPER_ADMIN.wire
        verify(exactly = 0) { repository.create(any(), any(), any(), any()) }
    }

    @Test
    fun `a super admin creates, and the creation is audited`() {
        val principal = principal(superAdmin = true, memberships = emptyList())
        every { repository.nameExists("acme") } returns false
        val acme = workspace("acme")
        every { repository.create("acme", "Acme", false, userId) } returns acme
        every { repository.findByName("acme") } returns acme

        service().create(principal, "acme", "Acme") shouldBe acme
        verify { auditLogger.log("auth.workspace.created", userId, null, null, null, any()) }
    }

    @Test
    fun `a malformed workspace name is rejected before the database is touched`() {
        val principal = principal(superAdmin = true, memberships = emptyList())

        shouldThrow<WorkspaceNameInvalidException> { service().create(principal, "ACME Corp!", "Acme") }
        verify(exactly = 0) { repository.create(any(), any(), any(), any()) }
    }

    private fun user() =
        User(userId, "alice@company.com", "Alice", null, "google", "sub-1", true, false, Instant.now(), Instant.now(), null)

    @Test
    fun `revoking an invitation that does not exist is workspace-invitation-not-found, not the workspace 404`() {
        every { repository.findByName("alpha") } returns wsA
        every { invitationRepository.delete(wsA.id, "ghost@company.com") } returns false

        val thrown =
            shouldThrow<WorkspaceInvitationNotFoundException> {
                service().revokeInvitation(principal(memberships = listOf(membership(wsA))), "alpha", "Ghost@Company.com")
            }

        // The workspace resolved; the not-found thing is the INVITATION. The email was
        // normalized before the lookup, and the code is the §13.12 three-segment form.
        thrown.code shouldBe "workspace.invitation.not_found"
        thrown.status shouldBe 404
        thrown.details["email"] shouldBe "ghost@company.com"
    }

    @Test
    fun `revoking an invitation succeeds, is audited, and never touches memberships`() {
        every { repository.findByName("alpha") } returns wsA
        every { invitationRepository.delete(wsA.id, "bob@company.com") } returns true

        service().revokeInvitation(principal(memberships = listOf(membership(wsA))), "alpha", "bob@company.com")

        verify {
            auditLogger.log(
                "workspace.invitation_revoked",
                userId,
                null,
                null,
                null,
                match { it["workspace"] == "alpha" && it["email"] == "bob@company.com" },
            )
        }
        // A revocation touches the invitation table only: no membership row was written,
        // removed or reflagged (the visibility read above is read-only).
        verify(exactly = 0) { repository.removeMember(any(), any()) }
        verify(exactly = 0) { repository.addMember(any(), any(), any()) }
        verify(exactly = 0) { repository.setFlags(any(), any(), any()) }
    }

    @Test
    fun `an invite whose domain the allowlist would refuse at login is refused AT INVITE, with the login's code`() {
        val allowlisted =
            WorkspaceService(
                repository,
                userRepository,
                cache,
                lastUsed,
                auditLogger,
                invitationRepository,
                AuthProperties(allowlist = AuthProperties.Allowlist(listOf("company.com"))),
                WorkspaceContentCheck.NONE,
                demoSeeder,
            )
        every { repository.findByName("alpha") } returns wsA
        every { userRepository.findByEmail("intruder@elsewhere.org") } returns null

        val thrown =
            shouldThrow<InvitationDomainNotAllowedException> {
                allowlisted.addMember(
                    principal(memberships = listOf(membership(wsA))),
                    "alpha",
                    "intruder@elsewhere.org",
                    MembershipFlags(author = true),
                )
            }

        // The SAME code the login would refuse the address with — an invite that could
        // never be honoured is a trap — at a 400, because the caller's REQUEST is the
        // defect the API surface is answering.
        thrown.code shouldBe AuthErrorCodes.LOGIN_DOMAIN_NOT_ALLOWED
        thrown.status shouldBe 400
        // Nothing was stored, and nothing was audited as an invitation.
        verify(exactly = 0) { invitationRepository.upsert(any(), any(), any(), any()) }
        verify(exactly = 0) { auditLogger.log("workspace.member_invited", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `inviting into a DEACTIVATED workspace is workspace-inactive for the super admin who can see it`() {
        val deactivated = workspace("alpha", deactivatedAt = Instant.now())
        every { repository.findByName("alpha") } returns deactivated

        val thrown =
            shouldThrow<WorkspaceInactiveException> {
                service().addMember(principal(superAdmin = true), "alpha", "bob@company.com")
            }

        thrown.code shouldBe WorkspaceErrorCodes.INACTIVE
        thrown.status shouldBe 404
        verify(exactly = 0) { invitationRepository.upsert(any(), any(), any(), any()) }
    }

    @Test
    fun `the members listing returns both arrays through one resolution`() {
        every { repository.findByName("alpha") } returns wsA
        every { repository.membershipsOf(userId) } returns listOf(membership(wsA))
        every { repository.findMembersOf(wsA.id) } returns
            listOf(
                WorkspaceMemberRow(userId, "a@x.test", "A", MembershipFlags.VIEWER, Instant.EPOCH),
            )
        every { invitationRepository.findByWorkspace(wsA.id) } returns
            listOf(
                WorkspaceInvitation(wsA.id, "b@x.test", MembershipFlags.VIEWER, invitedBy = userId, invitedAt = Instant.EPOCH),
            )

        val listing = service().membersWithInvitations(principal(memberships = listOf(membership(wsA))), "alpha")

        listing.members.single().email shouldBe "a@x.test"
        listing.invitations.single().email shouldBe "b@x.test"
    }
}
