package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [DemoWorkspaceSeeder] — the workspace the product ships (D-R11) and the rule that keeps it
 * from being unturnoffable (O-3).
 *
 * The content-seeder double is a recording lambda rather than a strict mock, for the reason
 * MISTAKES.md gives: for a "you must call me" collaborator a strict mock is green precisely
 * when the call is MISSING, and it hides a doubled call just as effectively.
 */
class DemoWorkspaceSeedingTest {
    private val workspaces = mockk<WorkspaceRepository>(relaxed = true)
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val systemActorId = UUID.randomUUID()
    private val userService =
        mockk<UserService> {
            every { systemActor() } returns user(systemActorId, "system@system.invalid")
        }

    private val seeded = mutableListOf<Pair<UUID, UUID>>()
    private val seeder =
        DemoWorkspaceSeeder(
            workspaces,
            auditLogger,
            userService,
            // Named: `instanceDatasourceGrants` is now the last parameter, so a trailing
            // lambda binds to THAT fun interface, not to the content seeder.
            contentSeeder = { workspaceId, userId -> seeded += workspaceId to userId },
        )

    /** The well-known id the seeder pins (metadata-db §4.11 convention). */
    private val demoId = DemoWorkspaceSeeder.DEMO_WORKSPACE_ID

    @Test
    fun `a fresh deployment creates demo and seeds the example content once`() {
        every { workspaces.findByName("demo") } returns null
        every { workspaces.createSystemWorkspace(demoId, "demo", "Demo") } returns demo()

        seeder.ensureDemoWorkspace()?.name shouldBe "demo"

        seeded shouldBe listOf(demoId to systemActorId)
    }

    @Test
    fun `a restart finds demo and seeds NOTHING - the content import is a creation-time act`() {
        every { workspaces.findByName("demo") } returns demo()

        seeder.ensureDemoWorkspace()?.name shouldBe "demo"

        seeded shouldBe emptyList()
        verify(exactly = 0) { workspaces.createSystemWorkspace(any(), any(), any()) }
    }

    @Test
    fun `O-3 - a DEACTIVATED demo is left exactly as it is, never recreated`() {
        // The check is "does a row named demo exist", not "is there an ACTIVE one". A seeder
        // that re-creates what an operator deliberately turned off is a seeder that cannot be
        // turned off — which is the whole of O-3.
        every { workspaces.findByName("demo") } returns demo(deactivatedAt = Instant.now())

        val found = seeder.ensureDemoWorkspace()

        found?.isActive shouldBe false
        verify(exactly = 0) { workspaces.createSystemWorkspace(any(), any(), any()) }
        seeded shouldBe emptyList()
    }

    @Test
    fun `two replicas racing a fresh database settle on ONE demo`() {
        every { workspaces.findByName("demo") } returnsMany listOf(null, demo())
        every { workspaces.createSystemWorkspace(demoId, "demo", "Demo") } throws
            org.springframework.dao.DuplicateKeyException("workspaces_name_key")

        seeder.ensureDemoWorkspace()?.name shouldBe "demo"

        // The loser re-reads the winner's row and does NOT seed content into it: the winner is
        // already doing that, and a second import collides on uq_pipelines_workspace_name.
        seeded shouldBe emptyList()
    }

    @Test
    fun `a content-seeding failure is NOT swallowed - it fails the boot loudly`() {
        every { workspaces.findByName("demo") } returns null
        every { workspaces.createSystemWorkspace(demoId, "demo", "Demo") } returns demo()
        val failing =
            DemoWorkspaceSeeder(workspaces, auditLogger, userService) { _, _ -> error("examples file is broken") }

        shouldThrow<IllegalStateException> { failing.ensureDemoWorkspace() }
    }

    @Test
    fun `first login with no membership joins demo as a VIEWER (D-R11)`() {
        val alice = UUID.randomUUID()
        every { workspaces.findByName("demo") } returns demo()
        every { workspaces.membershipsOf(alice) } returns emptyList()

        val context = seeder.joinDemoIfUnaffiliated(alice)

        context?.name shouldBe "demo"
        context?.flags shouldBe MembershipFlags.VIEWER
        verify { workspaces.addMember(demoId, alice, MembershipFlags.VIEWER) }
    }

    @Test
    fun `a user REMOVED from demo on purpose is not re-added by their next login`() {
        val bob = UUID.randomUUID()
        every { workspaces.findByName("demo") } returns demo()
        // Bob holds a membership somewhere — the join fires only for a user with NONE, which
        // is what stops the login path from undoing a deliberate removal.
        every { workspaces.membershipsOf(bob) } returns
            listOf(WorkspaceMembership(UUID.randomUUID(), "acme", MembershipFlags(author = true), Instant.EPOCH))

        seeder.joinDemoIfUnaffiliated(bob).shouldBeNull()
        verify(exactly = 0) { workspaces.addMember(any(), any(), any()) }
    }

    @Test
    fun `a deactivated demo has nobody to join - the caller returns the no-workspace state`() {
        val alice = UUID.randomUUID()
        every { workspaces.findByName("demo") } returns demo(deactivatedAt = Instant.now())

        seeder.joinDemoIfUnaffiliated(alice).shouldBeNull()
        verify(exactly = 0) { workspaces.addMember(any(), any(), any()) }
    }

    private fun demo(deactivatedAt: Instant? = null) =
        Workspace(
            id = demoId,
            name = "demo",
            displayName = "Demo",
            isPersonal = false,
            createdBy = null,
            isDeleted = false,
            createdAt = Instant.EPOCH,
            deactivatedAt = deactivatedAt,
        )

    private fun user(
        id: UUID,
        email: String,
    ) = User(id, email, "System", null, "system", "system", true, false, Instant.EPOCH, Instant.EPOCH, null)
}
