package co.datapipelines.application

import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.KeyRole
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Effective authority, independent of user identity and key-creator membership (keys v2 A13/A14). */
class LakeAppendAuthorizationTest {
    private val identity = UUID.randomUUID()
    private val workspace = UUID.randomUUID()
    private val at = Instant.parse("2026-11-05T00:00:00Z")

    @Test
    fun `only author and workspace admin member roles grant append and super admin also grants`() {
        WorkspaceRole.entries.forEach { role ->
            val principal = principal(role)
            if (role in setOf(WorkspaceRole.AUTHOR, WorkspaceRole.WORKSPACE_ADMIN)) {
                val grant = requireNotNull(LakeAppendAuthorization.grant(principal, writesManagedLake = true, at))
                grant.grantedBy shouldBe identity
                grant.principalKind shouldBe "session"
                grant.at shouldBe at
            } else {
                shouldThrow<DatapipelinesException> { LakeAppendAuthorization.grant(principal, true, at) }
                    .code shouldBe PipelineErrorCodes.Lake.APPEND_FORBIDDEN
            }
        }
        requireNotNull(LakeAppendAuthorization.grant(principal(superAdmin = true), true, at)).grantedBy shouldBe identity
    }

    @Test
    fun `key roles decide even when the surrounding member is viewer or super admin`() {
        KeyRole.entries.forEach { role ->
            val principal = principal(WorkspaceRole.VIEWER, keyRole = role, superAdmin = true)
            if (role in setOf(KeyRole.AUTHOR, KeyRole.WORKSPACE_ADMIN)) {
                requireNotNull(LakeAppendAuthorization.grant(principal, true, at)).principalKind shouldBe "mcp"
            } else {
                shouldThrow<DatapipelinesException> { LakeAppendAuthorization.grant(principal, true, at) }
                    .code shouldBe PipelineErrorCodes.Lake.APPEND_FORBIDDEN
            }
        }
    }

    @Test
    fun `read-only execution carries no append grant for any member role`() {
        WorkspaceRole.entries.forEach { role -> LakeAppendAuthorization.grant(principal(role), false, at) shouldBe null }
        LakeAppendAuthorization.grant(principal().copy(workspace = null), false, at) shouldBe null
    }

    @Test
    fun `system authority is its fixed set and not its user id`() {
        val system = principal().copy(authMethod = AuthMethod.SYSTEM, workspace = WorkspaceContext(workspace, "test", systemActor = true))
        requireNotNull(LakeAppendAuthorization.grant(system, true, at)).principalKind shouldBe "system"
        shouldThrow<DatapipelinesException> { LakeAppendAuthorization.grant(principal().copy(userId = system.userId), true, at) }
    }

    private fun principal(
        role: WorkspaceRole = WorkspaceRole.VIEWER,
        keyRole: KeyRole? = null,
        superAdmin: Boolean = false,
    ): AuthenticatedPrincipal =
        AuthenticatedPrincipal(
            userId = identity,
            email = "principal799@example.com",
            displayName = "Principal799",
            authMethod = if (keyRole == null) AuthMethod.OIDC else AuthMethod.API_KEY,
            workspace = WorkspaceContext(workspace, "test", role, superAdmin = superAdmin),
            superAdmin = superAdmin,
            keyRole = keyRole,
            keyKind =
                when (keyRole) {
                    KeyRole.AUTHOR, KeyRole.PROMOTER, KeyRole.WORKSPACE_ADMIN -> ApiKeyKind.MCP
                    KeyRole.API_CALLER -> ApiKeyKind.ENDPOINT
                    KeyRole.PROMOTION_RECEIVER -> ApiKeyKind.SERVER
                    KeyRole.DASHBOARD_VIEWER -> ApiKeyKind.DASHBOARD
                    null -> null
                },
        )
}
