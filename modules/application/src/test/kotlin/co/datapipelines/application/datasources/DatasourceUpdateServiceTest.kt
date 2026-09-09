package co.datapipelines.application.datasources

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.util.UUID

/**
 * [DatasourceUpdateService] — the ONE gated update path `PUT /api/v1/datasources/{name}` and
 * the §4.5 edit dialog both run (097 §A, after 068 did the same for create).
 *
 * What the SERVICE owns, and therefore what is pinned here, is the **order**: the three D8
 * gates, then the binding, then the save — and the surface's own binding of its payload
 * strictly after the gates, so a caller who may not perform this write is refused before
 * their input is interpreted at all. That is the property a second hand-written copy of the
 * sequence lost, twice.
 *
 * The rules are a RECORDING FAKE, not a strict mock: the question is "were they all called,
 * in this order", and a strict mock answers that question by throwing on the call that IS
 * there — the wrong way round. The registry save is a recording answer for the same reason.
 */
class DatasourceUpdateServiceTest {
    private val registry = mockk<DatasourceRegistry>()
    private val saved = mutableListOf<Datasource>()

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val otherWorkspaceId = UUID.randomUUID()

    private val principal =
        AuthenticatedPrincipal(
            userId,
            "author@example.test",
            "Author",
            setOf(Scope.AUTHOR),
            AuthMethod.API_KEY,
            workspace = WorkspaceContext(workspaceId, "acme"),
        )

    private val existing =
        Datasource(
            name = "pg_prod",
            displayName = "Production Postgres",
            dialect = Dialect.POSTGRES,
            jdbcUrl = "jdbc:postgresql://db:5432/app",
            username = "readonly",
            workspaceId = workspaceId,
            workspaceName = "acme",
        )

    /** Every rule call, in the order it arrived — the sequence IS the contract. */
    private val calls = mutableListOf<String>()

    private inner class RecordingRules(
        private val refuseAt: String? = null,
    ) : DatasourceUpdateRules {
        override fun requireGlobalMutationAllowed(
            principal: AuthenticatedPrincipal,
            existing: Datasource,
            name: String,
        ) = record("requireGlobalMutationAllowed($name)")

        override fun requireMemberDatasourcesGate(principal: AuthenticatedPrincipal) = record("requireMemberDatasourcesGate")

        override fun requireGlobalFlagWriteAllowed(
            principal: AuthenticatedPrincipal,
            globalRequested: Boolean?,
        ) = record("requireGlobalFlagWriteAllowed($globalRequested)")

        override fun resolveUpdateBinding(
            principal: AuthenticatedPrincipal,
            existing: Datasource,
            global: Boolean?,
            workspaceName: String?,
        ): UUID? {
            record("resolveUpdateBinding($global,$workspaceName)")
            return when {
                global == true -> null
                workspaceName != null -> otherWorkspaceId
                else -> existing.workspaceId
            }
        }

        private fun record(call: String) {
            calls += call
            if (refuseAt != null && call.startsWith(refuseAt)) error("refused at $call")
        }
    }

    private fun service(rules: DatasourceUpdateRules = RecordingRules()): DatasourceUpdateService {
        every { registry.save(any(), userId) } answers { firstArg<Datasource>().also { saved += it } }
        return DatasourceUpdateService(registry, rules)
    }

    private fun draft(): Datasource {
        calls += "bind"
        return existing.copy(jdbcUrl = "jdbc:postgresql://db:5432/other", isReadonly = true)
    }

    @Test
    fun `the three gates run in order, then the surface binds, then the binding, then the save`() {
        val result = service().update("pg_prod", existing, principal, globalRequested = null, workspaceName = null) { draft() }

        assertAll(
            {
                calls shouldBe
                    listOf(
                        "requireGlobalMutationAllowed(pg_prod)",
                        "requireMemberDatasourcesGate",
                        "requireGlobalFlagWriteAllowed(null)",
                        "bind",
                        "resolveUpdateBinding(null,null)",
                    )
            },
            { result shouldBe saved.single() },
            { result.jdbcUrl shouldBe "jdbc:postgresql://db:5432/other" },
            { result.isReadonly shouldBe true },
            // Absent flags keep the stored binding.
            { result.workspaceId shouldBe workspaceId },
        )
    }

    @Test
    fun `the resolved binding overrides whatever the surface put on the row`() {
        val result =
            service().update("pg_prod", existing, principal, globalRequested = true, workspaceName = null) {
                // A surface that tried to bind around the rules — the service replaces it.
                existing.copy(workspaceId = otherWorkspaceId)
            }

        result.workspaceId shouldBe null
    }

    @Test
    fun `an explicit workspace re-binds`() {
        val result =
            service().update("pg_prod", existing, principal, globalRequested = false, workspaceName = "other") { existing }

        result.workspaceId shouldBe otherWorkspaceId
    }

    @Test
    fun `a refused gate binds nothing and writes nothing`() {
        shouldThrow<IllegalStateException> {
            service(RecordingRules(refuseAt = "requireMemberDatasourcesGate"))
                .update("pg_prod", existing, principal, globalRequested = null, workspaceName = null) { draft() }
        }

        assertAll(
            { calls shouldBe listOf("requireGlobalMutationAllowed(pg_prod)", "requireMemberDatasourcesGate") },
            // The payload was never interpreted, and nothing reached the registry.
            { saved.shouldBeEmpty() },
        )
    }
}
