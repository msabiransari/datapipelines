package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The CRUD + member-management service paths (design §8/§9) — the REST surface's rules,
 * tested where they live: provisioning-mode creation, name validation, the duplicate
 * pre-check, the no-oracle 403/404 read split, owner-or-admin management, open-join
 * self-service, the owner-removal `in_use` guard, and the content-check delete block.
 */
class WorkspaceCrudServiceTest {
    private val repository = mockk<WorkspaceRepository>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val contentCheck = mockk<WorkspaceContentCheck>(relaxed = true)
    private val service =
        WorkspaceService(
            repository,
            userRepository,
            AuthCache(AuthProperties()),
            WorkspacesProperties(),
            null,
            auditLogger,
            null,
            contentCheck,
        )

    private val ownerId = UUID.randomUUID()
    private val memberB = UUID.randomUUID()
    private val ws =
        Workspace(UUID.randomUUID(), "acme", "Acme", isPersonal = false, createdBy = ownerId, isDeleted = false, createdAt = Instant.EPOCH)

    private fun owner() = principal(admin = false, memberships = listOf(membership(role = WorkspaceRole.OWNER)))

    private fun member() = principal(admin = false, memberships = listOf(membership(role = WorkspaceRole.MEMBER)))

    private fun membership(role: WorkspaceRole) = WorkspaceMembership(ws.id, ws.name, role, Instant.EPOCH)

    private fun principal(
        admin: Boolean,
        memberships: List<WorkspaceMembership>,
    ): AuthenticatedPrincipal {
        every { repository.membershipsOf(any()) } returns memberships
        every { userRepository.findById(any()) } returns null
        return AuthenticatedPrincipal(
            userId = if (admin) UUID.randomUUID() else ownerId,
            email = "alice@company.com",
            displayName = "Alice",
            scopes = if (admin) Scope.ADMIN.expand() else Scope.AUTHOR.expand(),
            authMethod = AuthMethod.OIDC,
        )
    }

    // ------------------------------------------------------------ create

    @Test
    fun `a malformed name is name_invalid before the database is touched`() {
        shouldThrow<WorkspaceNameInvalidException> { service.create(owner(), "ACME Corp!", "Acme") }
        verify(exactly = 0) { repository.create(any(), any(), any(), any()) }
    }

    @Test
    fun `a taken name is duplicate_name - soft-deleted included, the pre-check`() {
        every { repository.nameExists("acme") } returns true
        shouldThrow<WorkspaceDuplicateNameException> { service.create(owner(), "acme", "Acme") }
    }

    @Test
    fun `a racing create colliding on the constraint is still duplicate_name`() {
        every { repository.nameExists("acme") } returns false
        every { repository.create("acme", "Acme", false, any()) } throws
            org.springframework.dao.DuplicateKeyException("workspaces_name_key")
        shouldThrow<WorkspaceDuplicateNameException> { service.create(owner(), "acme", "Acme") }
    }

    @Test
    fun `closed mode refuses a non-admin with creation_forbidden`() {
        val closed = service(closed = true)
        shouldThrow<WorkspaceCreationForbiddenException> { closed.create(member(), "acme", "Acme") }
    }

    private fun service(closed: Boolean): WorkspaceService =
        WorkspaceService(
            repository,
            userRepository,
            AuthCache(AuthProperties()),
            WorkspacesProperties(provisioningMode = if (closed) WorkspaceProvisioningMode.CLOSED else WorkspaceProvisioningMode.SELF_SERVE),
            null,
            auditLogger,
            null,
            contentCheck,
        )

    // ------------------------------------------------------------ read + the no-oracle rule

    @Test
    fun `a member reads a workspace they belong to`() {
        every { repository.findByName("acme") } returns ws
        service.read(member(), "acme") shouldBe ws
    }

    @Test
    fun `unknown and non-member are the SAME membership_required for a member - no oracle`() {
        val strangerWs = ws.copy(id = UUID.randomUUID(), name = "rival")
        val stranger =
            principal(
                admin = false,
                memberships = listOf(WorkspaceMembership(strangerWs.id, strangerWs.name, WorkspaceRole.OWNER, Instant.EPOCH)),
            )
        every { repository.findByName("acme") } returns ws
        every { repository.findByName("ghost") } returns null

        val unknown = shouldThrow<WorkspaceMembershipRequiredException> { service.read(stranger, "ghost") }
        val nonMember = shouldThrow<WorkspaceMembershipRequiredException> { service.read(stranger, "acme") }
        (unknown.code to unknown.status) shouldBe (nonMember.code to nonMember.status)
    }

