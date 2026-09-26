package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * **The system identity holds EXACTLY its fixed set** (#9 R2, scheduler design revision §4) — the
 * walk-free guard the lane's gates name, because `RoleWalkE2eTest` never sees this principal (it
 * holds no credential and is no column of auth.md §7.6).
 *
 * The expectation is a LITERAL here, never read from `RolePermissions.SYSTEM_ACTOR`: a test whose
 * oracle is the code under test moves with the code and stays green (MISTAKES.md, "the oracle is the
 * thing under test"). And the falsification is kept as a test: a resolver whose system arm grants
 * ONE more permission is caught by the same measurement.
 */
class SystemActorPrincipalTest {
    @AfterEach
    fun restore() = PermissionResolution.install(RolePermissionsResolver)

    @Test
    fun `the system identity holds exactly pipeline execute, pipeline read and execution read - in any workspace`() {
        heldBy(principal(WORKSPACE_A)) shouldBe EXPECTED
        heldBy(principal(WORKSPACE_B)) shouldBe EXPECTED
    }

    @Test
    fun `it is never an admin, never an author, never a super admin, and holds no instance permission`() {
        val p = principal(WORKSPACE_A)
        p.isSuperAdmin shouldBe false
        p.isWorkspaceAdmin shouldBe false
        p.isAuthor shouldBe false
        p.isPromoter shouldBe false
        p.isLensed shouldBe false
        RolePermissions.INSTANCE.none { p.holds(it) } shouldBe true
        p.heldRole shouldBe WorkspaceContext.SYSTEM_ACTOR_WIRE
        p.authMethod shouldBe AuthMethod.SYSTEM
    }

    @Test
    fun `only the system row may be made into this principal`() {
        shouldThrow<IllegalArgumentException> {
            SystemActorPrincipals.forWorkspace(systemRow().copy(kind = UserKind.HUMAN), WORKSPACE_A, "a")
        }
        shouldThrow<IllegalArgumentException> {
            SystemActorPrincipals.forWorkspace(systemRow().copy(kind = UserKind.SERVICE), WORKSPACE_A, "a")
        }
    }

    @Test
    fun `falsified - a resolver whose system arm grants one more permission is caught by the same measurement`() {
        PermissionResolution.install(WidenedSystemArm)
        val widened = heldBy(principal(WORKSPACE_A))
        widened shouldNotBe EXPECTED
        (widened - EXPECTED) shouldBe setOf(Permission.SCHEDULE_CREATE)
    }

    @Test
    fun `a member context never reaches the system arm - the role column still answers for a person`() {
        PermissionResolution.install(WidenedSystemArm)
        val viewer = WorkspaceContext(id = WORKSPACE_A, name = "a", role = WorkspaceRole.VIEWER)
        viewer.permits(Permission.SCHEDULE_CREATE) shouldBe false
        viewer.permits(Permission.SCHEDULE_READ) shouldBe true
    }

    @Test
    fun `the schedule rows follow R8 and L2 - authors and admins manage, every member reads, the promoter reads through the lens`() {
        val manage =
            setOf(
                Permission.SCHEDULE_CREATE,
                Permission.SCHEDULE_UPDATE,
                Permission.SCHEDULE_PAUSE,
                Permission.SCHEDULE_DELETE,
                Permission.SCHEDULE_RUN,
            )
        manage.forEach { permission ->
            permission.roles shouldBe setOf(WorkspaceRole.AUTHOR, WorkspaceRole.WORKSPACE_ADMIN)
            permission.satisfiedBy(null, superAdmin = true) shouldBe true
        }
        Permission.SCHEDULE_READ.roles shouldBe WorkspaceRole.entries.toSet()
        // No key role reaches schedule management beyond its member column; the transport keys none.
        RolePermissions.of(KeyRole.API_CALLER).none { it in manage + Permission.SCHEDULE_READ } shouldBe true
        RolePermissions.of(KeyRole.PROMOTION_RECEIVER).none { it in manage + Permission.SCHEDULE_READ } shouldBe true
    }

    private fun heldBy(principal: AuthenticatedPrincipal): Set<Permission> = Permission.entries.filter(principal::holds).toSet()

    private fun principal(workspace: UUID) = SystemActorPrincipals.forWorkspace(systemRow(), workspace, "ws-$workspace")

    private fun systemRow() =
        User(
            id = SYSTEM_ID,
            email = UserService.SYSTEM_ACTOR_EMAIL,
            displayName = UserService.SYSTEM_ACTOR_DISPLAY_NAME,
            provider = UserService.SYSTEM_PROVIDER,
            providerSubject = UserService.SYSTEM_ACTOR_SUBJECT,
            isActive = true,
            isAdmin = false,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            kind = UserKind.SYSTEM,
        )

    /** A test resolver: the production answer everywhere, and ONE extra grant on the system arm. */
    private object WidenedSystemArm : PermissionResolver {
        override fun holds(
            workspaceId: UUID?,
            role: WorkspaceRole?,
            superAdmin: Boolean,
            permission: Permission,
        ): Boolean = RolePermissionsResolver.holds(workspaceId, role, superAdmin, permission)

        override fun holdsAsSystemActor(
            workspaceId: UUID?,
            permission: Permission,
        ): Boolean = super.holdsAsSystemActor(workspaceId, permission) || permission == Permission.SCHEDULE_CREATE
    }

    private companion object {
        /** THE fixed set, written out (R2: execute pipelines, and the reads the reconciler needs). */
        val EXPECTED = setOf(Permission.PIPELINE_EXECUTE, Permission.PIPELINE_READ, Permission.EXECUTION_READ)
        val SYSTEM_ID: UUID = UUID.fromString("5a570000-0000-0000-0000-000000000001")
        val WORKSPACE_A: UUID = UUID.fromString("a0000000-0000-0000-0000-00000000000a")
        val WORKSPACE_B: UUID = UUID.fromString("b0000000-0000-0000-0000-00000000000b")
    }
}
