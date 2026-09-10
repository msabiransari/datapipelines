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
 * RBAC round 1 kept the rule and moved it: it now sits at `read`/`contextFor`, the ONE
 * resolution every read and every management verb passes through, so a verb added later
 * inherits it instead of needing to be added to a guard list. Its refusal became the D-R5
 * 404, like every other unreachable workspace — "pinned elsewhere" and "does not exist"
 * stay indistinguishable, or the pin itself is an oracle. Sessions are untouched (their
 * active workspace is switchable by design), and `create` is exempt because there is no
 * EXISTING workspace to overreach into — its own gate is `super_admin`.
 *
 * A super admin's key is NOT exempt: the pin is a property of the credential, not of the
 * person, and a leaked key must not become a skeleton key because its owner is privileged.
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
            null,
            auditLogger,
            contentCheck,
        )

    private val ownerId = UUID.randomUUID()
    private val memberId = UUID.randomUUID()
    private val wsAdminFlags = MembershipFlags(author = true, admin = true)
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
                WorkspaceMembership(acme.id, acme.name, wsAdminFlags, Instant.EPOCH),
                WorkspaceMembership(globex.id, globex.name, wsAdminFlags, Instant.EPOCH),
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
            setOf(Scope.AUTHOR),
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
            // D-R1: a session carries no scopes; its capability is the membership.
            emptySet(),
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
        shouldThrow<WorkspaceNotFoundException> { service.updateDisplayName(key(), "globex", "Renamed") }
    }

    @Test
    fun `an api key pinned to acme cannot delete globex`() {
        stubOwnedWorld()
        shouldThrow<WorkspaceNotFoundException> { service.delete(key(), "globex") }
    }

    @Test
    fun `an api key pinned to acme cannot add a member to globex`() {
        stubOwnedWorld()
        shouldThrow<WorkspaceNotFoundException> { service.addMember(key(), "globex", "bob@company.com") }
    }

    @Test
    fun `an api key pinned to acme cannot remove a member from globex`() {
        stubOwnedWorld()
        shouldThrow<WorkspaceNotFoundException> { service.removeMember(key(), "globex", memberId) }
    }

    @Test
    fun `the pin refusal is the D-R5 404 - pinned elsewhere and does not exist are indistinguishable`() {
        stubOwnedWorld()
        // The control refusal: a session principal managing a workspace that does not
        // exist. The pin refusal must be the SAME exception, code and status — a distinct
        // answer would let a key enumerate the deployment's workspaces one name at a time.
        val notAMember = shouldThrow<WorkspaceNotFoundException> { service.updateDisplayName(session(), "ghost", "X") }
        val pinnedElsewhere = shouldThrow<WorkspaceNotFoundException> { service.updateDisplayName(key(), "globex", "X") }
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

        // …and its gate is `super_admin` since D-R11, which is what this key's owner is here.
        service.create(key().copy(superAdmin = true), "newco", "Newco").name shouldBe "newco"
    }
}