    @Test
    fun `an admin gets a real not_found - they could otherwise see any workspace`() {
        every { repository.findByName("ghost") } returns null
        shouldThrow<WorkspaceNotFoundException> { service.read(principal(admin = true, memberships = emptyList()), "ghost") }
    }

    // ------------------------------------------------------------ update / delete gates

    @Test
    fun `a non-owner member cannot rename - the same membership_required, not a role oracle`() {
        every { repository.findByName("acme") } returns ws
        shouldThrow<WorkspaceMembershipRequiredException> { service.updateDisplayName(member(), "acme", "Renamed") }
    }

    @Test
    fun `an owner renames and the workspace cache is invalidated`() {
        every { repository.findByName("acme") } returns ws
        every { repository.updateDisplayName(ws.id, "Renamed") } returns ws.copy(displayName = "Renamed")
        service.updateDisplayName(owner(), "acme", "Renamed").displayName shouldBe "Renamed"
        verify { auditLogger.log("auth.workspace.updated", any(), null, null, null, any()) }
    }

    @Test
    fun `a rename racing a delete is membership_required for a member - the no-oracle line holds on the race`() {
        // 022 review (below-cap): the vanished-row branch threw WorkspaceNotFoundException
        // to ANY caller — a 404-vs-403 oracle for members.
        every { repository.findByName("acme") } returns ws
        every { repository.updateDisplayName(ws.id, "Renamed") } returns null
        shouldThrow<WorkspaceMembershipRequiredException> { service.updateDisplayName(owner(), "acme", "Renamed") }
    }

    @Test
    fun `a rename racing a delete is the real not_found for an admin`() {
        every { repository.findByName("acme") } returns ws
        every { repository.updateDisplayName(ws.id, "Renamed") } returns null
        shouldThrow<WorkspaceNotFoundException> {
            service.updateDisplayName(principal(admin = true, memberships = emptyList()), "acme", "Renamed")
        }
    }

    @Test
    fun `delete is blocked by workspace_in_use with the counts of what blocks`() {
        every { repository.findByName("acme") } returns ws
        every { contentCheck.nonDeletedCounts(ws.id) } returns mapOf("pipelines" to 2, "datasources" to 1)
        val inUse = shouldThrow<WorkspaceInUseException> { service.delete(owner(), "acme") }
        inUse.details["counts"] shouldBe mapOf("pipelines" to 2, "datasources" to 1)
        verify(exactly = 0) { repository.softDelete(any()) }
    }

    @Test
    fun `delete with empty content soft-deletes and invalidates every member's cache`() {
        every { repository.findByName("acme") } returns ws
        every { contentCheck.nonDeletedCounts(ws.id) } returns emptyMap()
        every { repository.findMembersOf(ws.id) } returns
            listOf(
                WorkspaceMemberRow(ownerId, "a@company.com", "Alice", WorkspaceRole.OWNER, Instant.EPOCH),
                WorkspaceMemberRow(memberB, "b@company.com", "Bob", WorkspaceRole.MEMBER, Instant.EPOCH),
            )
        every { repository.softDelete(ws.id) } returns true

        service.delete(owner(), "acme")

        verify { repository.softDelete(ws.id) }
        verify { auditLogger.log("auth.workspace.deleted", any(), null, null, null, any()) }
    }

    /**
     * The accepted delete race (022/F10, 025 A3 — the design decision is the workspaces
     * design §11 note): content created between the pre-delete count and the soft delete
     * strands. The deletion itself stands, but the stranding is DETECTED — a post-delete
     * recount that finds content emits `auth.workspace.stranded_content` for the operator
     * instead of leaving rows invisible to every listing with the name permanently taken.
     */
    @Test
    fun `content landing in the delete window is detected and audited, not silently stranded`() {
        every { repository.findByName("acme") } returns ws
        // First count (the gate): empty. Second count (the post-delete recount): a racing
        // pipeline landed.
        every { contentCheck.nonDeletedCounts(ws.id) } returns emptyMap() andThen mapOf("pipelines" to 1)
        every { repository.findMembersOf(ws.id) } returns
            listOf(WorkspaceMemberRow(ownerId, "a@company.com", "Alice", WorkspaceRole.OWNER, Instant.EPOCH))
        every { repository.softDelete(ws.id) } returns true

        service.delete(owner(), "acme")

        verify {
            auditLogger.log(
                "auth.workspace.stranded_content",
                withArg { it shouldBe ownerId },
                null,
                null,
                null,
                withArg { details -> details["counts"] shouldBe mapOf("pipelines" to 1) },
            )
        }
    }

    // ------------------------------------------------------------ members

