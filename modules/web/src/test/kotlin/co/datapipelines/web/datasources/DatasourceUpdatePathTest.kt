package co.datapipelines.web.datasources

import co.datapipelines.application.datasources.DatasourceCreateService
import co.datapipelines.application.datasources.DatasourceUpdateService
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.MembershipFlags
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.auth.WorkspacesProperties
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceReferences
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.ui.DatasourceBrowseModel
import co.datapipelines.web.ui.DatasourcePartialController
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.servlet.ModelAndView
import java.util.UUID

/**
 * **One gated path** — `PUT /api/v1/datasources/{name}` (REST) and
 * `POST /partials/datasources/{name}` (the §4.5 edit dialog) reach the same
 * [DatasourceUpdateService] over the same REAL [DatasourceWorkspaceRules] instance, and both
 * therefore run the D8 gates in the same order (097 §A). This is the sibling of
 * [DatasourceCreatePathTest] and the only module that can see both surfaces.
 *
 * Before the extraction the sequence — `requireGlobalMutationAllowed`,
 * `requireMemberDatasourcesGate`, `requireGlobalFlagWriteAllowed`, `resolveUpdateBinding`,
 * `save` — was written longhand in each controller, which is a permission matrix maintained
 * twice.
 *
 * ## How it can fail
 *
 * Delete the `updates.update(...)` call from either controller and there is no other
 * implementation of the sequence to fall back on: the row never reaches the recording
 * registry, and every case below goes red.
 */
class DatasourceUpdatePathTest {
    /** D-R7: registration grants the datasource to its own workspace in the same breath. */
    private val grants = mockk<co.datapipelines.datasources.DatasourceGrantRepository>(relaxed = true)

    private val registry = mockk<DatasourceRegistry>()
    private val workspaceService = mockk<WorkspaceService>(relaxed = true)
    private val mapper = JsonMapper.builder().build()

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    /** Every row a surface handed the registry — a recording answer, never a bare mock. */
    private val saved = mutableListOf<Datasource>()

    private val bound =
        Datasource(
            name = "pg_prod",
            displayName = "Production Postgres",
            dialect = Dialect.POSTGRES,
            jdbcUrl = "jdbc:postgresql://db:5432/app",
            username = "readonly",
            ownerWorkspaceId = workspaceId,
            workspaceName = "acme",
        )

    private val global = bound.copy(ownerWorkspaceId = null, workspaceName = null)

    private fun rules(memberGate: Boolean) =
        DatasourceWorkspaceRules(workspaceService, WorkspacesProperties(memberDatasourcesEnabled = memberGate))

    private fun rest(rules: DatasourceWorkspaceRules) =
        DatasourcesController(
            registry,
            rules,
            DatasourceCreateService(registry, rules::resolveCreateBinding, grants),
            DatasourceUpdateService(registry, rules),
        )

    private fun ui(rules: DatasourceWorkspaceRules) =
        DatasourcePartialController(
            DatasourceBrowseModel(registry),
            registry,
            rules,
            DatasourceUpdateService(registry, rules),
            DatasourceReferences.NONE,
        )

    private fun authenticate(admin: Boolean) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    userId,
                    "a@b.c",
                    "A",
                    setOf(Scope.AUTHOR),
                    AuthMethod.API_KEY,
                    workspace =
                        WorkspaceContext(
                            workspaceId,
                            "acme",
                            MembershipFlags(author = true, promoter = true, admin = true),
                        ),
                    superAdmin = admin,
                ),
                null,
                emptyList(),
            )
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun stubRegistry(existing: Datasource) {
        every { registry.getVisible(existing.name, workspaceId) } returns existing
        every { registry.listVisible(any(), workspaceId) } returns listOf(existing)
        every { registry.save(any(), userId) } answers { firstArg<Datasource>().also { saved += it } }
    }

    private fun restUpdate(
        rules: DatasourceWorkspaceRules,
        extra: String = "",
    ) = rest(rules).update(
        "pg_prod",
        mapper.readTree(
            """{"name":"pg_prod","dialect":"POSTGRES","jdbc_url":"jdbc:postgresql://db:5432/other",
               "username":"readonly"$extra}""",
        ),
    )

    @Suppress("LongParameterList")
    private fun uiUpdate(
        rules: DatasourceWorkspaceRules,
        global: Boolean = false,
        globalPresent: Boolean = false,
    ) = ui(rules).update(
        ExtendedModelMap(),
        "pg_prod",
        jdbcUrl = "jdbc:postgresql://db:5432/other",
        credentialKind = "password",
        username = "readonly",
        password = null,
        displayName = "Production Postgres",
        description = null,
        global = global,
        globalPresent = globalPresent,
        readonly = false,
        params = emptyMap(),
    )

    @Test
    fun `both surfaces write the row through the shared service`() {
        authenticate(admin = false)
        stubRegistry(bound)
        val rules = rules(memberGate = true)

        restUpdate(rules)
        uiUpdate(rules)

        saved shouldHaveSize 2
        assertAll(
            { saved.map { it.jdbcUrl }.distinct() shouldBe listOf("jdbc:postgresql://db:5432/other") },
            // Absent flags keep the stored binding — on both surfaces.
            { saved.map { it.ownerWorkspaceId }.distinct() shouldBe listOf(workspaceId) },
        )
    }

    /**
     * The two surfaces SHAPE a refusal differently — REST throws the catalogued
     * [ApiException], the dialog renders the same rule's message inline as a 400 rather than
     * an error page (§5.1) — but the refusal itself comes from the one rules instance, and
     * neither writes.
     */
    private fun refusedInline(result: Any) {
        val refusal = result as ModelAndView
        refusal.viewName shouldBe "partials/inline-refusal"
        refusal.status?.value() shouldBe 400
    }

    @Test
    fun `the member gate refuses both surfaces with the catalogued code, and writes nothing`() {
        authenticate(admin = false)
        stubRegistry(bound)
        val rules = rules(memberGate = false)

        val fromRest = shouldThrow<ApiException> { restUpdate(rules) }

        assertAll(
            { fromRest.code shouldBe PipelineErrorCodes.Datasource.WORKSPACE_FORBIDDEN },
            { refusedInline(uiUpdate(rules)) },
            { saved shouldHaveSize 0 },
        )
    }

    @Test
    fun `a member mutating a global datasource is refused on both surfaces`() {
        authenticate(admin = false)
        stubRegistry(global)
        val rules = rules(memberGate = true)

        val fromRest = shouldThrow<ApiException> { restUpdate(rules) }

        assertAll(
            { fromRest.code shouldBe PipelineErrorCodes.Datasource.WORKSPACE_FORBIDDEN },
            { refusedInline(uiUpdate(rules)) },
            { saved shouldHaveSize 0 },
        )
    }

    @Test
    fun `the global flag is admin-only on both surfaces, and un-globals the row for an admin`() {
        stubRegistry(bound)
        val rules = rules(memberGate = true)

        authenticate(admin = false)
        shouldThrow<ApiException> { restUpdate(rules, ""","global":true""") }
            .code shouldBe PipelineErrorCodes.Datasource.WORKSPACE_FORBIDDEN
        refusedInline(uiUpdate(rules, global = true, globalPresent = true))
        saved shouldHaveSize 0

        authenticate(admin = true)
        restUpdate(rules, ""","global":true""")
        uiUpdate(rules, global = true, globalPresent = true)

        saved.map { it.ownerWorkspaceId } shouldBe listOf(null, null)
    }
}
