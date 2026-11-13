package co.datapipelines.auth

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * #786 B.4 — the premise `PipelinePurgePinGuard` relies on when it names every API-version and schedule pinner in
 * `pipeline.version.pinned` (record §14, A39; brief §Security: "a hidden pinner is reported with the native safe
 * protected-reference wording, never its name"). The guard needs no redaction only because a hidden pinner cannot
 * reach it, for two reasons:
 *
 * - every role that holds a verb reaching the guard (`pipeline.version.manage`, `pipeline.delete`) also reads API
 *   versions and schedules (`endpoint.read`, `schedule.read`);
 * - none of those roles is lensed. `AuthenticatedPrincipal.isLensed` is the PROMOTER role, as a member or as a key
 *   (pinned by `PromoterLensPredicateTest`), and the promoter is the only role whose reads are narrowed.
 *
 * If a future matrix change gives a lensed or non-reading role one of those verbs, this test goes red. That role
 * could then see a pinner's name it may not read, and the guard would need the protected-reference wording.
 */
class PinRefusalVisibilityTest {
    @Test
    fun `every role that can reach the pipeline pin refusal reads API versions and schedules and is not lensed`() {
        val memberHolders = WorkspaceRole.entries.filter { role -> REACHING.any { it in RolePermissions.of(role) } }
        val keyHolders = KeyRole.entries.filter { role -> REACHING.any { it in RolePermissions.of(role) } }

        withClue("non-vacuity: the author and the workspace admin discard and purge pipeline versions") {
            memberHolders shouldContainAll listOf(WorkspaceRole.AUTHOR, WorkspaceRole.WORKSPACE_ADMIN)
        }
        withClue("the lensed member role reaches the refusal") { memberHolders shouldNotContain WorkspaceRole.PROMOTER }
        withClue("the lensed key role reaches the refusal") { keyHolders shouldNotContain KeyRole.PROMOTER }
        memberHolders.forEach { role ->
            withClue("member role $role reaches the refusal") { RolePermissions.of(role) shouldContainAll PINNER_READS }
        }
        keyHolders.forEach { role ->
            withClue("key role $role reaches the refusal") { RolePermissions.of(role) shouldContainAll PINNER_READS }
        }
        withClue("super admin") {
            RolePermissions.SUPER_ADMIN shouldContainAll REACHING
            RolePermissions.SUPER_ADMIN shouldContainAll PINNER_READS
        }
    }

    private companion object {
        val REACHING = setOf(Permission.PIPELINE_VERSION_MANAGE, Permission.PIPELINE_DELETE)
        val PINNER_READS = setOf(Permission.ENDPOINT_READ, Permission.SCHEDULE_READ)
    }
}
