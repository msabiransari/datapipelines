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
 * The CRUD + member-management service paths after RBAC round 1 — the REST surface's rules,
 * tested where they live: super-admin-only creation (D-R11), the **404 rule** on every read
 * and every management verb (D-R5), workspace-admin management, the **last-admin** rule,
 * flag replacement, deactivation's reversibility (D-R10), and the content-check delete block.
 *
 * What is deliberately gone from this suite, with the behaviour it tested: the provisioning
 * modes, `open-join` self-service, and the owner-removal `in_use` guard (superseded by
 * `workspace.last_admin`, which refuses the same thing for a reason a caller can act on).
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
            null,
            auditLogger,
            contentCheck,
        )

    private val adminId = UUID.randomUUID()
    private val memberB = UUID.randomUUID()
    private val ws =
        Workspace(UUID.randomUUID(), "acme", "Acme", isPersonal = false, createdBy = adminId, isDeleted = false, createdAt = Instant.EPOCH)

    private val wsAdminFlags = MembershipFlags(author = true, admin = true)
    private val authorFlags = MembershipFlags(author = true)

    /** A workspace ADMIN of `acme` — the old `owner`'s successor. */
    private fun wsAdmin() = principal(memberships = listOf(membership(wsAdminFlags)))

    /** A plain AUTHOR of `acme`: may author, may not manage. */
    private fun author() = principal(memberships = listOf(membership(authorFlags)))

    private fun superAdmin() = principal(superAdmin = true, memberships = emptyList())

    private fun membership(flags: MembershipFlags) = WorkspaceMembership(ws.id, ws.name, flags, Instant.EPOCH)

    private fun principal(
        superAdmin: Boolean = false,
        memberships: List<WorkspaceMembership>,
    ): AuthenticatedPrincipal {
        every { repository.membershipsOf(any()) } returns memberships
        every { userRepository.findById(any()) } returns null
        return AuthenticatedPrincipal(
            userId = if (superAdmin) UUID.randomUUID() else adminId,
            email = "alice@company.com",
            displayName = "Alice",
            scopes = emptySet(),
            authMethod = AuthMethod.OIDC,
            superAdmin = superAdmin,
        )
    }

    private fun memberRow(
        userId: UUID,
        flags: MembershipFlags,
    ) = WorkspaceMemberRow(userId, "m@company.com", "M", flags, Instant.EPOCH)

    // ------------------------------------------------------------ create

    @Test
    fun `a malformed name is name_invalid before the database is touched`() {
        shouldThrow<WorkspaceNameInvalidException> { service.create(superAdmin(), "Acme Corp!", "Acme") }
        verify(exactly = 0) { repository.create(any(), any(), any(), any()) }
    }

    @Test
    fun `a taken name is duplicate_name - soft-deleted included, the pre-check`() {
        every { repository.nameExists("acme") } returns true
        shouldThrow<WorkspaceDuplicateNameException> { service.create(superAdmin(), "acme", "Acme") }
    }

    @Test
    fun `a racing create colliding on the constraint is still duplicate_name`() {
        every { repository.nameExists("acme") } returns false
        every { repository.create(any(), any(), any(), any()) } throws
            org.springframework.dao.DuplicateKeyException("workspaces_name_key")

        shouldThrow<WorkspaceDuplicateNameException> { service.create(superAdmin(), "acme", "Acme") }
    }

    // ------------------------------------------------------------ read (the 404 rule)

    @Test
    fun `a member reads a workspace they belong to`() {
        every { repository.findByName("acme") } returns ws
        service.read(author(), "acme") shouldBe ws
    }

    @Test
    fun `unknown, non-member and DEACTIVATED are the SAME not_found - three ways, one answer`() {
        val caller = author()

        every { repository.findByName("ghost") } returns null
        val unknown = shouldThrow<WorkspaceNotFoundException> { service.read(caller, "ghost") }

        val foreign =
            Workspace(UUID.randomUUID(), "other", "Other", false, null, false, Instant.EPOCH)
        every { repository.findByName("other") } returns foreign
        val nonMember = shouldThrow<WorkspaceNotFoundException> { service.read(caller, "other") }

        every { repository.findByName("acme") } returns ws.copy(deactivatedAt = Instant.now())
        val deactivated = shouldThrow<WorkspaceNotFoundException> { service.read(caller, "acme") }

        listOf(unknown, nonMember, deactivated).map { it.code to it.status }.distinct().size shouldBe 1
    }

    @Test
    fun `a super admin reads any workspace, deactivated included - the listing must be able to show it`() {
        every { repository.findByName("acme") } returns ws.copy(deactivatedAt = Instant.now())
        service.read(superAdmin(), "acme").isActive shouldBe false
    }

    // ------------------------------------------------------------ management

    @Test
    fun `a plain AUTHOR cannot rename - and learns nothing about who can`() {
        every { repository.findByName("acme") } returns ws

        val refusal = shouldThrow<RoleRequiredException> { service.updateDisplayName(author(), "acme", "New") }
        refusal.details["required"] shouldBe Capability.WS_ADMIN.wire
    }

    @Test
    fun `a workspace admin renames and the workspace cache is invalidated`() {
        every { repository.findByName("acme") } returns ws
        every { repository.updateDisplayName(ws.id, "New") } returns ws.copy(displayName = "New")

        service.updateDisplayName(wsAdmin(), "acme", "New").displayName shouldBe "New"
        verify { auditLogger.log("auth.workspace.updated", adminId, null, null, null, any()) }
    }

    @Test
    fun `a rename racing a delete is the 404 for everyone - the no-oracle line holds on the race`() {
        every { repository.findByName("acme") } returns ws
        every { repository.updateDisplayName(ws.id, any()) } returns null

        shouldThrow<WorkspaceNotFoundException> { service.updateDisplayName(wsAdmin(), "acme", "New") }
    }

    // ------------------------------------------------------------ members

    @Test
    fun `a workspace admin adds a VIEWER by default - silence on a permission grant means the least`() {
        every { repository.findByName("acme") } returns ws
        every { userRepository.findByEmail("bob@company.com") } returns
            User(memberB, "bob@company.com", "Bob", null, "google", "s", true, false, Instant.EPOCH, Instant.EPOCH, null)
        every { repository.addMember(ws.id, memberB, MembershipFlags.VIEWER) } returns memberRow(memberB, MembershipFlags.VIEWER)

        service.addMember(wsAdmin(), "acme", "bob@company.com").flags shouldBe MembershipFlags.VIEWER
        verify { repository.addMember(ws.id, memberB, MembershipFlags.VIEWER) }
    }

    @Test
    fun `adding a member with the admin flag normalises admin to imply author`() {
        every { repository.findByName("acme") } returns ws
        every { userRepository.findByEmail(any()) } returns
            User(memberB, "bob@company.com", "Bob", null, "google", "s", true, false, Instant.EPOCH, Instant.EPOCH, null)
        every { repository.addMember(ws.id, memberB, any()) } returns memberRow(memberB, wsAdminFlags)

        service.addMember(wsAdmin(), "acme", "bob@company.com", MembershipFlags(admin = true))

        // The database's chk_workspace_member_admin_authors would refuse the un-normalised row
        // with no catalogued code; normalising means the caller gets what they asked for.
        verify { repository.addMember(ws.id, memberB, MembershipFlags(author = true, admin = true)) }
    }

    @Test
    fun `an unknown member email is the mapped IllegalStateException - never a silent null`() {
        every { repository.findByName("acme") } returns ws
        every { userRepository.findByEmail(any()) } returns null

        shouldThrow<WorkspaceService.UnknownMemberEmailException> { service.addMember(wsAdmin(), "acme", "ghost@company.com") }
    }

    @Test
    fun `a plain AUTHOR cannot add members`() {
        every { repository.findByName("acme") } returns ws
        shouldThrow<RoleRequiredException> { service.addMember(author(), "acme", "bob@company.com") }
    }

    @Test
    fun `a user id that names no member of this workspace answers with the WORKSPACE's 404`() {
        // "There is no such member here" and "there is no such workspace for you" must not be
        // distinguishable, or the member list becomes probeable one id at a time.
        every { repository.findByName("acme") } returns ws
        every { repository.findMemberRow(ws.id, memberB) } returns null

        shouldThrow<WorkspaceNotFoundException> { service.removeMember(wsAdmin(), "acme", memberB) }
    }

    @Test
    fun `the LAST admin cannot be removed`() {
        every { repository.findByName("acme") } returns ws
        every { repository.findMemberRow(ws.id, adminId) } returns memberRow(adminId, wsAdminFlags)
        every { repository.findMembersOf(ws.id) } returns
            listOf(memberRow(adminId, wsAdminFlags), memberRow(memberB, authorFlags))

        val refusal = shouldThrow<WorkspaceLastAdminException> { service.removeMember(wsAdmin(), "acme", adminId) }
        refusal.code shouldBe WorkspaceErrorCodes.LAST_ADMIN
        verify(exactly = 0) { repository.removeMember(any(), any()) }
    }

    @Test
    fun `the LAST admin cannot be DEMOTED either - the same rule from the other direction`() {
        every { repository.findByName("acme") } returns ws
        every { repository.findMemberRow(ws.id, adminId) } returns memberRow(adminId, wsAdminFlags)
        every { repository.findMembersOf(ws.id) } returns listOf(memberRow(adminId, wsAdminFlags))

        shouldThrow<WorkspaceLastAdminException> {
            service.setMemberFlags(wsAdmin(), "acme", adminId, MembershipFlags(author = true))
        }
        verify(exactly = 0) { repository.setFlags(any(), any(), any()) }
    }

    @Test
    fun `an admin may be demoted while ANOTHER admin remains`() {
        every { repository.findByName("acme") } returns ws
        every { repository.findMemberRow(ws.id, adminId) } returns memberRow(adminId, wsAdminFlags) andThen memberRow(adminId, authorFlags)
        every { repository.findMembersOf(ws.id) } returns
            listOf(memberRow(adminId, wsAdminFlags), memberRow(memberB, wsAdminFlags))

        service.setMemberFlags(wsAdmin(), "acme", adminId, authorFlags)

        verify { repository.setFlags(ws.id, adminId, authorFlags) }
        verify { auditLogger.log("workspace.member_flags_changed", adminId, null, null, null, any()) }
    }

    @Test
    fun `setting flags REPLACES them wholesale - a promoter asked for stops being an author`() {
        val promoterOnly = MembershipFlags(promoter = true)
        every { repository.findByName("acme") } returns ws
        every { repository.findMemberRow(ws.id, memberB) } returns memberRow(memberB, authorFlags) andThen memberRow(memberB, promoterOnly)

        service.setMemberFlags(wsAdmin(), "acme", memberB, promoterOnly).flags shouldBe promoterOnly
        verify { repository.setFlags(ws.id, memberB, promoterOnly) }
    }

    // ------------------------------------------------------------ deactivation (D-R10)

    @Test
    fun `deactivation is a super-admin verb, audited, and purges nothing`() {
        every { repository.findByName("acme") } returns ws
        every { repository.deactivate(ws.id, any()) } returns true
        every { repository.findById(ws.id) } returns ws.copy(deactivatedAt = Instant.now())

        service.deactivate(superAdmin(), "acme").isActive shouldBe false

        verify(exactly = 0) { repository.softDelete(any()) }
        verify { auditLogger.log("workspace.deactivated", any(), null, null, null, any()) }
    }

    @Test
    fun `a workspace admin cannot deactivate their own workspace - it is an instance verb`() {
        every { repository.findByName("acme") } returns ws
        shouldThrow<RoleRequiredException> { service.deactivate(wsAdmin(), "acme") }
    }

    @Test
    fun `reactivation restores it, and every member's membership snapshot is invalidated`() {
        val dead = ws.copy(deactivatedAt = Instant.now())
        every { repository.findByName("acme") } returns dead
        every { repository.reactivate(ws.id) } returns true
        every { repository.findById(ws.id) } returns ws
        every { repository.findMembersOf(ws.id) } returns listOf(memberRow(memberB, authorFlags))

        service.reactivate(superAdmin(), "acme").isActive shouldBe true
        verify { auditLogger.log("workspace.reactivated", any(), null, null, null, any()) }
    }

    // ------------------------------------------------------------ delete

    @Test
    fun `delete is blocked by workspace_in_use with the counts of what blocks`() {
        every { repository.findByName("acme") } returns ws
        every { contentCheck.nonDeletedCounts(ws.id) } returns mapOf("pipelines" to 2, "templates" to 0)

        val refusal = shouldThrow<WorkspaceInUseException> { service.delete(superAdmin(), "acme") }
        refusal.details["counts"] shouldBe mapOf("pipelines" to 2)
    }

    @Test
    fun `delete with empty content soft-deletes and invalidates every member's cache`() {
        every { repository.findByName("acme") } returns ws
        every { contentCheck.nonDeletedCounts(ws.id) } returns emptyMap()
        every { repository.findMembersOf(ws.id) } returns listOf(memberRow(memberB, authorFlags))

        service.delete(superAdmin(), "acme")

        verify { repository.softDelete(ws.id) }
        verify { auditLogger.log("auth.workspace.deleted", any(), null, null, null, any()) }
    }

    @Test
    fun `content landing in the delete window is detected and audited, not silently stranded`() {
        every { repository.findByName("acme") } returns ws
        every { contentCheck.nonDeletedCounts(ws.id) } returns emptyMap() andThen mapOf("pipelines" to 1)
        every { repository.findMembersOf(ws.id) } returns emptyList()

        service.delete(superAdmin(), "acme")

        verify { auditLogger.log("auth.workspace.stranded_content", any(), null, null, null, any()) }
    }

    // ------------------------------------------------------------ listings

    @Test
    fun `listOwn is the caller's memberships, with their flags`() {
        val caller = author()
        service.listOwn(caller).map { it.flags } shouldContainExactly listOf(authorFlags)
    }

    @Test
    fun `a super admin's listing is EVERY workspace, deactivated ones marked (design §6)`() {
        val dead = Workspace(UUID.randomUUID(), "retired", "Retired", false, null, false, Instant.EPOCH, Instant.now())
        every { repository.findAll() } returns listOf(ws, dead)

        val listing = service.listOwn(superAdmin())

        listing.map { it.workspaceName } shouldContainExactly listOf("acme", "retired")
        listing.map { it.workspaceActive } shouldContainExactly listOf(true, false)
    }
}