    @Test
    fun `an owner adds a member by email and the member's cache is invalidated`() {
        every { repository.findByName("acme") } returns ws
        val bob =
            User(
                memberB,
                "bob@company.com",
                "Bob",
                null,
                "google",
                "sub-b",
                isActive = true,
                isAdmin = false,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
        every { userRepository.findByEmail("bob@company.com") } returns bob
        every { repository.addMember(ws.id, memberB) } returns
            WorkspaceMemberRow(memberB, "bob@company.com", "Bob", WorkspaceRole.MEMBER, Instant.EPOCH)

        val added = service.addMember(owner(), "acme", "BOB@Company.com")

        added.email shouldBe "bob@company.com"
    }

    @Test
    fun `an unknown member email is the mapped IllegalStateException - never a silent null`() {
        every { repository.findByName("acme") } returns ws
        every { userRepository.findByEmail("ghost@company.com") } returns null
        shouldThrow<WorkspaceService.UnknownMemberEmailException> { service.addMember(owner(), "acme", "ghost@company.com") }
    }

    @Test
    fun `a plain member cannot add others`() {
        every { repository.findByName("acme") } returns ws
        shouldThrow<WorkspaceMembershipRequiredException> { service.addMember(member(), "acme", "bob@company.com") }
    }

    @Test
    fun `open-join lets a NON-member add their OWN email - the membership pre-check must not run first`() {
        // 022 review F4: addMember ran read()'s membership pre-check BEFORE the self-join
        // branch, so every non-member 403ed and open-join was unreachable. This test was
        // vacuous while its fixture was already a member — the caller below belongs to
        // ANOTHER workspace only.
        val openJoin =
            WorkspaceService(
                repository,
                userRepository,
                AuthCache(AuthProperties()),
                WorkspacesProperties(openJoin = true),
                null,
                auditLogger,
                null,
                contentCheck,
            )
        every { repository.findByName("acme") } returns ws
        val outsider =
            principal(
                admin = false,
                memberships = listOf(WorkspaceMembership(UUID.randomUUID(), "elsewhere", WorkspaceRole.MEMBER, Instant.EPOCH)),
            )
        val alice =
            User(
                ownerId,
                "alice@company.com",
                "Alice",
                null,
                "google",
                "sub-a",
                isActive = true,
                isAdmin = false,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
        every { userRepository.findByEmail("alice@company.com") } returns alice
        every { repository.addMember(ws.id, ownerId) } returns
            WorkspaceMemberRow(ownerId, "alice@company.com", "Alice", WorkspaceRole.MEMBER, Instant.EPOCH)

        openJoin.addMember(outsider, "acme", "alice@company.com").role shouldBe WorkspaceRole.MEMBER
    }

    @Test
    fun `without open-join a self-add is still the membership_required - no free join`() {
        every { repository.findByName("acme") } returns ws
        shouldThrow<WorkspaceMembershipRequiredException> { service.addMember(member(), "acme", "alice@company.com") }
    }

    @Test
    fun `removing an OWNER is in_use with blocked_by owner_membership - no ownerless workspace`() {
        every { repository.findByName("acme") } returns ws
        every { repository.findMemberRow(ws.id, ownerId) } returns
            WorkspaceMemberRow(ownerId, "alice@company.com", "Alice", WorkspaceRole.OWNER, Instant.EPOCH)

        val refused = shouldThrow<WorkspaceInUseException> { service.removeMember(owner(), "acme", ownerId) }
        refused.details["blocked_by"] shouldBe "owner_membership"
        verify(exactly = 0) { repository.removeMember(any(), any()) }
    }

    @Test
    fun `removing a plain member works and invalidates their cache`() {
        every { repository.findByName("acme") } returns ws
        every { repository.findMemberRow(ws.id, memberB) } returns
            WorkspaceMemberRow(memberB, "bob@company.com", "Bob", WorkspaceRole.MEMBER, Instant.EPOCH)
        every { repository.removeMember(ws.id, memberB) } returns true

        service.removeMember(owner(), "acme", memberB)

        verify { repository.removeMember(ws.id, memberB) }
    }

    // ------------------------------------------------------------ listings

    @Test
    fun `listOwn is the caller's memberships - an admin gets no merged view`() {
        val admin = principal(admin = true, memberships = listOf(membership(role = WorkspaceRole.MEMBER)))
        service.listOwn(admin) shouldContainExactly listOf(membership(role = WorkspaceRole.MEMBER))
    }

    @Test
    fun `joinable is empty when open-join is off`() {
        service.joinable(member()) shouldBe emptyList()
    }
}
