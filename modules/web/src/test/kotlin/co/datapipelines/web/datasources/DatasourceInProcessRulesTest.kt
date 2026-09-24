package co.datapipelines.web.datasources

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.auth.WorkspacesProperties
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.api.ApiException
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.util.UUID

/**
 * The #186 registration gate at its decision point: an IN-PROCESS engine — H2 `mem:`/`file:`,
 * DuckDB, SQLite — is registered or re-pointed by a **super admin only**, whatever
 * `member-datasources-enabled` says, because its SQL runs inside the server's own JVM. A
 * server-reachable H2 (`tcp:`/`ssl:`) is not in-process and follows the ordinary D8 rules.
 *
 * The E2E matrix over both roles and both surfaces is `DatasourceInProcessRegistrationE2eTest`;
 * these pin the rule at the unit seam, where a regression names itself in milliseconds.
 */
class DatasourceInProcessRulesTest {
    private val workspaceService = mockk<WorkspaceService>()

    /** The member gate ON — the whole point is that the in-process rule refuses anyway. */
    private val rules = DatasourceWorkspaceRules(workspaceService, WorkspacesProperties(memberDatasourcesEnabled = true))

    private val workspace = WorkspaceContext(UUID.randomUUID(), "acme")

    private fun principal(superAdmin: Boolean) =
        AuthenticatedPrincipal(
            UUID.randomUUID(),
            "a@b.c",
            "A",
            AuthMethod.OIDC,
            workspace = workspace,
            superAdmin = superAdmin,
        )

    @Test
    fun `a workspace admin cannot register an in-process engine even with the member gate on`() {
        val member = principal(superAdmin = false)

        assertAll(
            { refused(member, Dialect.H2, "jdbc:h2:mem:appdata") },
            { refused(member, Dialect.H2, "jdbc:h2:file:/data/app") },
            { refused(member, Dialect.H2, "jdbc:h2:/data/bare-path") },
            { refused(member, Dialect.DUCKDB, "jdbc:duckdb::memory:") },
            { refused(member, Dialect.DUCKDB, "jdbc:duckdb:/data/app.duckdb") },
            { refused(member, Dialect.SQLITE, "jdbc:sqlite::memory:") },
            { refused(member, Dialect.SQLITE, "jdbc:sqlite:/data/app.db") },
            // LAKE: the same embedded DuckDB with external access ON — gated whatever the URL
            // form says, because the form classifies it as Server (186 review M1).
            { refused(member, Dialect.LAKE, "jdbc:duckdb::memory:") },
            { refused(member, Dialect.LAKE, "jdbc:duckdb:/data/lake.duckdb") },
        )
    }

    @Test
    fun `a workspace admin CAN register a server-reachable H2 with the member gate on`() {
        val member = principal(superAdmin = false)

        // tcp/ssl are network clients of an H2 server — the same posture as a Postgres URL.
        rules.resolveCreateBinding(member, null, null, Dialect.H2, "jdbc:h2:tcp://db.internal:9092/app") shouldBe workspace.id
        rules.resolveCreateBinding(member, null, null, Dialect.H2, "jdbc:h2:ssl://db.internal/app") shouldBe workspace.id
    }

    @Test
    fun `a super admin registers every form`() {
        val admin = principal(superAdmin = true)

        assertAll(
            {
                shouldNotThrow<ApiException> {
                    rules.resolveCreateBinding(admin, null, null, Dialect.H2, "jdbc:h2:mem:appdata")
                }
            },
            {
                shouldNotThrow<ApiException> {
                    rules.resolveCreateBinding(admin, null, null, Dialect.DUCKDB, "jdbc:duckdb::memory:")
                }
            },
            {
                shouldNotThrow<ApiException> {
                    rules.resolveCreateBinding(admin, null, null, Dialect.SQLITE, "jdbc:sqlite:/data/app.db")
                }
            },
            {
                shouldNotThrow<ApiException> {
                    rules.resolveCreateBinding(admin, null, null, Dialect.LAKE, "jdbc:duckdb::memory:")
                }
            },
        )
    }

    @Test
    fun `the update-side gate refuses a re-point to an in-process form for a workspace admin`() {
        val member = principal(superAdmin = false)
        val existing =
            co.datapipelines.datasources
                .Datasource(
                    name = "h2-srv",
                    displayName = "H2 server",
                    dialect = Dialect.H2,
                    jdbcUrl = "jdbc:h2:tcp://db.internal:9092/app",
                    ownerWorkspaceId = workspace.id,
                )

        // The existing row is a server H2; re-pointing its URL at an in-process form is a
        // registration, and refuses.
        val moved = existing.copy(jdbcUrl = "jdbc:h2:mem:appdata")
        shouldThrow<ApiException> { rules.requireInProcessDatasourceAllowed(member, moved) }
        // …while an unchanged server URL passes, and a super admin may do either.
        shouldNotThrow<ApiException> { rules.requireInProcessDatasourceAllowed(member, existing) }
        shouldNotThrow<ApiException> { rules.requireInProcessDatasourceAllowed(principal(superAdmin = true), moved) }
    }

    private fun refused(
        member: AuthenticatedPrincipal,
        dialect: Dialect,
        jdbcUrl: String,
    ) {
        val thrown =
            shouldThrow<ApiException> {
                rules.resolveCreateBinding(member, null, null, dialect, jdbcUrl)
            }
        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Datasource.WORKSPACE_FORBIDDEN },
            { thrown.message shouldContain "in-process" },
        )
    }
}
