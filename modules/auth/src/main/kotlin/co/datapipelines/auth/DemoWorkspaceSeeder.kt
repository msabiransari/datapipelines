package co.datapipelines.auth

import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The out-of-the-box workspace (D-R11): `demo`, seeded at first boot, and the workspace every
 * user with no membership becomes a VIEWER of on first login.
 *
 * V23 inserts the row for a fresh database. This seeder exists for the two cases DDL cannot
 * cover: a deployment that migrated past V23 with the row already removed, and — the one that
 * matters — **idempotence across restarts** (O-3).
 *
 * ## O-3: a deactivated `demo` is never recreated
 * `demo` is deactivated like any other workspace, and a seeder that re-creates what an operator
 * deliberately turned off is a seeder that cannot be turned off. So the check is "does a row
 * named `demo` exist", NOT "is there an active one": a deactivated row is present, the seeder
 * does nothing, and first-login viewers land on the no-workspace state instead.
 *
 * Nothing here is configurable. The owner ruled the BEHAVIOUR, not a knob (design §5):
 * "the only workspace which comes out of the box is demo and every user who logs in using
 * social will be a member of demo workspace as viewer."
 */
class DemoWorkspaceSeeder(
    private val workspaceRepository: WorkspaceRepository,
    private val auditLogger: AuditLogger,
) {
    private val log = LoggerFactory.getLogger(DemoWorkspaceSeeder::class.java)

    /**
     * Ensures the `demo` row exists, or leaves it exactly as it is. Returns the row when one
     * exists after this call — active or not — and null only when creation lost a race it then
     * could not re-read, which is a fault worth seeing rather than a silent null.
     */
    fun ensureDemoWorkspace(): Workspace? {
        workspaceRepository.findByName(DEMO_WORKSPACE)?.let { existing ->
            if (!existing.isActive) {
                log.info("Demo workspace exists and is deactivated; leaving it alone (O-3)")
            }
            return existing
        }
        return create()
    }

    /**
     * Makes [userId] a VIEWER of `demo` when they hold no membership at all (D-R11, first
     * login). Returns the context to stamp, or null when there is nothing to join — no `demo`
     * row, or a deactivated one, in which case the caller returns the "no workspace" state and
     * round 2 draws the page for it.
     *
     * Deliberately conditional on holding NO membership: a user removed from `demo` on purpose
     * must not be re-added by their next login, which is the same rule O-3 states for the
     * workspace itself.
     */
    fun joinDemoIfUnaffiliated(userId: UUID): WorkspaceContext? {
        val demo = workspaceRepository.findByName(DEMO_WORKSPACE)?.takeIf { it.isActive } ?: return null
        if (workspaceRepository.membershipsOf(userId).isNotEmpty()) return null
        workspaceRepository.addMember(demo.id, userId, MembershipFlags.VIEWER)
        auditLogger.log(
            event = "workspace.member_added",
            userId = userId,
            details =
                mapOf(
                    "workspace" to demo.name,
                    "member_user_id" to userId.toString(),
                    "flags" to emptyList<String>(),
                    "reason" to "first_login_demo_viewer",
                ),
        )
        log.info("First login with no membership: user_id={} joined '{}' as viewer", userId, demo.name)
        return WorkspaceContext(demo.id, demo.name, MembershipFlags.VIEWER)
    }

    /**
     * Creates `demo` with no creator (`created_by` NULL — R1's system-provisioned convention)
     * and no member. It is not [WorkspaceRepository.create], which inserts a creator membership
     * in the same CTE: there is no human to make an admin of a workspace the product ships, and
     * inventing one would put a real user in a role nobody granted them.
     */
    private fun create(): Workspace? =
        try {
            workspaceRepository.createSystemWorkspace(DEMO_WORKSPACE, DEMO_DISPLAY_NAME).also {
                auditLogger.log(event = "auth.workspace.created", details = mapOf("workspace" to DEMO_WORKSPACE, "actor" to "system"))
                log.info("Seeded the '{}' workspace", DEMO_WORKSPACE)
            }
        } catch (_: org.springframework.dao.DuplicateKeyException) {
            // Two replicas racing a fresh database: the loser re-reads the winner's row, the
            // same catch-and-reread the bootstrap actor and the system actor use (ARCH-AUDIT M5).
            workspaceRepository.findByName(DEMO_WORKSPACE)
        }

    companion object {
        /** D-R11 — the one workspace the product ships. Not configurable; the owner ruled the behaviour. */
        const val DEMO_WORKSPACE = "demo"
        const val DEMO_DISPLAY_NAME = "Demo"
    }
}
