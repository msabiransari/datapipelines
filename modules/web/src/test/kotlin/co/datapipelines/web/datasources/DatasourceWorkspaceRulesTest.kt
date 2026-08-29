package co.datapipelines.web.datasources

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.auth.WorkspacesProperties
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.api.ApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [DatasourceWorkspaceRules.resolveUpdateBinding] (D8): absent flags keep the stored
 * binding, `global:false` re-binds (named workspace, else ACTIVE), `workspace` re-binds to
 * an accessible one — and `global:true` WITH a `workspace` is the one true contradiction
 * (`datasource.validation.workspace_forbidden` 400). 022 review F7: the guard rejected
 * `global:false` + `workspace` too, so the re-bind payload its own KDoc documents — and
 * create accepts — was dead code.
 */
class DatasourceWorkspaceRulesTest {
    private val workspaceService = mockk<WorkspaceService>()
    private val rules = DatasourceWorkspaceRules(workspaceService, WorkspacesProperties())

    private val activeWorkspace = WorkspaceContext(UUID.randomUUID(), "acme")
    private val globex = WorkspaceContext(UUID.randomUUID(), "globex")
    private val principal =
        AuthenticatedPrincipal(
            UUID.randomUUID(),
            "a@b.c",
            "A",
            setOf(Scope.AUTHOR),
            AuthMethod.OIDC,
            workspace = activeWorkspace,
        )

    private val existing =
        co.datapipelines.datasources
            .Datasource(
                name = "pg-prod",
                displayName = "Prod",
                dialect = co.datapipelines.typesystem.Dialect.POSTGRES,
                jdbcUrl = "jdbc:postgresql://db:5432/app",
                username = "app",
                workspaceId = activeWorkspace.id,
            )

    @Test
    fun `global false with a named workspace RE-BINDS - the payload create accepts and the KDoc documents`() {
        every { workspaceService.resolveSwitch(principal, "globex") } returns globex

        rules.resolveUpdateBinding(principal, existing, global = false, workspaceName = "globex") shouldBe globex.id
    }

    @Test
    fun `global false without a workspace re-binds to the ACTIVE workspace`() {
        rules.resolveUpdateBinding(principal, existing, global = false, workspaceName = null) shouldBe activeWorkspace.id
    }

    @Test
    fun `global TRUE with a named workspace is the true contradiction - workspace_forbidden 400`() {
        val refused =
            shouldThrow<ApiException> {
                rules.resolveUpdateBinding(principal, existing, global = true, workspaceName = "globex")
            }

        refused.code shouldBe PipelineErrorCodes.Datasource.WORKSPACE_FORBIDDEN
    }

    @Test
    fun `absent flags keep the stored binding`() {
        rules.resolveUpdateBinding(principal, existing, global = null, workspaceName = null) shouldBe existing.workspaceId
    }

    @Test
    fun `a workspace alone re-binds to the named accessible workspace`() {
        every { workspaceService.resolveSwitch(principal, "globex") } returns globex

        rules.resolveUpdateBinding(principal, existing, global = null, workspaceName = "globex") shouldBe globex.id
    }
}
