package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The pinned-workspace rule for API-key principals (auth.md §5.6, design D3; the 025
 * review's blocking finding): a key's workspace is fixed at issuance, so a key may
 * manage ONLY the workspace it is pinned to. Before this rule the four management
 * paths authorized against the user's whole membership set — a key pinned to `acme`
 * renamed, deleted and edited the membership of `globex` when its owner owned both,
 * defeating the pin `WorkspaceResolutionFilter` hard-refuses `DP-Workspace` to protect.
 *
 * The refusal reuses the owner-or-admin check's no-oracle 403: "pinned elsewhere" and
 * "not a member" must stay indistinguishable, or the pin itself becomes an existence
 * oracle. Sessions are untouched — their active workspace is switchable by design —
 * and `create` is exempt: there is no target workspace yet, creation grants the caller
 * ownership of a NEW workspace only, and the `author` floor plus the per-mode refusal
 * are its gates.
 */
class WorkspaceKeyPinTest {
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
    private val memberId = UUID.randomUUID()
    private val acme = workspace("acme", "Acme")
    private val globex = workspace("globex", "Globex")

    private fun workspace(
        name: String,
        displayName: String,
    ) = Workspace(
        UUID.randomUUID(),
        name,
        displayName,
        isPersonal = false,
        createdBy = ownerId,
        isDeleted = false,
        createdAt = Instant.EPOCH,
    )

    /** The user owns BOTH workspaces — the finding's exact shape. */
    private fun stubOwnedWorld() {
        every { repository.membershipsOf(ownerId) } returns
            listOf(
                WorkspaceMembership(acme.id, acme.name, WorkspaceRole.OWNER, Instant.EPOCH),
                WorkspaceMembership(globex.id, globex.name, WorkspaceRole.OWNER, Instant.EPOCH),
            )
        every { repository.findByName("acme") } returns acme
        every { repository.findByName("globex") } returns globex
        every { repository.findByName("ghost") } returns null
    }

    /** A key minted by the owner, pinned to `acme` (the V4 pin, §7.4). */
    private fun key() =
        AuthenticatedPrincipal(
            ownerId,
            "alice@company.com",
            "Alice",
            Scope.AUTHOR.expand(),
            AuthMethod.API_KEY,
            keyId = "dpk_TESTKEY",
            workspaceName = "acme",
        )

    /** The same user on a session — the control group: no pin exists to honor. */
    private fun session() =
        AuthenticatedPrincipal(
            ownerId,
            "alice@company.com",
            "Alice",
            Scope.AUTHOR.expand(),
            AuthMethod.OIDC,
        )

    @Test
    fun `an api key manages its pinned workspace`() {
        stubOwnedWorld()
        every { repository.updateDisplayName(acme.id, "Renamed") } returns acme.copy(displayName = "Renamed")

        service.updateDisplayName(key(), "acme", "Renamed").displayName shouldBe "Renamed"
    }

    @Test
    fun `an api key pinned to acme cannot rename globex - though the user owns both`() {
        stubOwnedWorld()
        shouldThrow<WorkspaceMembershipRequiredException> { service.updateDisplayName(key(), "globex", "Renamed") }
    }

    @Test
    fun `an api key pinned to acme cannot delete globex`() {
        stubOwnedWorld()
        shouldThrow<WorkspaceMembershipRequiredException> { service.delete(key(), "globex") }
    }

    @Test
    fun `an api key pinned to acme cannot add a member to globex`() {
        stubOwnedWorld()
        shouldThrow<WorkspaceMembershipRequiredException> { service.addMember(key(), "globex", "bob@company.com") }
    }

    @Test
    fun `an api key pinned to acme cannot remove a member from globex`() {
        stubOwnedWorld()
        shouldThrow<WorkspaceMembershipRequiredException> { service.removeMember(key(), "globex", memberId) }
    }

    @Test
    fun `the pin refusal is the no-oracle 403 - pinned elsewhere and not a member are indistinguishable`() {
        stubOwnedWorld()
        // The control refusal: a session principal managing a workspace that does not
        // exist. The pin refusal must be the SAME exception, code and status — a
        // distinct answer would let a key probe which workspace it is pinned to.
        val notAMember = shouldThrow<WorkspaceMembershipRequiredException> { service.updateDisplayName(session(), "ghost", "X") }
        val pinnedElsewhere = shouldThrow<WorkspaceMembershipRequiredException> { service.updateDisplayName(key(), "globex", "X") }
        (pinnedElsewhere.code to pinnedElsewhere.status) shouldBe (notAMember.code to notAMember.status)
    }

    @Test
    fun `a session manages every workspace the user owns - the pin is the credential's, not the user's`() {
        stubOwnedWorld()
        every { repository.updateDisplayName(globex.id, "Renamed") } returns globex.copy(displayName = "Renamed")

        service.updateDisplayName(session(), "globex", "Renamed").displayName shouldBe "Renamed"
    }

    @Test
    fun `create is exempt from the pin - there is no target workspace yet`() {
        // Creation grants the caller ownership of a NEW workspace only; the author floor
        // (§7.6) and the per-mode refusal are its gates, not the pin.
        stubOwnedWorld()
        every { repository.nameExists("newco") } returns false
        every { repository.create("newco", "Newco", false, ownerId) } returns workspace("newco", "Newco")

        service.create(key(), "newco", "Newco").name shouldBe "newco"
    }
}
