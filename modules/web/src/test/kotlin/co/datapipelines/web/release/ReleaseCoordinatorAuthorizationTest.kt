package co.datapipelines.web.release

import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuthErrorCodes
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.KeyRole
import co.datapipelines.auth.Permission
import co.datapipelines.auth.PermissionResolver
import co.datapipelines.auth.PermissionResolverInstallation
import co.datapipelines.auth.RolePermissionsResolver
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleasePlanRequest
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * Record §5 (FROZEN): a cascade needs the caller's native `*.release` for the root AND for every version it flips — a
 * parent's release is never delegated authority over another family. Judged by the production
 * [MatrixReleaseAuthorization] (the matrix function the interceptor calls) over a synthetic grant installed through the
 * permission-resolution seam: an author who holds `pipeline.release` but not `template.release` is refused
 * `auth.role_required` before any preparation, guard or flip — on a session and on an MCP key alike.
 */
class ReleaseCoordinatorAuthorizationTest {
    private val fx = CoordinatorFixture()
    private val t1 = fx.template("test/t1.sql")
    private val t2 = fx.template("test/t2.sql", status = ReleaseNodeStatus.RELEASED)
    private val a = fx.pipeline("test/a", listOf(t1, t2))
    private val context = WorkspaceContext(fx.workspaceId, "acme", WorkspaceRole.AUTHOR)

    /** The production answer, minus `template.release` for an author. */
    private val withoutTemplateRelease =
        object : PermissionResolver {
            override fun holds(
                workspaceId: UUID?,
                role: WorkspaceRole?,
                superAdmin: Boolean,
                permission: Permission,
            ): Boolean =
                !(role == WorkspaceRole.AUTHOR && permission == Permission.TEMPLATE_RELEASE) &&
                    RolePermissionsResolver.holds(workspaceId, role, superAdmin, permission)
        }

    private val session = AuthenticatedPrincipal(fx.actor, "author@datapipelines.test", "Author", AuthMethod.OIDC, workspace = context)
    private val mcpKey =
        AuthenticatedPrincipal(
            fx.actor,
            "key@keys.invalid",
            "Author key",
            AuthMethod.API_KEY,
            keyId = "k1",
            workspace = context,
            keyKind = ApiKeyKind.MCP,
            keyRole = KeyRole.AUTHOR,
        )

    @AfterEach
    fun clear() {
        SecurityContextHolder.clearContext()
    }

    private fun actingAs(principal: AuthenticatedPrincipal) {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun planned() = fx.preview(a).let { fx.request(a, ReleasePlanRequest(it.treeHash, it.ids(t1))) }

    @Test
    fun `an author without template release is refused role_required before any preparation, guard or flip`() {
        PermissionResolverInstallation(withoutTemplateRelease).use {
            listOf(session, mcpKey).forEach { principal ->
                withClue(principal.authMethod) {
                    actingAs(principal)
                    val error = shouldThrow<DatapipelinesException> { fx.coordinator(MatrixReleaseAuthorization()).release(planned()) }
                    error.code shouldBe AuthErrorCodes.ROLE_REQUIRED
                }
            }
        }
        fx.log shouldBe emptyList()
        verify(exactly = 0) { fx.releases.prepare(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the legacy alias is judged the same way - its template flips need template release`() {
        PermissionResolverInstallation(withoutTemplateRelease).use {
            actingAs(session)
            val coordinator = fx.coordinator(MatrixReleaseAuthorization())
            val error = shouldThrow<DatapipelinesException> { coordinator.release(fx.request(a, alias = true)) }
            error.code shouldBe AuthErrorCodes.ROLE_REQUIRED
        }
        withClue("refused under the guard, before the first flip: ${fx.log}") {
            fx.log.none { it.startsWith("flip:") } shouldBe true
            fx.log.last() shouldBe "rollback"
        }
    }

    @Test
    fun `the same author with the production grants releases - the refusal above is the missing permission, nothing else`() {
        listOf(session, mcpKey).forEach { principal ->
            withClue(principal.authMethod) {
                fx.log.clear()
                actingAs(principal)
                fx.coordinator(MatrixReleaseAuthorization()).release(planned())
                fx.log.last() shouldBe "commit"
            }
        }
    }

    @Test
    fun `each family maps to its own native release permission - no new string`() {
        ReleaseFamily.entries.associateWith(MatrixReleaseAuthorization::permissionOf) shouldBe
            mapOf(
                ReleaseFamily.PIPELINE to Permission.PIPELINE_RELEASE,
                ReleaseFamily.TEMPLATE to Permission.TEMPLATE_RELEASE,
                ReleaseFamily.PARAMETER_SET to Permission.PARAMETER_SET_RELEASE,
                ReleaseFamily.VISUALIZATION to Permission.VISUALIZATION_RELEASE,
                ReleaseFamily.DASHBOARD to Permission.DASHBOARD_RELEASE,
            )
    }
}
