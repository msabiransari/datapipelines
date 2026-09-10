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
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
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
import java.util.UUID

/**
 * **One validated path** — `POST /api/v1/datasources` reaches [DatasourceCreateService] over a
 * REAL [DatasourceWorkspaceRules] instance, and the D8 gates it enforces are asserted here rather
 * than described (049's principle, applied to datasources by 068).
 *
 * It was `DatasourceCreateSharedPathTest` and it drove BOTH surfaces: 068's `datasources_create`
 * MCP tool called the same service, and this was the only module that could see both. 094 removed
 * that tool — no credential travels through an agent — so the second half of every case below is
 * gone with it, and the file is named for what it still proves. The service stays extracted: the
 * bootstrap registrar and the UI form must not diverge from this sequence either, and folding it
 * back into the controller would make "one validated path" a claim rather than a structure.
 *
 * ## How it can fail
 *
 * Delete `registrations.create(...)` from [DatasourcesController.create] and there is no other
 * implementation of registration to fall back on — every case below goes red.
 */
class DatasourceCreatePathTest {
    /** D-R7: registration grants the datasource to its own workspace in the same breath. */
    private val grants = mockk<co.datapipelines.datasources.DatasourceGrantRepository>(relaxed = true)

    private val registry = mockk<DatasourceRegistry>()
    private val workspaceService = mockk<WorkspaceService>(relaxed = true)
    private val mapper = JsonMapper.builder().build()

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val workspace = WorkspaceContext(workspaceId, "acme")

    /** Every row the shared service handed the registry — a recording answer, never a bare mock. */
    private val saved = mutableListOf<Datasource>()

    private fun controller(memberGate: Boolean = true): DatasourcesController {
        val rules = DatasourceWorkspaceRules(workspaceService, WorkspacesProperties(memberDatasourcesEnabled = memberGate))
        return DatasourcesController(
            registry,
            rules,
            DatasourceCreateService(registry, rules::resolveCreateBinding, grants),
            DatasourceUpdateService(registry, rules),
        )
    }

    private fun principal(admin: Boolean) =
        AuthenticatedPrincipal(
            userId,
            "a@b.c",
            "A",
            setOf(Scope.AUTHOR),
            AuthMethod.API_KEY,
            workspace = workspace.copy(flags = MembershipFlags(author = true, promoter = true, admin = true)),
            superAdmin = admin,
        )

    private fun authenticate(admin: Boolean) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal(admin), null, emptyList())
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun stubRegistry() {
        every { registry.exists(any()) } returns false
        every { registry.save(any(), userId) } answers {
            firstArg<Datasource>().also { saved += it }
        }
    }

    private fun restBody(extra: String = "") =
        mapper.readTree(
            """{"name":"pg_rest","display_name":"Production Postgres","dialect":"POSTGRES","jdbc_url":"jdbc:postgresql://db:5432/app",
               "username":"readonly","password":"s3cret","readonly":true,
               "introspection_include_schemas":["Apex_Reporting"]$extra}""",
        )

    @Test
    fun `the payload binds to the stored row through the shared service`() {
        stubRegistry()
        authenticate(admin = false)

        controller().create(restBody())

        saved shouldHaveSize 1
        val rest = saved.single()
        assertAll(
            { rest.ownerWorkspaceId shouldBe workspaceId },
            { rest.isReadonly shouldBe true },
            // §3.3 allowlist normalization happens on the way in, once, at the save boundary.
            { rest.introspectionIncludeSchemas shouldBe listOf("apex_reporting") },
        )
    }

    @Test
    fun `global without admin is refused with the catalogued code`() {
        stubRegistry()
        authenticate(admin = false)

        val fromRest = shouldThrow<DatapipelinesException> { controller().create(restBody(""","global":true""")) }

        assertAll(
            { fromRest.code shouldBe PipelineErrorCodes.Datasource.WORKSPACE_FORBIDDEN },
            { saved shouldHaveSize 0 },
        )
    }

    @Test
    fun `an admin may register a global datasource`() {
        stubRegistry()
        authenticate(admin = true)

        controller().create(restBody(""","global":true"""))

        saved.map { it.ownerWorkspaceId } shouldBe listOf(null)
    }

    @Test
    fun `the member-datasources gate closes the surface to a non-admin`() {
        stubRegistry()
        authenticate(admin = false)

        val fromRest = shouldThrow<DatapipelinesException> { controller(memberGate = false).create(restBody()) }

        assertAll(
            { fromRest.code shouldBe PipelineErrorCodes.Datasource.WORKSPACE_FORBIDDEN },
            { saved shouldHaveSize 0 },
        )
    }

    @Test
    fun `a duplicate name is refused with the catalogued code`() {
        every { registry.exists(any()) } returns true
        authenticate(admin = false)

        shouldThrow<DatapipelinesException> { controller().create(restBody()) }.code shouldBe
            PipelineErrorCodes.Datasource.DUPLICATE_NAME
    }

    @Test
    fun `the REST response is the row the shared service saved, with the password gone`() {
        stubRegistry()
        authenticate(admin = false)
        val body = mapper.writeValueAsString(controller().create(restBody()))

        assertAll(
            { saved shouldHaveSize 1 },
            {
                mapper
                    .readTree(body)
                    .get("data")
                    .get("name")
                    .asText() shouldBe saved.single().name
            },
            {
                mapper
                    .readTree(body)
                    .get("data")
                    .get("password_set")
                    .asBoolean() shouldBe true
            },
            { body.contains("s3cret") shouldBe false },
        )
    }
}
