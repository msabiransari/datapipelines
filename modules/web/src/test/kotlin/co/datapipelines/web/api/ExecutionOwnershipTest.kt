package co.datapipelines.web.api

import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.KeyRole
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.executor.ExecutedByKeyKind
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * D11's own-or-all rule as the two catalog permissions that lift it (#215 A.3): another user's
 * run is visible with `execution.read_all` and cancellable with `execution.cancel_all` — the
 * workspace admin's and the super admin's — and with neither, only the caller's OWN run is.
 * An endpoint key is never the owner of anything (§7.7).
 *
 * These two predicates are what every REST and UI execution path asks (`visibleTo` on the
 * reads, `cancellableBy` on the cancels), so weakening either check to a permission every
 * member holds turns a row here red by role.
 */
class ExecutionOwnershipTest {
    private val workspaceId = UUID.randomUUID()
    private val me = UUID.randomUUID()
    private val someoneElse = UUID.randomUUID()

    @Test
    fun `a member sees and cancels only their own run - an admin any run in the workspace`() {
        val expectations =
            mapOf(
                WorkspaceRole.VIEWER to false,
                WorkspaceRole.AUTHOR to false,
                WorkspaceRole.PROMOTER to false,
                WorkspaceRole.WORKSPACE_ADMIN to true,
            )
        expectations.forEach { (role, seesOthers) ->
            val principal = session(role)
            withClue(role.wire) {
                run(executedBy = me).visibleTo(principal) shouldBe true
                run(executedBy = me).cancellableBy(principal) shouldBe true
                run(executedBy = someoneElse).visibleTo(principal) shouldBe seesOthers
                run(executedBy = someoneElse).cancellableBy(principal) shouldBe seesOthers
            }
        }
    }

    @Test
    fun `a super admin sees and cancels any run - the implicit membership carries both _all permissions`() {
        val superAdmin =
            session(WorkspaceRole.VIEWER).copy(
                superAdmin = true,
                workspace = WorkspaceContext.superAdminOver(workspaceId, "acme", explicitRole = null),
            )

        run(executedBy = someoneElse).visibleTo(superAdmin) shouldBe true
        run(executedBy = someoneElse).cancellableBy(superAdmin) shouldBe true
    }

    /**
     * #215 A.5: an endpoint key acts as its OWN identity, so it owns exactly the runs stamped with
     * that identity — it reads them (`execution.result.read` own) and cancels nothing. A run its
     * CREATOR started is not the key's, whoever the creator is; and the creator does not see the
     * key's run as their own (it is visible to them only through `execution.read_all`).
     */
    @Test
    fun `an endpoint key owns the runs its identity executed - never its creator's, and cancels nothing`() {
        val identity = UUID.randomUUID()
        val endpointKey =
            AuthenticatedPrincipal(
                userId = identity,
                email = "dpk_endpoint@keys.invalid",
                displayName = "ci",
                authMethod = AuthMethod.API_KEY,
                keyId = "dpk_ENDPOINT",
                workspace = WorkspaceContext(workspaceId, "acme", WorkspaceRole.VIEWER),
                keyKind = ApiKeyKind.ENDPOINT,
                keyRole = KeyRole.API_CALLER,
            )
        val keysRun = run(executedBy = identity).copy(executedByKeyKind = ExecutedByKeyKind.ENDPOINT)

        keysRun.visibleTo(endpointKey) shouldBe true
        keysRun.cancellableBy(endpointKey) shouldBe false
        run(executedBy = me).visibleTo(endpointKey) shouldBe false
        // The creator is not the key: the key's run is not the creator's own…
        keysRun.visibleTo(session(WorkspaceRole.AUTHOR)) shouldBe false
        // …and a workspace admin sees it through `execution.read_all`, as every run of the workspace.
        keysRun.visibleTo(session(WorkspaceRole.WORKSPACE_ADMIN)) shouldBe true
    }

    private fun session(role: WorkspaceRole) =
        AuthenticatedPrincipal(
            userId = me,
            email = "me@company.com",
            displayName = "Me",
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext(workspaceId, "acme", role),
        )

    private fun run(executedBy: UUID) =
        ExecutionRecord(
            executionId = UUID.randomUUID(),
            pipelineId = UUID.randomUUID(),
            pipelineVersion = 1,
            status = ExecutionStatus.RUNNING,
            parametersJson = "{}",
            executedBy = executedBy,
            triggeredVia = ExecutionTrigger.REST,
            startedAt = Instant.parse("2026-09-24T09:00:00Z"),
        )
}
