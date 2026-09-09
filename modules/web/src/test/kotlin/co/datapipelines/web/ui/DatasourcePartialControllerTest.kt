package co.datapipelines.web.ui

import co.datapipelines.application.datasources.DatasourceUpdateService
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.datasources.DatasourceReference
import co.datapipelines.datasources.DatasourceReferences
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DatasourceTestOutcome
import co.datapipelines.datasources.DeleteResult
import co.datapipelines.datasources.TestResult
import co.datapipelines.datasources.pooling.PoolSettings
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.datasources.DatasourceWorkspaceRules
import co.datapipelines.web.datasources.PoolFieldView
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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

    /** The delete dialog's usage question. Default: nothing references anything (§6.2). */
    private var references = co.datapipelines.datasources.DatasourceReferences.NONE
    private val controller =
        DatasourcePartialController(
            DatasourceBrowseModel(datasources),
            datasources,
            rules,
            DatasourceUpdateService(datasources, rules),
        ) { name -> references.referencesTo(name) }

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

/**
     * A WORKSPACE-BOUND datasource. `workspaceId` — not the rendered `workspaceName` — is what
     * the D8 rules read, so a fixture that sets only the name is a GLOBAL row wearing a label,
     * and every bound-row test written against it would silently exercise the global branch.
     */
    private fun bound(name: String) = ds(name, workspaceName = "acme").copy(workspaceId = workspaceId)

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
                credentialKind = "password",
                username = " u ",
                password = "pw",
                displayName = null,
                description = null,
                global = false,
                readonly = true,
                params = emptyMap(),
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
                credentialKind = "password",
                username = "u",
                password = "p",
                displayName = null,
                description = null,
                global = false,
                readonly = false,
                params = emptyMap(),
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
                credentialKind = "password",
                username = "u",
                password = "p",
                displayName = null,
                description = null,
                global = false,
                readonly = false,
                params = emptyMap(),
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
                credentialKind = "password",
                username = "u",
                password = "p",
                displayName = null,
                description = null,
                global = false,
                readonly = false,
                params = emptyMap(),
            ) as org.springframework.http.ResponseEntity<*>

        response.statusCode shouldBe HttpStatus.BAD_REQUEST
        response.body.toString() shouldContain "Members cannot register datasources"
    }

    // ------------------------------------------------------------ 094 §A: the pool section

    @Test
    fun `the pool fields are the catalogued keys, prefilled with the chosen dialect's defaults`() {
        controller.poolFields(model, dialect = "postgres")

        @Suppress("UNCHECKED_CAST")
        val fields = model["poolFields"] as List<PoolFieldView>
        fields.map { it.key } shouldBe PoolSettings.CATALOG.map { it.key }
        fields.single { it.key == "minimumIdle" }.value shouldBe "2"
        fields.single { it.key == "minimumIdle" }.help shouldContain "this server"
        fields.single { it.key == "maximumPoolSize" }.value shouldBe "10"
        fields.single { it.key == "maximumPoolSize" }.help shouldContain "HikariCP"
    }

    @Test
    fun `an unknown dialect falls back to the first, never to an empty section`() {
        // The select can only post a catalogued value, so this is the hand-crafted-request
        // path; an empty pool section would be a form with no fields and no explanation.
        controller.poolFields(model, dialect = "not-a-dialect")

        @Suppress("UNCHECKED_CAST")
        (model["poolFields"] as List<PoolFieldView>).size shouldBe PoolSettings.CATALOG.size
    }

    // ------------------------------------------------------------ 094 §A: edit

    @Test
    fun `the edit form prefills from the row, including its own pool values`() {
        every { datasources.getVisible("pg1", workspaceId) } returns
            ds("pg1").copy(properties = DatasourceProperties(hikari = mapOf("maximumPoolSize" to 25)))

        controller.editForm(model, "pg1") shouldBe "partials/datasource-edit"

        @Suppress("UNCHECKED_CAST")
        val fields = model["poolFields"] as List<PoolFieldView>
        fields.single { it.key == "maximumPoolSize" }.value shouldBe "25"
        fields.single { it.key == "maximumPoolSize" }.configured shouldBe true
        // Untouched keys still show their default and say so.
        fields.single { it.key == "maxLifetime" }.help shouldContain "HikariCP"
    }

    @Test
    fun `an edit form for an invisible datasource is a refusal, never an error page`() {
        every { datasources.getVisible("other", workspaceId) } returns null

        val response = controller.editForm(model, "other") as ResponseEntity<*>

        response.statusCode shouldBe HttpStatus.BAD_REQUEST
    }

    @Test
    fun `a pool field left at its default is NOT persisted`() {
        // The whole reason the form compares rather than copies: persisting the prefill would
        // freeze today's defaults into the row, and a later change to a product default would
        // then reach nothing.
        every { datasources.getVisible("pg1", workspaceId) } returns ds("pg1")
        every { rules.requireGlobalMutationAllowed(any(), any(), any()) } returns Unit
        every { rules.requireMemberDatasourcesGate(any()) } returns Unit
        every { rules.requireGlobalFlagWriteAllowed(any(), any()) } returns Unit
        every { rules.resolveUpdateBinding(any(), any(), any(), any()) } returns workspaceId
        val saved = slot<Datasource>()
        every { datasources.save(capture(saved), userId) } answers { saved.captured }

        controller.update(
            model,
            name = "pg1",
            jdbcUrl = "jdbc:postgresql://h/pg1",
            credentialKind = "password",
            username = "u",
            password = null,
            displayName = "pg1",
            description = null,
            global = false,
            globalPresent = false,
            readonly = false,
            params = mapOf("pool.maximumPoolSize" to "10", "pool.minimumIdle" to "2"),
        )

        saved.captured.properties.hikari shouldBe emptyMap()
    }

    @Test
    fun `a pool field changed from its default IS persisted, and an emptied one is removed`() {
        every { datasources.getVisible("pg1", workspaceId) } returns
            ds("pg1").copy(properties = DatasourceProperties(hikari = mapOf("maxLifetime" to 60_000)))
        every { rules.requireGlobalMutationAllowed(any(), any(), any()) } returns Unit
        every { rules.requireMemberDatasourcesGate(any()) } returns Unit
        every { rules.requireGlobalFlagWriteAllowed(any(), any()) } returns Unit
        every { rules.resolveUpdateBinding(any(), any(), any(), any()) } returns workspaceId
        val saved = slot<Datasource>()
        every { datasources.save(capture(saved), userId) } answers { saved.captured }

        controller.update(
            model,
            name = "pg1",
            jdbcUrl = "jdbc:postgresql://h/pg1",
            credentialKind = "password",
            username = "u",
            password = null,
            displayName = "pg1",
            description = null,
            global = false,
            globalPresent = false,
            readonly = false,
            // maximumPoolSize retuned; maxLifetime cleared back to the default.
            params = mapOf("pool.maximumPoolSize" to "25", "pool.maxLifetime" to ""),
        )

        saved.captured.properties.hikari shouldBe mapOf("maximumPoolSize" to 25L)
    }

    @Test
    fun `a blank secret keeps the stored credential`() {
        every { datasources.getVisible("pg1", workspaceId) } returns ds("pg1")
        every { rules.requireGlobalMutationAllowed(any(), any(), any()) } returns Unit
        every { rules.requireMemberDatasourcesGate(any()) } returns Unit
        every { rules.requireGlobalFlagWriteAllowed(any(), any()) } returns Unit
        every { rules.resolveUpdateBinding(any(), any(), any(), any()) } returns workspaceId
        val saved = slot<Datasource>()
        every { datasources.save(capture(saved), userId) } answers { saved.captured }

        controller.update(
            model,
            name = "pg1",
            jdbcUrl = "jdbc:postgresql://h/pg1",
            credentialKind = "password",
            username = "u",
            password = "",
            displayName = "pg1",
            description = null,
            global = false,
            globalPresent = false,
            readonly = false,
            params = emptyMap(),
        )

        // null secret = "keep what is stored" all the way down to the repository (§9.4).
        saved.captured.secret shouldBe null
    }

    // ------------------------------------------------------------ 094 §B: delete

    @Test
    fun `the delete dialog asks the usage question first and carries the rows the 409 carries`() {
        every { datasources.getVisible("pg1", workspaceId) } returns bound("pg1")
        references =
            DatasourceReferences {
                listOf(
                    DatasourceReference("sales_daily", 3, "RELEASED", "extract"),
                    DatasourceReference("sales_daily", 4, "DRAFT", "extract"),
                    DatasourceReference("ops_hourly", 1, "RELEASED", "load"),
                )
            }

        controller.deleteDialog(model, "pg1") shouldBe "partials/datasource-delete"

        (model["usages"] as List<*>).size shouldBe 3
        model["usedByPipelines"] shouldBe listOf("sales_daily", "ops_hourly")
        model["forbidden"] shouldBe null
    }

    @Test
    fun `an unused datasource's dialog carries no usages`() {
        every { datasources.getVisible("pg1", workspaceId) } returns bound("pg1")

        controller.deleteDialog(model, "pg1")

        (model["usages"] as List<*>).size shouldBe 0
        model["forbidden"] shouldBe null
    }

    @Test
    fun `a member deleting a GLOBAL datasource is refused in the dialog, before any scan runs`() {
        // workspaceName null = global. The D8 rule is answered first, so the dialog never
        // renders a confirm whose POST would refuse — and never runs the reverse scan either.
        every { datasources.getVisible("shared", workspaceId) } returns ds("shared")
        references = DatasourceReferences { error("the usage scan must not run when D8 already refused") }

        controller.deleteDialog(model, "shared")

        (model["forbidden"] as String) shouldContain "requires admin"
        (model["usages"] as List<*>).size shouldBe 0
    }

    @Test
    fun `the confirmed delete crosses the registry and answers with the Shape A success`() {
        every { datasources.getVisible("pg1", workspaceId) } returns bound("pg1")
        every { rules.requireGlobalMutationAllowed(any(), any(), any()) } returns Unit
        every { rules.requireMemberDatasourcesGate(any()) } returns Unit
        every { datasources.delete("pg1") } returns DeleteResult(deleted = true, name = "pg1")
        every { datasources.listVisible(null, workspaceId) } returns emptyList()

        controller.delete(model, "pg1") shouldBe "partials/datasource-saved"

        model["savedName"] shouldBe "pg1"
        model["savedVerb"] shouldBe "deleted"
        model["oob"] shouldBe true
        verify(exactly = 1) { datasources.delete("pg1") }
    }

    @Test
    fun `the in-use guard is re-run at the POST, not trusted from the dialog`() {
        // A pipeline can start referencing the datasource between the dialog opening and the
        // button being pressed. The screen is never the authority.
        every { datasources.getVisible("pg1", workspaceId) } returns bound("pg1")
        every { rules.requireGlobalMutationAllowed(any(), any(), any()) } returns Unit
        every { rules.requireMemberDatasourcesGate(any()) } returns Unit
        every { datasources.delete("pg1") } returns
            DeleteResult(
                deleted = false,
                name = "pg1",
                errorCode = "datasource.in_use",
                references = listOf(DatasourceReference("sales_daily", 3, "RELEASED", "extract")),
            )

        val response = controller.delete(model, "pg1") as ResponseEntity<*>

        response.statusCode shouldBe HttpStatus.BAD_REQUEST
        (response.body as String) shouldContain "still used by"
    }
}
