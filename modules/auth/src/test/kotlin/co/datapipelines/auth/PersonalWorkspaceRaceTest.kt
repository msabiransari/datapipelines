package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import java.time.Instant
import java.util.UUID

/**
 * The first-login name race on the D9 seeding path (048/§C).
 *
 * 036's M5/M6 (`55b6e80`) tolerated the two `UserService` insert races; the personal-workspace
 * allocation right behind them stayed check-then-act — `availablePersonalName` asks `nameExists`
 * and then inserts into a globally unique `workspaces.name`. The loser used to get a raw
 * `DuplicateKeyException` out of its login.
 *
 * Deterministic, like [UserServiceRaceTest]: the repository is mocked to answer "absent" on the
 * pre-check and throw on the insert, which is exactly what two replicas produce. The two
 * interleavings have OPPOSITE right answers, which is why both are pinned — take the winner's
 * workspace when the winner is you, take the next name when it is somebody else.
 */
class PersonalWorkspaceRaceTest {
    private val alice = user("alice@company.com")

    private val personal =
        Workspace(
            id = UUID.randomUUID(),
            name = "alice",
            displayName = "alice",
            isPersonal = true,
            createdBy = alice.id,
            isDeleted = false,
            createdAt = Instant.EPOCH,
        )

    private val seeded = mutableListOf<Pair<UUID, UUID>>()

    private fun service(repository: WorkspaceRepository): WorkspaceService =
        WorkspaceService(
            repository,
            mockk(relaxed = true),
            AuthCache(AuthProperties()),
            WorkspacesProperties(provisioningMode = WorkspaceProvisioningMode.AUTO_PER_USER),
            null,
            mockk(relaxed = true),
            { workspaceId, userId -> seeded += workspaceId to userId },
        )

    @Test
    fun `the same user's two concurrent first logins yield ONE workspace, seeded once`() {
        val membership = WorkspaceMembership(personal.id, personal.name, WorkspaceRole.OWNER, Instant.EPOCH)
        val repository =
            mockk<WorkspaceRepository> {
                // Absent on the pre-check, present once the winner's insert has landed.
                every { membershipsOf(alice.id) } returnsMany listOf(emptyList(), listOf(membership), listOf(membership))
                every { nameExists(any()) } returns false
                every { create(any(), any(), any(), any()) } throws DuplicateKeyException("uq_workspaces_name")
                every { findById(personal.id) } returns personal
            }

        val result = service(repository).ensurePersonalWorkspace(alice, alice.email)

        result shouldBe personal
        // The winner seeds it. Seeding here would import the examples a second time into a
        // workspace that already has them — the loser must not.
        seeded shouldBe emptyList()
    }

    @Test
    fun `two different users racing for one name give the loser the NEXT name, not a 500`() {
        val bob = user("alice@other-company.com")
        val bobs =
            Workspace(
                id = UUID.randomUUID(),
                name = "alice-2",
                displayName = "alice-2",
                isPersonal = true,
                createdBy = bob.id,
                isDeleted = false,
                createdAt = Instant.EPOCH,
            )
        val repository =
            mockk<WorkspaceRepository> {
                // Bob never gains a membership from Alice's win — the name is hers, the
                // workspace is not his, and re-reading finds nothing.
                every { membershipsOf(bob.id) } returns emptyList()
                every { nameExists("alice") } returnsMany listOf(false, true)
                every { nameExists("alice-2") } returns false
                every { create("alice", any(), any(), bob.id) } throws DuplicateKeyException("uq_workspaces_name")
                every { create("alice-2", "alice-2", true, bob.id) } returns bobs
            }

        val result = service(repository).ensurePersonalWorkspace(bob, bob.email)

        result shouldBe bobs
        seeded shouldBe listOf(bobs.id to bob.id)
    }

    @Test
    fun `a repository that always collides fails after a bounded number of attempts`() {
        val repository =
            mockk<WorkspaceRepository> {
                every { membershipsOf(alice.id) } returns emptyList()
                every { nameExists(any()) } returns false
                every { create(any(), any(), any(), any()) } throws DuplicateKeyException("uq_workspaces_name")
            }

        shouldThrow<IllegalStateException> { service(repository).ensurePersonalWorkspace(alice, alice.email) }

        verify(exactly = 3) { repository.create(any(), any(), any(), alice.id) }
        seeded shouldBe emptyList()
    }

    private fun user(email: String) =
        User(
            id = UUID.randomUUID(),
            email = email,
            displayName = email.substringBefore('@'),
            provider = "google",
            providerSubject = email,
            isActive = true,
            isAdmin = false,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
}
