package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DatasourceTestOutcome
import co.datapipelines.datasources.TestResult
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.datasources.DatasourceWorkspaceRules
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.time.Instant
import java.util.UUID

/**
 * [DatasourcePartialController] — the shared-rules register path, the search's every-column
 * contract, and the probe toast's three outcomes, not the list template
 * (DatasourcesTemplateRenderTest renders it). The register action must cross the SAME
 * [DatasourceWorkspaceRules] + `registry.save` boundary as the REST endpoint — two write
 * paths, one rule set is the point of the extracted component, so the test pins the
 * binding resolution and the saved row's shape.
 */
class DatasourcePartialControllerTest {
    private val datasources = mockk<DatasourceRegistry>(relaxed = true)
    private val rules = mockk<DatasourceWorkspaceRules>()
    private val controller = DatasourcePartialController(datasources, rules)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val model = ExtendedModelMap()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    init {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    userId,
                    "a@b.c",
                    "A",
                    setOf(Scope.AUTHOR),
                    AuthMethod.OIDC,
                    workspace = WorkspaceContext(workspaceId, "acme"),
                ),
                null,
                emptyList(),
            )
    }

    private fun ds(
        name: String,
        dialect: Dialect = Dialect.POSTGRES,
        lastTestOk: Boolean? = null,
        description: String? = null,
        workspaceName: String? = null,
    ) = Datasource(
        name = name,
        displayName = name,
        description = description,
        dialect = dialect,
        jdbcUrl = "jdbc:postgresql://h/$name",
        username = "u",
        lastTest = lastTestOk?.let { DatasourceTestOutcome(testedAt = Instant.EPOCH, ok = it) },
        workspaceName = workspaceName,
    )

    // ------------------------------------------------------------ list

    @Test
    fun `the list is workspace-visible, dialect-filtered and page-modelled`() {
        every { datasources.listVisible(Dialect.POSTGRES, workspaceId) } returns
            listOf(ds("pg1", Dialect.POSTGRES), ds("pg2", Dialect.POSTGRES))

        controller.list(model, q = null, dialect = "postgres", offset = 0)

        (model["datasources"] as List<*>).size shouldBe 2
        model["total"] shouldBe 2
        model["hasMore"] shouldBe false
        model["selectedDialect"] shouldBe "postgres"
    }

    @Test
    fun `the search covers every rendered column - including the last-test words`() {
        every { datasources.listVisible(null, workspaceId) } returns
            listOf(
                ds("ok_db", lastTestOk = true),
                ds("bad_db", lastTestOk = false),
                ds("never_db", lastTestOk = null),
                ds("quiet_db"),
            )

        // "ok" and "failed" are the words the column renders (061/T84); "never tested"
        // matches BOTH untested rows — the word is the column's, not the row's.
        controller.list(model, q = "ok", dialect = null, offset = 0)
        (model["datasources"] as List<*>).size shouldBe 1

        controller.list(model, q = "failed", dialect = null, offset = 0)
        (model["datasources"] as List<*>).size shouldBe 1

        controller.list(model, q = "never tested", dialect = null, offset = 0)
        (model["datasources"] as List<*>).size shouldBe 2
    }

    @Test
    fun `the search matches the workspace column - global and named`() {
        every { datasources.listVisible(null, workspaceId) } returns
            listOf(ds("shared", workspaceName = null), ds("bound", workspaceName = "acme"))

        controller.list(model, q = "global", dialect = null, offset = 0)
        (model["datasources"] as List<*>).size shouldBe 1

        controller.list(model, q = "acme", dialect = null, offset = 0)
        (model["datasources"] as List<*>).size shouldBe 1
    }

    // ------------------------------------------------------------ test probe

    @Test
    fun `an invisible datasource probes as not-found - never a hint it exists`() {
        every { datasources.getVisible("ghost", workspaceId) } returns null

        controller.test(model, "ghost")

        model["variant"] shouldBe "danger"
        model["title"] shouldBe "Datasource not found"
    }

    @Test
    fun `a successful probe renders the success toast with the server version`() {
        every { datasources.getVisible("pg", workspaceId) } returns ds("pg")
        every { datasources.testConnection("pg") } returns
            TestResult(connected = true, testedAt = Instant.EPOCH, serverVersion = "16.2")

        controller.test(model, "pg")

        model["variant"] shouldBe "success"
        model["message"] shouldBe "pg — Server version: 16.2"
    }

    @Test
    fun `a failed probe renders the danger toast with the error`() {
        every { datasources.getVisible("pg", workspaceId) } returns ds("pg")
        every { datasources.testConnection("pg") } returns
            TestResult(connected = false, testedAt = Instant.EPOCH, error = "refused")

        controller.test(model, "pg")

        model["variant"] shouldBe "danger"
        model["message"] shouldBe "pg — refused"
    }

    // ------------------------------------------------------------ register

    @Test
    fun `register resolves the binding through the shared rules and saves the trimmed row`() {
        every { rules.resolveCreateBinding(any(), any(), any()) } returns workspaceId
        every { datasources.exists("warehouse") } returns false
        every { datasources.listVisible(null, workspaceId) } returns emptyList()

        val result =
            controller.register(
                model,
                name = " warehouse ",
                dialect = "postgres",
                jdbcUrl = "jdbc:postgresql://h/db",
                username = " u ",
                password = "pw",
                displayName = null,
                description = null,
                global = false,
                readonly = true,
            )

        result shouldBe "partials/datasource-registered"
        verify {
            datasources.save(
                withArg {
                    it.name shouldBe "warehouse"
                    it.username shouldBe "u"
                    it.isReadonly shouldBe true
                    it.workspaceId shouldBe workspaceId
                },
                userId,
            )
        }
        model["oob"] shouldBe true
        model["registeredName"] shouldBe "warehouse"
    }

    @Test
    fun `an unknown dialect is the inline refusal`() {
        val response =
            controller.register(
                model,
                name = "x",
                dialect = "db2",
                jdbcUrl = "jdbc:x",
                username = "u",
                password = "p",
                displayName = null,
                description = null,
                global = false,
                readonly = false,
            ) as org.springframework.http.ResponseEntity<*>

        response.statusCode shouldBe HttpStatus.BAD_REQUEST
        response.body.toString() shouldContain "Unknown dialect"
    }

    @Test
    fun `a duplicate name is the inline refusal`() {
        every { rules.resolveCreateBinding(any(), any(), any()) } returns workspaceId
        every { datasources.exists("dupe") } returns true

        val response =
            controller.register(
                model,
                name = "dupe",
                dialect = "postgres",
                jdbcUrl = "jdbc:postgresql://h/d",
                username = "u",
                password = "p",
                displayName = null,
                description = null,
                global = false,
                readonly = false,
            ) as org.springframework.http.ResponseEntity<*>

        response.statusCode shouldBe HttpStatus.BAD_REQUEST
        response.body.toString() shouldContain "already exists"
    }

    @Test
    fun `a member refused by the workspace gate gets the refusal html - not an error page`() {
        every { rules.resolveCreateBinding(any(), any(), any()) } throws
            DatapipelinesException("datasource.workspace_gate", "Members cannot register datasources")

        val response =
            controller.register(
                model,
                name = "m",
                dialect = "postgres",
                jdbcUrl = "jdbc:postgresql://h/m",
                username = "u",
                password = "p",
                displayName = null,
                description = null,
                global = false,
                readonly = false,
            ) as org.springframework.http.ResponseEntity<*>

        response.statusCode shouldBe HttpStatus.BAD_REQUEST
        response.body.toString() shouldContain "Members cannot register datasources"
    }
}
