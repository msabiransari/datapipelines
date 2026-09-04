package co.datapipelines.mcp

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [McpNotFound] — the read-side not-found codes, each REUSING the §13 catalogued code that
 * names the condition (the reported spec gap in the KDoc: no dedicated read codes exist, and
 * inventing them would silently extend a frozen catalog). Pins each factory's code and
 * details — an accidental new code here is a spec-drift failure — plus the §5.3
 * [DatasourceRegistry.requireVisible] gate: an invisible datasource is the SAME not-found an
 * unknown name gets, with no pool opened.
 */
class McpNotFoundTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `pipeline not-found reuses the execution-side pipeline code`() {
        val e = McpNotFound.pipeline(id)

        e.code shouldBe PipelineErrorCodes.Execution.NOT_FOUND
        e.message shouldBe "Pipeline $id does not exist."
        e.details shouldBe mapOf("pipeline_id" to id.toString())
    }

    @Test
    fun `pipeline version not-found carries the version in details`() {
        val e = McpNotFound.pipelineVersion(id, 7)

        e.code shouldBe PipelineErrorCodes.Execution.NOT_FOUND
        e.details shouldBe mapOf("pipeline_id" to id.toString(), "version" to 7)
    }

    @Test
    fun `template and template version use the catalogued template code`() {
        McpNotFound.template("acme/rev").code shouldBe PipelineErrorCodes.Template.NOT_FOUND
        McpNotFound.templateVersion("acme/rev", 3).let {
            it.code shouldBe PipelineErrorCodes.Template.NOT_FOUND
            it.details shouldBe mapOf("template_id" to "acme/rev", "version" to 3)
        }
    }

    @Test
    fun `datasource and execution use their catalogued codes`() {
        McpNotFound.datasource("pg").code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND
        McpNotFound.execution(id).code shouldBe PipelineErrorCodes.Result.EXECUTION_NOT_FOUND
    }

    @Test
    fun `requireVisible returns the visible datasource`() {
        val registry = mockk<DatasourceRegistry>()
        val ds = mockk<Datasource>()
        val workspaceId = UUID.randomUUID()
        every { registry.getVisible("pg", workspaceId) } returns ds

        registry.requireVisible("pg", workspaceId) shouldBe ds
    }

    @Test
    fun `requireVisible turns an invisible datasource into the SAME not-found as an unknown name`() {
        val registry = mockk<DatasourceRegistry>()
        val workspaceId = UUID.randomUUID()
        every { registry.getVisible("secret", workspaceId) } returns null

        val e =
            shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                registry.requireVisible("secret", workspaceId)
            }

        e.code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND
        e.details shouldBe mapOf("datasource" to "secret")
    }

    @Test
    fun `the context variant resolves the workspace off the principal`() {
        val registry = mockk<DatasourceRegistry>()
        val workspaceId = UUID.randomUUID()
        val ds = mockk<Datasource>()
        every { registry.getVisible("pg", workspaceId) } returns ds
        val ctx =
            McpToolContext(
                principal =
                    AuthenticatedPrincipal(
                        UUID.randomUUID(),
                        "a@b.c",
                        "A",
                        setOf(Scope.READ),
                        AuthMethod.OIDC,
                        workspace = WorkspaceContext(workspaceId, "acme"),
                    ),
                correlationId = UUID.randomUUID(),
            )

        registry.requireVisible("pg", ctx) shouldBe ds
    }
}
