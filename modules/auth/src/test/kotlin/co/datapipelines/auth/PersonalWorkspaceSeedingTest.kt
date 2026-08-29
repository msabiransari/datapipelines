package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The D9 hook's contract at the `auth` seam (sample-data design §6.1): it fires exactly once, on
 * a workspace that was just created, and a failure is **not** swallowed.
 *
 * The seeder double here is a recording lambda rather than a strict mock, for the reason
 * MISTAKES.md gives: for a "you must call me" collaborator a strict mock is green precisely when
 * the call is missing, and it would hide a doubled call just as effectively.
 */
class PersonalWorkspaceSeedingTest {
    private val alice =
        User(
            id = UUID.randomUUID(),
            email = "alice@company.com",
            displayName = "Alice",
            provider = "google",
            providerSubject = "sub-1",
            isActive = true,
            isAdmin = false,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

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

    private fun service(
        repository: WorkspaceRepository,
        seeder: PersonalWorkspaceSeeder?,
    ): WorkspaceService =
        WorkspaceService(
            repository,
            mockk(relaxed = true),
            AuthCache(AuthProperties()),
            WorkspacesProperties(provisioningMode = WorkspaceProvisioningMode.AUTO_PER_USER),
            null,
            mockk(relaxed = true),
            seeder,
        )

    @Test
    fun `seeding fires once, with the new workspace and its owner`() {
        val repository = freshRepository()
        val seeded = mutableListOf<Pair<UUID, UUID>>()

        val created =
            service(
                repository,
            ) { workspaceId, userId -> seeded += workspaceId to userId }.ensurePersonalWorkspace(alice, alice.email)

        created.id shouldBe personal.id
        seeded shouldBe listOf(personal.id to alice.id)
    }

    @Test
    fun `seeding does not re-run for a workspace a previous login already provisioned`() {
        val repository =
            mockk<WorkspaceRepository> {
                every { membershipsOf(alice.id) } returns
                    listOf(WorkspaceMembership(personal.id, personal.name, WorkspaceRole.OWNER, Instant.EPOCH))
                every { findById(personal.id) } returns personal
            }
        var calls = 0

        service(repository) { _, _ -> calls++ }.ensurePersonalWorkspace(alice, alice.email)

        calls shouldBe 0
    }

    @Test
    fun `a failing seeder fails provisioning loudly rather than handing out an empty workspace`() {
        val repository = freshRepository()

        val error =
            shouldThrow<IllegalStateException> {
                service(repository) { _, _ -> error("examples.json references a datasource this deployment lacks") }
                    .ensurePersonalWorkspace(alice, alice.email)
            }

        error.message shouldBe "examples.json references a datasource this deployment lacks"
    }

    @Test
    fun `no seeder configured is a legitimate no-op`() {
        service(freshRepository(), null).ensurePersonalWorkspace(alice, alice.email).id shouldBe personal.id
    }

    /** A user with no memberships yet — the first-login shape. */
    private fun freshRepository(): WorkspaceRepository =
        mockk {
            every { membershipsOf(alice.id) } returns emptyList()
            every { nameExists(any()) } returns false
            every { create("alice", "alice", true, alice.id) } returns personal
        }
}
