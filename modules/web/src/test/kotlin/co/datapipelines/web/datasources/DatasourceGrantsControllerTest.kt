package co.datapipelines.web.datasources

import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.Workspace
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceAuditEvents
import co.datapipelines.datasources.DatasourceErrorCodes
import co.datapipelines.datasources.DatasourceGrantRepository
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.MANAGED_DIALECT_KEY
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * The REST grants controller's revoke (RBAC design §4, D-R7) and #823's refusal: the managed lake is granted to every
 * workspace by the server, so a revoke of that grant is `datasource.managed` (409) before anything is deleted or
 * audited. An ordinary datasource's revoke is what it always was.
 *
 * The 403 for everyone below super admin is `ScopeInterceptor`'s and is not re-asserted here; what this pins is the
 * ANNOTATION (so the rule is claimed at all) and the behaviour the handler adds on top of it.
 */
class DatasourceGrantsControllerTest {
    private val datasources = mockk<DatasourceRegistry>()
    private val workspaces = mockk<WorkspaceService>()
    private val grants = mockk<DatasourceGrantRepository>()
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val controller = DatasourceGrantsController(datasources, workspaces, grants, auditLogger)

    private val userId = UUID.randomUUID()
    private val globexId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `revoke declares the super-admin grants permission`() {
        DatasourceGrantsController::class.java.methods
            .first { it.name == "revoke" }
            .getAnnotation(RequiredScope::class.java)
            .value shouldBe Permission.DATASOURCE_GRANT
    }

    @Test
    fun `revoking an ordinary datasource's grant deletes the row and audits it (unchanged)`() {
        authenticate()
        every { datasources.get("pg-prod") } returns datasource("pg-prod")
        every { workspaces.read(any(), "globex") } returns workspace("globex")
        every { grants.revoke("pg-prod", globexId) } returns true

        controller.revoke("pg-prod", "globex")

        verify(exactly = 1) { grants.revoke("pg-prod", globexId) }
        verify {
            auditLogger.log(
                event = DatasourceAuditEvents.REVOKED,
                userId = userId,
                keyId = null,
                details = mapOf("datasource" to "pg-prod", "workspace" to "globex", "had_grant" to true),
            )
        }
    }

    /** #823 — red at the base: the handler answered 204 and the grant was gone. */
    @Test
    fun `revoking the managed lake's grant is refused datasource_managed, deletes nothing and audits nothing (#823)`() {
        authenticate()
        every { datasources.get("lake") } returns managedLake()
        every { workspaces.read(any(), "globex") } returns workspace("globex")
        every { grants.revoke(any(), any()) } returns true

        val refusal = shouldThrow<DatapipelinesException> { controller.revoke("lake", "globex") }

        refusal.code shouldBe DatasourceErrorCodes.MANAGED
        // The catalogued answer names the grant and nothing about the datasource's internals (no URL, no credential).
        refusal.message.orEmpty() shouldNotContain "jdbc:"
        refusal.message.orEmpty() shouldNotContain "duckdb"
        verify(exactly = 0) { grants.revoke(any(), any()) }
        verify(exactly = 0) { auditLogger.log(any(), any(), any(), any(), any(), any()) }
    }

    /** The refusal is about the managed lake's grant: its absence from a workspace that was never granted is the same 409. */
    @Test
    fun `the managed lake's grant is refused for a workspace that holds no grant too (#823)`() {
        authenticate()
        every { datasources.get("lake") } returns managedLake()
        every { workspaces.read(any(), "globex") } returns workspace("globex")

        shouldThrow<DatapipelinesException> { controller.revoke("lake", "globex") }.code shouldBe DatasourceErrorCodes.MANAGED
        verify(exactly = 0) { grants.revoke(any(), any()) }
    }

    @Test
    fun `an unknown datasource is still datasource_not_found`() {
        authenticate()
        every { datasources.get("ghost") } returns null

        shouldThrow<DatapipelinesException> { controller.revoke("ghost", "globex") }.code shouldBe DatasourceErrorCodes.NOT_FOUND
    }

    /** Granting the managed lake stays idempotent and allowed: only the REVOKE is refused. */
    @Test
    fun `granting the managed lake stays an idempotent success`() {
        authenticate()
        every { datasources.get("lake") } returns managedLake()
        every { workspaces.read(any(), "globex") } returns workspace("globex")
        every { grants.grant("lake", globexId, userId) } returns false

        controller.grant("lake", "globex").data["granted"] shouldBe true
    }

    // ------------------------------------------------------------------ fixtures

    private fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                userId = userId,
                email = "root@acme.test",
                displayName = "Root",
                authMethod = AuthMethod.OIDC,
                workspaceName = "acme",
                workspace = WorkspaceContext.superAdminOver(UUID.randomUUID(), "acme", explicitRole = null),
                superAdmin = true,
            )
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun datasource(name: String) =
        Datasource(
            name = name,
            displayName = name,
            dialect = Dialect.POSTGRES,
            jdbcUrl = "jdbc:postgresql://localhost/db",
            username = "app",
        )

    private fun managedLake() =
        Datasource(
            name = "lake",
            displayName = "Managed lake",
            dialect = Dialect.LAKE,
            jdbcUrl = "jdbc:duckdb::memory:",
            properties = DatasourceProperties(dialect = mapOf("catalog.kind" to "s3", MANAGED_DIALECT_KEY to "true")),
            isReadonly = true,
        )

    private fun workspace(name: String) =
        Workspace(
            id = globexId,
            name = name,
            displayName = name,
            isPersonal = false,
            createdBy = userId,
            isDeleted = false,
            createdAt = Instant.EPOCH,
        )
}
